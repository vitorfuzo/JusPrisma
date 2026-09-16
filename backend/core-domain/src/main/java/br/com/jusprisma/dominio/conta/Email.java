package br.com.jusprisma.dominio.conta;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Endereço de e-mail, já normalizado.
 *
 * <p>Existe como tipo porque o e-mail é a credencial de login: comparar
 * {@code "Ana@Exemplo.com"} com {@code "ana@exemplo.com"} como String crua permitiria criar
 * duas contas para a mesma pessoa, e o índice único do banco é sobre {@code lower(email)}.
 * Normalizar no domínio garante que a comparação em memória e a do banco concordem.
 *
 * <p>A validação é deliberadamente frouxa. Validar e-mail por expressão regular estrita
 * rejeita endereços válidos e não prova entrega; quem prova é a verificação por link.
 */
public record Email(String valor) {

    private static final Pattern FORMATO_MINIMO = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");
    private static final int TAMANHO_MAXIMO = 254; // RFC 5321

    public Email {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("e-mail é obrigatório");
        }
        valor = valor.trim().toLowerCase(Locale.ROOT);

        if (valor.length() > TAMANHO_MAXIMO) {
            throw new IllegalArgumentException("e-mail excede " + TAMANHO_MAXIMO + " caracteres");
        }
        if (!FORMATO_MINIMO.matcher(valor).matches()) {
            throw new IllegalArgumentException("e-mail em formato inválido");
        }
    }

    public static Email de(String valor) {
        return new Email(valor);
    }

    @Override
    public String toString() {
        return valor;
    }
}
