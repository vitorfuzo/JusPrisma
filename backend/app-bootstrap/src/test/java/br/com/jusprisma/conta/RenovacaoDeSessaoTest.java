package br.com.jusprisma.conta;

import br.com.jusprisma.JusPrismaApplication;
import br.com.jusprisma.web.seguranca.CookieDeRenovacao;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cobre a rotação de token de renovação e a detecção de reuso.
 *
 * <p>A detecção de reuso é a razão de a rotação existir. Rotacionar sem detectar apenas
 * troca strings; o que protege o usuário é a família inteira cair quando um token já
 * consumido reaparece.
 */
@SpringBootTest(classes = JusPrismaApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class RenovacaoDeSessaoTest {

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
        registro.add("jusprisma.jwt.segredo",
                () -> Base64.getEncoder().encodeToString(new byte[64]));
        registro.add("jusprisma.cookie.seguro", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("renovar devolve acesso novo e troca o token de renovação")
    void renovacaoRotacionaOToken() throws Exception {
        String primeiro = login(criarConta());

        MvcResult renovacao = mockMvc.perform(post("/api/v1/sessoes/renovacao")
                        .cookie(new Cookie(CookieDeRenovacao.NOME, primeiro)))
                .andExpect(status().isOk())
                .andReturn();

        String segundo = cookieDaResposta(renovacao);

        assertThat(segundo)
                .as("cada renovação emite um token novo; repetir o mesmo anularia a rotação")
                .isNotNull()
                .isNotEqualTo(primeiro);

        assertThat(json.readTree(renovacao.getResponse().getContentAsString())
                .get("tokenDeAcesso").asString())
                .as("a renovação também entrega um token de acesso novo")
                .isNotBlank();
    }

    @Test
    @DisplayName("o token anterior deixa de funcionar depois da rotação")
    void tokenAnteriorNaoServeMais() throws Exception {
        String primeiro = login(criarConta());

        mockMvc.perform(post("/api/v1/sessoes/renovacao")
                        .cookie(new Cookie(CookieDeRenovacao.NOME, primeiro)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/sessoes/renovacao")
                        .cookie(new Cookie(CookieDeRenovacao.NOME, primeiro)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("reusar um token consumido derruba a família inteira")
    void reusoDerrubaAFamilia() throws Exception {
        String primeiro = login(criarConta());

        MvcResult primeiraRenovacao = mockMvc.perform(post("/api/v1/sessoes/renovacao")
                        .cookie(new Cookie(CookieDeRenovacao.NOME, primeiro)))
                .andExpect(status().isOk())
                .andReturn();
        String segundo = cookieDaResposta(primeiraRenovacao);

        // O ladrão usa o token antigo, que ele copiou antes da rotação.
        mockMvc.perform(post("/api/v1/sessoes/renovacao")
                        .cookie(new Cookie(CookieDeRenovacao.NOME, primeiro)))
                .andExpect(status().isUnauthorized());

        // E o token do usuário legítimo, que era válido, cai junto. É o ponto do desenho:
        // como não dá para saber quem é quem, ninguém continua — o legítimo refaz o login.
        mockMvc.perform(post("/api/v1/sessoes/renovacao")
                        .cookie(new Cookie(CookieDeRenovacao.NOME, segundo)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout revoga a família e o token de renovação para de valer")
    void logoutRevogaAFamilia() throws Exception {
        String token = login(criarConta());

        mockMvc.perform(delete("/api/v1/sessoes")
                        .cookie(new Cookie(CookieDeRenovacao.NOME, token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/sessoes/renovacao")
                        .cookie(new Cookie(CookieDeRenovacao.NOME, token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout sem cookie ainda responde 204")
    void logoutSemCookieEhIdempotente() throws Exception {
        mockMvc.perform(delete("/api/v1/sessoes"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("um token de acesso não serve como token de renovação")
    void tokenDeAcessoNaoRenova() throws Exception {
        MvcResult sessao = mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "senha": "%s"}
                                """.formatted(criarConta(), SENHA_VALIDA)))
                .andExpect(status().isOk())
                .andReturn();

        String acesso = json.readTree(sessao.getResponse().getContentAsString())
                .get("tokenDeAcesso").asString();

        // Os dois são assinados com a mesma chave. Sem a checagem de tipo, este teste
        // passaria a renovar sessão com um token de acesso — confusão de credenciais.
        mockMvc.perform(post("/api/v1/sessoes/renovacao")
                        .cookie(new Cookie(CookieDeRenovacao.NOME, acesso)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("o cookie de renovação é HttpOnly, SameSite=Strict e restrito às rotas de sessão")
    void cookieTemAsProtecoes() throws Exception {
        MvcResult sessao = mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "senha": "%s"}
                                """.formatted(criarConta(), SENHA_VALIDA)))
                .andExpect(status().isOk())
                .andReturn();

        String cabecalho = sessao.getResponse().getHeader(HttpHeaders.SET_COOKIE);

        assertThat(cabecalho)
                .as("sem HttpOnly, um script injetado lê o token de 30 dias")
                .contains("HttpOnly")
                .as("SameSite=Strict é o que substitui o token de CSRF nesta rota")
                .contains("SameSite=Strict")
                .as("Path restrito impede que o cookie seja enviado ao resto da API")
                .contains("Path=/api/v1/sessoes");
    }

    // ------------------------------------------------------------------ apoio

    private String criarConta() throws Exception {
        String email = "socio-" + UUID.randomUUID() + "@exemplo.invalido";
        mockMvc.perform(post("/api/v1/contas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nomeDoEscritorio": "Escritorio Ficticio de Teste",
                                 "email": "%s", "senha": "%s"}
                                """.formatted(email, SENHA_VALIDA)))
                .andExpect(status().isCreated());
        return email;
    }

    private String login(String email) throws Exception {
        MvcResult sessao = mockMvc.perform(post("/api/v1/sessoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "senha": "%s"}
                                """.formatted(email, SENHA_VALIDA)))
                .andExpect(status().isOk())
                .andReturn();
        return cookieDaResposta(sessao);
    }

    private static String cookieDaResposta(MvcResult resultado) {
        Cookie cookie = resultado.getResponse().getCookie(CookieDeRenovacao.NOME);
        return cookie == null ? null : cookie.getValue();
    }
}
