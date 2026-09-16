package br.com.jusprisma.dominio.estatistica;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * O tamanho da amostra que sustenta uma métrica, e a decisão de publicar ou não um
 * percentual sobre ela.
 *
 * <p>Materializa a regra inviolável 2 do CLAUDE.md: todo percentual exibe o {@code n} que o
 * gerou, e abaixo de {@link #MINIMO} decisões não se gera percentual — só o número
 * absoluto, marcado como amostra insuficiente.
 *
 * <p>O mínimo é constante de domínio e não vem de configuração de propósito. Se fosse
 * ajustável por ambiente, bastaria baixá-lo em produção para o sistema passar a publicar
 * percentual sobre 3 decisões sem que nenhum teste reclamasse — que é exatamente o risco
 * jurídico que a regra existe para eliminar.
 *
 * <p>O gate é <strong>por métrica</strong>, não por perfil: um relator com 4.000 acórdãos
 * ainda cai abaixo do mínimo quando o recorte fatia por assunto.
 */
public record Amostra(int n) {

    /** Abaixo disto, nenhum percentual é publicado. */
    public static final int MINIMO = 20;

    private static final int CASAS_DECIMAIS = 1;

    public Amostra {
        if (n < 0) {
            throw new IllegalArgumentException("amostra não pode ser negativa: " + n);
        }
    }

    public static Amostra de(int n) {
        return new Amostra(n);
    }

    /** Verdadeiro quando a amostra autoriza a publicação de percentuais. */
    public boolean suficiente() {
        return n >= MINIMO;
    }

    /**
     * O percentual que {@code parte} representa na amostra, em pontos percentuais.
     *
     * @return vazio quando a amostra é insuficiente — o chamador deve exibir o número
     *         absoluto e a marcação de amostra insuficiente, nunca um percentual.
     * @throws IllegalArgumentException se {@code parte} não couber na amostra, o que
     *         indica erro de apuração e não deve ser exibido de forma alguma.
     */
    public Optional<BigDecimal> percentual(long parte) {
        if (parte < 0 || parte > n) {
            throw new IllegalArgumentException(
                    "parte %d não cabe na amostra de %d".formatted(parte, n));
        }
        if (!suficiente()) {
            return Optional.empty();
        }
        return Optional.of(BigDecimal.valueOf(parte)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(n), CASAS_DECIMAIS, RoundingMode.HALF_UP));
    }
}
