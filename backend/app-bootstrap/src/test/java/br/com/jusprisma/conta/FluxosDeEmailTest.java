package br.com.jusprisma.conta;

import br.com.jusprisma.JusPrismaApplication;
import br.com.jusprisma.web.seguranca.CookieDeRenovacao;
import jakarta.servlet.http.Cookie;
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

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verificação de e-mail e recuperação de senha, ponta a ponta.
 *
 * <p>O envio é substituído por um duplo que <em>captura o token real</em>, e não por um
 * token inventado pelo teste. Assim o teste exercita a geração, o hash e a conferência de
 * verdade: se o valor gravado deixasse de corresponder ao valor enviado, o teste quebraria —
 * que é o bug clássico desses fluxos, e o que faz o usuário ver "link inválido" num link
 * legítimo.
 */
@SpringBootTest(classes = {JusPrismaApplication.class, CaixaDeSaidaDeTeste.Configuracao.class})
@AutoConfigureMockMvc
@Testcontainers
class FluxosDeEmailTest {

    private static final String SENHA_DONO = "owner_teste";
    private static final String SENHA_APP = "app_teste";
    private static final String SENHA_ORIGINAL = "senha-de-teste-comprida";
    private static final String SENHA_NOVA = "outra-senha-bem-comprida";

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

    @Autowired
    private CaixaDeSaidaDeTeste caixa;

    @BeforeEach
    void limparCaixa() {
        caixa.limpar();
    }

    // ------------------------------------------------------ verificação de e-mail

    @Test
    @DisplayName("o cadastro dispara o e-mail de verificação e o link confirma a conta")
    void cadastroEnviaEConfirma() throws Exception {
        String email = criarConta();

        assertThat(caixa.ultimo("VERIFICACAO").destinatario()).isEqualTo(email);
        assertThat(entrar(email, SENHA_ORIGINAL).get("emailVerificado").asBoolean())
                .as("recém-cadastrado ainda não verificou")
                .isFalse();

        confirmar(caixa.ultimo("VERIFICACAO").segredo()).andExpect(status().isNoContent());

        assertThat(entrar(email, SENHA_ORIGINAL).get("emailVerificado").asBoolean())
                .as("depois do link, verificado")
                .isTrue();
    }

    @Test
    @DisplayName("o link de verificação não vale duas vezes")
    void linkDeVerificacaoEhDeUsoUnico() throws Exception {
        criarConta();
        String token = caixa.ultimo("VERIFICACAO").segredo();

        confirmar(token).andExpect(status().isNoContent());
        confirmar(token).andExpect(status().isGone());
    }

    @Test
    @DisplayName("um link de recuperação não serve para verificar e-mail")
    void finalidadeDoTokenEhConferida() throws Exception {
        String email = criarConta();
        caixa.limpar();

        solicitarRecuperacao(email);
        String tokenDeSenha = caixa.ultimo("RECUPERACAO").segredo();

        // Os dois tipos vivem na mesma tabela; sem conferir a finalidade, este link
        // verificaria o e-mail sem que ninguém tivesse provado ter acesso à caixa.
        confirmar(tokenDeSenha).andExpect(status().isGone());
    }

    // ------------------------------------------------------ recuperação de senha

    @Test
    @DisplayName("e-mail com conta e sem conta produzem a mesma resposta")
    void recuperacaoNaoRevelaQuemTemConta() throws Exception {
        String comConta = criarConta();
        caixa.limpar();

        MvcResult existente = solicitarRecuperacao(comConta);
        MvcResult inexistente = solicitarRecuperacao("ninguem-" + UUID.randomUUID() + "@exemplo.invalido");

        assertThat(inexistente.getResponse().getStatus())
                .isEqualTo(existente.getResponse().getStatus());
        assertThat(inexistente.getResponse().getContentAsString())
                .as("corpos diferentes transformariam o formulário num verificador de cadastro")
                .isEqualTo(existente.getResponse().getContentAsString());

        assertThat(caixa.todos())
                .as("só quem tem conta recebe e-mail — a diferença fica no servidor, não na resposta")
                .hasSize(1);
    }

