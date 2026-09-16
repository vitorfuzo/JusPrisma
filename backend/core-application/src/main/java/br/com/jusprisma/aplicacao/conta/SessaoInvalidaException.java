package br.com.jusprisma.aplicacao.conta;

/**
 * Token de renovação ausente, desconhecido, expirado, revogado ou reusado.
 *
 * <p>Mensagem única para todos os casos: dizer a alguém que o token "já foi usado" versus
 * "não existe" informa se ele capturou um token que já circulou de verdade.
 */
public class SessaoInvalidaException extends RuntimeException {

    public SessaoInvalidaException() {
        super("sessão inválida ou expirada");
    }
}
