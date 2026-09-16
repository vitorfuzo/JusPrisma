package br.com.jusprisma.cobranca;

import br.com.jusprisma.JusPrismaApplication;
import br.com.jusprisma.aplicacao.credito.Creditos;
import br.com.jusprisma.conta.CaixaDeSaidaDeTeste;
import br.com.jusprisma.dominio.credito.TipoCredito;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O recebimento de notificações do gateway de cobrança.
 *
 * <p>O teste que mais importa é o da reentrega. Todo gateway reentrega webhook — por
 * timeout nosso, por falha de rede, por retentativa programada. Se "pagamento confirmado"
 * for processado duas vezes, o cliente ganha os créditos do mês em dobro, e isso sai
 * direto da margem.
 */
@SpringBootTest(classes = {JusPrismaApplication.class, CaixaDeSaidaDeTeste.Configuracao.class})
@AutoConfigureMockMvc
@Testcontainers
class WebhookDeCobrancaTest {

    private static final String SENHA_DONO = "owner_teste";
    private static final String SENHA_APP = "app_teste";
    private static final String SENHA = "senha-de-teste-comprida";
    private static final String TOKEN_WEBHOOK = "token-de-webhook-para-teste";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("jusprisma")
            .withUsername("jusprisma_owner")
            .withPassword(SENHA_DONO)
            .withInitScript("db/roles-teste.sql");

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registro.add("spring.datasource.username", () -> "jusprisma_app");
        registro.add("spring.datasource.password", () -> SENHA_APP);
        registro.add("spring.flyway.user", POSTGRES::getUsername);
        registro.add("spring.flyway.password", () -> SENHA_DONO);
        registro.add("jusprisma.limite-de-tentativas.habilitado", () -> false);
        registro.add("jusprisma.jwt.segredo",
                () -> Base64.getEncoder().encodeToString(new byte[64]));
        registro.add("jusprisma.cookie.seguro", () -> false);
        registro.add("jusprisma.asaas.token-webhook", () -> TOKEN_WEBHOOK);
        // O job diário não deve disparar sozinho no meio do teste.
        registro.add("jusprisma.jobs.habilitados", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private Creditos creditos;

    // ------------------------------------------------------------ autenticação

    @Test
    @DisplayName("notificação sem token é recusada")
    void semTokenEhRecusada() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/cobranca")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDePagamento("evt_" + UUID.randomUUID(), "sub_qualquer")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("notificação com token errado é recusada")
    void tokenErradoEhRecusado() throws Exception {
        // Sem esta verificação, qualquer um manda "pagamento confirmado" e ganha acesso
        // pago de graça — a rota é pública por natureza, o gateway não faz login.
        mockMvc.perform(post("/api/v1/webhooks/cobranca")
                        .header("asaas-access-token", "token-errado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDePagamento("evt_" + UUID.randomUUID(), "sub_qualquer")))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ idempotência

    @Test
    @DisplayName("a mesma notificação entregue duas vezes só tem efeito uma")
    void reentregaNaoDuplicaEfeito() throws Exception {
        UUID tenant = criarConta();
        String assinaturaNoGateway = vincularAoGateway(tenant);
        int antes = creditos.saldo(tenant, TipoCredito.PERFIL);

        String idDoEvento = "evt_" + UUID.randomUUID();

        notificar(idDoEvento, assinaturaNoGateway)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("processado"));

        // Segunda entrega do mesmo evento: 200 de novo, porque o gateway não deve
        // reentregar indefinidamente, mas sem repetir o crédito.
        notificar(idDoEvento, assinaturaNoGateway)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("repetido"));

        int depois = creditos.saldo(tenant, TipoCredito.PERFIL);
        assertThat(depois - antes)
                .as("o pagamento credita as cotas do plano uma única vez")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("eventos distintos do mesmo pagamento são processados separadamente")
    void eventosDistintosNaoSaoConfundidos() throws Exception {
        UUID tenant = criarConta();
        String assinatura = vincularAoGateway(tenant);
        int antes = creditos.saldo(tenant, TipoCredito.PERFIL);

        notificar("evt_" + UUID.randomUUID(), assinatura).andExpect(status().isOk());
        notificar("evt_" + UUID.randomUUID(), assinatura).andExpect(status().isOk());

        assertThat(creditos.saldo(tenant, TipoCredito.PERFIL) - antes)
                .as("dois pagamentos são duas recargas — a idempotência é por evento, não por assinatura")
                .isEqualTo(4);
    }

    // ------------------------------------------------------------------ efeito

    @Test
    @DisplayName("pagamento confirmado ativa a assinatura")
    void pagamentoAtivaAssinatura() throws Exception {
        UUID tenant = criarConta();
        String assinatura = vincularAoGateway(tenant);

        assertThat(statusDaAssinatura(tenant)).isEqualTo("TRIAL");
        notificar("evt_" + UUID.randomUUID(), assinatura).andExpect(status().isOk());
        assertThat(statusDaAssinatura(tenant)).isEqualTo("ATIVA");
    }

    @Test
    @DisplayName("pagamento vencido marca inadimplente, sem cortar o acesso")
    void pagamentoVencidoNaoCortaAcesso() throws Exception {
        UUID tenant = criarConta();
        String assinatura = vincularAoGateway(tenant);

        enviar("""
                {"id": "evt_%s", "event": "PAYMENT_OVERDUE",
                 "payment": {"subscription": "%s"}}
                """.formatted(UUID.randomUUID(), assinatura))
                .andExpect(status().isOk());

        // Inadimplente continua vigente de propósito: cortar no primeiro boleto atrasado
        // perde o cliente que só trocou de cartão.
        assertThat(statusDaAssinatura(tenant)).isEqualTo("INADIMPLENTE");
    }

    @Test
    @DisplayName("evento de assinatura desconhecida é aceito e ignorado")
    void assinaturaDesconhecidaEhIgnorada() throws Exception {
        // Acontece de verdade: evento de teste disparado do painel do gateway. Devolver
        // erro faria o gateway reentregar para sempre algo que nunca vai casar.
        notificar("evt_" + UUID.randomUUID(), "sub_que_nao_existe")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("ignorado"));
    }

    @Test
    @DisplayName("evento de tipo irrelevante é registrado sem efeito")
    void tipoIrrelevanteEhRegistrado() throws Exception {
        UUID tenant = criarConta();
        String assinatura = vincularAoGateway(tenant);
        int antes = creditos.saldo(tenant, TipoCredito.PERFIL);

        enviar("""
                {"id": "evt_%s", "event": "PAYMENT_UPDATED",
                 "payment": {"subscription": "%s"}}
                """.formatted(UUID.randomUUID(), assinatura))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("ignorado"));

        assertThat(creditos.saldo(tenant, TipoCredito.PERFIL)).isEqualTo(antes);
    }

    @Test
    @DisplayName("corpo ilegível é aceito e descartado, sem provocar reentrega")
    void corpoIlegivelNaoProvocaReentrega() throws Exception {
        enviar("isto nao e json").andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ apoio

    private org.springframework.test.web.servlet.ResultActions notificar(
            String idDoEvento, String assinatura) throws Exception {
        return enviar(corpoDePagamento(idDoEvento, assinatura));
    }

    private org.springframework.test.web.servlet.ResultActions enviar(String corpo)
            throws Exception {
        return mockMvc.perform(post("/api/v1/webhooks/cobranca")
                .header("asaas-access-token", TOKEN_WEBHOOK)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    private static String corpoDePagamento(String idDoEvento, String assinatura) {
        return """
                {"id": "%s", "event": "PAYMENT_CONFIRMED",
                 "payment": {"subscription": "%s", "nextDueDate": "2026-12-01"}}
                """.formatted(idDoEvento, assinatura);
    }

    private UUID criarConta() throws Exception {
        MvcResult criada = mockMvc.perform(post("/api/v1/contas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nomeDoEscritorio": "Escritorio Ficticio de Teste",
                                 "email": "socio-%s@exemplo.invalido", "senha": "%s"}
                                """.formatted(UUID.randomUUID(), SENHA)))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(json.readTree(criada.getResponse().getContentAsString())
                .get("tenantId").asString());
    }

    /** Simula o que a criação da assinatura no gateway gravaria. */
    private String vincularAoGateway(UUID tenant) throws SQLException {
        String assinaturaNoGateway = "sub_" + UUID.randomUUID();
        try (Connection dono = conexaoDono();
             PreparedStatement ps = dono.prepareStatement("""
                     UPDATE assinatura SET gateway_subscription_id = ?
                      WHERE tenant_id = ? AND status IN ('TRIAL', 'ATIVA', 'INADIMPLENTE')
                     """)) {
            ps.setString(1, assinaturaNoGateway);
            ps.setObject(2, tenant);
            ps.executeUpdate();
        }
        return assinaturaNoGateway;
    }

    private String statusDaAssinatura(UUID tenant) throws SQLException {
        try (Connection dono = conexaoDono();
             PreparedStatement ps = dono.prepareStatement(
                     "SELECT status FROM assinatura WHERE tenant_id = ?")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static Connection conexaoDono() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), SENHA_DONO);
    }
}
