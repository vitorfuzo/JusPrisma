package br.com.jusprisma.aplicacao.conta;

/**
 * E-mail inexistente, e-mail malformado e senha errada levantam esta mesma exceção, com
 * esta mesma mensagem, de propósito: distinguir os casos para quem está do lado de fora
 * revelaria quais e-mails têm conta na plataforma.
 */
public class CredenciaisInvalidasException extends RuntimeException {

    public CredenciaisInvalidasException() {
        super("e-mail ou senha inválidos");
    }
}
