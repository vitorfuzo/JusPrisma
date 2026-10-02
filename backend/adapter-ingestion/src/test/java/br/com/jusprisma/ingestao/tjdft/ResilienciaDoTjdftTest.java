package br.com.jusprisma.ingestao.tjdft;

import br.com.jusprisma.aplicacao.ingestao.FonteDeAcordaos;
import br.com.jusprisma.aplicacao.ingestao.FonteDeAcordaos.ConsultaDeAcordaos;
import br.com.jusprisma.aplicacao.ingestao.RespostaInesperadaDaFonteException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prova que as anotações do Resilience4j estão de fato ativas no adaptador.
 *
 * <p>Sem este teste, uma dependência de AOP faltando no classpath faria retry e circuito
 * simplesmente não acontecerem, sem erro nenhum — a armadilha do Boot 4 que já custou
 * tempo neste projeto.
 */
@SpringBootTest(classes = ResilienciaDoTjdftTest.Contexto.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "resilience4j.retry.instances.tjdft.max-attempts=3",
                "resilience4j.retry.instances.tjdft.wait-duration=10ms",
                "resilience4j.retry.instances.tjdft.ignore-exceptions="
                        + "java.lang.IllegalArgumentException,"
                        + "br.com.jusprisma.aplicacao.ingestao.RespostaInesperadaDaFonteException",
                "resilience4j.ratelimiter.instances.tjdft.limit-for-period=100",
                "resilience4j.ratelimiter.instances.tjdft.limit-refresh-period=1s"
        })
class ResilienciaDoTjdftTest {

    private static final WireMockServer TJDFT = new WireMockServer(options().dynamicPort());

    static {
        TJDFT.start();
    }

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry registro) {
        registro.add("jusprisma.tjdft.url-base", () -> "http://127.0.0.1:" + TJDFT.port());
    }

    @AfterAll
    static void derrubar() {
        TJDFT.stop();
    }

    @Configuration
    @EnableAutoConfiguration
    @Import(TjdftAcordaos.class)
    static class Contexto {
    }

    @Autowired
    private FonteDeAcordaos fonte;

    @BeforeEach
    void limpar() {
        TJDFT.resetAll();
    }

    @Test
    @DisplayName("falha passageira do TJDFT é repetida e a busca termina")
    void repeteFalhaPassageira() {
        TJDFT.stubFor(post(urlPathEqualTo("/api/v1/pesquisa")).inScenario("instavel")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recuperado"));
        TJDFT.stubFor(post(urlPathEqualTo("/api/v1/pesquisa")).inScenario("instavel")
                .whenScenarioStateIs("recuperado")
                .willReturn(okJson("{\"hits\": {\"value\": 0}, \"registros\": []}")));

        assertThat(fonte.buscar(new ConsultaDeAcordaos("dano moral", null, 0, 40)).acordaos()).isEmpty();
        TJDFT.verify(2, postRequestedFor(urlPathEqualTo("/api/v1/pesquisa")));
    }

    @Test
    @DisplayName("resposta fora do contrato não é repetida")
    void naoRepeteRespostaInesperada() {
        TJDFT.stubFor(post(urlPathEqualTo("/api/v1/pesquisa"))
                .willReturn(okJson("{\"hits\": {\"value\": 10}}")));

        assertThatThrownBy(() -> fonte.buscar(new ConsultaDeAcordaos("dano moral", null, 0, 40)))
                .isInstanceOf(RespostaInesperadaDaFonteException.class);
        TJDFT.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/pesquisa")));
    }
}
