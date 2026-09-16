package br.com.jusprisma.aplicacao.conta;

/**
 * Token de e-mail desconhecido, expirado, já usado ou de outra finalidade.
 *
 * <p>Uma mensagem só para todos os casos: dizer "este link já foi usado" confirma a quem
 * interceptou o e-mail que o link chegou a existir e pertencia a uma conta real.
 */
public class TokenInvalidoException extends RuntimeException {

    public TokenInvalidoException() {
        super("link inválido ou expirado");
    }
}
