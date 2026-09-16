package br.com.jusprisma.dominio.plano;

import java.time.Instant;
import java.util.UUID;

/**
 * O vínculo entre um escritório e o plano que ele paga.
 */
public record Assinatura(
        UUID id,
        UUID tenantId,
        String planoCodigo,
        Status status,
        Instant inicioEm,
        Instant fimDoPeriodo,
        Instant proximaCobranca,
        String gatewayCustomerId,
        String gatewaySubscriptionId) {

    public enum Status {
        /** Degustação paga, converte automaticamente ao fim do período. */
        TRIAL,
        ATIVA,
        /** Cobrança falhou. Mantém acesso por um período de tolerância antes de cancelar. */
        INADIMPLENTE,
        CANCELADA
    }

    public Assinatura {
        if (tenantId == null) {
            throw new IllegalArgumentException("assinatura sem tenant não existe");
        }
        if (planoCodigo == null || planoCodigo.isBlank()) {
            throw new IllegalArgumentException("assinatura sem plano não existe");
        }
    }

    public static Assinatura iniciarTrial(UUID tenantId, String planoCodigo, Instant fimDoTrial) {
        return new Assinatura(
                UUID.randomUUID(), tenantId, planoCodigo, Status.TRIAL,
                Instant.now(), fimDoTrial, fimDoTrial, null, null);
    }

    /**
     * Se a assinatura dá direito de uso agora.
     *
     * <p>Inadimplente continua valendo de propósito: cortar o acesso no primeiro boleto
     * atrasado perde cliente que só trocou de cartão. O corte acontece quando a assinatura
     * é efetivamente cancelada.
     */
    public boolean vigente() {
        return status == Status.TRIAL || status == Status.ATIVA || status == Status.INADIMPLENTE;
    }
}
