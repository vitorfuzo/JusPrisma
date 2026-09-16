package br.com.jusprisma.admin;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Painel interno da plataforma.
 *
 * <p>O que este teste realmente protege é a separação entre as duas credenciais. Token de
 * cliente não pode abrir o painel — abriria todos os escritórios de uma vez — e token de
 * operador não pode abrir rota de cliente, porque ele não pertence a tenant nenhum e o
 * escopo de RLS não teria como ser aberto corretamente.
 */
@SpringBootTest(classes = {JusPrismaApplication.class, CaixaDeSaidaDeTeste.Configuracao.class})
@AutoConfigureMockMvc
@Testcontainers
class PainelAdministrativoTest {

    private static final String SENHA_DONO = "owner_teste";
    private static final String SENHA_APP = "app_teste";
    private static final String SENHA = "senha-de-teste-comprida";
    private static final String SENHA_OPERADOR = "senha-de-operador-bem-comprida";
    private static final String EMAIL_OPERADOR = "operador@exemplo.invalido";

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
        // Exercita o próprio mecanismo de criação do primeiro operador, em vez de inserir
        // a linha por SQL no teste: assim o caminho testado é o que roda em produção.
        registro.add("jusprisma.operador-inicial.email", () -> EMAIL_OPERADOR);
        registro.add("jusprisma.operador-inicial.senha", () -> SENHA_OPERADOR);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private Creditos creditos;

    // ------------------------------------------------------- separação de escopo

    @Test
    @DisplayName("token de cliente não abre o painel administrativo")
    void clienteNaoAbreOPainel() throws Exception {
        String tokenDeCliente = entrarComoCliente(criarConta());

        mockMvc.perform(get("/api/v1/admin/tenants")
                        .header("Authorization", "Bearer " + tokenDeCliente))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("token de operador não abre rota de cliente")
    void operadorNaoAbreRotaDeCliente() throws Exception {
        String tokenDeOperador = entrarComoOperador();

        // O operador não pertence a escritório nenhum. Se este token passasse, a rota
        // tentaria abrir escopo de RLS sem tenant e o resultado seria imprevisível.
        mockMvc.perform(get("/api/v1/assinatura")
                        .header("Authorization", "Bearer " + tokenDeOperador))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("o painel exige autenticação")
    void painelExigeAutenticacao() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenants")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ painel

    @Test
    @DisplayName("o painel lista escritórios de todos os tenants")
    void listaEscritorios() throws Exception {
        criarConta();
        criarConta();
        String operador = entrarComoOperador();

        MvcResult resultado = mockMvc.perform(get("/api/v1/admin/tenants")
                        .header("Authorization", "Bearer " + operador))
                .andExpect(status().isOk())
                .andReturn();

        var lista = json.readTree(resultado.getResponse().getContentAsString());
        assertThat(lista.size())
                .as("a listagem atravessa a fronteira de tenant de propósito")
                .isGreaterThanOrEqualTo(2);
        assertThat(lista.get(0).get("planoCodigo").asString()).isEqualTo("DEGUSTACAO");
    }

    @Test
    @DisplayName("conceder crédito soma no saldo do escritório e deixa trilha")
    void concederCreditoDeixaTrilha() throws Exception {
        UUID tenant = criarConta();
        String operador = entrarComoOperador();
        int antes = creditos.saldo(tenant, TipoCredito.PERFIL);

        mockMvc.perform(post("/api/v1/admin/tenants/" + tenant + "/creditos")
                        .header("Authorization", "Bearer " + operador)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tipo": "PERFIL", "quantidade": 5,
                                 "motivo": "perfil nao gerou por falha de pipeline"}
                                """))
                .andExpect(status().isNoContent());

        assertThat(creditos.saldo(tenant, TipoCredito.PERFIL)).isEqualTo(antes + 5);

        // Conceder crédito mexe em algo que vale dinheiro. Sem trilha não há como
        // distinguir correção legítima de fraude interna.
        assertThat(acoesRegistradas(tenant)).isEqualTo(1);
    }

    @Test
    @DisplayName("concessão sem motivo é recusada")
    void concessaoExigeMotivo() throws Exception {
        UUID tenant = criarConta();
        String operador = entrarComoOperador();

        mockMvc.perform(post("/api/v1/admin/tenants/" + tenant + "/creditos")
                        .header("Authorization", "Bearer " + operador)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tipo": "PERFIL", "quantidade": 5, "motivo": "  "}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(acoesRegistradas(tenant)).isZero();
    }

    @Test
    @DisplayName("a trilha administrativa não pode ser alterada nem apagada")
    void trilhaEhAppendOnly() throws Exception {
        UUID tenant = criarConta();
        String operador = entrarComoOperador();

        mockMvc.perform(post("/api/v1/admin/tenants/" + tenant + "/creditos")
                        .header("Authorization", "Bearer " + operador)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tipo": "IA", "quantidade": 10, "motivo": "cortesia de suporte"}
                                """))
                .andExpect(status().isNoContent());

        // Como dono do schema, para provar que o bloqueio não depende de GRANT:
        // trilha que pode ser editada não é trilha.
        try (Connection dono = conexaoDono(); Statement st = dono.createStatement()) {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> st.executeUpdate("DELETE FROM acao_administrativa"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("append-only");
        }
    }

    // ------------------------------------------------------------------ apoio

    private String entrarComoOperador() throws Exception {
        MvcResult sessao = mockMvc.perform(post("/api/v1/admin/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "senha": "%s"}
                                """.formatted(EMAIL_OPERADOR, SENHA_OPERADOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").exists())
                .andReturn();

        return json.readTree(sessao.getResponse().getContentAsString())
                .get("tokenDeAcesso").asString();
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

    private String entrarComoCliente(UUID ignorado) throws Exception {
        // Cria uma conta nova só para o login, porque criarConta não devolve o e-mail.
        String email = "socio-" + UUID.randomUUID() + "@exemplo.invalido";
        mockMvc.perform(post("/api/v1/contas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nomeDoEscritorio": "Ficticio Cliente", "email": "%s", "senha": "%s"}
                                """.formatted(email, SENHA)))
                .andExpect(status().isCreated());

        MvcResult sessao = mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "senha": "%s"}
                                """.formatted(email, SENHA)))
                .andExpect(status().isOk())
                .andReturn();

        return json.readTree(sessao.getResponse().getContentAsString())
                .get("tokenDeAcesso").asString();
    }

    private int acoesRegistradas(UUID tenant) throws SQLException {
        try (Connection dono = conexaoDono();
             var ps = dono.prepareStatement(
                     "SELECT count(*) FROM acao_administrativa WHERE tenant_alvo = ?")) {
            ps.setObject(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static Connection conexaoDono() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), SENHA_DONO);
    }
}
