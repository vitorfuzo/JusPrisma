package br.com.jusprisma.dominio.conta;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Documentos gerados pelo algoritmo de dígito verificador a partir de bases sequenciais —
 * válidos na forma, sem pertencer a ninguém.
 */
class DocumentoDeCobrancaTest {

    private static final String CPF_VALIDO = "12345678909";
    private static final String CNPJ_VALIDO = "11222333000181";

    @Test
    @DisplayName("aceita CPF e CNPJ com ou sem máscara, guardando só os dígitos")
    void normalizaMascara() {
        assertThat(DocumentoDeCobranca.de("123.456.789-09").digitos()).isEqualTo(CPF_VALIDO);
        assertThat(DocumentoDeCobranca.de(CPF_VALIDO).tipo()).isEqualTo(DocumentoDeCobranca.Tipo.CPF);
        assertThat(DocumentoDeCobranca.de("11.222.333/0001-81").digitos()).isEqualTo(CNPJ_VALIDO);
        assertThat(DocumentoDeCobranca.de(CNPJ_VALIDO).tipo()).isEqualTo(DocumentoDeCobranca.Tipo.CNPJ);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"12345678900", "11222333000180"})
    @DisplayName("recusa dígito verificador errado")
    void recusaDigitoErrado(String documento) {
        // O Asaas recusaria na hora de assinar; recusar aqui dá ao advogado a mensagem
        // certa no formulário, em vez de um erro do gateway no meio da contratação.
        assertThatThrownBy(() -> DocumentoDeCobranca.de(documento))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"11111111111", "00000000000", "22222222222222"})
    @DisplayName("recusa sequência repetida, que passa no cálculo e não é documento")
    void recusaRepeticao(String documento) {
        assertThatThrownBy(() -> DocumentoDeCobranca.de(documento))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "123", "1234567890123", "abc.def.ghi-jk"})
    @DisplayName("recusa tamanho que não é CPF nem CNPJ")
    void recusaTamanho(String documento) {
        assertThatThrownBy(() -> DocumentoDeCobranca.de(documento))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a mensagem de erro não repete o documento informado")
    void erroNaoEcoaDocumento() {
        assertThatThrownBy(() -> DocumentoDeCobranca.de("12345678900"))
                .message()
                .doesNotContain("12345678900");
    }

    @Test
    @DisplayName("toString e máscara de exibição não revelam o documento inteiro")
    void naoVazaEmLogOuTela() {
        DocumentoDeCobranca cpf = DocumentoDeCobranca.de(CPF_VALIDO);

        // toString é o que acaba em log quando alguém concatena o objeto sem pensar.
        assertThat(cpf.toString()).doesNotContain(CPF_VALIDO).doesNotContain("456789");
        assertThat(cpf.mascarado()).isEqualTo("***.456.789-**");
        assertThat(DocumentoDeCobranca.de(CNPJ_VALIDO).mascarado()).isEqualTo("**.222.333/0001-**");
    }
}
