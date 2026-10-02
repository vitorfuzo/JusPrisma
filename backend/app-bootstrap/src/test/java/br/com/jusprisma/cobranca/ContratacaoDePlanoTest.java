package br.com.jusprisma.cobranca;

import br.com.jusprisma.JusPrismaApplication;
import br.com.jusprisma.aplicacao.cobranca.EventoDeCobranca;
import br.com.jusprisma.aplicacao.cobranca.GatewayDePagamento;
import br.com.jusprisma.aplicacao.cobranca.GatewayIndisponivelException;
import br.com.jusprisma.conta.CaixaDeSaidaDeTeste;
import br.com.jusprisma.plano.PlanoDeTeste;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contratação de plano pago com CPF ou CNPJ de cobrança.
 *
 * <p>O gateway é substituído por um dublê em memória que registra o que recebeu; o que vai
 * no fio do Asaas tem teste próprio no adaptador. Documentos são gerados pelo algoritmo do
 * dígito verificador e não pertencem a ninguém.
 */
@SpringBootTest(classes = {JusPrismaApplication.class, CaixaDeSaidaDeTeste.Configuracao.class,
        ContratacaoDePlanoTest.Configuracao.class})
@AutoConfigureMockMvc
@Testcontainers
class ContratacaoDePlanoTest {

