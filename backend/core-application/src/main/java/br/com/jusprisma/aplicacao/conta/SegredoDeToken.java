package br.com.jusprisma.aplicacao.conta;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Gera o valor secreto de um token de e-mail e calcula o hash que vai para o banco.
 *
 * <p>Separado do resto para que exista um único lugar onde o segredo é criado e um único
 * lugar onde ele é transformado em hash. Dois caminhos independentes fazendo isso é como
 * surge a discrepância que faz "meu link não funciona".
 */
public final class SegredoDeToken {

    /**
     * 32 bytes de entropia. O token viaja em URL e fica em caixa de e-mail e em log de
     * servidor intermediário — precisa ser impossível de adivinhar mesmo sabendo o horário
     * exato em que foi emitido.
     */
    private static final int BYTES = 32;

    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final Base64.Encoder CODIFICADOR = Base64.getUrlEncoder().withoutPadding();

    private SegredoDeToken() {
    }

    /** Valor que vai no link do e-mail. Nunca é persistido. */
    public static String gerar() {
        byte[] bytes = new byte[BYTES];
        ALEATORIO.nextBytes(bytes);
        return CODIFICADOR.encodeToString(bytes);
    }

    /** O que efetivamente é guardado e comparado. */
    public static byte[] hash(String segredo) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(segredo.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível nesta JVM", e);
        }
    }
}
