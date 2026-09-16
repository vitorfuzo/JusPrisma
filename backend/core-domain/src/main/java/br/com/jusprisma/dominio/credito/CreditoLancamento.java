package br.com.jusprisma.dominio.credito;

import java.time.Instant;
import java.util.UUID;

/**
 * Um lançamento no ledger de créditos.
 *
 * <p>Append-only: não existe alterar nem apagar. Estorno é um lançamento novo, de sinal
 * oposto, apontando para o original. É o que permite reconstruir o extrato de qualquer
 * tenant em qualquer data — requisito de auditoria, não preferência de estilo.
 */
public record CreditoLancamento(
        Long id,
        UUID tenantId,
        TipoCredito tipo,
        int delta,
        String motivo,
        String referenciaId,
        Long estornoDe,
        Instant criadoEm) {

    public CreditoLancamento {
        if (tenantId == null) {
            throw new IllegalArgumentException("lançamento sem tenant não existe");
        }
        if (tipo == null) {
            throw new IllegalArgumentException("tipo de crédito é obrigatório");
        }
        if (delta == 0) {
            // Lançamento nulo só polui o extrato e esconde erro de cálculo a montante.
            throw new IllegalArgumentException("lançamento de valor zero não faz sentido");
        }
        if (motivo == null || motivo.isBlank()) {
            // Sem motivo, o extrato vira uma lista de números que ninguém consegue explicar
            // ao cliente seis meses depois.
            throw new IllegalArgumentException("todo lançamento precisa de motivo");
        }
    }

    public static CreditoLancamento credito(
            UUID tenantId, TipoCredito tipo, int quantidade, String motivo, String referenciaId) {
        if (quantidade <= 0) {
            throw new IllegalArgumentException("crédito precisa ser positivo: " + quantidade);
        }
        return new CreditoLancamento(
                null, tenantId, tipo, quantidade, motivo, referenciaId, null, Instant.now());
    }

    public static CreditoLancamento debito(
            UUID tenantId, TipoCredito tipo, int quantidade, String motivo, String referenciaId) {
        if (quantidade <= 0) {
            throw new IllegalArgumentException("débito precisa ser positivo: " + quantidade);
        }
        return new CreditoLancamento(
                null, tenantId, tipo, -quantidade, motivo, referenciaId, null, Instant.now());
    }

    /** O estorno deste lançamento: mesmo valor, sinal oposto, apontando para ele. */
    public CreditoLancamento estornar(String motivo) {
        if (id == null) {
            throw new IllegalStateException("não se estorna lançamento que ainda não foi gravado");
        }
        return new CreditoLancamento(
                null, tenantId, tipo, -delta, motivo, referenciaId, id, Instant.now());
    }

    public boolean ehDebito() {
        return delta < 0;
    }
}
