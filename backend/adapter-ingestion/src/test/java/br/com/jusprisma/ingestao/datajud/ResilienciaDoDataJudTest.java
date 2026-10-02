package br.com.jusprisma.ingestao.datajud;

import br.com.jusprisma.aplicacao.ingestao.FonteDeMetadadosProcessuais;
import br.com.jusprisma.aplicacao.ingestao.FonteDeMetadadosProcessuais.Grau;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * O resultado parcial do cluster sobrecarregado é repetido até vir completo — e não entregue
 * como "estes processos não existem".
 */
@SpringBootTest(classes = ResilienciaDoDataJudTest.Contexto.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "jusprisma.datajud.chave=chave-de-teste",
                "resilience4j.retry.instances.datajud.max-attempts=3",
                "resilience4j.retry.instances.datajud.wait-duration=10ms",
                "resilience4j.ratelimiter.instances.datajud.limit-for-period=100",
                "resilience4j.ratelimiter.instances.datajud.limit-refresh-period=1s"
        })
class ResilienciaDoDataJudTest {

    private static final WireMockServer DATAJUD = new WireMockServer(options().dynamicPort());

    static {
        DATAJUD.start();
    }

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry registro) {
        registro.add("jusprisma.datajud.url-base", () -> "http://127.0.0.1:" + DATAJUD.port());
    }

    @AfterAll
    static void derrubar() {
        DATAJUD.stop();
    }

    @Configuration
    @EnableAutoConfiguration
    @Import(DataJudMetadados.class)
    static class Contexto {
    }

    @Autowired
    private FonteDeMetadadosProcessuais fonte;

    @Test
    @DisplayName("resposta com shards falhos é repetida, e a segunda, completa, é a que vale")
    void repeteResultadoParcial() {
        String numero = "07000000020268070001";
        DATAJUD.stubFor(post(urlPathEqualTo("/api_publica_tjdft/_search")).inScenario("sobrecarga")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("""
                        {"timed_out": false, "_shards": {"total": 3, "successful": 1, "failed": 2},
                         "hits": {"hits": []}}
                        """))
                .willSetStateTo("aliviado"));
        DATAJUD.stubFor(post(urlPathEqualTo("/api_publica_tjdft/_search")).inScenario("sobrecarga")
                .whenScenarioStateIs("aliviado")
                .willReturn(okJson(DataJudMetadadosTest.resposta(
                        DataJudMetadadosTest.registro(numero, "G2", 0)))));

        assertThat(fonte.buscar("TJDFT", List.of(numero), Grau.G2).processos()).hasSize(1);
        DATAJUD.verify(2, postRequestedFor(urlPathEqualTo("/api_publica_tjdft/_search")));
    }
}
