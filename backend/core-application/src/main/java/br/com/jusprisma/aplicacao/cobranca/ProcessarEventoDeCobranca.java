package br.com.jusprisma.aplicacao.cobranca;

import br.com.jusprisma.aplicacao.credito.RecargaDeCreditos;
import br.com.jusprisma.dominio.plano.Assinatura;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Reage às notificações do gateway de cobrança.
 *
 * <p>O ponto central é a idempotência. Todo gateway reentrega webhook — por timeout nosso,
 * por falha de rede, por retentativa programada. Processar "pagamento confirmado" duas
 * vezes daria crédito em dobro; processar "assinatura cancelada" duas vezes não faria mal,
 * mas não dá para contar com sorte caso a caso. O registro do evento acontece antes do
 * efeito, e a unicidade no banco é o que decide se aquilo já foi visto.
 */
@Service
public class ProcessarEventoDeCobranca {

    private static final Logger log = LoggerFactory.getLogger(ProcessarEventoDeCobranca.class);

    private final RepositorioDeEventoDeCobranca eventos;
    private final RecargaDeCreditos recarga;
    private final GatewayDePagamento gateway;

    public ProcessarEventoDeCobranca(RepositorioDeEventoDeCobranca eventos,
                                     RecargaDeCreditos recarga,
                                     GatewayDePagamento gateway) {
        this.eventos = eventos;
        this.recarga = recarga;
        this.gateway = gateway;
    }

    public enum Resultado {
        PROCESSADO,
        /** Já tínhamos visto este evento. Resposta de sucesso, sem repetir o efeito. */
        REPETIDO,
        /** Registrado para auditoria, sem efeito no domínio. */
        IGNORADO
    }

    public Resultado processar(EventoDeCobranca evento) {
        if (!eventos.registrarSeNovo(gateway.nome(), evento)) {
            log.info("evento {} do {} já havia sido processado; nada a fazer",
                    evento.idExterno(), gateway.nome());
            return Resultado.REPETIDO;
        }

        try {
            Resultado resultado = aplicar(evento);
            eventos.marcarProcessado(gateway.nome(), evento.idExterno());
            return resultado;
        } catch (RuntimeException e) {
            // O evento fica registrado com o erro, e não some. Uma varredura posterior
            // encontra o que entrou e não completou — sem isso, uma falha transitória
            // significaria perder silenciosamente um pagamento confirmado.
            eventos.marcarErro(gateway.nome(), evento.idExterno(), e.toString());
            log.error("falha ao processar evento {} do {}", evento.idExterno(), gateway.nome(), e);
            throw e;
        }
    }

    private Resultado aplicar(EventoDeCobranca evento) {
        if (evento.tipo() == EventoDeCobranca.Tipo.DESCONHECIDO) {
            return Resultado.IGNORADO;
        }

        Optional<RepositorioDeEventoDeCobranca.AssinaturaLocalizada> encontrada =
                eventos.porIdentificadorDoGateway(evento.assinaturaNoGateway());

        if (encontrada.isEmpty()) {
            // Acontece de verdade: evento de teste do painel do gateway, assinatura criada
            // noutro ambiente. Registrar e seguir é melhor que falhar e provocar retentativa
            // infinita de algo que nunca vai casar.
            log.warn("evento {} referencia assinatura desconhecida no gateway: {}",
                    evento.idExterno(), evento.assinaturaNoGateway());
            return Resultado.IGNORADO;
        }

        RepositorioDeEventoDeCobranca.AssinaturaLocalizada assinatura = encontrada.get();

        switch (evento.tipo()) {
            case PAGAMENTO_CONFIRMADO -> {
                eventos.atualizarStatus(assinatura.assinaturaId(),
                        Assinatura.Status.ATIVA, evento.proximaCobranca());
                // O plano contratado só passa a valer aqui, antes da recarga: a recarga lê o
                // plano vigente, e as cotas do primeiro período têm que ser as do plano pago.
                eventos.efetivarPlanoContratado(assinatura.assinaturaId())
                        .ifPresent(plano -> log.info("assinatura {} passou ao plano {}",
                                assinatura.assinaturaId(), plano));
                // Pagou, recebe as cotas do período. É o único lugar onde a recarga
                // recorrente acontece: crédito só entra contra dinheiro que entrou.
                recarga.abastecer(assinatura.tenantId(), "renovação do plano");
                log.info("assinatura {} ativada por pagamento confirmado", assinatura.assinaturaId());
            }
            case PAGAMENTO_FALHOU -> {
                // Inadimplente mantém acesso de propósito — ver Assinatura.vigente().
                // Cortar no primeiro boleto atrasado perde cliente que só trocou de cartão.
                eventos.atualizarStatus(assinatura.assinaturaId(),
                        Assinatura.Status.INADIMPLENTE, null);
                log.warn("assinatura {} marcada inadimplente", assinatura.assinaturaId());
            }
            case ASSINATURA_CANCELADA -> {
                eventos.atualizarStatus(assinatura.assinaturaId(),
                        Assinatura.Status.CANCELADA, null);
                log.info("assinatura {} cancelada", assinatura.assinaturaId());
            }
            default -> {
                return Resultado.IGNORADO;
            }
        }

        return Resultado.PROCESSADO;
    }
}
