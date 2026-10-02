package br.com.jusprisma.aplicacao.cobranca;

import br.com.jusprisma.dominio.plano.Assinatura;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepositorioDeEventoDeCobranca {

    /**
     * Registra a chegada do evento.
     *
     * @return false se o evento já havia sido registrado antes — é assim que a
     *         reentrega do gateway é detectada, pela unicidade no banco e não por
     *         uma consulta prévia que perderia a corrida entre duas entregas simultâneas.
     */
    boolean registrarSeNovo(String gateway, EventoDeCobranca evento);

    /**
     * Reserva o efeito de um pagamento para este evento.
     *
     * @return false se outro evento do mesmo pagamento já o aplicou — a confirmação e o
     *         recebimento do mesmo cartão, por exemplo. Decidido pela unicidade no banco,
     *         como em {@link #registrarSeNovo}.
     */
    boolean registrarPagamentoSeNovo(String gateway, String pagamentoNoGateway, String idDoEvento);

    void marcarProcessado(String gateway, String idExterno);

    void marcarErro(String gateway, String idExterno, String erro);

    Optional<AssinaturaLocalizada> porIdentificadorDoGateway(String assinaturaNoGateway);

    List<TrialPendente> trialsAAvisar(Instant limite);

    List<TrialPendente> trialsAConverter(Instant agora);

    void marcarAvisoEnviado(UUID assinaturaId);

    void atualizarStatus(UUID assinaturaId, Assinatura.Status status, Instant proximaCobranca);

    /**
     * Troca o plano vigente pelo plano contratado e pendente de pagamento, se houver.
     *
     * @return o plano que passou a valer, ou vazio se não havia contratação pendente — o caso
     *         da renovação mensal e da notificação reentregue.
     */
    Optional<String> efetivarPlanoContratado(UUID assinaturaId);

    /**
     * Cancela a assinatura honrando o período já pago: agenda o fim do acesso para a próxima
     * cobrança (ou o fim da degustação) quando ela está no futuro, e cancela já nos demais
     * casos. Não reagenda um cancelamento já agendado.
     *
     * @return o status resultante, ou vazio se não havia o que cancelar.
     */
    Optional<Assinatura.Status> agendarCancelamento(UUID assinaturaId, Instant agora);

    /** Encerra as assinaturas cujo fim de acesso agendado já passou. */
    int encerrarCancelamentosVencidos(Instant agora);

    record AssinaturaLocalizada(UUID assinaturaId, UUID tenantId, String planoCodigo, String status) {
    }

    record TrialPendente(UUID assinaturaId, UUID tenantId, Instant fimDoPeriodo) {
    }
}
