package br.com.jusprisma.worker;

import br.com.jusprisma.aplicacao.cobranca.CicloDaDegustacao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Roda o ciclo da degustação uma vez por dia.
 *
 * <p>Diário, e não de hora em hora, porque a granularidade do produto é o dia: o aviso é
 * "três dias antes" e a conversão é "ao fim do período". Rodar com mais frequência não
 * adianta nada e multiplica o custo de uma falha.
 *
 * <p>As duas operações são idempotentes por construção — o aviso só sai para quem ainda
 * não recebeu, e a conversão só age sobre quem ainda está em degustação vencida. Isso
 * importa porque um job diário vai, mais cedo ou mais tarde, rodar duas vezes no mesmo dia:
 * por reinício, por implantação, por engano.
 */
@Component
public class CicloDeCobrancaJob {

    private static final Logger log = LoggerFactory.getLogger(CicloDeCobrancaJob.class);

    private final CicloDaDegustacao ciclo;
    private final boolean habilitado;

    public CicloDeCobrancaJob(CicloDaDegustacao ciclo,
                              @Value("${jusprisma.jobs.habilitados:true}") boolean habilitado) {
        this.ciclo = ciclo;
        this.habilitado = habilitado;
    }

    /** Às 9h no fuso de Brasília: e-mail de manhã é lido; e-mail de madrugada é ignorado. */
    @Scheduled(cron = "0 0 9 * * *", zone = "America/Sao_Paulo")
    public void executar() {
        if (!habilitado) {
            return;
        }

        try {
            int avisados = ciclo.avisarQuemEstaPertoDoFim();
            int convertidas = ciclo.converterVencidas();

            if (avisados > 0 || convertidas > 0) {
                log.info("ciclo de cobrança: {} avisos enviados, {} degustações encerradas",
                        avisados, convertidas);
            }
        } catch (RuntimeException e) {
            // Falha aqui não pode derrubar o agendador: se a exceção escapar, o Spring
            // cancela as execuções seguintes desta tarefa e ninguém mais é avisado.
            log.error("falha no ciclo de cobrança; será retomado na próxima execução", e);
        }
    }
}
