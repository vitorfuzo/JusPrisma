package br.com.jusprisma.credito;

import br.com.jusprisma.JusPrismaApplication;
import br.com.jusprisma.aplicacao.credito.Creditos;
import br.com.jusprisma.aplicacao.credito.SaldoInsuficienteException;
import br.com.jusprisma.conta.CaixaDeSaidaDeTeste;
import br.com.jusprisma.dominio.credito.CreditoLancamento;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O ledger de créditos: append-only, saldo por soma, e débito à prova de corrida.
 *
 * <p>A corrida não é cenário de laboratório. Com 2 créditos de perfil na degustação e 10 no
 * Solo, dois cliques no mesmo botão bastam para um usuário gastar duas vezes o que pagou
 * uma. É o tipo de falha que só aparece em produção e sempre em favor do cliente.
 *
 * <p>As asserções são relativas ao saldo inicial. A conta nasce com os créditos do plano,
 * então fixar um número absoluto amarraria estes testes ao seed da Degustação e os
 * quebraria a cada ajuste de tabela de preços — que é dado, e muda.
 */
@SpringBootTest(classes = {JusPrismaApplication.class, CaixaDeSaidaDeTeste.Configuracao.class})
@AutoConfigureMockMvc
@Testcontainers
class LedgerDeCreditosTest {

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
        // Estes testes criam dezenas de contas da mesma origem; a protecao contra
        // abuso tem teste proprio em LimiteDeTentativasTest.
        registro.add("jusprisma.limite-de-tentativas.habilitado", () -> false);
        registro.add("jusprisma.jwt.segredo",
                () -> Base64.getEncoder().encodeToString(new byte[64]));
        registro.add("jusprisma.cookie.seguro", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private Creditos creditos;

    // ------------------------------------------------------------------ saldo

    @Test
    @DisplayName("saldo é a soma dos lançamentos, não um campo")
    void saldoEhSoma() throws Exception {
        UUID tenant = novoTenant();

        int inicial = creditos.saldo(tenant, TipoCredito.PERFIL);

        creditos.creditar(tenant, TipoCredito.PERFIL, 10, "recarga do plano", null);
        creditos.debitar(tenant, TipoCredito.PERFIL, 3, "geração de perfil", "perfil-1");
        creditos.debitar(tenant, TipoCredito.PERFIL, 2, "geração de perfil", "perfil-2");

        assertThat(creditos.saldo(tenant, TipoCredito.PERFIL)).isEqualTo(inicial + 5);
    }

    @Test
    @DisplayName("os tipos de crédito não se misturam")
    void tiposSaoIndependentes() throws Exception {
        UUID tenant = novoTenant();

        int perfilInicial = creditos.saldo(tenant, TipoCredito.PERFIL);
        int iaInicial = creditos.saldo(tenant, TipoCredito.IA);

        creditos.creditar(tenant, TipoCredito.PERFIL, 5, "recarga", null);
        creditos.creditar(tenant, TipoCredito.IA, 100, "recarga", null);
        creditos.debitar(tenant, TipoCredito.IA, 40, "conversa", null);

        assertThat(creditos.saldo(tenant, TipoCredito.PERFIL)).isEqualTo(perfilInicial + 5);
        assertThat(creditos.saldo(tenant, TipoCredito.IA)).isEqualTo(iaInicial + 60);
    }

    @Test
    @DisplayName("débito sem saldo é recusado e não deixa rastro")
    void debitoSemSaldoEhRecusado() throws Exception {
        UUID tenant = novoTenant();
        // CONSULTA nasce em zero na Degustação, o que dá um ponto de partida limpo sem
        // precisar gastar o saldo do plano antes.
        creditos.creditar(tenant, TipoCredito.CONSULTA, 1, "recarga", null);

        assertThatThrownBy(() ->
                creditos.debitar(tenant, TipoCredito.CONSULTA, 2, "consulta", null))
                .isInstanceOf(SaldoInsuficienteException.class);

        assertThat(creditos.saldo(tenant, TipoCredito.CONSULTA)).isEqualTo(1);
    }

    // -------------------------------------------------------------- concorrência

    @Test
    @DisplayName("com um crédito e dois débitos simultâneos, exatamente um passa")
    void debitoConcorrenteNaoGastaDuasVezes() throws Exception {
        UUID tenant = novoTenant();
        // Um crédito exato, num tipo que nasce zerado: é o cenário mínimo em que a corrida
        // aparece. Com saldo de sobra, os dois débitos passariam legitimamente e o teste
        // não mediria nada.
        creditos.creditar(tenant, TipoCredito.CONSULTA, 1, "recarga", null);

        CountDownLatch largada = new CountDownLatch(1);
        Callable<Boolean> tentarDebitar = () -> {
            largada.await();
            try {
                creditos.debitar(tenant, TipoCredito.CONSULTA, 1, "consulta simultânea", null);
                return true;
            } catch (SaldoInsuficienteException esperado) {
                return false;
            }
        };

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> primeira = executor.submit(tentarDebitar);
            Future<Boolean> segunda = executor.submit(tentarDebitar);
            largada.countDown();

            int sucessos = (primeira.get() ? 1 : 0) + (segunda.get() ? 1 : 0);

            assertThat(sucessos)
                    .as("dois cliques no mesmo botão não podem gastar dois créditos de um só")
                    .isEqualTo(1);
        }

        assertThat(creditos.saldo(tenant, TipoCredito.CONSULTA)).isZero();
    }

