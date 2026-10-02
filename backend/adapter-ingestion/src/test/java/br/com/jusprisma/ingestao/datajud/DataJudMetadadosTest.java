package br.com.jusprisma.ingestao.datajud;

import br.com.jusprisma.aplicacao.ingestao.FonteDeMetadadosProcessuais.AssuntoCnj;
import br.com.jusprisma.aplicacao.ingestao.FonteDeMetadadosProcessuais.Grau;
import br.com.jusprisma.aplicacao.ingestao.FonteDeMetadadosProcessuais.LoteDeMetadados;
import br.com.jusprisma.aplicacao.ingestao.FonteDeMetadadosProcessuais.MetadadosDoProcesso;
import br.com.jusprisma.aplicacao.ingestao.FonteIndisponivelException;
import br.com.jusprisma.aplicacao.ingestao.RespostaInesperadaDaFonteException;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contrato com o DataJud, contra um servidor simulado. Sem Spring: retry, circuito e
 * espaçamento têm teste próprio.
 *
 * <p>Números de processo fictícios, com a estrutura CNJ e sem corresponder a processo real.
 */
class DataJudMetadadosTest {

    private static final String NUMERO = "07000000020268070001";
    private static final String NUMERO_FORMATADO = "0700000-00.2026.8.07.0001";
    private static final String OUTRO = "07000010020268070001";
    private static final String CAMINHO = "/api_publica_tjdft/_search";

    private WireMockServer datajud;
    private DataJudMetadados fonte;

    @BeforeEach
    void subir() {
        datajud = new WireMockServer(options().dynamicPort());
        datajud.start();
        // 127.0.0.1 e não baseUrl(): o reactor-netty trava resolvendo "localhost" nesta máquina.
        fonte = new DataJudMetadados(WebClient.builder(), "http://127.0.0.1:" + datajud.port(),
                "chave-de-teste");
    }

    @AfterEach
    void derrubar() {
        datajud.stop();
    }

    @Test
    @DisplayName("consulta pelos 20 dígitos, com a chave no cabeçalho APIKey, e lê o grau pedido")
    void consultaELeOGrauPedido() {
        responder(resposta(registro(NUMERO, "G1", 0), registro(NUMERO, "G2", 0)));

        LoteDeMetadados lote = fonte.buscar("TJDFT", List.of(NUMERO_FORMATADO), Grau.G2);

        datajud.verify(postRequestedFor(urlPathEqualTo(CAMINHO))
                .withHeader("Authorization", equalTo("APIKey chave-de-teste"))
                .withRequestBody(equalToJson("""
                        {"size": 4, "query": {"terms": {"numeroProcesso": ["07000000020268070001"]}}}
                        """, true, true)));

        assertThat(lote.descartadosPorSigilo()).isZero();
        assertThat(lote.processos()).singleElement().satisfies(processo -> {
            assertThat(processo.numeroProcesso()).isEqualTo(NUMERO);
            assertThat(processo.grau()).isEqualTo(Grau.G2);
            assertThat(processo.codigoClasse()).isEqualTo(198);
            assertThat(processo.nomeClasse()).isEqualTo("Apelação Cível");
            assertThat(processo.assuntos()).containsExactly(
                    new AssuntoCnj(10433, "Indenização por Dano Moral"),
                    new AssuntoCnj(7779, "Indenização por Dano Material"));
            assertThat(processo.dataAjuizamento()).isEqualTo(LocalDateTime.of(2023, 2, 16, 17, 58, 45));
        });
    }

    @Test
    @DisplayName("o mesmo processo com e sem máscara é consultado uma vez só")
    void deduplicaNumeros() {
        responder(resposta());

        fonte.buscar("TJDFT", List.of(NUMERO, NUMERO_FORMATADO, OUTRO), Grau.G2);

        datajud.verify(postRequestedFor(urlPathEqualTo(CAMINHO)).withRequestBody(equalToJson("""
                {"size": 8, "query": {"terms": {"numeroProcesso":
                  ["07000000020268070001", "07000010020268070001"]}}}
                """, true, true)));
    }

    @Test
    @DisplayName("regra 8: processo com nível de sigilo, ou sem o campo, não sai do adaptador")
    void sigiloNaoEntra() {
        String semNivel = registro(OUTRO, "G2", 0).replace("\"nivelSigilo\": 0,", "");
        responder(resposta(registro(NUMERO, "G2", 0), registro("07000020020268070001", "G2", 5), semNivel));

        LoteDeMetadados lote = fonte.buscar("TJDFT",
                List.of(NUMERO, OUTRO, "07000020020268070001"), Grau.G2);

        assertThat(lote.processos()).extracting(MetadadosDoProcesso::numeroProcesso).containsExactly(NUMERO);
        assertThat(lote.descartadosPorSigilo()).isEqualTo(2);
    }

