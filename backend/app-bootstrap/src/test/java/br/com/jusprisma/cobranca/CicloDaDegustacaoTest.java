package br.com.jusprisma.cobranca;

import br.com.jusprisma.JusPrismaApplication;
import br.com.jusprisma.aplicacao.cobranca.CicloDaDegustacao;
import br.com.jusprisma.conta.CaixaDeSaidaDeTeste;
import org.junit.jupiter.api.BeforeEach;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O aviso e a conversão da degustação.
 *
 * <p>Converter sem avisar é tecnicamente possível e comercialmente ruinoso: gera
 * chargeback, reclamação pública e cancelamento por irritação, não por preço. O aviso três
 * dias antes é promessa de produto, e é isto que a garante.
 */
@SpringBootTest(classes = {JusPrismaApplication.class, CaixaDeSaidaDeTeste.Configuracao.class})
@AutoConfigureMockMvc
@Testcontainers
class CicloDaDegustacaoTest {

    private static final String SENHA_DONO = "owner_teste";
    private static final String SENHA_APP = "app_teste";
    private static final String SENHA = "senha-de-teste-comprida";

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
        registro.add("jusprisma.jobs.habilitados", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CicloDaDegustacao ciclo;

    @Autowired
    private CaixaDeSaidaDeTeste caixa;

    @BeforeEach
    void limparCaixa() {
        caixa.limpar();
    }

    @Test
    @DisplayName("quem está a três dias do fim recebe o aviso")
    void avisaComTresDiasDeAntecedencia() throws Exception {
        Conta conta = criarConta();
        moverFimDoPeriodo(conta.tenantId(), Instant.now().plus(Duration.ofDays(2)));

        assertThat(ciclo.avisarQuemEstaPertoDoFim()).isEqualTo(1);

        CaixaDeSaidaDeTeste.Enviado aviso =
                caixa.ultimo(CaixaDeSaidaDeTeste.FIM_DA_DEGUSTACAO);
        assertThat(aviso.destinatario()).isEqualTo(conta.email());
    }

    @Test
    @DisplayName("quem ainda tem folga não recebe aviso")
    void naoAvisaAntesDaHora() throws Exception {
        Conta conta = criarConta();
        moverFimDoPeriodo(conta.tenantId(), Instant.now().plus(Duration.ofDays(10)));

        ciclo.avisarQuemEstaPertoDoFim();

        assertThat(caixa.todos())
                .as("avisar cedo demais só faz o cliente esquecer de novo")
                .noneMatch(e -> e.finalidade().equals(CaixaDeSaidaDeTeste.FIM_DA_DEGUSTACAO)
                        && e.destinatario().equals(conta.email()));
    }

    @Test
    @DisplayName("o aviso não é enviado duas vezes")
    void avisoNaoSeRepete() throws Exception {
        Conta conta = criarConta();
        moverFimDoPeriodo(conta.tenantId(), Instant.now().plus(Duration.ofDays(1)));

        assertThat(ciclo.avisarQuemEstaPertoDoFim()).isEqualTo(1);

        // O job roda todo dia e, mais cedo ou mais tarde, roda duas vezes no mesmo dia —
        // por reinício, por implantação, por engano. Receber o mesmo aviso repetidamente
        // é o tipo de ruído que faz o cliente marcar o remetente como spam.
        assertThat(ciclo.avisarQuemEstaPertoDoFim()).isZero();
    }

    @Test
    @DisplayName("degustação vencida deixa de ser trial")
    void converteAoFimDoPeriodo() throws Exception {
        Conta conta = criarConta();
        assertThat(statusDaAssinatura(conta.tenantId())).isEqualTo("TRIAL");

        moverFimDoPeriodo(conta.tenantId(), Instant.now().minus(Duration.ofHours(1)));
        assertThat(ciclo.converterVencidas()).isEqualTo(1);

        // Inadimplente, e não cancelada: sem confirmação de pagamento ainda, mas mantendo
        // o acesso. Melhor um cliente usando de graça por alguns dias do que um cliente
        // pagante barrado por erro nosso de integração.
        assertThat(statusDaAssinatura(conta.tenantId())).isEqualTo("INADIMPLENTE");
    }

    @Test
    @DisplayName("degustação em dia não é convertida")
    void naoConverteQuemEstaEmDia() throws Exception {
        Conta conta = criarConta();
        moverFimDoPeriodo(conta.tenantId(), Instant.now().plus(Duration.ofDays(5)));

        ciclo.converterVencidas();

        assertThat(statusDaAssinatura(conta.tenantId())).isEqualTo("TRIAL");
    }

    // ------------------------------------------------------------------ apoio

    private record Conta(UUID tenantId, String email) {
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
        return new Conta(tenant, email);
    }

    /** Move o fim do período no banco, em vez de esperar 14 dias reais. */
    private void moverFimDoPeriodo(UUID tenant, Instant novoFim) throws SQLException {
        try (Connection dono = conexaoDono();
             PreparedStatement ps = dono.prepareStatement("""
                     UPDATE assinatura SET fim_do_periodo = ?
                      WHERE tenant_id = ? AND status = 'TRIAL'
                     """)) {
            ps.setTimestamp(1, Timestamp.from(novoFim));
            ps.setObject(2, tenant);
            ps.executeUpdate();
        }
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
