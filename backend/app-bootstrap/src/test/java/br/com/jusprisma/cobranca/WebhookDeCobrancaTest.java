package br.com.jusprisma.cobranca;

import br.com.jusprisma.JusPrismaApplication;
import br.com.jusprisma.aplicacao.cobranca.CicloDaDegustacao;
import br.com.jusprisma.aplicacao.cobranca.RepositorioDeEventoDeCobranca;
import br.com.jusprisma.aplicacao.credito.Creditos;
import br.com.jusprisma.aplicacao.plano.LimitesVigentes;
import br.com.jusprisma.aplicacao.plano.SemAssinaturaVigenteException;
import br.com.jusprisma.conta.CaixaDeSaidaDeTeste;
import br.com.jusprisma.dominio.credito.TipoCredito;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Autowired
    private LimitesVigentes limites;

    @Autowired
    private CicloDaDegustacao ciclo;

    @Autowired
    private RepositorioDeEventoDeCobranca cobranca;

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

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
    @DisplayName("pagamentos distintos da mesma assinatura são duas recargas")
    void eventosDistintosNaoSaoConfundidos() throws Exception {
        UUID tenant = criarConta();
        String assinatura = vincularAoGateway(tenant);
        int antes = creditos.saldo(tenant, TipoCredito.PERFIL);

        notificar("evt_" + UUID.randomUUID(), assinatura, "pay_" + UUID.randomUUID())
                .andExpect(status().isOk());
        notificar("evt_" + UUID.randomUUID(), assinatura, "pay_" + UUID.randomUUID())
                .andExpect(status().isOk());

        assertThat(creditos.saldo(tenant, TipoCredito.PERFIL) - antes)
                .as("dois pagamentos são duas recargas — a idempotência é por pagamento, não por assinatura")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("confirmação e recebimento do mesmo pagamento no cartão creditam uma vez")
    void confirmacaoERecebimentoDoMesmoPagamentoCreditamUmaVez() throws Exception {
        // No cartão o Asaas notifica PAYMENT_CONFIRMED na aprovação e PAYMENT_RECEIVED na
        // liquidação, ~32 dias depois: dois eventos, ids diferentes, um pagamento só.
        UUID tenant = criarConta();
        String assinatura = vincularAoGateway(tenant);
        int antes = creditos.saldo(tenant, TipoCredito.PERFIL);
        String pagamento = "pay_" + UUID.randomUUID();

        enviar(corpoDePagamento("evt_" + UUID.randomUUID(), "PAYMENT_CONFIRMED", assinatura, pagamento))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("processado"));
        enviar(corpoDePagamento("evt_" + UUID.randomUUID(), "PAYMENT_RECEIVED", assinatura, pagamento))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("repetido"));

        assertThat(creditos.saldo(tenant, TipoCredito.PERFIL) - antes)
                .as("o mesmo pagamento recarrega uma única vez, qualquer que seja o evento")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("pagamento confirmado sem identificador de pagamento não credita")
    void pagamentoSemIdentificadorNaoCredita() throws Exception {
        // Sem o pagamento não há como saber se o outro evento dele já creditou. Fica
        // registrado para tratamento manual, em vez de arriscar crédito em dobro.
        UUID tenant = criarConta();
        String assinatura = vincularAoGateway(tenant);
        int antes = creditos.saldo(tenant, TipoCredito.PERFIL);

        enviar("""
                {"id": "evt_%s", "event": "PAYMENT_CONFIRMED",
                 "payment": {"subscription": "%s", "dueDate": "2026-11-01"}}
                """.formatted(UUID.randomUUID(), assinatura))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("ignorado"));

        assertThat(creditos.saldo(tenant, TipoCredito.PERFIL)).isEqualTo(antes);
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
    @DisplayName("pagamento confirmado avança a próxima cobrança em um ciclo a partir do vencimento pago")
    void pagamentoAvancaProximaCobranca() throws Exception {
        UUID tenant = criarConta();
        String assinatura = vincularAoGateway(tenant);

        // Corpo no formato documentado pelo Asaas: o objeto payment traz o vencimento daquela
        // cobrança (dueDate), e não o da próxima.
        enviar("""
                {"id": "evt_%s", "event": "PAYMENT_CONFIRMED",
                 "payment": {"object": "payment", "id": "pay_ficticio",
                             "subscription": "%s", "dueDate": "2026-10-16",
                             "originalDueDate": "2026-10-16", "value": 137.00,
                             "billingType": "PIX", "status": "CONFIRMED"}}
                """.formatted(UUID.randomUUID(), assinatura))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("processado"));

        assertThat(proximaCobranca(tenant)).isEqualTo(LocalDate.of(2026, 11, 16));
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

    // ------------------------------------------------------------- cancelamento

    @ParameterizedTest
    @ValueSource(strings = {"SUBSCRIPTION_DELETED", "SUBSCRIPTION_INACTIVATED"})
    @DisplayName("assinatura paga cancelada no gateway continua valendo até o fim do período pago")
    void cancelamentoValeNoFimDoPeriodoPago(String evento) throws Exception {
        UUID tenant = criarConta();
        String assinatura = vincularAoGateway(tenant);
        LocalDate vencimento = LocalDate.now(FUSO).plusDays(10);
        pagar(assinatura, vencimento);

        cancelar(assinatura, evento)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("processado"));

        // Pagou até a próxima cobrança: o acesso vai até lá, e não acaba no dia do cancelamento.
        assertThat(statusDaAssinatura(tenant)).isEqualTo("ATIVA");
        assertThat(cancelaEm(tenant)).isEqualTo(vencimento.plusMonths(1));
        assertThat(limites.planoDe(tenant)).isNotNull();
    }

    @Test
    @DisplayName("passado o fim do período, o acesso acaba mesmo antes de o job encerrar a assinatura")
    void cancelamentoVencidoCortaAcesso() throws Exception {
        UUID tenant = criarConta();
        String assinatura = vincularAoGateway(tenant);
        pagar(assinatura, LocalDate.now(FUSO).plusDays(10));
        cancelar(assinatura, "SUBSCRIPTION_DELETED").andExpect(status().isOk());

        executarComoDono("UPDATE assinatura SET cancela_em = now() - interval '1 minute' WHERE tenant_id = ?",
                tenant);

        assertThatThrownBy(() -> limites.planoDe(tenant))
                .isInstanceOf(SemAssinaturaVigenteException.class);

        ciclo.encerrarCancelamentosVencidos();
        assertThat(statusDaAssinatura(tenant)).isEqualTo("CANCELADA");
    }

    @Test
    @DisplayName("inadimplente cancelado perde o acesso na hora: não há período pago a honrar")
    void inadimplenteCanceladoPerdeAcessoNaHora() throws Exception {
        UUID tenant = criarConta();
        String assinatura = vincularAoGateway(tenant);
        enviar("""
                {"id": "evt_%s", "event": "PAYMENT_OVERDUE",
                 "payment": {"id": "pay_%s", "subscription": "%s"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), assinatura))
                .andExpect(status().isOk());

        cancelar(assinatura, "SUBSCRIPTION_INACTIVATED").andExpect(status().isOk());

        assertThat(statusDaAssinatura(tenant)).isEqualTo("CANCELADA");
        assertThatThrownBy(() -> limites.planoDe(tenant))
                .isInstanceOf(SemAssinaturaVigenteException.class);
    }

    @Test
    @DisplayName("degustação cancelada vale até o fim e não recebe o aviso de cobrança")
    void degustacaoCanceladaValeAteOFim() throws Exception {
        UUID tenant = criarConta();
        String assinatura = vincularAoGateway(tenant);

        cancelar(assinatura, "SUBSCRIPTION_DELETED").andExpect(status().isOk());

        assertThat(statusDaAssinatura(tenant)).isEqualTo("TRIAL");
        assertThat(cancelaEm(tenant)).isNotNull();
        // O aviso diz que a cobrança vai começar; para quem cancelou, seria falso.
        assertThat(cobranca.trialsAAvisar(Instant.now().plus(Duration.ofDays(365))))
                .noneMatch(trial -> trial.tenantId().equals(tenant));
    }

    @Test
    @DisplayName("o mesmo cancelamento entregue duas vezes não muda a data do fim do acesso")
    void cancelamentoReentregue() throws Exception {
        UUID tenant = criarConta();
        String assinatura = vincularAoGateway(tenant);
        pagar(assinatura, LocalDate.now(FUSO).plusDays(10));
        String idDoEvento = "evt_" + UUID.randomUUID();

        enviar(corpoDeCancelamento(idDoEvento, "SUBSCRIPTION_DELETED", assinatura)).andExpect(status().isOk());
        LocalDate primeiro = cancelaEm(tenant);
        enviar(corpoDeCancelamento(idDoEvento, "SUBSCRIPTION_DELETED", assinatura))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("repetido"));

        assertThat(cancelaEm(tenant)).isEqualTo(primeiro);
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
        return notificar(idDoEvento, assinatura, "pay_" + UUID.randomUUID());
    }

    private org.springframework.test.web.servlet.ResultActions notificar(
            String idDoEvento, String assinatura, String pagamento) throws Exception {
        return enviar(corpoDePagamento(idDoEvento, "PAYMENT_CONFIRMED", assinatura, pagamento));
    }

    private org.springframework.test.web.servlet.ResultActions enviar(String corpo)
            throws Exception {
        return mockMvc.perform(post("/api/v1/webhooks/cobranca")
                .header("asaas-access-token", TOKEN_WEBHOOK)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    private static String corpoDePagamento(String idDoEvento, String assinatura) {
        return corpoDePagamento(idDoEvento, "PAYMENT_CONFIRMED", assinatura, "pay_" + UUID.randomUUID());
    }

    private static String corpoDePagamento(String idDoEvento, String evento,
                                           String assinatura, String pagamento) {
        return """
                {"id": "%s", "event": "%s",
                 "payment": {"id": "%s", "subscription": "%s", "dueDate": "2026-11-01"}}
                """.formatted(idDoEvento, evento, pagamento, assinatura);
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

    private LocalDate proximaCobranca(UUID tenant) throws SQLException {
        try (Connection dono = conexaoDono();
             PreparedStatement ps = dono.prepareStatement("""
                     SELECT (proxima_cobranca AT TIME ZONE 'America/Sao_Paulo')::date
                       FROM assinatura WHERE tenant_id = ?
                     """)) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, LocalDate.class);
            }
        }
    }

    private void pagar(String assinatura, LocalDate vencimento) throws Exception {
        enviar("""
                {"id": "evt_%s", "event": "PAYMENT_RECEIVED",
                 "payment": {"id": "pay_%s", "subscription": "%s", "dueDate": "%s"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), assinatura, vencimento))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("processado"));
    }

    private org.springframework.test.web.servlet.ResultActions cancelar(String assinatura, String evento)
            throws Exception {
        return enviar(corpoDeCancelamento("evt_" + UUID.randomUUID(), evento, assinatura));
    }

    // Formato documentado pelo Asaas para eventos de assinatura: o objeto subscription vem na
    // raiz, com o próprio id, e não existe objeto payment.
    private static String corpoDeCancelamento(String idDoEvento, String evento, String assinatura) {
        return """
                {"id": "%s", "event": "%s",
                 "subscription": {"object": "subscription", "id": "%s",
                                  "customer": "cus_ficticio", "cycle": "MONTHLY",
                                  "status": "INACTIVE", "deleted": true}}
                """.formatted(idDoEvento, evento, assinatura);
    }

    private LocalDate cancelaEm(UUID tenant) throws SQLException {
        try (Connection dono = conexaoDono();
             PreparedStatement ps = dono.prepareStatement("""
                     SELECT (cancela_em AT TIME ZONE 'America/Sao_Paulo')::date
                       FROM assinatura WHERE tenant_id = ?
                     """)) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, LocalDate.class);
            }
        }
    }

    private static void executarComoDono(String sql, UUID tenant) throws SQLException {
        try (Connection dono = conexaoDono(); PreparedStatement ps = dono.prepareStatement(sql)) {
            ps.setObject(1, tenant);
            ps.executeUpdate();
        }
    }

    private static Connection conexaoDono() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), SENHA_DONO);
    }
}
