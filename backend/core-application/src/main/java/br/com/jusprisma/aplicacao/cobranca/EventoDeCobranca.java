package br.com.jusprisma.aplicacao.cobranca;

import java.time.Instant;

/**
 * Uma notificação do gateway, já traduzida para o vocabulário do domínio.
 *
 * <p>Traduzir na borda, e não deixar o nome do fornecedor circular, é o que permite trocar
 * de gateway sem tocar na regra de negócio: o caso de uso reage a "pagamento confirmado",
 * não a {@code PAYMENT_CONFIRMED} nem a {@code charge.succeeded}.
 */
public record EventoDeCobranca(
        String idExterno,
        Tipo tipo,
        String assinaturaNoGateway,
        Instant proximaCobranca,
        String corpoOriginal) {

    public enum Tipo {
        PAGAMENTO_CONFIRMADO,
        PAGAMENTO_FALHOU,
        ASSINATURA_CANCELADA,
        /** Recebido e registrado, mas sem efeito no domínio. Guardado para auditoria. */
        DESCONHECIDO
    }
}
