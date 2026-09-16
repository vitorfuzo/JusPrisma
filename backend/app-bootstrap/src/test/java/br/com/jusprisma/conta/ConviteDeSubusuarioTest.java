package br.com.jusprisma.conta;

import br.com.jusprisma.JusPrismaApplication;
import br.com.jusprisma.plano.PlanoDeTeste;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Convite de subusuário e aplicação de papéis.
 *
 * <p>O caso central é o mais fácil de errar sem perceber: o convidado tem que cair
 * <em>dentro do escritório que o convidou</em>. Se o aceite criasse um tenant novo, tudo
 * continuaria funcionando — login, token, rotas — e o subusuário simplesmente não enxergaria
 * nada do escritório, o que aparece como "sumiram meus processos" muito longe da causa.
 */
@SpringBootTest(classes = {JusPrismaApplication.class, CaixaDeSaidaDeTeste.Configuracao.class})
@AutoConfigureMockMvc
@Testcontainers
class ConviteDeSubusuarioTest {

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
    private CaixaDeSaidaDeTeste caixa;

    @BeforeEach
    void limparCaixa() {
        caixa.limpar();
    }

    @Test
    @DisplayName("o convidado entra no escritório de quem convidou, não num novo")
    void convidadoEntraNoMesmoEscritorio() throws Exception {
        Conta dono = criarConta();
        String convidado = emailNovo();

        convidar(dono.acesso(), convidado, "MEMBRO").andExpect(status().isCreated());

        MvcResult aceite = aceitar(segredoDoConvite()).andExpect(status().isCreated()).andReturn();
        var corpo = json.readTree(aceite.getResponse().getContentAsString());

        assertThat(corpo.get("tenantId").asString())
                .as("o subusuário pertence ao escritório que o convidou")
                .isEqualTo(dono.tenantId());
        assertThat(corpo.get("papel").asString()).isEqualTo("MEMBRO");

        // E o token emitido no login dele carrega o mesmo tenant — que é o que decide o
        // que ele enxerga no banco.
        assertThat(tenantDoToken(entrar(convidado))).isEqualTo(dono.tenantId());
    }

    @Test
    @DisplayName("quem aceita convite já entra com e-mail verificado")
    void convidadoNaoPrecisaVerificarEmailDeNovo() throws Exception {
        Conta dono = criarConta();
        convidar(dono.acesso(), emailNovo(), "MEMBRO").andExpect(status().isCreated());

        String convidado = caixa.ultimo(CaixaDeSaidaDeTeste.CONVITE).destinatario();
        aceitar(segredoDoConvite()).andExpect(status().isCreated());

        // Clicar no link do convite já provou acesso à caixa; pedir outro link seria ritual.
        mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDeLogin(convidado)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerificado").value(true));
    }

