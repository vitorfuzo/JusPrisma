package br.com.jusprisma.ingestao.tjdft;

import br.com.jusprisma.aplicacao.ingestao.FonteDeAcordaos.AcordaoDaFonte;
import br.com.jusprisma.aplicacao.ingestao.FonteDeAcordaos.ConsultaDeAcordaos;
import br.com.jusprisma.aplicacao.ingestao.FonteDeAcordaos.PaginaDeAcordaos;
import br.com.jusprisma.aplicacao.ingestao.FonteIndisponivelException;
import br.com.jusprisma.aplicacao.ingestao.RespostaInesperadaDaFonteException;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contrato com a pesquisa do TJDFT, contra um servidor simulado. Sem Spring: retry, circuito
 * e espaçamento são aspectos e têm teste próprio.
 *
 * <p>Os registros são fictícios: números, nomes e textos não correspondem a processo real.
 */
class TjdftAcordaosTest {

    private WireMockServer tjdft;
    private TjdftAcordaos fonte;

    @BeforeEach
    void subir() {
        tjdft = new WireMockServer(options().dynamicPort());
        tjdft.start();
        // 127.0.0.1 e não baseUrl(): o reactor-netty trava resolvendo "localhost" nesta
        // máquina, e o teste passaria a medir o resolvedor.
        fonte = new TjdftAcordaos(WebClient.builder(), "http://127.0.0.1:" + tjdft.port());
    }

    @AfterEach
    void derrubar() {
        tjdft.stop();
    }

    @Test
    @DisplayName("envia a consulta no formato da API e lê os campos do acórdão")
    void mapeiaConsultaERegistro() {
        responder("""
                {"hits": {"value": 1234}, "paginacao": {"pagina": 2, "tamanho": 40},
                 "registros": [%s]}
                """.formatted(registro("9000001", false)));

        PaginaDeAcordaos pagina = fonte.buscar(
                new ConsultaDeAcordaos("dano moral", "RELATORA FICTICIA DE TESTE", 2, 40));

        tjdft.verify(postRequestedFor(urlPathEqualTo("/api/v1/pesquisa"))
                .withRequestBody(equalToJson("""
                        {"query": "dano moral", "pagina": 2, "tamanho": 40,
                         "termosAcessorios": [{"campo": "nomeRelator", "valor": "RELATORA FICTICIA DE TESTE"}]}
                        """)));

        assertThat(pagina.total()).isEqualTo(1234);
        assertThat(pagina.pagina()).isEqualTo(2);
        assertThat(pagina.descartadosPorSigilo()).isZero();
        assertThat(pagina.acordaos()).singleElement().satisfies(acordao -> {
            assertThat(acordao.identificadorExterno()).isEqualTo("9000001");
            assertThat(acordao.numeroProcesso()).isEqualTo("0700000-00.2026.8.07.0001");
            assertThat(acordao.nomeRelator()).isEqualTo("RELATORA FICTICIA DE TESTE");
            assertThat(acordao.relatorAtivo()).isTrue();
            assertThat(acordao.orgaoJulgador()).isEqualTo("9ª TURMA CÍVEL");
            assertThat(acordao.codigoClasseCnj()).isEqualTo(198);
            assertThat(acordao.ementa()).startsWith("EMENTA FICTÍCIA");
            assertThat(acordao.dispositivo()).isEqualTo("CONHECIDO. DESPROVIDO. UNÂNIME.");
            assertThat(acordao.dataJulgamento()).isEqualTo(LocalDate.of(2026, 9, 10));
            assertThat(acordao.dataPublicacao()).isEqualTo(Instant.parse("2026-09-21T14:45:44Z"));
        });
    }

    @Test
    @DisplayName("sem relator, a consulta não leva filtro acessório")
    void semRelatorSemFiltro() {
        responder("{\"hits\": {\"value\": 0}, \"registros\": []}");

        fonte.buscar(new ConsultaDeAcordaos("dano moral", null, 0, 20));

        tjdft.verify(postRequestedFor(urlPathEqualTo("/api/v1/pesquisa"))
                .withRequestBody(equalToJson("{\"query\": \"dano moral\", \"pagina\": 0, \"tamanho\": 20}")));
    }

    @Test
    @DisplayName("regra 8: acórdão sob segredo de justiça, ou sem a marca, não sai do adaptador")
    void sigiloNaoEntra() {
        String semMarca = registro("9000003", false).replace("\"segredoJustica\": false,", "");
        responder("""
                {"hits": {"value": 3}, "registros": [%s, %s, %s]}
                """.formatted(registro("9000001", false), registro("9000002", true), semMarca));

        PaginaDeAcordaos pagina = fonte.buscar(new ConsultaDeAcordaos("dano moral", null, 0, 40));

        assertThat(pagina.acordaos()).extracting(AcordaoDaFonte::identificadorExterno)
                .containsExactly("9000001");
        assertThat(pagina.descartadosPorSigilo()).isEqualTo(2);
    }

