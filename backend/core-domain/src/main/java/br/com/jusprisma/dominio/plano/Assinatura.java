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
        String gatewaySubscriptionId,
        String planoContratado,
        /** Fim do acesso de uma assinatura cancelada com período já pago; nulo se não há. */
        Instant cancelaEm) {

    public enum Status {
        /** Degustação paga, converte automaticamente ao fim do período. */
        TRIAL,
        ATIVA,
        /** Cobrança falhou. Mantém acesso por um período de tolerância antes de cancelar. */
        INADIMPLENTE,
        CANCELADA,
        /**
         * Recontratação depois de um cancelamento, à espera do primeiro pagamento. Não dá
         * acesso: quem só clicou em contratar não volta a usar o produto antes de pagar.
         */
        AGUARDANDO_PAGAMENTO
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
                Instant.now(), fimDoTrial, fimDoTrial, null, null, null, null);
    }

    /** Assinatura nova de quem volta depois de cancelar. Sem degustação: cobra no dia. */
    public static Assinatura aguardandoPagamento(UUID tenantId, String planoCodigo) {
        return new Assinatura(
                UUID.randomUUID(), tenantId, planoCodigo, Status.AGUARDANDO_PAGAMENTO,
                Instant.now(), null, null, null, null, null, null);
    }

    /** Já existe assinatura criada no gateway: contratar de novo cobraria em dobro. */
    public boolean contratadaNoGateway() {
        return gatewaySubscriptionId != null;
    }

    /**
     * Se a assinatura dá direito de uso no instante informado.
     *
     * <p>Inadimplente continua valendo de propósito: cortar o acesso no primeiro boleto
     * atrasado perde cliente que só trocou de cartão. O corte acontece quando a assinatura
     * é efetivamente cancelada.
     *
     * <p>Cancelada com período pago vale até {@code cancelaEm}, e deixa de valer nesse
     * instante sem esperar o job que muda o status.
     */
    public boolean vigente(Instant agora) {
        boolean statusVigente = status == Status.TRIAL || status == Status.ATIVA
                || status == Status.INADIMPLENTE;
        return statusVigente && (cancelaEm == null || agora.isBefore(cancelaEm));
    }
}
