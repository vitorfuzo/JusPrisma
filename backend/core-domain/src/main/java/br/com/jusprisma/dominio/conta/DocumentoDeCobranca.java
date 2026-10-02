package br.com.jusprisma.dominio.conta;

/**
 * CPF ou CNPJ de quem paga a assinatura.
 *
 * <p>O gateway de cobrança recusa criar assinatura sem um deles, e o advogado autônomo só
 * tem CPF. Quando é CPF, é dado pessoal: por isso {@link #toString()} e
 * {@link #mascarado()} nunca devolvem o número inteiro, e a mensagem de erro não o repete.
 */
public record DocumentoDeCobranca(Tipo tipo, String digitos) {

    public enum Tipo { CPF, CNPJ }

    private static final int[] PESOS_CNPJ_1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] PESOS_CNPJ_2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    public DocumentoDeCobranca {
        if (tipo == null || digitos == null) {
            throw new IllegalArgumentException("documento de cobrança incompleto");
        }
    }

    /** Aceita com ou sem máscara. */
    public static DocumentoDeCobranca de(String informado) {
        String digitos = informado == null ? "" : informado.replaceAll("[.\\-/\\s]", "");
        if (!digitos.matches("\\d+")) {
            throw new IllegalArgumentException("documento deve conter apenas números");
        }
        if (digitos.chars().distinct().count() == 1) {
            // 111.111.111-11 passa no cálculo do dígito e não pertence a ninguém.
            throw new IllegalArgumentException("documento inválido");
        }
        return switch (digitos.length()) {
            case 11 -> {
                if (!cpfValido(digitos)) {
                    throw new IllegalArgumentException("CPF inválido");
                }
                yield new DocumentoDeCobranca(Tipo.CPF, digitos);
            }
            case 14 -> {
                if (!cnpjValido(digitos)) {
                    throw new IllegalArgumentException("CNPJ inválido");
                }
                yield new DocumentoDeCobranca(Tipo.CNPJ, digitos);
            }
            default -> throw new IllegalArgumentException("informe um CPF (11 dígitos) ou CNPJ (14 dígitos)");
        };
    }

    /** Para exibir ao dono da conta: suficiente para reconhecer, insuficiente para usar. */
    public String mascarado() {
        return tipo == Tipo.CPF
                ? "***.%s.%s-**".formatted(digitos.substring(3, 6), digitos.substring(6, 9))
                : "**.%s.%s/%s-**".formatted(
                        digitos.substring(2, 5), digitos.substring(5, 8), digitos.substring(8, 12));
    }

    @Override
    public String toString() {
        return "DocumentoDeCobranca[" + tipo + " " + mascarado() + "]";
    }

    private static boolean cpfValido(String cpf) {
        return digitoCpf(cpf, 9) == cpf.charAt(9) - '0' && digitoCpf(cpf, 10) == cpf.charAt(10) - '0';
    }

    private static int digitoCpf(String cpf, int tamanho) {
        int soma = 0;
        for (int i = 0; i < tamanho; i++) {
            soma += (cpf.charAt(i) - '0') * (tamanho + 1 - i);
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    private static boolean cnpjValido(String cnpj) {
        return digitoCnpj(cnpj, PESOS_CNPJ_1) == cnpj.charAt(12) - '0'
                && digitoCnpj(cnpj, PESOS_CNPJ_2) == cnpj.charAt(13) - '0';
    }

    private static int digitoCnpj(String cnpj, int[] pesos) {
        int soma = 0;
        for (int i = 0; i < pesos.length; i++) {
            soma += (cnpj.charAt(i) - '0') * pesos[i];
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }
}
