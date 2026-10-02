package br.com.jusprisma.aplicacao.cobranca;

import br.com.jusprisma.aplicacao.conta.OperacaoNaoPermitidaException;
import br.com.jusprisma.aplicacao.conta.RepositorioDeConta;
import br.com.jusprisma.aplicacao.plano.RepositorioDeAssinatura;
import br.com.jusprisma.aplicacao.plano.RepositorioDePlano;
import br.com.jusprisma.aplicacao.plano.SemAssinaturaVigenteException;
import br.com.jusprisma.aplicacao.porta.EscopoDeTenant;
import br.com.jusprisma.dominio.conta.DocumentoDeCobranca;
import br.com.jusprisma.dominio.conta.Papel;
import br.com.jusprisma.dominio.plano.Assinatura;
import br.com.jusprisma.dominio.plano.Plano;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Contrata um plano pago: registra o pagador no gateway e cria a assinatura recorrente.
 *
 * <p>O plano escolhido fica pendente até o primeiro pagamento confirmado — ver
 * {@link ProcessarEventoDeCobranca}. Até lá o escritório continua com os limites que tinha.
 *
 * <p>As chamadas ao gateway acontecem com a linha da assinatura travada. Segurar a trava
 * durante uma chamada externa é o preço de serializar o clique duplo; a linha é de um único
 * escritório, então ninguém mais espera por ela.
 */
@Service
public class ContratarPlano {

    private static final Logger log = LoggerFactory.getLogger(ContratarPlano.class);
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final String PLANO_DE_DEGUSTACAO = "DEGUSTACAO";

    private final EscopoDeTenant escopo;
    private final RepositorioDeAssinatura assinaturas;
    private final RepositorioDePlano planos;
    private final RepositorioDeConta contas;
    private final GatewayDePagamento gateway;

    public ContratarPlano(EscopoDeTenant escopo, RepositorioDeAssinatura assinaturas,
                          RepositorioDePlano planos, RepositorioDeConta contas,
                          GatewayDePagamento gateway) {
        this.escopo = escopo;
        this.assinaturas = assinaturas;
        this.planos = planos;
        this.contas = contas;
        this.gateway = gateway;
    }

    public record Comando(UUID tenantId, Papel papel, String planoCodigo, String documento) {

        @Override
        public String toString() {
            return "Comando[tenant=%s, plano=%s]".formatted(tenantId, planoCodigo);
        }
    }

    public record Contratacao(String planoCodigo, String documentoMascarado, LocalDate primeiraCobranca) {
    }

    public Contratacao contratar(Comando comando) {
        if (!comando.papel().administraAConta()) {
            throw new OperacaoNaoPermitidaException("apenas o dono da conta pode contratar um plano");
        }
        // Validado antes de qualquer chamada externa: documento inválido nunca sai daqui.
        DocumentoDeCobranca documento = DocumentoDeCobranca.de(comando.documento());
        Plano plano = planoContratavel(comando.planoCodigo());

        return escopo.executarComo(comando.tenantId(), () -> {
            Assinatura assinatura = assinaturas.travarVigenteDoTenant(comando.tenantId())
                    .orElseThrow(SemAssinaturaVigenteException::new);
            if (assinatura.contratadaNoGateway()) {
                throw new AssinaturaJaContratadaException();
            }

            contas.registrarDocumentoDeCobranca(comando.tenantId(), documento);
            String nome = contas.nomeDoEscritorio().orElseThrow();
            String email = contas.emailDoDonoDaConta().orElseThrow();

            GatewayDePagamento.ClienteNoGateway cliente = gateway.garantirCliente(
                    new GatewayDePagamento.DadosDoCliente(
                            comando.tenantId().toString(), nome, email, documento.digitos()));

            LocalDate primeiraCobranca = primeiraCobranca(assinatura, LocalDate.now(FUSO));
            // A referência é a assinatura, não o tenant: um escritório que cancela e volta
            // tem outra assinatura, e não pode reencontrar a antiga como se fosse a nova.
            GatewayDePagamento.AssinaturaNoGateway criada = gateway.garantirAssinatura(
                    new GatewayDePagamento.NovaAssinatura(
                            assinatura.id().toString(), cliente.id(), plano.codigo(),
                            plano.precoCentavos(), primeiraCobranca));

            // A data gravada é a que nós pedimos. O Asaas gera a primeira fatura ao criar a
            // assinatura e devolve nextDueDate já no ciclo seguinte — gravar aquilo mostraria
            // ao advogado uma cobrança um mês depois da real.
            assinaturas.registrarContratacao(assinatura.id(), cliente.id(), criada.id(),
                    plano.codigo(), primeiraCobranca.atStartOfDay(FUSO).toInstant());

            log.info("plano {} contratado para a assinatura {}; primeira cobrança em {}",
                    plano.codigo(), assinatura.id(), primeiraCobranca);
            return new Contratacao(plano.codigo(), documento.mascarado(), primeiraCobranca);
        });
    }

    /**
     * Situação da contratação, para a interface não oferecer de novo o que já foi feito.
     *
     * @param planoPendente plano escolhido aguardando o primeiro pagamento, ou nulo.
     */
    public record Situacao(boolean contratado, String planoPendente, LocalDate proximaCobranca) {
    }

    public Situacao situacao(UUID tenantId) {
        return escopo.executarComo(tenantId, () -> assinaturas.vigenteDoTenant(tenantId)
                .map(a -> new Situacao(a.contratadaNoGateway(), a.planoContratado(),
                        a.proximaCobranca() == null ? null : a.proximaCobranca().atZone(FUSO).toLocalDate()))
                .orElse(new Situacao(false, null, null)));
    }

    /**
     * Quem contrata no meio da degustação já pagou por ela: a primeira mensalidade vence
     * quando a degustação acaba, e não amanhã. Fora da degustação, vence hoje.
     */
    static LocalDate primeiraCobranca(Assinatura assinatura, LocalDate hoje) {
        if (assinatura.status() == Assinatura.Status.TRIAL && assinatura.fimDoPeriodo() != null) {
            LocalDate fim = assinatura.fimDoPeriodo().atZone(FUSO).toLocalDate();
            return fim.isAfter(hoje) ? fim : hoje;
        }
        return hoje;
    }

    private Plano planoContratavel(String codigo) {
        return planos.listarAtivos().stream()
                .filter(plano -> plano.codigo().equals(codigo))
                // A degustação nasce com o cadastro e não é recorrente: assiná-la no gateway
                // cobraria todo mês um plano que só existe para os primeiros dias.
                .filter(plano -> !PLANO_DE_DEGUSTACAO.equals(plano.codigo()))
                .filter(plano -> plano.precoCentavos() > 0)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("plano indisponível para contratação"));
    }
}
