package br.com.jusprisma.dominio.conta;

import java.time.Instant;
import java.util.UUID;

/**
 * Um elo da cadeia de renovação de um login.
 *
 * <p>Cada login abre uma <em>família</em>. Cada renovação consome o elo corrente e cria o
 * próximo, na mesma família. Um elo só pode ser consumido uma vez.
 */
public record SessaoRefresh(
        UUID id,
        UUID familiaId,
        UUID tenantId,
        UUID usuarioId,
        Instant criadoEm,
        Instant expiraEm,
        Instant usadoEm,
        Instant revogadoEm,
        MotivoDeRevogacao motivoRevogacao) {

    public enum MotivoDeRevogacao {
        LOGOUT,
        REUSO_DETECTADO,
        ROTACAO,
        TROCA_DE_SENHA
    }

    /** Primeiro elo de uma família nova — o que um login cria. */
    public static SessaoRefresh abrirFamilia(UUID tenantId, UUID usuarioId, Instant expiraEm) {
        UUID familia = UUID.randomUUID();
        return new SessaoRefresh(
                UUID.randomUUID(), familia, tenantId, usuarioId,
                Instant.now(), expiraEm, null, null, null);
    }

    /** Próximo elo da mesma família — o que uma renovação cria. */
    public SessaoRefresh proximoElo(Instant expiraEm) {
        return new SessaoRefresh(
                UUID.randomUUID(), familiaId, tenantId, usuarioId,
                Instant.now(), expiraEm, null, null, null);
    }

    public boolean jaConsumida() {
        return usadoEm != null;
    }

    public boolean revogada() {
        return revogadoEm != null;
    }

    public boolean expirada(Instant agora) {
        return !agora.isBefore(expiraEm);
    }

    /**
     * Verdadeiro apenas quando o elo pode ser trocado por um novo.
     *
     * <p>Note que {@link #jaConsumida()} não é simplesmente o contrário disto: um elo já
     * consumido não é só "inutilizável", é indício de reuso e dispara a revogação da
     * família. Quem chama precisa distinguir os dois casos.
     */
    public boolean utilizavel(Instant agora) {
        return !jaConsumida() && !revogada() && !expirada(agora);
    }
}
