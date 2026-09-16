package br.com.jusprisma.aplicacao.conta;

/**
 * O usuário está autenticado, mas seu papel não permite a operação.
 */
public class OperacaoNaoPermitidaException extends RuntimeException {

    public OperacaoNaoPermitidaException(String mensagem) {
        super(mensagem);
    }
}