    private static final String SENHA_DONO = "owner_teste";
    private static final String SENHA_APP = "app_teste";
    private static final String SENHA = "senha-de-teste-comprida";
    private static final String TOKEN_WEBHOOK = "token-de-webhook-para-teste";
    private static final String CPF = "12345678909";
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

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
        registro.add("jusprisma.jobs.habilitados", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private GatewayEmMemoria gateway;

    @Autowired
    private CaixaDeSaidaDeTeste caixa;

    @BeforeEach
    void limpar() {
        gateway.limpar();
        caixa.limpar();
    }

    // ------------------------------------------------------------- caminho feliz

    @Test
    @DisplayName("o dono contrata na degustação: plano pendente, cobrança no fim dela")
    void donoContrataNaDegustacao() throws Exception {
        Conta conta = criarConta();
        LocalDate fimDaDegustacao = fimDoPeriodo(conta.tenantId()).atZone(FUSO).toLocalDate();

        contratar(conta.acesso(), "SOLO", "123.456.789-09")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planoCodigo").value("SOLO"))
                .andExpect(jsonPath("$.documento").value("***.456.789-**"))
                .andExpect(jsonPath("$.primeiraCobranca").value(fimDaDegustacao.toString()));

        assertThat(gateway.clientes).singleElement().satisfies(cliente -> {
            assertThat(cliente.documento()).as("só os dígitos chegam ao gateway").isEqualTo(CPF);
            assertThat(cliente.referenciaExterna()).isEqualTo(conta.tenantId().toString());
        });
        assertThat(gateway.assinaturas).singleElement().satisfies(assinatura -> {
            assertThat(assinatura.primeiraCobranca())
                    .as("quem já pagou a degustação não é cobrado de novo antes de ela acabar")
                    .isEqualTo(fimDaDegustacao);
            assertThat(assinatura.valorCentavos()).isEqualTo(5700);
        });

        assertThat(coluna("SELECT documento_cobranca FROM tenant WHERE id = ?", conta.tenantId()))
                .isEqualTo(CPF);
        mockMvc.perform(get("/api/v1/assinatura").header("Authorization", "Bearer " + conta.acesso()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contratacao.contratado").value(true))
                .andExpect(jsonPath("$.contratacao.planoPendente").value("SOLO"))
                .andExpect(jsonPath("$.contratacao.proximaCobranca")
                        .value(fimDaDegustacao.toString()));
        assertThat(coluna("SELECT plano_contratado FROM assinatura WHERE tenant_id = ?", conta.tenantId()))
                .isEqualTo("SOLO");
        assertThat(coluna("SELECT plano_codigo FROM assinatura WHERE tenant_id = ?", conta.tenantId()))
                .as("os limites do plano pago só valem depois do pagamento")
                .isEqualTo("DEGUSTACAO");
    }

    @Test
    @DisplayName("o pagamento confirmado efetiva o plano contratado")
    void pagamentoEfetivaPlano() throws Exception {
        Conta conta = criarConta();
        contratar(conta.acesso(), "SOLO", CPF).andExpect(status().isOk());
        String assinaturaNoGateway = coluna(
                "SELECT gateway_subscription_id FROM assinatura WHERE tenant_id = ?", conta.tenantId());

        mockMvc.perform(post("/api/v1/webhooks/cobranca")
                        .header("asaas-access-token", TOKEN_WEBHOOK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "evt_%s", "event": "PAYMENT_CONFIRMED",
                                 "payment": {"id": "pay_%s", "subscription": "%s",
                                             "dueDate": "2026-11-01"}}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID(), assinaturaNoGateway)))
                .andExpect(status().isOk());

        assertThat(coluna("SELECT plano_codigo FROM assinatura WHERE tenant_id = ?", conta.tenantId()))
                .isEqualTo("SOLO");
        assertThat(coluna("SELECT plano_contratado FROM assinatura WHERE tenant_id = ?", conta.tenantId()))
                .isNull();
        assertThat(coluna("SELECT status FROM assinatura WHERE tenant_id = ?", conta.tenantId()))
                .isEqualTo("ATIVA");
        assertThat(coluna("""
                SELECT string_agg(DISTINCT referencia_id, ',') FROM credito_lancamento
                 WHERE tenant_id = ? AND motivo = 'renovação do plano'
                """, conta.tenantId()))
                .as("a recarga do primeiro pagamento usa as cotas do plano pago")
                .isEqualTo("SOLO");
    }

    // ------------------------------------------------------------- recusas

    @Test
    @DisplayName("documento inválido é recusado sem chegar ao gateway")
    void documentoInvalidoNaoSai() throws Exception {
        Conta conta = criarConta();

        contratar(conta.acesso(), "SOLO", "123.456.789-00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("CPF inválido"));

        assertThat(gateway.clientes).isEmpty();
        assertThat(coluna("SELECT documento_cobranca FROM tenant WHERE id = ?", conta.tenantId()))
                .isNull();
    }

    @Test
    @DisplayName("membro não contrata plano")
    void membroNaoContrata() throws Exception {
        Conta dono = criarConta();
        try (Connection conexao = conexaoDono()) {
            PlanoDeTeste.promover(conexao, dono.tenantId(), "PRO");
        }
        String membro = "membro-" + UUID.randomUUID() + "@exemplo.invalido";
        mockMvc.perform(post("/api/v1/convites")
                        .header("Authorization", "Bearer " + dono.acesso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"papel\": \"MEMBRO\"}".formatted(membro)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/convites/aceite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"%s\", \"senha\": \"%s\"}".formatted(
                                caixa.ultimo(CaixaDeSaidaDeTeste.CONVITE).segredo(), SENHA)))
                .andExpect(status().isCreated());

        contratar(entrar(membro), "SOLO", CPF).andExpect(status().isForbidden());

        assertThat(gateway.clientes).isEmpty();
    }

    @Test
    @DisplayName("a degustação não se contrata")
    void degustacaoNaoSeContrata() throws Exception {
        Conta conta = criarConta();
        contratar(conta.acesso(), "DEGUSTACAO", CPF).andExpect(status().isBadRequest());
        contratar(conta.acesso(), "PLANO_QUE_NAO_EXISTE", CPF).andExpect(status().isBadRequest());
        assertThat(gateway.clientes).isEmpty();
    }

    @Test
    @DisplayName("contratar de novo é conflito, sem segunda assinatura no gateway")
    void segundaContratacaoEhConflito() throws Exception {
        Conta conta = criarConta();
        contratar(conta.acesso(), "SOLO", CPF).andExpect(status().isOk());

        contratar(conta.acesso(), "ESCRITORIO", CPF).andExpect(status().isConflict());

        assertThat(gateway.assinaturas).hasSize(1);
    }

    // ------------------------------------------------------------- falha parcial

    @Test
    @DisplayName("falha do gateway não grava nada, e a nova tentativa usa as mesmas referências")
    void falhaDoGatewayEhRepetivel() throws Exception {
        Conta conta = criarConta();
        gateway.falharNaProximaAssinatura.set(true);

        contratar(conta.acesso(), "SOLO", CPF)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.correlacaoId").exists());

        assertThat(coluna("SELECT plano_contratado FROM assinatura WHERE tenant_id = ?", conta.tenantId()))
                .isNull();
        assertThat(coluna("SELECT documento_cobranca FROM tenant WHERE id = ?", conta.tenantId()))
                .as("a gravação do documento volta junto com o resto")
                .isNull();

        contratar(conta.acesso(), "SOLO", CPF).andExpect(status().isOk());

        // O cliente foi criado na primeira tentativa. A segunda manda a mesma referência, e é
        // essa referência que faz o gateway devolver o existente em vez de criar outro.
        assertThat(gateway.clientes).hasSize(2)
                .extracting(GatewayDePagamento.DadosDoCliente::referenciaExterna)
                .containsOnly(conta.tenantId().toString());
    }

    @Test
    @DisplayName("a resposta e o erro não devolvem o documento inteiro")
    void documentoNaoVaza() throws Exception {
        Conta conta = criarConta();

        String resposta = contratar(conta.acesso(), "SOLO", CPF)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(resposta).doesNotContain(CPF);

        String erro = contratar(criarConta().acesso(), "SOLO", "12345678900")
                .andReturn().getResponse().getContentAsString();
        assertThat(erro).doesNotContain("12345678900");
    }

    // ------------------------------------------------------------- recontratação

    @Test
    @DisplayName("escritório com assinatura cancelada contrata de novo: assinatura nova, sem acesso até pagar")
    void canceladoContrataDeNovo() throws Exception {
        Conta conta = criarConta();
        UUID antiga = UUID.fromString(coluna("SELECT id FROM assinatura WHERE tenant_id = ?", conta.tenantId()));
        executar("UPDATE assinatura SET status = 'CANCELADA', gateway_subscription_id = 'sub_antiga_"
                + antiga + "' WHERE id = ?", antiga);

        mockMvc.perform(get("/api/v1/assinatura").header("Authorization", "Bearer " + conta.acesso()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plano").doesNotExist())
                .andExpect(jsonPath("$.contratacao.contratado").value(false));

        LocalDate hoje = LocalDate.now(FUSO);
        contratar(conta.acesso(), "ESCRITORIO", CPF)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primeiraCobranca").value(hoje.toString()));

        assertThat(gateway.assinaturas).singleElement().satisfies(assinatura -> {
            assertThat(assinatura.referenciaExterna())
                    .as("a referência é a assinatura nova; a antiga não pode ser reencontrada")
                    .isNotEqualTo(antiga.toString())
                    .isEqualTo(coluna("SELECT id FROM assinatura WHERE tenant_id = ? AND status = 'AGUARDANDO_PAGAMENTO'",
                            conta.tenantId()));
            assertThat(assinatura.primeiraCobranca()).as("sem degustação de novo").isEqualTo(hoje);
        });
        assertThat(coluna("SELECT status FROM assinatura WHERE id = ?", antiga))
                .as("a cancelada fica no histórico").isEqualTo("CANCELADA");
        assertThat(coluna("SELECT plano_contratado FROM assinatura WHERE tenant_id = ? AND status = 'AGUARDANDO_PAGAMENTO'",
                conta.tenantId())).isEqualTo("ESCRITORIO");

        mockMvc.perform(get("/api/v1/assinatura").header("Authorization", "Bearer " + conta.acesso()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plano").doesNotExist())
                .andExpect(jsonPath("$.contratacao.contratado").value(true))
                .andExpect(jsonPath("$.contratacao.planoPendente").value("ESCRITORIO"));
        contratar(conta.acesso(), "SOLO", CPF).andExpect(status().isConflict());
        assertThat(gateway.assinaturas).hasSize(1);
    }

    @Test
    @DisplayName("o primeiro pagamento da recontratação ativa a assinatura nova e recarrega")
    void primeiroPagamentoDaRecontratacaoAtiva() throws Exception {
        Conta conta = criarConta();
        cancelarAssinaturaAtual(conta.tenantId());
        contratar(conta.acesso(), "ESCRITORIO", CPF).andExpect(status().isOk());
        String nova = coluna("SELECT gateway_subscription_id FROM assinatura WHERE tenant_id = ? AND status = 'AGUARDANDO_PAGAMENTO'",
                conta.tenantId());

        webhook("PAYMENT_CONFIRMED", nova).andExpect(status().isOk());

        assertThat(coluna("SELECT status || ':' || plano_codigo FROM assinatura WHERE tenant_id = ? AND status <> 'CANCELADA'",
                conta.tenantId())).isEqualTo("ATIVA:ESCRITORIO");
        assertThat(coluna("""
                SELECT string_agg(DISTINCT referencia_id, ',') FROM credito_lancamento
                 WHERE tenant_id = ? AND motivo = 'renovação do plano'
                """, conta.tenantId())).isEqualTo("ESCRITORIO");
        mockMvc.perform(get("/api/v1/assinatura").header("Authorization", "Bearer " + conta.acesso()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plano.codigo").value("ESCRITORIO"));
    }

    @Test
    @DisplayName("evento atrasado da assinatura cancelada não a reativa nem credita")
    void eventoDaCanceladaNaoReativa() throws Exception {
        Conta conta = criarConta();
        String antiga = cancelarAssinaturaAtual(conta.tenantId());
        contratar(conta.acesso(), "ESCRITORIO", CPF).andExpect(status().isOk());

        webhook("PAYMENT_CONFIRMED", antiga).andExpect(status().isOk());
        webhook("PAYMENT_OVERDUE", antiga).andExpect(status().isOk());

        assertThat(coluna("SELECT status FROM assinatura WHERE gateway_subscription_id = ?", antiga))
                .isEqualTo("CANCELADA");
        assertThat(coluna("SELECT status FROM assinatura WHERE tenant_id = ? AND gateway_subscription_id <> '" + antiga + "'",
                conta.tenantId())).isEqualTo("AGUARDANDO_PAGAMENTO");
        assertThat(coluna("SELECT count(*) FROM credito_lancamento WHERE tenant_id = ? AND motivo = 'renovação do plano'",
                conta.tenantId())).as("crédito só contra pagamento da assinatura corrente").isEqualTo("0");
    }

    @Test
    @DisplayName("cobrança vencida na recontratação não pagou nada e não dá acesso")
    void cobrancaVencidaNaoDaAcesso() throws Exception {
        Conta conta = criarConta();
        cancelarAssinaturaAtual(conta.tenantId());
        contratar(conta.acesso(), "ESCRITORIO", CPF).andExpect(status().isOk());
        String nova = coluna("SELECT gateway_subscription_id FROM assinatura WHERE tenant_id = ? AND status = 'AGUARDANDO_PAGAMENTO'",
                conta.tenantId());

        webhook("PAYMENT_OVERDUE", nova).andExpect(status().isOk());

        assertThat(coluna("SELECT status FROM assinatura WHERE gateway_subscription_id = ?", nova))
                .as("inadimplente teria acesso; quem nunca pagou não tem")
                .isEqualTo("AGUARDANDO_PAGAMENTO");
    }

    @Test
    @DisplayName("cancelamento agendado que já venceu, antes do job, não impede contratar")
    void cancelamentoVencidoSemJob() throws Exception {
        Conta conta = criarConta();
        UUID antiga = UUID.fromString(coluna("SELECT id FROM assinatura WHERE tenant_id = ?", conta.tenantId()));
        executar("""
                UPDATE assinatura SET status = 'ATIVA', gateway_subscription_id = 'sub_vencida',
                       cancela_em = now() - interval '1 hour'
                 WHERE id = ?
                """, antiga);

        contratar(conta.acesso(), "SOLO", CPF).andExpect(status().isOk());

        assertThat(coluna("SELECT status FROM assinatura WHERE id = ?", antiga)).isEqualTo("CANCELADA");
        assertThat(gateway.assinaturas).hasSize(1);
    }

    @Test
    @DisplayName("no período pago de uma assinatura cancelada, contratar é conflito")
    void periodoPagoNaoRecontrata() throws Exception {
        Conta conta = criarConta();
        executar("""
                UPDATE assinatura SET status = 'ATIVA', gateway_subscription_id = 'sub_em_periodo',
                       cancela_em = now() + interval '10 days'
                 WHERE tenant_id = ?
                """, conta.tenantId());

        contratar(conta.acesso(), "SOLO", CPF).andExpect(status().isConflict());

        assertThat(gateway.assinaturas).isEmpty();
    }

    @Test
    @DisplayName("falha do gateway na recontratação: a nova tentativa usa a mesma referência")
    void falhaNaRecontratacaoReaproveitaReferencia() throws Exception {
        Conta conta = criarConta();
        cancelarAssinaturaAtual(conta.tenantId());
        gateway.falharNaProximaAssinatura.set(true);

        contratar(conta.acesso(), "SOLO", CPF).andExpect(status().isServiceUnavailable());
        contratar(conta.acesso(), "SOLO", CPF).andExpect(status().isOk());

        assertThat(gateway.referenciasTentadas)
                .as("referência diferente criaria segunda assinatura no Asaas, com cobrança em dobro")
                .hasSize(2).containsOnly(gateway.referenciasTentadas.getFirst());
        assertThat(coluna("SELECT count(*) FROM assinatura WHERE tenant_id = ? AND status <> 'CANCELADA'",
                conta.tenantId())).isEqualTo("1");
    }

    @Test
    @DisplayName("duas recontratações simultâneas geram uma assinatura só")
    void recontratacoesSimultaneas() throws Exception {
        Conta conta = criarConta();
        cancelarAssinaturaAtual(conta.tenantId());

        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var largada = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> tentativa = () -> {
                largada.await();
                return contratar(conta.acesso(), "SOLO", CPF).andReturn().getResponse().getStatus();
            };
            var a = executor.submit(tentativa);
            var b = executor.submit(tentativa);
            largada.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }

        assertThat(gateway.assinaturas).hasSize(1);
        assertThat(coluna("SELECT count(*) FROM assinatura WHERE tenant_id = ? AND status <> 'CANCELADA'",
                conta.tenantId())).isEqualTo("1");
    }

    // ------------------------------------------------------------------ apoio

    private record Conta(UUID tenantId, String acesso) {
    }

    private Conta criarConta() throws Exception {
        String email = "socio-" + UUID.randomUUID() + "@exemplo.invalido";
        MvcResult criada = mockMvc.perform(post("/api/v1/contas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nomeDoEscritorio": "Escritorio Ficticio de Teste",
                                 "email": "%s", "senha": "%s"}
                                """.formatted(email, SENHA)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenant = UUID.fromString(json.readTree(criada.getResponse().getContentAsString())
                .get("tenantId").asString());
        return new Conta(tenant, entrar(email));
    }

    private String entrar(String email) throws Exception {
        MvcResult sessao = mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, SENHA)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(sessao.getResponse().getContentAsString())
                .get("tokenDeAcesso").asString();
    }

    private ResultActions contratar(String acesso, String plano, String documento) throws Exception {
        return mockMvc.perform(post("/api/v1/assinatura/contratacao")
                .header("Authorization", "Bearer " + acesso)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"planoCodigo\": \"%s\", \"documento\": \"%s\"}".formatted(plano, documento)));
    }

    private Instant fimDoPeriodo(UUID tenant) throws SQLException {
        try (Connection dono = conexaoDono();
             PreparedStatement ps = dono.prepareStatement(
                     "SELECT fim_do_periodo FROM assinatura WHERE tenant_id = ?")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toInstant();
            }
        }
    }

    /** Cancela a assinatura atual como o job faria, com um id no gateway; devolve esse id. */
    private String cancelarAssinaturaAtual(UUID tenant) throws SQLException {
        String noGateway = "sub_cancelada_" + UUID.randomUUID();
        executar("UPDATE assinatura SET status = 'CANCELADA', gateway_subscription_id = '" + noGateway
                + "' WHERE tenant_id = ?", tenant);
        return noGateway;
    }

    private ResultActions webhook(String evento, String assinaturaNoGateway) throws Exception {
        return mockMvc.perform(post("/api/v1/webhooks/cobranca")
                .header("asaas-access-token", TOKEN_WEBHOOK)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"id": "evt_%s", "event": "%s",
                         "payment": {"id": "pay_%s", "subscription": "%s",
                                     "dueDate": "2026-11-01"}}
                        """.formatted(UUID.randomUUID(), evento, UUID.randomUUID(), assinaturaNoGateway)));
    }

    private static void executar(String sql, UUID parametro) throws SQLException {
        try (Connection dono = conexaoDono();
             PreparedStatement ps = dono.prepareStatement(sql)) {
            ps.setObject(1, parametro);
            ps.executeUpdate();
        }
    }

    private static String coluna(String sql, Object parametro) throws SQLException {
        try (Connection dono = conexaoDono();
             PreparedStatement ps = dono.prepareStatement(sql)) {
            ps.setObject(1, parametro);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static Connection conexaoDono() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), SENHA_DONO);
    }

    @TestConfiguration
    static class Configuracao {

        @Bean
        @Primary
        // Pelo nome do bean, e não pela classe: referenciar AsaasGateway aqui obrigaria o teste
        // a enxergar as anotações do Resilience4j, que o adaptador não exporta.
        GatewayEmMemoria gatewayEmMemoria(@Qualifier("asaasGateway") GatewayDePagamento asaas) {
            return new GatewayEmMemoria(asaas);
        }
    }

    /**
     * Dublê do gateway para a contratação. Autenticação e interpretação do webhook continuam
     * sendo as do Asaas, para que o teste do pagamento passe pelo caminho real.
     */
    static class GatewayEmMemoria implements GatewayDePagamento {

        private final GatewayDePagamento real;
        final List<DadosDoCliente> clientes = new ArrayList<>();
        final List<NovaAssinatura> assinaturas = new ArrayList<>();
        final List<String> referenciasTentadas = new ArrayList<>();
        final AtomicBoolean falharNaProximaAssinatura = new AtomicBoolean();

        GatewayEmMemoria(GatewayDePagamento real) {
            this.real = real;
        }

        synchronized void limpar() {
            clientes.clear();
            assinaturas.clear();
            referenciasTentadas.clear();
            falharNaProximaAssinatura.set(false);
        }

        @Override
        public String nome() {
            return real.nome();
        }

        @Override
        public synchronized ClienteNoGateway garantirCliente(DadosDoCliente dados) {
            clientes.add(dados);
            return new ClienteNoGateway("cus_" + dados.referenciaExterna());
        }

        @Override
        public synchronized AssinaturaNoGateway garantirAssinatura(NovaAssinatura dados) {
            referenciasTentadas.add(dados.referenciaExterna());
            if (falharNaProximaAssinatura.getAndSet(false)) {
                throw new GatewayIndisponivelException("falha simulada", null);
            }
            assinaturas.add(dados);
            return new AssinaturaNoGateway("sub_" + UUID.randomUUID());
        }

        @Override
        public void cancelarAssinatura(String assinaturaNoGateway) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean notificacaoAutentica(String tokenRecebido) {
            return real.notificacaoAutentica(tokenRecebido);
        }

        @Override
        public Optional<EventoDeCobranca> interpretar(String corpo) {
            return real.interpretar(corpo);
        }
    }
}
