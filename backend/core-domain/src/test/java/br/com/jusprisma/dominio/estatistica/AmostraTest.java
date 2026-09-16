package br.com.jusprisma.dominio.estatistica;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cobre especificamente a regra inviolável 2 do CLAUDE.md.
 */
class AmostraTest {

    @Nested
    @DisplayName("abaixo do mínimo não se gera percentual")
    class AmostraInsuficiente {

        @ParameterizedTest(name = "n={0}")
        @ValueSource(ints = {0, 1, 7, 19})
        void naoProduzPercentual(int n) {
            long parte = Math.min(1L, n); // parte válida para a amostra, inclusive n=0
            assertThat(Amostra.de(n).percentual(parte))
                    .as("amostra de %d está abaixo do mínimo de %d", n, Amostra.MINIMO)
                    .isEmpty();
        }

        @Test
        @DisplayName("19 decisões não bastam, 20 bastam")
        void limiteExato() {
            assertThat(Amostra.de(Amostra.MINIMO - 1).suficiente()).isFalse();
            assertThat(Amostra.de(Amostra.MINIMO).suficiente()).isTrue();
        }

        @Test
        @DisplayName("mesmo com todas as decisões no mesmo sentido, não sai percentual")
        void unanimidadeNaoBurlaOMinimo() {
            // O caso perigoso: 10 de 10 é tentador de exibir como 100%.
            assertThat(Amostra.de(10).percentual(10L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("a partir do mínimo o percentual é publicável")
    class AmostraSuficiente {

        @Test
        void calculaComUmaCasaDecimal() {
            assertThat(Amostra.de(147).percentual(121L))
                    .contains(new BigDecimal("82.3"));
        }

        @Test
        void extremosSaoValidos() {
            assertThat(Amostra.de(20).percentual(0L)).contains(new BigDecimal("0.0"));
            assertThat(Amostra.de(20).percentual(20L)).contains(new BigDecimal("100.0"));
        }
    }

    @Nested
    @DisplayName("apuração inconsistente é erro, não número")
    class Invariantes {

        @Test
        void parteMaiorQueAmostra() {
            assertThatThrownBy(() -> Amostra.de(20).percentual(21L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("não cabe na amostra");
        }

        @Test
        void parteNegativa() {
            assertThatThrownBy(() -> Amostra.de(20).percentual(-1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void amostraNegativa() {
            assertThatThrownBy(() -> Amostra.de(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("não pode ser negativa");
        }
    }
}
