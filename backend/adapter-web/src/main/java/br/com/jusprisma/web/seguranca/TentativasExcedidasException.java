package br.com.jusprisma.web.seguranca;

import java.time.Duration;

/**
 * Tentativas demais na janela. Responde 429 com Retry-After.
 */
public class TentativasExcedidasException extends RuntimeException {

    private final transient Duration esperar;

    public TentativasExcedidasException(Duration esperar) {
        super("tentativas demais; tente novamente mais tarde");
        this.esperar = esperar;
    }

    public Duration esperar() {
        return esperar;
    }
}
