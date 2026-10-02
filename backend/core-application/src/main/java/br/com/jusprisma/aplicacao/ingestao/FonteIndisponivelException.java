package br.com.jusprisma.aplicacao.ingestao;

/**
 * A fonte não respondeu, ou respondeu com erro dela. A ingestão é idempotente por chave
 * natural, então tentar de novo mais tarde é seguro.
 */
public class FonteIndisponivelException extends RuntimeException {

    public FonteIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