    @Test
    @DisplayName("redefinir troca a senha: a nova entra, a antiga não")
    void redefinicaoTrocaASenha() throws Exception {
        String email = criarConta();
        caixa.limpar();
        solicitarRecuperacao(email);

        redefinir(caixa.ultimo("RECUPERACAO").segredo(), SENHA_NOVA)
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDeLogin(email, SENHA_NOVA)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDeLogin(email, SENHA_ORIGINAL)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("redefinir a senha derruba as sessões já abertas")
    void redefinicaoDerrubaSessoes() throws Exception {
        String email = criarConta();

        MvcResult sessao = mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDeLogin(email, SENHA_ORIGINAL)))
                .andExpect(status().isOk())
                .andReturn();
        String renovacao = sessao.getResponse().getCookie(CookieDeRenovacao.NOME).getValue();

        caixa.limpar();
        solicitarRecuperacao(email);
        redefinir(caixa.ultimo("RECUPERACAO").segredo(), SENHA_NOVA)
                .andExpect(status().isNoContent());

        // Quem troca a senha costuma fazê-lo por suspeitar de acesso indevido. Se a sessão
        // de renovação sobrevivesse, o invasor seguiria dentro por mais trinta dias.
        mockMvc.perform(post("/api/v1/sessoes/renovacao")
                        .cookie(new Cookie(CookieDeRenovacao.NOME, renovacao)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("pedir o link de novo invalida o anterior")
    void segundoPedidoInvalidaOPrimeiro() throws Exception {
        String email = criarConta();
        caixa.limpar();

        solicitarRecuperacao(email);
        String primeiro = caixa.ultimo("RECUPERACAO").segredo();

        solicitarRecuperacao(email);
        String segundo = caixa.ultimo("RECUPERACAO").segredo();

        assertThat(segundo).isNotEqualTo(primeiro);
        redefinir(primeiro, SENHA_NOVA).andExpect(status().isGone());
        redefinir(segundo, SENHA_NOVA).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("senha nova curta é recusada e o link continua valendo")
    void senhaCurtaNaoConsomeOLink() throws Exception {
        String email = criarConta();
        caixa.limpar();
        solicitarRecuperacao(email);
        String token = caixa.ultimo("RECUPERACAO").segredo();

        redefinir(token, "curta").andExpect(status().isBadRequest());
        redefinir(token, SENHA_NOVA).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("token inventado é recusado")
    void tokenInventadoNaoFunciona() throws Exception {
        redefinir("token-que-nunca-foi-emitido", SENHA_NOVA).andExpect(status().isGone());
    }

    // ------------------------------------------------------------------ apoio

    private String criarConta() throws Exception {
        String email = "socio-" + UUID.randomUUID() + "@exemplo.invalido";
        mockMvc.perform(post("/api/v1/contas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nomeDoEscritorio": "Escritorio Ficticio de Teste",
                                 "email": "%s", "senha": "%s"}
                                """.formatted(email, SENHA_ORIGINAL)))
                .andExpect(status().isCreated());
        return email;
    }

    private tools.jackson.databind.JsonNode entrar(String email, String senha) throws Exception {
        MvcResult resultado = mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDeLogin(email, senha)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(resultado.getResponse().getContentAsString());
    }

    private static String corpoDeLogin(String email, String senha) {
        return """
                {"email": "%s", "senha": "%s"}
                """.formatted(email, senha);
    }

    private org.springframework.test.web.servlet.ResultActions confirmar(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/contas/verificacao")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token": "%s"}
                        """.formatted(token)));
    }

    private MvcResult solicitarRecuperacao(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/senha/recuperacao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s"}
                                """.formatted(email)))
                .andExpect(status().isAccepted())
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions redefinir(String token, String senha)
            throws Exception {
        return mockMvc.perform(post("/api/v1/senha/redefinicao")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token": "%s", "senha": "%s"}
                        """.formatted(token, senha)));
    }
}
