package br.com.jusprisma.dominio.magistrado;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Normalização de nome de magistrado.
 *
 * <p>É o eixo do produto: tratar duas grafias do mesmo desembargador como pessoas
 * diferentes fragmenta o acervo, derruba a amostra abaixo do mínimo e gera um perfil sobre
 * metade das decisões de alguém. O erro não aparece como falha — aparece como estatística
 * plausível e errada, que é o pior defeito possível aqui.
 *
 * <p>Os nomes são inventados. Magistrado é agente público e seu nome é dado público, mas
 * fixture inventada deixa explícito que o teste mede a regra, não uma pessoa.
 */
class NomeDeMagistradoTest {

    @Nested
    @DisplayName("as grafias que as fontes realmente produzem convergem")
    class Convergencia {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "Joao Pereira Alcantara",
                "JOAO PEREIRA ALCANTARA",
                "joao pereira alcantara",
                "João Pereira Alcântara",
                "  João   Pereira   Alcântara  ",
                "Des. João Pereira Alcântara",
                "DES. JOAO PEREIRA ALCANTARA",
                "Desembargador João Pereira Alcântara",
                "Dr. João Pereira Alcântara",
                "Alcantara, João Pereira",
                "ALCÂNTARA, JOÃO PEREIRA",
        })
        void todasResolvemParaAMesmaChave(String grafia) {
            assertThat(NomeDeMagistrado.normalizar(grafia))
                    .isEqualTo("JOAO PEREIRA ALCANTARA");
        }

        @Test
        @DisplayName("duas grafias diferentes são reconhecidas como a mesma pessoa")
        void mesmaPessoa() {
            NomeDeMagistrado deUmaFonte = NomeDeMagistrado.de("Desa. Maria das Graças Bittencourt");
            NomeDeMagistrado deOutra = NomeDeMagistrado.de("BITTENCOURT, MARIA DAS GRACAS");

            assertThat(deUmaFonte.mesmaPessoaQue(deOutra)).isTrue();
        }
    }

    @Nested
    @DisplayName("o que NÃO pode ser confundido")
    class Distincao {

        @Test
        @DisplayName("pai e filho com sufixo diferente são pessoas diferentes")
        void sufixoDistingue() {
            // Caso real no Judiciário brasileiro, e o mais perigoso: os nomes são quase
            // idênticos e uma normalização gulosa juntaria os acervos de dois magistrados.
            assertThat(NomeDeMagistrado.normalizar("João Pereira Alcântara"))
                    .isNotEqualTo(NomeDeMagistrado.normalizar("João Pereira Alcântara Neto"));

            assertThat(NomeDeMagistrado.normalizar("Carlos Andrade Filho"))
                    .isNotEqualTo(NomeDeMagistrado.normalizar("Carlos Andrade"));
        }

        @Test
        @DisplayName("a partícula faz parte do nome e não é descartada")
        void particulaImporta() {
            // "Maria de Souza" e "Maria Souza" podem ser pessoas diferentes. Remover
            // partículas para "limpar" o nome juntaria duas pessoas distintas.
            assertThat(NomeDeMagistrado.normalizar("Maria de Souza"))
                    .isNotEqualTo(NomeDeMagistrado.normalizar("Maria Souza"));
        }

        @Test
        @DisplayName("nomes simplesmente diferentes continuam diferentes")
        void nomesDiferentes() {
            assertThat(NomeDeMagistrado.de("Ana Ribeiro Tavares")
                    .mesmaPessoaQue(NomeDeMagistrado.de("Ana Ribeiro Teixeira")))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("sujeira que vem das fontes")
    class Sujeira {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "'JOAO PEREIRA (RELATOR)', 'JOAO PEREIRA'",
                "'JOAO PEREIRA - 2a TURMA', 'JOAO PEREIRA A TURMA'",
                "'JOAO  PEREIRA', 'JOAO PEREIRA'",
                "'JOAO\tPEREIRA', 'JOAO PEREIRA'",
        })
        void pontuacaoEDigitosSaoRemovidos(String bruto, String esperado) {
            assertThat(NomeDeMagistrado.normalizar(bruto)).isEqualTo(esperado);
        }

        @Test
        @DisplayName("duas vírgulas não são interpretadas como inversão")
        void inversaoSoComUmaVirgula() {
            // Formato desconhecido: inverter no escuro produziria nome pior que o original.
            // Melhor deixar como está e deixar a tabela de aliases resolver.
            assertThat(NomeDeMagistrado.normalizar("SILVA, JOAO, DES"))
                    .isEqualTo("SILVA JOAO");
        }

        @Test
        @DisplayName("nome que fica vazio depois da limpeza é recusado")
        void nomeVazioEhRecusado() {
            // "Des." sozinho não identifica ninguém. Deixar passar criaria um magistrado
            // fantasma agregando decisões de várias pessoas.
            assertThatThrownBy(() -> NomeDeMagistrado.de("Des."))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> NomeDeMagistrado.de("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("forma de exibição")
    class Exibicao {

        @Test
        @DisplayName("preserva acentos e caixa, remove o título")
        void preservaAcentuacao() {
            NomeDeMagistrado nome = NomeDeMagistrado.de("Des. João Pereira Alcântara");

            assertThat(nome.exibicao())
                    .as("a interface mostra o nome como uma pessoa o escreveria")
                    .isEqualTo("João Pereira Alcântara");
            assertThat(nome.normalizado())
                    .as("a chave continua sendo a forma canônica")
                    .isEqualTo("JOAO PEREIRA ALCANTARA");
        }

        @Test
        @DisplayName("desfaz a inversão também na exibição")
        void exibicaoDesfazInversao() {
            assertThat(NomeDeMagistrado.de("Alcântara, João Pereira").exibicao())
                    .isEqualTo("João Pereira Alcântara");
        }
    }

    @Nested
    @DisplayName("iniciais")
    class Iniciais {

        @Test
        @DisplayName("ignoram partículas")
        void ignoramParticulas() {
            assertThat(NomeDeMagistrado.de("Maria de Souza Andrade").iniciais())
                    .as("MSA, não MDSA")
                    .isEqualTo("MSA");
        }
    }
}