    @Test
    @DisplayName("membro não convida, não lista e não revoga")
    void membroNaoAdministraAConta() throws Exception {
        Conta dono = criarConta();
        convidar(dono.acesso(), emailNovo(), "MEMBRO").andExpect(status().isCreated());
        String convidado = caixa.ultimo(CaixaDeSaidaDeTeste.CONVITE).destinatario();
        aceitar(segredoDoConvite()).andExpect(status().isCreated());

        String acessoDoMembro = entrar(convidado);

        convidar(acessoDoMembro, emailNovo(), "MEMBRO").andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/convites").header("Authorization", "Bearer " + acessoDoMembro))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/convites/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + acessoDoMembro))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a listagem de convites não atravessa escritórios")
    void listagemNaoVazaEntreEscritorios() throws Exception {
        Conta alfa = criarConta();
        Conta beta = criarConta();

        convidar(alfa.acesso(), emailNovo(), "MEMBRO").andExpect(status().isCreated());
        String convidadoDeAlfa = caixa.ultimo(CaixaDeSaidaDeTeste.CONVITE).destinatario();

        mockMvc.perform(get("/api/v1/convites").header("Authorization", "Bearer " + alfa.acesso()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value(convidadoDeAlfa));

        mockMvc.perform(get("/api/v1/convites").header("Authorization", "Bearer " + beta.acesso()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("antes de aceitar, o convidado vê de qual escritório se trata")
    void convidadoVeOEscritorioAntesDeAceitar() throws Exception {
        Conta dono = criarConta();
        convidar(dono.acesso(), emailNovo(), "MEMBRO").andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/convites/pendente").param("token", segredoDoConvite()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nomeDoEscritorio").value(dono.nome()))
                .andExpect(jsonPath("$.papel").value("MEMBRO"));
    }

    @Test
    @DisplayName("o convite não vale duas vezes")
    void conviteEhDeUsoUnico() throws Exception {
        Conta dono = criarConta();
        convidar(dono.acesso(), emailNovo(), "MEMBRO").andExpect(status().isCreated());
        String segredo = segredoDoConvite();

        aceitar(segredo).andExpect(status().isCreated());
        aceitar(segredo).andExpect(status().isGone());
    }

    @Test
    @DisplayName("convite revogado deixa de funcionar")
    void conviteRevogadoNaoServe() throws Exception {
        Conta dono = criarConta();
        MvcResult criado = convidar(dono.acesso(), emailNovo(), "MEMBRO")
                .andExpect(status().isCreated()).andReturn();
        String conviteId = json.readTree(criado.getResponse().getContentAsString())
                .get("id").asString();
        String segredo = segredoDoConvite();

        mockMvc.perform(delete("/api/v1/convites/" + conviteId)
                        .header("Authorization", "Bearer " + dono.acesso()))
                .andExpect(status().isNoContent());

        aceitar(segredo).andExpect(status().isGone());
    }

    @Test
    @DisplayName("convidar de novo invalida o convite anterior")
    void segundoConviteInvalidaOPrimeiro() throws Exception {
        Conta dono = criarConta();
        String convidado = emailNovo();

        convidar(dono.acesso(), convidado, "MEMBRO").andExpect(status().isCreated());
        String primeiro = segredoDoConvite();

        convidar(dono.acesso(), convidado, "MEMBRO").andExpect(status().isCreated());
        String segundo = segredoDoConvite();

        assertThat(segundo).isNotEqualTo(primeiro);
        // Dois links vivos para o mesmo e-mail seriam duas portas para o mesmo lugar.
        aceitar(primeiro).andExpect(status().isGone());
        aceitar(segundo).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("não se convida quem já tem conta")
    void naoConvidaQuemJaTemConta() throws Exception {
        Conta dono = criarConta();
        Conta outro = criarConta();

        // O e-mail é único no sistema inteiro; o aceite falharia de qualquer forma.
        // Recusar agora evita mandar um link que só quebra quando a pessoa clicar.
        convidar(dono.acesso(), outro.email(), "MEMBRO").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("convite inventado é recusado")
    void conviteInventadoNaoFunciona() throws Exception {
        aceitar("convite-que-nunca-foi-emitido").andExpect(status().isGone());
    }

    // ------------------------------------------------------------------ apoio

    private record Conta(String email, String nome, String tenantId, String acesso) {
    }

    private static String emailNovo() {
        return "pessoa-" + UUID.randomUUID() + "@exemplo.invalido";
    }

    private Conta criarConta() throws Exception {
        String email = emailNovo();
        String nome = "Escritorio Ficticio " + UUID.randomUUID().toString().substring(0, 8);

        MvcResult criada = mockMvc.perform(post("/api/v1/contas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nomeDoEscritorio": "%s", "email": "%s", "senha": "%s"}
                                """.formatted(nome, email, SENHA)))
                .andExpect(status().isCreated())
                .andReturn();

        String tenantId = json.readTree(criada.getResponse().getContentAsString())
                .get("tenantId").asString();

        // Toda conta nasce na Degustacao, que nao tem vaga de subusuario. Promover para Pro
        // e o que este teste precisa exercitar; a cota em si tem teste proprio em
        // CotaDeSubusuarioTest.
        try (Connection dono = conexaoDono()) {
            PlanoDeTeste.promover(dono, UUID.fromString(tenantId), "PRO");
        }

        return new Conta(email, nome, tenantId, entrar(email));
    }

    private static Connection conexaoDono() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), SENHA_DONO);
    }

    private String entrar(String email) throws Exception {
        MvcResult sessao = mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDeLogin(email)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(sessao.getResponse().getContentAsString())
                .get("tokenDeAcesso").asString();
    }

    private static String corpoDeLogin(String email) {
        return """
                {"email": "%s", "senha": "%s"}
                """.formatted(email, SENHA);
    }

    private ResultActions convidar(String acesso, String email, String papel) throws Exception {
        return mockMvc.perform(post("/api/v1/convites")
                .header("Authorization", "Bearer " + acesso)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "papel": "%s"}
                        """.formatted(email, papel)));
    }

    private ResultActions aceitar(String segredo) throws Exception {
        return mockMvc.perform(post("/api/v1/convites/aceite")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token": "%s", "senha": "%s"}
                        """.formatted(segredo, SENHA)));
    }

    private String segredoDoConvite() {
        return caixa.ultimo(CaixaDeSaidaDeTeste.CONVITE).segredo();
    }

    private String tenantDoToken(String token) {
        byte[] carga = Base64.getUrlDecoder().decode(token.split("\\.")[1]);
        return json.readTree(carga).get("tenant").asString();
    }
}
