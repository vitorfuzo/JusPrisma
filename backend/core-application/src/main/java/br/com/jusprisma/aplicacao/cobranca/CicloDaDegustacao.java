package br.com.jusprisma.aplicacao.cobranca;

import br.com.jusprisma.aplicacao.conta.RepositorioDeConta;
import br.com.jusprisma.aplicacao.porta.EnviadorDeEmail;
import br.com.jusprisma.aplicacao.porta.EscopoDeTenant;
import br.com.jusprisma.dominio.plano.Assinatura;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Avisa e converte a degustação.
 *
 * <p>A conversão automática ao fim dos 14 dias é o desenho do produto, e o aviso três dias
 * antes é a contrapartida. Converter sem avisar é tecnicamente possível e comercialmente
 * ruinoso: gera chargeback, reclamação pública e cancelamento por irritação, não por preço.
 * Custa um e-mail evitar os três.
 */
@Service
public class CicloDaDegustacao {

    /** Aviso no dia 11 de 14: três dias de antecedência. */
    public static final Duration ANTECEDENCIA_DO_AVISO = Duration.ofDays(3);

    private static final Logger log = LoggerFactory.getLogger(CicloDaDegustacao.class);

    private final RepositorioDeEventoDeCobranca cobranca;
    private final RepositorioDeConta contas;
    private final EnviadorDeEmail email;
    private final EscopoDeTenant escopo;

    public CicloDaDegustacao(RepositorioDeEventoDeCobranca cobranca,
                             RepositorioDeConta contas,
                             EnviadorDeEmail email,
                             EscopoDeTenant escopo) {
        this.cobranca = cobranca;
        this.contas = contas;
        this.email = email;
        this.escopo = escopo;
    }

    /**
     * Envia o aviso às degustações que terminam em breve.
     *
     * @return quantos avisos foram enviados
     */
    public int avisarQuemEstaPertoDoFim() {
        Instant limite = Instant.now().plus(ANTECEDENCIA_DO_AVISO);
        List<RepositorioDeEventoDeCobranca.TrialPendente> pendentes = cobranca.trialsAAvisar(limite);

        int enviados = 0;
        for (RepositorioDeEventoDeCobranca.TrialPendente trial : pendentes) {
            try {
                String destinatario = escopo.executarComo(trial.tenantId(),
                        () -> contas.emailDoDonoDaConta().orElse(null));

                if (destinatario == null) {
                    log.warn("degustação {} sem dono identificável; aviso não enviado",
                            trial.assinaturaId());
                    continue;
                }

                email.enviarAvisoDeFimDaDegustacao(destinatario, trial.fimDoPeriodo());

                // Marca só depois do envio. Marcar antes faria uma falha de SMTP virar
                // silêncio permanente: o job não tentaria de novo e o cliente seria
                // cobrado sem nunca ter sido avisado.
                cobranca.marcarAvisoEnviado(trial.assinaturaId());
                enviados++;
            } catch (RuntimeException e) {
                // Uma falha não pode interromper a fila: o próximo cliente também merece
                // o aviso, e a próxima execução do job repete quem ficou para trás.
                log.error("falha ao avisar sobre o fim da degustação {}", trial.assinaturaId(), e);
            }
        }

        if (enviados > 0) {
            log.info("{} avisos de fim de degustação enviados", enviados);
        }
        return enviados;
    }

    /**
     * Converte as degustações vencidas.
     *
     * <p>Hoje a conversão apenas marca a assinatura como inadimplente, porque não há meio
     * de pagamento cadastrado enquanto a integração de cobrança não estiver ligada de
     * verdade. Inadimplente mantém o acesso, o que é a escolha correta para este estado
     * intermediário: melhor um cliente usando de graça por alguns dias do que um cliente
     * pagante barrado por um erro nosso de integração.
     *
     * @return quantas assinaturas mudaram de estado
     */
    public int converterVencidas() {
        List<RepositorioDeEventoDeCobranca.TrialPendente> vencidas =
                cobranca.trialsAConverter(Instant.now());

        for (RepositorioDeEventoDeCobranca.TrialPendente trial : vencidas) {
            cobranca.atualizarStatus(trial.assinaturaId(), Assinatura.Status.INADIMPLENTE, null);
            log.info("degustação {} terminou; aguardando confirmação de pagamento",
                    trial.assinaturaId());
        }

        return vencidas.size();
    }
}
