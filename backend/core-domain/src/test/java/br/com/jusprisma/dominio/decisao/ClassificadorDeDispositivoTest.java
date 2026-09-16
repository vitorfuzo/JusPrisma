package br.com.jusprisma.dominio.decisao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classificação do dispositivo do acórdão.
 *
 * <p>As formas testadas aqui são as que o campo {@code decisao} do TJDFT realmente produz,
 * observadas numa amostra da API em 16/09/2026 — não são variações inventadas por simetria.
 *
 * <p>O risco que este teste cobre é sutil: "parcialmente provido" contém "provido", e
 * "negar provimento" contém "provimento". Uma ordem de regras errada classifica tudo como
 * provido e produz uma estatística plausível e completamente falsa.
 */
class ClassificadorDeDispositivoTest {

    @Nested
    @DisplayName("formas observadas nas fontes")
    class FormasReais {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "DESPROVIDO",
                "DESPROVIDOS",
                "NEGAR PROVIMENTO",
                "NEGAR PROVIMENTO AO RECURSO",
                "NEGAR PROVIMENTO AOS RECURSOS",
                "CONHECIDO. DESPROVIDO. UNÂNIME.",
        })
        void variantesDeImprovido(String dispositivo) {
            // As quatro primeiras formas apareceram numa única amostra de 25 registros.
            assertThat(ClassificadorDeDispositivo.classificar(dispositivo))
                    .isEqualTo(ResultadoDoRecurso.IMPROVIDO);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "PARCIALMENTE PROVIDO",
                "DAR PARCIAL PROVIMENTO AO RECURSO",
                "CONHECIDO. PARCIALMENTE PROVIDO. UNÂNIME.",
                "PROVIDO EM PARTE",
        })
        void variantesDeParcial(String dispositivo) {
            assertThat(ClassificadorDeDispositivo.classificar(dispositivo))
                    .isEqualTo(ResultadoDoRecurso.PARCIALMENTE_PROVIDO);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "PROVIDO",
                "PROVIDOS",
                "DAR PROVIMENTO AO RECURSO",
                "CONHECIDO. PROVIDO. UNÂNIME.",
        })
        void variantesDeProvido(String dispositivo) {
            assertThat(ClassificadorDeDispositivo.classificar(dispositivo))
                    .isEqualTo(ResultadoDoRecurso.PROVIDO);
        }
    }

    @Nested
    @DisplayName("a ordem das regras é o que impede o erro silencioso")
    class Precedencia {

        @Test
        @DisplayName("parcialmente provido não é lido como provido")
        void parcialNaoViraProvido() {
            // "PARCIALMENTE PROVIDO" contém "PROVIDO". Testar a forma curta primeiro
            // classificaria toda reforma parcial como reforma total.
            assertThat(ClassificadorDeDispositivo.classificar("PARCIALMENTE PROVIDO"))
                    .isNotEqualTo(ResultadoDoRecurso.PROVIDO);
        }

        @Test
        @DisplayName("negar provimento não é lido como provimento")
        void negativaNaoViraProvido() {
            // O erro mais caro possível: inverteria o resultado de metade do acervo.
            assertThat(ClassificadorDeDispositivo.classificar("NEGAR PROVIMENTO AO RECURSO"))
                    .isEqualTo(ResultadoDoRecurso.IMPROVIDO);
            assertThat(ClassificadorDeDispositivo.classificar("NÃO PROVIDO"))
                    .isEqualTo(ResultadoDoRecurso.IMPROVIDO);
        }

        @Test
        @DisplayName("não conhecido não é lido como conhecido e improvido")
        void naoConhecidoTemPrecedencia() {
            assertThat(ClassificadorDeDispositivo.classificar("NÃO CONHECIDO. UNÂNIME."))
                    .isEqualTo(ResultadoDoRecurso.NAO_CONHECIDO);
        }
    }

    @Nested
    @DisplayName("admissibilidade e mérito são eixos separados")
    class Admissibilidade {

        @Test
        @DisplayName("não conhecido não conta como manutenção da sentença")
        void naoConhecidoNaoEhMerito() {
            ResultadoDoRecurso resultado =
                    ClassificadorDeDispositivo.classificar("NÃO CONHECIDO");

            assertThat(resultado.ehDeMerito())
                    .as("somar não conhecido a improvido inflaria a taxa de manutenção")
                    .isFalse();
        }

        @Test
        @DisplayName("prejudicado é encerramento sem mérito")
        void prejudicadoSemMerito() {
            assertThat(ClassificadorDeDispositivo.classificar("RECURSO PREJUDICADO"))
                    .isEqualTo(ResultadoDoRecurso.SEM_RESOLUCAO_DE_MERITO);
        }
    }

    @Nested
    @DisplayName("o desconhecido é contado como desconhecido")
    class Indeterminado {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "",
                "   ",
                "DECISÃO PROFERIDA NOS TERMOS DO VOTO",
                "ACÓRDÃO REGISTRADO",
        })
        void formaNaoReconhecidaNaoEhChutada(String dispositivo) {
            // Empurrar o desconhecido para a categoria mais próxima faria uma decisão
            // entrar na estatística como se fosse outra coisa. Marcá-lo apenas reduz a
            // amostra, o que é honesto e fica visível no n.
            assertThat(ClassificadorDeDispositivo.classificar(dispositivo))
                    .isEqualTo(ResultadoDoRecurso.INDETERMINADO);
        }

        @Test
        @DisplayName("nulo não quebra")
        void nuloEhTolerado() {
            assertThat(ClassificadorDeDispositivo.classificar(null))
                    .isEqualTo(ResultadoDoRecurso.INDETERMINADO);
        }
    }

    @Nested
    @DisplayName("as regras precisam estar escritas na forma normalizada")
    class RegrasNormalizadas {

        @Test
        @DisplayName("nenhuma forma tem hifen, acento ou minuscula")
        void formasEstaoNormalizadas() {
            // Este teste existe por causa de um bug real. A primeira versao tinha
            // "NEGAR-SE PROVIMENTO" com hifen, e o texto comparado ja tem o hifen trocado
            // por espaco — a regra existia e nunca casava. O smoke test de cobertura
            // apontou 81%, e este teste impede que a classe do erro volte.
            assertThat(ClassificadorDeDispositivo.formasConfiguradas())
                    .allSatisfy(forma -> assertThat(forma)
                            .as("forma nao normalizada: '%s'", forma)
                            .matches("[A-Z ]+"));
        }

        @Test
        @DisplayName("a forma enclitica e reconhecida")
        void encliseEhReconhecida() {
            // Dominante na redacao forense e a que mais faltava: quatro ocorrencias numa
            // amostra de 118 dispositivos reais.
            assertThat(ClassificadorDeDispositivo.classificar(
                    "CONHECER DO RECURSO E NEGAR-LHE PROVIMENTO. DECISÃO UNÂNIME."))
                    .isEqualTo(ResultadoDoRecurso.IMPROVIDO);

            assertThat(ClassificadorDeDispositivo.classificar(
                    "CONHECER DO AGRAVO DE INSTRUMENTO E DAR-LHE PROVIMENTO. DECISÃO UNÂNIME."))
                    .isEqualTo(ResultadoDoRecurso.PROVIDO);

            assertThat(ClassificadorDeDispositivo.classificar("Negou-se provimento. Unânime."))
                    .isEqualTo(ResultadoDoRecurso.IMPROVIDO);
        }

        @Test
        @DisplayName("embargos rejeitados e ordem denegada sao improvimento")
        void desfechosDeAcoesProprias() {
            assertThat(ClassificadorDeDispositivo.classificar(
                    "EMBARGOS DE DECLARAÇÃO CONHECIDOS E REJEITADOS. UNÂNIME."))
                    .isEqualTo(ResultadoDoRecurso.IMPROVIDO);

            assertThat(ClassificadorDeDispositivo.classificar("DENEGAR A ORDEM. UNÂNIME."))
                    .isEqualTo(ResultadoDoRecurso.IMPROVIDO);
        }

        @Test
        @DisplayName("conflito de competencia nao e resultado de recurso")
        void conflitoDeCompetenciaFicaIndeterminado() {
            // Nao e' lacuna do classificador: declarar qual juizo e' competente nao tem
            // nada a ver com provimento. Forcar uma categoria aqui poluiria a estatistica.
            assertThat(ClassificadorDeDispositivo.classificar(
                    "Declarado competente o Juízo suscitado, unânime"))
                    .isEqualTo(ResultadoDoRecurso.INDETERMINADO);
        }
    }

    @Nested
    @DisplayName("unanimidade")
    class Unanimidade {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "'CONHECIDO. PROVIDO. UNÂNIME.', true",
                "'CONHECIDO. PROVIDO. POR MAIORIA.', false",
                "'DESPROVIDO. NÃO UNÂNIME.', false",
                "'PROVIDO. VENCIDO O RELATOR.', false",
        })
        void reconheceUnanimidade(String dispositivo, boolean esperado) {
            assertThat(ClassificadorDeDispositivo.unanime(dispositivo)).isEqualTo(esperado);
        }

        @Test
        @DisplayName("ausência de informação não vira 'não foi unânime'")
        void ausenciaNaoEhNegativa() {
            // Dado ausente é dado ausente. Tratá-lo como divergência inventaria divergência
            // onde não há, e a seção de comparação com o órgão se apoia justamente nisso.
            assertThat(ClassificadorDeDispositivo.unanime("CONHECIDO. PROVIDO.")).isNull();
        }
    }
}
