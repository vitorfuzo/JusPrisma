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

    void marcarProcessado(String gateway, String idExterno);

    void marcarErro(String gateway, String idExterno, String erro);

    Optional<AssinaturaLocalizada> porIdentificadorDoGateway(String assinaturaNoGateway);

    List<TrialPendente> trialsAAvisar(Instant limite);

    List<TrialPendente> trialsAConverter(Instant agora);

    void marcarAvisoEnviado(UUID assinaturaId);

    void atualizarStatus(UUID assinaturaId, Assinatura.Status status, Instant proximaCobranca);

    record AssinaturaLocalizada(UUID assinaturaId, UUID tenantId, String planoCodigo, String status) {
    }

    record TrialPendente(UUID assinaturaId, UUID tenantId, Instant fimDoPeriodo) {
    }
}
