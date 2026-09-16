package br.com.jusprisma.aplicacao.conta;

/**
 * Convite desconhecido, expirado, já aceito ou revogado. Mensagem única para todos.
 */
public class ConviteInvalidoException extends RuntimeException {

    public ConviteInvalidoException() {
        super("convite inválido ou expirado");
    }
}
