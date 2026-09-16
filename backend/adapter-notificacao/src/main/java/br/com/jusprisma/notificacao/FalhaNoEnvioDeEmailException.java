package br.com.jusprisma.notificacao;

public class FalhaNoEnvioDeEmailException extends RuntimeException {

    public FalhaNoEnvioDeEmailException(Throwable causa) {
        super("não foi possível enviar o e-mail", causa);
    }
}
