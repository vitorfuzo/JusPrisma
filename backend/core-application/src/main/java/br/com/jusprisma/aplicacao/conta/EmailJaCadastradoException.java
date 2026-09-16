package br.com.jusprisma.aplicacao.conta;

import br.com.jusprisma.dominio.conta.Email;

public class EmailJaCadastradoException extends RuntimeException {

    private final transient Email email;

    public EmailJaCadastradoException(Email email) {
        super("já existe conta para este e-mail");
        this.email = email;
    }

    public Email email() {
        return email;
    }
}