    @Test
    @DisplayName("200 com shards falhos é indisponibilidade, não processo inexistente")
    void resultadoParcialEhIndisponibilidade() {
        // Resposta real do cluster sobrecarregado: dois de três shards rejeitaram a busca.
        responder("""
                {"took": 32051, "timed_out": false,
                 "_shards": {"total": 3, "successful": 1, "skipped": 0, "failed": 2},
                 "hits": {"total": {"value": 0, "relation": "eq"}, "hits": []}}
                """);

        assertThatThrownBy(() -> fonte.buscar("TJDFT", List.of(NUMERO), Grau.G2))
                .isInstanceOf(FonteIndisponivelException.class);
    }

    @Test
    @DisplayName("consulta que estourou o tempo no cluster é indisponibilidade")
    void timedOutEhIndisponibilidade() {
        responder("""
                {"took": 60000, "timed_out": true, "_shards": {"total": 3, "successful": 3, "failed": 0},
                 "hits": {"hits": []}}
                """);

        assertThatThrownBy(() -> fonte.buscar("TJDFT", List.of(NUMERO), Grau.G2))
                .isInstanceOf(FonteIndisponivelException.class);
    }

    @Test
    @DisplayName("resposta sem hits.hits é erro de contrato")
    void respostaSemHits() {
        responder("{\"took\": 5}");

        assertThatThrownBy(() -> fonte.buscar("TJDFT", List.of(NUMERO), Grau.G2))
                .isInstanceOf(RespostaInesperadaDaFonteException.class);
    }

    @Test
    @DisplayName("número sem 20 dígitos, lote grande e sigla estranha são recusados sem chamar a API")
    void entradasInvalidas() {
        assertThatThrownBy(() -> fonte.buscar("TJDFT", List.of("0700000-00.2026.8.07.001"), Grau.G2))
                .isInstanceOf(IllegalArgumentException.class);

        List<String> muitos = IntStream.range(0, DataJudMetadados.LOTE_MAXIMO + 1)
                .mapToObj(i -> "%07d0020268070001".formatted(i)).toList();
        assertThatThrownBy(() -> fonte.buscar("TJDFT", muitos, Grau.G2))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> fonte.buscar("tjdft/../outro", List.of(NUMERO), Grau.G2))
                .isInstanceOf(IllegalArgumentException.class);

        datajud.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("lista vazia não consulta")
    void listaVazia() {
        assertThat(fonte.buscar("TJDFT", List.of(), Grau.G2).processos()).isEmpty();
        datajud.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("data de ajuizamento fora de yyyyMMddHHmmss é erro, não data aproximada")
    void ajuizamentoEstrito() {
        assertThat(DataJudMetadados.ajuizamento("20230728124939"))
                .isEqualTo(LocalDateTime.of(2023, 7, 28, 12, 49, 39));
        assertThatThrownBy(() -> DataJudMetadados.ajuizamento("20231345000000"))
                .isInstanceOf(RespostaInesperadaDaFonteException.class);
        assertThatThrownBy(() -> DataJudMetadados.ajuizamento("2023-07-28T12:49:39"))
                .isInstanceOf(RespostaInesperadaDaFonteException.class);
    }

    @Test
    @DisplayName("chave recusada não é indisponibilidade; 504 do proxy é")
    void traducaoDeErros() {
        datajud.stubFor(post(urlPathEqualTo(CAMINHO)).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> fonte.buscar("TJDFT", List.of(NUMERO), Grau.G2))
                .isInstanceOf(RespostaInesperadaDaFonteException.class);

        datajud.stubFor(post(urlPathEqualTo(CAMINHO)).willReturn(aResponse().withStatus(504)));
        assertThatThrownBy(() -> fonte.buscar("TJDFT", List.of(NUMERO), Grau.G2))
                .isInstanceOf(FonteIndisponivelException.class);
    }

    private void responder(String corpo) {
        datajud.stubFor(post(urlPathEqualTo(CAMINHO)).willReturn(okJson(corpo)));
    }

    static String resposta(String... registros) {
        return """
                {"took": 120, "timed_out": false,
                 "_shards": {"total": 3, "successful": 3, "skipped": 0, "failed": 0},
                 "hits": {"total": {"value": %d, "relation": "eq"}, "hits": [%s]}}
                """.formatted(registros.length, String.join(",", registros));
    }

    static String registro(String numero, String grau, int nivelSigilo) {
        return """
                {"_index": "api_publica_tjdft", "_id": "TJDFT_%s_%s", "_source": {
                  "numeroProcesso": "%s", "grau": "%s", "nivelSigilo": %d,
                  "dataAjuizamento": "20230216175845",
                  "classe": {"codigo": 198, "nome": "Apelação Cível"},
                  "assuntos": [{"codigo": 10433, "nome": "Indenização por Dano Moral"},
                               {"codigo": 7779, "nome": "Indenização por Dano Material"}]}}
                """.formatted(grau, numero, numero, grau, nivelSigilo);
    }
}
