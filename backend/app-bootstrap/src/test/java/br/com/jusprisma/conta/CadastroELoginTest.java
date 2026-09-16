package br.com.jusprisma.conta;

import br.com.jusprisma.JusPrismaApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercita cadastro e login pela borda HTTP, contra um Postgres real com RLS ligado.
 *
 * <p>É o teste que prova a fatia inteira: requisição, validação, caso de uso, escopo de
 * tenant, policies do banco e emissão de token. Os testes de unidade a montante cobrem as
 * peças; este cobre a costura, que é onde os erros de ordem aparecem — foi exatamente uma
 * inversão entre abrir transação e publicar o tenant que quase passou despercebida aqui.
 *
 * <p>Fixtures explicitamente fictícias: domínio {@code .invalido} não resolve.
 */
@SpringBootTest(classes = JusPrismaApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class CadastroELoginTest {

    private static final String SENHA_DONO = "owner_teste";
    private static final String SENHA_APP = "app_teste";
    private static final String SENHA_VALIDA = "senha-de-teste-comprida";

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
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("cadastra uma conta e autentica com ela")
    void cadastraEAutentica() throws Exception {
        String email = emailUnico();

        MvcResult cadastro = mockMvc.perform(post("/api/v1/contas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDeCadastro("Escritorio Ficticio Alfa", email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usuarioId").exists())
                .andExpect(jsonPath("$.tenantId").exists())
                .andExpect(jsonPath("$.email").value(email))
                .andReturn();

        JsonNode contaCriada = json.readTree(cadastro.getResponse().getContentAsString());
        String tenantEsperado = contaCriada.get("tenantId").asString();

        MvcResult login = mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "senha": "%s"}
                                """.formatted(email, SENHA_VALIDA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenDeAcesso").exists())
                .andExpect(jsonPath("$.tipo").value("Bearer"))
                .andExpect(jsonPath("$.emailVerificado").value(false))
                .andReturn();

        String token = json.readTree(login.getResponse().getContentAsString())
                .get("tokenDeAcesso").asString();

        assertThat(tenantDoToken(token))
                .as("o token carrega o tenant, que é o que decide o que a requisição enxerga")
                .isEqualTo(tenantEsperado);
    }

    @Test
    @DisplayName("o e-mail é normalizado: cadastrar com maiúsculas e entrar com minúsculas")
    void emailNormalizado() throws Exception {
        String email = emailUnico();

        mockMvc.perform(post("/api/v1/contas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDeCadastro("Escritorio Ficticio Beta",
                                email.toUpperCase(java.util.Locale.ROOT))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "senha": "%s"}
                                """.formatted(email, SENHA_VALIDA)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("o mesmo e-mail não cria duas contas, nem variando a caixa")
    void emailDuplicadoEhRecusado() throws Exception {
        String email = emailUnico();

        mockMvc.perform(post("/api/v1/contas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDeCadastro("Escritorio Ficticio Gama", email)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/contas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDeCadastro("Outro Escritorio Ficticio",
                                email.toUpperCase(java.util.Locale.ROOT))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("senha errada e e-mail inexistente respondem exatamente a mesma coisa")
    void respostasIndistinguiveis() throws Exception {
        String email = emailUnico();
        mockMvc.perform(post("/api/v1/contas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDeCadastro("Escritorio Ficticio Delta", email)))
                .andExpect(status().isCreated());

        String comSenhaErrada = mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "senha": "senha-errada-mas-comprida"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String comEmailInexistente = mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "ninguem@exemplo.invalido", "senha": "senha-errada-mas-comprida"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // O correlacaoId e removido da comparacao porque muda a cada requisicao, e nao em
        // funcao da conta: ele nao diz nada sobre o e-mail existir ou nao. Todo o resto do
        // corpo tem que ser identico — e e' o resto que entregaria quais e-mails tem conta.
        assertThat(semCorrelacao(comEmailInexistente))
                .as("respostas diferentes entregariam quais e-mails têm conta")
                .isEqualTo(semCorrelacao(comSenhaErrada));
    }

    @Test
    @DisplayName("senha curta é recusada antes de criar qualquer coisa")
    void senhaCurtaEhRecusada() throws Exception {
        mockMvc.perform(post("/api/v1/contas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nomeDoEscritorio": "Escritorio Ficticio Epsilon",
                                 "email": "%s", "senha": "curta"}
                                """.formatted(emailUnico())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("rota protegida sem token responde 401")
    void rotaProtegidaExigeToken() throws Exception {
        mockMvc.perform(get("/api/v1/qualquer-coisa-protegida"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ apoio

    /** Remove o identificador de correlacao, que e' por requisicao e nao por conta. */
    private String semCorrelacao(String corpo) {
        var no = (tools.jackson.databind.node.ObjectNode) json.readTree(corpo);
        no.remove("correlacaoId");
        return no.toString();
    }

    private static String emailUnico() {
        return "socio-" + UUID.randomUUID() + "@exemplo.invalido";
    }

    private static String corpoDeCadastro(String escritorio, String email) {
        return """
                {"nomeDoEscritorio": "%s", "email": "%s", "senha": "%s", "oab": "12345", "ufOab": "DF"}
                """.formatted(escritorio, email, SENHA_VALIDA);
    }

    private String tenantDoToken(String token) throws Exception {
        String cargaUtil = token.split("\\.")[1];
        byte[] decodificada = Base64.getUrlDecoder().decode(cargaUtil);
        return json.readTree(decodificada).get("tenant").asString();
    }
}