    // ------------------------------------------------------------------ estorno

    @Test
    @DisplayName("estorno devolve o crédito como lançamento novo")
    void estornoDevolveOCredito() throws Exception {
        UUID tenant = novoTenant();
        int lancamentosIniciais = contarLancamentos(tenant);
        int inicial = creditos.saldo(tenant, TipoCredito.PERFIL);
        creditos.creditar(tenant, TipoCredito.PERFIL, 5, "recarga", null);

        CreditoLancamento debito =
                creditos.debitar(tenant, TipoCredito.PERFIL, 2, "geração", "perfil-x");
        assertThat(creditos.saldo(tenant, TipoCredito.PERFIL)).isEqualTo(inicial + 3);

        creditos.estornar(tenant, debito.id(), "falha na geração do perfil");

        assertThat(creditos.saldo(tenant, TipoCredito.PERFIL)).isEqualTo(inicial + 5);
        assertThat(contarLancamentos(tenant) - lancamentosIniciais)
                .as("o débito continua no extrato; o estorno é linha nova")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("o mesmo lançamento não é estornado duas vezes")
    void estornoDuploEhRecusado() throws Exception {
        UUID tenant = novoTenant();
        int inicial = creditos.saldo(tenant, TipoCredito.PERFIL);
        creditos.creditar(tenant, TipoCredito.PERFIL, 5, "recarga", null);
        CreditoLancamento debito =
                creditos.debitar(tenant, TipoCredito.PERFIL, 2, "geração", null);

        creditos.estornar(tenant, debito.id(), "falha");

        // Sem o índice único em estorno_de, um retry de job devolveria crédito em dobro.
        assertThatThrownBy(() -> creditos.estornar(tenant, debito.id(), "falha de novo"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        assertThat(creditos.saldo(tenant, TipoCredito.PERFIL)).isEqualTo(inicial + 5);
    }

    // --------------------------------------------------------------- append-only

    @Test
    @DisplayName("o banco recusa UPDATE e DELETE no ledger")
    void ledgerEhAppendOnlyNoBanco() throws Exception {
        UUID tenant = novoTenant();
        int inicial = creditos.saldo(tenant, TipoCredito.PERFIL);
        creditos.creditar(tenant, TipoCredito.PERFIL, 5, "recarga", null);

        // Conecta como dono do schema — que tem todos os privilégios — justamente para
        // provar que o bloqueio não depende de GRANT. Um GRANT bem-intencionado durante
        // uma correção de produção não pode transformar o ledger em tabela comum.
        try (Connection dono = conexaoDono(); Statement st = dono.createStatement()) {
            assertThatThrownBy(() ->
                    st.executeUpdate("UPDATE credito_lancamento SET delta = 999"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("append-only");

            assertThatThrownBy(() ->
                    st.executeUpdate("DELETE FROM credito_lancamento"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("append-only");
        }

        assertThat(creditos.saldo(tenant, TipoCredito.PERFIL)).isEqualTo(inicial + 5);
    }

    @Test
    @DisplayName("a role da aplicação não tem privilégio de alterar o ledger")
    void aplicacaoNaoRecebeUpdateNemDelete() throws Exception {
        try (Connection dono = conexaoDono();
             Statement st = dono.createStatement();
             var rs = st.executeQuery("""
                     SELECT privilege_type FROM information_schema.table_privileges
                      WHERE grantee = 'jusprisma_app' AND table_name = 'credito_lancamento'
                     """)) {

            var privilegios = new java.util.ArrayList<String>();
            while (rs.next()) {
                privilegios.add(rs.getString(1));
            }
            assertThat(privilegios)
                    .as("cinto e suspensório: sem privilégio, além do gatilho")
                    .containsExactlyInAnyOrder("SELECT", "INSERT");
        }
    }

    // ------------------------------------------------------------------ apoio

    private UUID novoTenant() throws Exception {
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

    private int contarLancamentos(UUID tenant) throws SQLException {
        try (Connection dono = conexaoDono();
             var ps = dono.prepareStatement(
                     "SELECT count(*) FROM credito_lancamento WHERE tenant_id = ?")) {
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