    @Test
    @DisplayName("a data de julgamento é o dia civil em Brasília, não o dia em UTC")
    void dataDeJulgamentoNoFusoDoTribunal() {
        // 02:00Z é 23:00 do dia anterior em Brasília.
        responder("{\"hits\": {\"value\": 1}, \"registros\": [%s]}".formatted(
                registro("9000001", false).replace("2026-09-10T03:00:00.000Z", "2026-09-10T02:00:00.000Z")));

        assertThat(fonte.buscar(new ConsultaDeAcordaos("x", null, 0, 1)).acordaos().getFirst()
                .dataJulgamento()).isEqualTo(LocalDate.of(2026, 9, 9));
    }

    @Test
    @DisplayName("tamanho acima de 40 é recusado sem chamar a API")
    void tamanhoAcimaDoMaximo() {
        assertThatThrownBy(() -> fonte.buscar(new ConsultaDeAcordaos("dano moral", null, 0, 41)))
                .isInstanceOf(IllegalArgumentException.class);

        tjdft.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/pesquisa")));
    }

    @Test
    @DisplayName("200 sem a lista de registros é erro, não página vazia")
    void respostaSemRegistros() {
        // É exatamente o que a API devolve quando o tamanho passa de 40.
        responder("{\"hits\": {\"value\": 264584}, \"paginacao\": {\"pagina\": 0, \"tamanho\": 50}}");

        assertThatThrownBy(() -> fonte.buscar(new ConsultaDeAcordaos("dano moral", null, 0, 40)))
                .isInstanceOf(RespostaInesperadaDaFonteException.class);
    }

    @Test
    @DisplayName("lista vazia é página vazia de verdade")
    void listaVazia() {
        responder("{\"hits\": {\"value\": 0}, \"registros\": []}");

        PaginaDeAcordaos pagina = fonte.buscar(new ConsultaDeAcordaos("termo sem resultado", null, 0, 40));

        assertThat(pagina.acordaos()).isEmpty();
        assertThat(pagina.total()).isZero();
    }

    @Test
    @DisplayName("registro sem identificador interrompe, em vez de virar acórdão sem chave")
    void registroSemIdentificador() {
        responder("{\"hits\": {\"value\": 1}, \"registros\": [%s]}".formatted(
                registro("9000001", false).replace("\"identificador\": \"9000001\",", "")));

        assertThatThrownBy(() -> fonte.buscar(new ConsultaDeAcordaos("x", null, 0, 1)))
                .isInstanceOf(RespostaInesperadaDaFonteException.class);
    }

    @Test
    @DisplayName("erro do servidor é indisponibilidade; recusa da consulta não é")
    void traducaoDeErros() {
        tjdft.stubFor(post(urlPathEqualTo("/api/v1/pesquisa"))
                .withRequestBody(matchingJsonPath("$.query", equalTo("cai")))
                .willReturn(aResponse().withStatus(503)));
        tjdft.stubFor(post(urlPathEqualTo("/api/v1/pesquisa"))
                .withRequestBody(matchingJsonPath("$.query", equalTo("recusa")))
                .willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> fonte.buscar(new ConsultaDeAcordaos("cai", null, 0, 1)))
                .isInstanceOf(FonteIndisponivelException.class);
        assertThatThrownBy(() -> fonte.buscar(new ConsultaDeAcordaos("recusa", null, 0, 1)))
                .isInstanceOf(RespostaInesperadaDaFonteException.class);
    }

    private void responder(String corpo) {
        tjdft.stubFor(post(urlPathEqualTo("/api/v1/pesquisa")).willReturn(okJson(corpo)));
    }

    static String registro(String identificador, boolean sigiloso) {
        return """
                {"sequencial": 1, "base": "acordaos", "uuid": "00000000-0000-0000-0000-%s",
                 "identificador": "%s",
                 "dataJulgamento": "2026-09-10T03:00:00.000Z",
                 "dataPublicacao": "2026-09-21T14:45:44.000Z",
                 "decisao": "CONHECIDO. DESPROVIDO. UNÂNIME.",
                 "ementa": "EMENTA FICTÍCIA PARA TESTE. APELAÇÃO CÍVEL. RECURSO DESPROVIDO.",
                 "processo": "0700000-00.2026.8.07.0001",
                 "nomeRelator": "RELATORA FICTICIA DE TESTE", "relatorAtivo": true,
                 "segredoJustica": %s, "turmaRecursal": false,
                 "descricaoOrgaoJulgador": "9ª TURMA CÍVEL", "codigoClasseCnj": 198,
                 "inteiroTeorHtml": "Inteiro Teor indisponível.", "possuiInteiroTeor": true}
                """.formatted("%012d".formatted(Long.parseLong(identificador)), identificador, sigiloso);
    }
}
