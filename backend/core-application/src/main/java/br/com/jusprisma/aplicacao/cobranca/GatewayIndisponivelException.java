package br.com.jusprisma.aplicacao.cobranca;

/**
 * O gateway não respondeu ou respondeu com erro dele. Nada foi recusado; tentar de novo
 * mais tarde é seguro porque a contratação é idempotente.
 */
public class GatewayIndisponivelException extends RuntimeException {

    public GatewayIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
