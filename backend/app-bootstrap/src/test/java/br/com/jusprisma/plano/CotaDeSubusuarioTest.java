package br.com.jusprisma.plano;

import br.com.jusprisma.JusPrismaApplication;
import br.com.jusprisma.conta.CaixaDeSaidaDeTeste;
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
import org.springframework.test.web.servlet.ResultActions;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A cota de subusuários, e a prova de que ela vem de dado e não de código.
 *
 * <p>É a regra do CLAUDE.md que diz que limite de plano mora em {@code Plano.limites}
 * (JSONB), nunca num {@code if}. O teste decisivo é o último: altera o JSONB do plano em
 * tempo de execução e verifica que o comportamento muda, sem recompilar nada. Se alguém
 * substituir isso por uma constante no código, aquele teste fica vermelho.
 */
@SpringBootTest(classes = {JusPrismaApplication.class, CaixaDeSaidaDeTeste.Configuracao.class})
@AutoConfigureMockMvc
@Testcontainers
class CotaDeSubusuarioTest {

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
        registro.add("jusprisma.jwt.segredo",
                () -> Base64.getEncoder().encodeToString(new byte[64]));
        registro.add("jusprisma.cookie.seguro", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("Degustação não tem vaga de subusuário")
    void degustacaoNaoConvida() throws Exception {
        Conta conta = criarConta();
        // Nasce em DEGUSTACAO, cuja cota SUBUSUARIOS é 0 no seed.
        convidar(conta, emailNovo()).andExpect(status().isPaymentRequired());
    }

    @Test
    @DisplayName("Solo também não: subusuário é recurso de Pro em diante")
    void soloNaoConvida() throws Exception {
        Conta conta = criarConta();
        promover(conta, "SOLO");
        convidar(conta, emailNovo()).andExpect(status().isPaymentRequired());
    }

    @Test
    @DisplayName("Pro permite três, e o quarto é recusado")
    void proPermiteTres() throws Exception {
        Conta conta = criarConta();
        promover(conta, "PRO");

        convidar(conta, emailNovo()).andExpect(status().isCreated());
        convidar(conta, emailNovo()).andExpect(status().isCreated());
        convidar(conta, emailNovo()).andExpect(status().isCreated());

        // O quarto estoura a cota de 3 do Pro — e nenhum dos três foi aceito ainda, o que
        // prova que convite pendente ocupa vaga.
        convidar(conta, emailNovo()).andExpect(status().isPaymentRequired());
    }

    @Test
    @DisplayName("revogar um convite devolve a vaga")
    void revogarLiberaVaga() throws Exception {
        Conta conta = criarConta();
        promover(conta, "PRO");

        String primeiro = idDoConvite(convidar(conta, emailNovo()));
        convidar(conta, emailNovo()).andExpect(status().isCreated());
        convidar(conta, emailNovo()).andExpect(status().isCreated());
        convidar(conta, emailNovo()).andExpect(status().isPaymentRequired());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/convites/" + primeiro)
                        .header("Authorization", "Bearer " + conta.acesso()))
                .andExpect(status().isNoContent());

        convidar(conta, emailNovo()).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("mudar o JSONB do plano muda a cota, sem tocar em código")
    void cotaVemDeDadoNaoDeCodigo() throws Exception {
        // Usa um plano descartável, criado só para este teste, em vez de alterar um plano
        // semeado. Mexer no seed contaminaria os outros testes, que compartilham o mesmo
        // banco — e um teste que depende da ordem de execução não prova nada.
        String codigo = "TESTE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        criarPlanoDescartavel(codigo, 0);

        Conta conta = criarConta();
        promover(conta, codigo);
        convidar(conta, emailNovo()).andExpect(status().isPaymentRequired());

        // Agora o produto decide que esse plano passa a ter uma vaga. Tem que ser um
        // UPDATE, não um deploy. Se a cota estivesse num if, esta alteração não surtiria
        // efeito nenhum e a próxima chamada continuaria recusando.
        try (Connection dono = conexaoDono(); Statement st = dono.createStatement()) {
            st.executeUpdate("""
                    UPDATE plano
                       SET limites = jsonb_set(limites, '{cotas,SUBUSUARIOS}', '1')
                     WHERE codigo = '%s'
                    """.formatted(codigo));
        }

        convidar(conta, emailNovo()).andExpect(status().isCreated());
        // E a cota nova é respeitada: uma vaga, não duas.
        convidar(conta, emailNovo()).andExpect(status().isPaymentRequired());
    }

    private void criarPlanoDescartavel(String codigo, int subusuarios) throws SQLException {
        try (Connection dono = conexaoDono(); Statement st = dono.createStatement()) {
            st.executeUpdate("""
                    INSERT INTO plano (codigo, nome, preco_centavos, limites)
                    VALUES ('%s', 'Plano de teste', 100,
                            '{"cotas": {"SUBUSUARIOS": %d}, "recursos": []}'::jsonb)
                    """.formatted(codigo, subusuarios));
        }
    }

    // ------------------------------------------------------------------ apoio

    private record Conta(UUID tenantId, String acesso) {
    }

    private static String emailNovo() {
        return "pessoa-" + UUID.randomUUID() + "@exemplo.invalido";
    }

    private Conta criarConta() throws Exception {
        String email = emailNovo();
        MvcResult criada = mockMvc.perform(post("/api/v1/contas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nomeDoEscritorio": "Escritorio Ficticio de Teste",
                                 "email": "%s", "senha": "%s"}
                                """.formatted(email, SENHA)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID tenantId = UUID.fromString(json.readTree(criada.getResponse().getContentAsString())
                .get("tenantId").asString());

        MvcResult sessao = mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "senha": "%s"}
                                """.formatted(email, SENHA)))
                .andExpect(status().isOk())
                .andReturn();

        return new Conta(tenantId, json.readTree(sessao.getResponse().getContentAsString())
                .get("tokenDeAcesso").asString());
    }

    private void promover(Conta conta, String plano) throws SQLException {
        try (Connection dono = conexaoDono()) {
            PlanoDeTeste.promover(dono, conta.tenantId(), plano);
        }
    }

    private ResultActions convidar(Conta conta, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/convites")
                .header("Authorization", "Bearer " + conta.acesso())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "papel": "MEMBRO"}
                        """.formatted(email)));
    }

    private String idDoConvite(ResultActions resultado) throws Exception {
        return json.readTree(resultado.andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("id").asString();
    }

    private static Connection conexaoDono() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), SENHA_DONO);
    }
}
