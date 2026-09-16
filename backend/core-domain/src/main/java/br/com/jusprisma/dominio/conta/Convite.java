package br.com.jusprisma.dominio.conta;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Convite para alguém entrar num escritório já existente.
 *
 * <p>Aceitar um convite não é só ganhar login: é passar a enxergar os processos e os
 * clientes daquele escritório. Por isso o prazo é curto e o segredo só existe no e-mail.
 */
public record Convite(
        UUID id,
        UUID tenantId,
        String nomeDoEscritorio,
        UUID convidadoPor,
        Email email,
        Papel papel,
        Instant criadoEm,
        Instant expiraEm,
        Instant aceitoEm,
        Instant revogadoEm) {

    /**
     * Sete dias. Longo o bastante para alguém voltar de férias, curto o bastante para que
     * um convite esquecido em caixa de e-mail não vire porta de entrada meses depois.
     */
    public static final Duration VALIDADE = Duration.ofDays(7);

    public Convite {
        if (tenantId == null) {
            throw new IllegalArgumentException("convite sem tenant não existe");
        }
        if (email == null) {
            throw new IllegalArgumentException("e-mail do convidado é obrigatório");
        }
        if (papel == null) {
            throw new IllegalArgumentException("papel do convidado é obrigatório");
        }
    }

    public static Convite novo(UUID tenantId, UUID convidadoPor, Email email, Papel papel) {
        Instant agora = Instant.now();
        return new Convite(
                UUID.randomUUID(), tenantId, null, convidadoPor, email, papel,
                agora, agora.plus(VALIDADE), null, null);
    }

    public boolean pendente(Instant agora) {
        return aceitoEm == null && revogadoEm == null && agora.isBefore(expiraEm);
    }
}
