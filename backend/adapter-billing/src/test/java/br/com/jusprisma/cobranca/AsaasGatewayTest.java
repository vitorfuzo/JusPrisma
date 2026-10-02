package br.com.jusprisma.cobranca;

import br.com.jusprisma.aplicacao.cobranca.CobrancaRecusadaException;
import br.com.jusprisma.aplicacao.cobranca.EventoDeCobranca;
import br.com.jusprisma.aplicacao.cobranca.GatewayDePagamento.DadosDoCliente;
import br.com.jusprisma.aplicacao.cobranca.GatewayDePagamento.NovaAssinatura;
import br.com.jusprisma.aplicacao.cobranca.GatewayIndisponivelException;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.ZoneId;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contrato com o Asaas na contratação. Sem Spring: o retry e o circuit breaker são
 * aspectos e não entram aqui — o que se testa é o que vai no fio e como a resposta volta.
 *
 * <p>Documento e identificadores são fictícios.
 */
class AsaasGatewayTest {

    private static final String CPF = "12345678909";
    private static final DadosDoCliente CLIENTE =
            new DadosDoCliente("tenant-ficticio", "Escritório Fictício", "dono@exemplo.test", CPF);

    private WireMockServer asaas;
    private AsaasGateway gateway;

    @BeforeEach
    void subir() {
        asaas = new WireMockServer(options().dynamicPort());
        asaas.start();
        // 127.0.0.1 e não baseUrl(): a resolução de "localhost" pelo reactor-netty trava nesta
        // máquina até o tempo limite, e o teste passaria a medir o resolvedor.
        gateway = new AsaasGateway(WebClient.builder(), "http://127.0.0.1:" + asaas.port(),
                "chave-de-teste", "");
    }

    @AfterEach
    void derrubar() {
        asaas.stop();
    }

    @Test
    @DisplayName("cria o cliente com o documento e a referência quando ainda não existe")
    void criaCliente() {
        asaas.stubFor(get(urlPathEqualTo("/customers"))
                .withQueryParam("externalReference", equalTo("tenant-ficticio"))
                .willReturn(okJson("{\"data\":[]}")));
        asaas.stubFor(post("/customers").willReturn(okJson("{\"id\":\"cus_novo\"}")));

        assertThat(gateway.garantirCliente(CLIENTE).id()).isEqualTo("cus_novo");

        asaas.verify(postRequestedFor(urlPathEqualTo("/customers"))
                .withHeader("access_token", equalTo("chave-de-teste"))
                .withRequestBody(equalToJson("""
                        {"cpfCnpj": "12345678909", "externalReference": "tenant-ficticio",
                         "notificationDisabled": true}
                        """, true, true)));
    }

    @Test
    @DisplayName("reaproveita o cliente de uma tentativa anterior, atualizando o documento")
    void reaproveitaCliente() {
        asaas.stubFor(get(urlPathEqualTo("/customers"))
                .willReturn(okJson("{\"data\":[{\"id\":\"cus_existente\"}]}")));
        asaas.stubFor(put("/customers/cus_existente").willReturn(okJson("{\"id\":\"cus_existente\"}")));

        assertThat(gateway.garantirCliente(CLIENTE).id()).isEqualTo("cus_existente");

        asaas.verify(0, postRequestedFor(urlPathEqualTo("/customers")));
        asaas.verify(putRequestedFor(urlPathEqualTo("/customers/cus_existente"))
                .withRequestBody(equalToJson("{\"cpfCnpj\": \"12345678909\"}", true, true)));
    }

