package br.com.jusprisma.aplicacao.conta;

/**
 * Credenciais corretas, mas a conta do escritório está suspensa ou cancelada.
 */
public class ContaIndisponivelException extends RuntimeException {

    public ContaIndisponivelException(String mensagem) {
        super(mensagem);
    }
}