    @Test
    @DisplayName("a primeira cobrança vence na data pedida, e não no dia seguinte")
    void primeiraCobrancaNaDataPedida() {
        asaas.stubFor(get(urlPathEqualTo("/subscriptions")).willReturn(okJson("{\"data\":[]}")));
        asaas.stubFor(post("/subscriptions").willReturn(okJson("""
                {"id": "sub_nova", "nextDueDate": "2026-10-15"}
                """)));

        var criada = gateway.garantirAssinatura(new NovaAssinatura(
                "assinatura-ficticia", "cus_novo", "SOLO", 13700, LocalDate.of(2026, 10, 15)));

        assertThat(criada.id()).isEqualTo("sub_nova");
        asaas.verify(postRequestedFor(urlPathEqualTo("/subscriptions"))
                .withRequestBody(equalToJson("""
                        {"customer": "cus_novo", "value": "137.00", "nextDueDate": "2026-10-15",
                         "cycle": "MONTHLY", "externalReference": "assinatura-ficticia"}
                        """, true, true)));
    }

    @Test
    @DisplayName("não cria segunda assinatura quando a de uma tentativa anterior está ativa")
    void naoDuplicaAssinatura() {
        asaas.stubFor(get(urlPathEqualTo("/subscriptions"))
                .withQueryParam("externalReference", equalTo("assinatura-ficticia"))
                .withQueryParam("status", equalTo("ACTIVE"))
                .willReturn(okJson("{\"data\":[{\"id\":\"sub_existente\",\"nextDueDate\":\"2026-10-15\"}]}")));

        var encontrada = gateway.garantirAssinatura(new NovaAssinatura(
                "assinatura-ficticia", "cus_novo", "SOLO", 13700, LocalDate.of(2026, 10, 15)));

        assertThat(encontrada.id()).isEqualTo("sub_existente");
        asaas.verify(0, postRequestedFor(urlPathEqualTo("/subscriptions")));
    }

    @Test
    @DisplayName("recusa de dados vira CobrancaRecusada com a descrição do Asaas")
    void recusaDeDados() {
        asaas.stubFor(get(urlPathEqualTo("/customers")).willReturn(okJson("{\"data\":[]}")));
        asaas.stubFor(post("/customers").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"errors\":[{\"code\":\"invalid_email\",\"description\":\"O email informado é inválido.\"}]}")));

        assertThatThrownBy(() -> gateway.garantirCliente(CLIENTE))
                .isInstanceOf(CobrancaRecusadaException.class)
                .hasMessage("O email informado é inválido.");
    }

    @Test
    @DisplayName("erro do servidor e chave recusada viram indisponibilidade, não culpa do cliente")
    void indisponibilidade() {
        asaas.stubFor(get(urlPathEqualTo("/customers")).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> gateway.garantirCliente(CLIENTE))
                .isInstanceOf(GatewayIndisponivelException.class);

        asaas.stubFor(get(urlPathEqualTo("/customers")).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> gateway.garantirCliente(CLIENTE))
                .isInstanceOf(GatewayIndisponivelException.class);
    }

    @Test
    @DisplayName("pagamento confirmado leva a próxima cobrança a um ciclo depois do vencimento pago")
    void proximaCobrancaUmCicloDepoisDoVencimento() {
        // Corpo no formato documentado: o payment traz dueDate, e não nextDueDate.
        var evento = gateway.interpretar("""
                {"id": "evt_ficticio", "event": "PAYMENT_RECEIVED",
                 "payment": {"object": "payment", "id": "pay_ficticio",
                             "subscription": "sub_ficticia", "dueDate": "2026-10-16",
                             "originalDueDate": "2026-10-16", "billingType": "PIX"}}
                """).orElseThrow();

        assertThat(evento.tipo()).isEqualTo(EventoDeCobranca.Tipo.PAGAMENTO_CONFIRMADO);
        assertThat(evento.proximaCobranca()).isEqualTo(
                LocalDate.of(2026, 11, 16).atStartOfDay(ZoneId.of("America/Sao_Paulo")).toInstant());
    }

    @Test
    @DisplayName("evento sem vencimento não inventa próxima cobrança")
    void semVencimentoSemProximaCobranca() {
        var evento = gateway.interpretar("""
                {"id": "evt_ficticio", "event": "SUBSCRIPTION_DELETED",
                 "subscription": {"id": "sub_ficticia"}}
                """).orElseThrow();

        assertThat(evento.proximaCobranca()).isNull();
    }
}
