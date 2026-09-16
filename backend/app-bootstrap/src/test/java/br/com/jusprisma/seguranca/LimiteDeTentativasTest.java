package br.com.jusprisma.seguranca;

import br.com.jusprisma.JusPrismaApplication;
import br.com.jusprisma.conta.CaixaDeSaidaDeTeste;
import br.com.jusprisma.web.observabilidade.CorrelacaoDeRequisicao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proteção contra abuso nas rotas de autenticação, e o rastro de correlação.
 *
 * <p>É o único teste com o limitador ligado. Os demais o desligam porque criam dezenas de
 * contas da mesma origem — comportamento que, num usuário real, seria justamente o abuso
 * que estamos barrando.
 */
@SpringBootTest(classes = {JusPrismaApplication.class, CaixaDeSaidaDeTeste.Configuracao.class})
@AutoConfigureMockMvc
@Testcontainers
class LimiteDeTentativasTest {

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
        registro.add("jusprisma.limite-de-tentativas.habilitado", () -> true);
        registro.add("jusprisma.jwt.segredo",
                () -> Base64.getEncoder().encodeToString(new byte[64]));
        registro.add("jusprisma.cookie.seguro", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    /**
     * Cada teste sai de um IP proprio.
     *
     * <p>Sem isso, o limite por IP dispara antes do limite por e-mail e os testes medem a
     * politica errada — foi o que aconteceu na primeira versao. O JUnit cria uma instancia
     * por metodo, entao este campo ja da um endereco distinto a cada teste.
     */
    private final String ipDoTeste = "10.%d.%d.%d".formatted(
            ThreadLocalRandom.current().nextInt(1, 255),
            ThreadLocalRandom.current().nextInt(1, 255),
            ThreadLocalRandom.current().nextInt(1, 255));

    private RequestPostProcessor origem() {
        return requisicao -> {
            requisicao.setRemoteAddr(ipDoTeste);
            return requisicao;
        };
    }

    // ------------------------------------------------------------ força bruta

    @Test
    @DisplayName("errar a senha muitas vezes passa a responder 429, com Retry-After")
    void forcaBrutaPorEmailEhBarrada() throws Exception {
        String email = criarConta();

        // A política do e-mail tolera 8 na janela. As primeiras erram a senha e recebem 401.
        for (int i = 0; i < 8; i++) {
            entrar(email, "senha-errada-porem-longa").andExpect(status().isUnauthorized());
        }

        MvcResult barrado = entrar(email, "senha-errada-porem-longa")
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertThat(barrado.getResponse().getHeader(HttpHeaders.RETRY_AFTER))
                .as("dizer quando voltar é melhor do que deixar o cliente martelando a rota")
                .isNotNull();
    }

    @Test
    @DisplayName("a senha certa continua barrada depois do limite — não há bypass")
    void limiteValeMesmoComSenhaCerta() throws Exception {
        String email = criarConta();

        for (int i = 0; i < 8; i++) {
            entrar(email, "senha-errada-porem-longa").andExpect(status().isUnauthorized());
        }

        // O limite é conferido antes de olhar a senha. Se fosse depois, um atacante
        // continuaria testando à vontade e só o acerto seria bloqueado — inútil.
        entrar(email, SENHA).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("login bem-sucedido zera o contador de tentativas")
    void sucessoZeraOContador() throws Exception {
        String email = criarConta();

        // Sete erros, dentro da tolerância de oito.
        for (int i = 0; i < 7; i++) {
            entrar(email, "senha-errada-porem-longa").andExpect(status().isUnauthorized());
        }

        entrar(email, SENHA).andExpect(status().isOk());

        // Sem o zeramento, quem erra várias vezes e acerta ficaria com crédito queimado e
        // seria barrado no próximo login legítimo, minutos depois.
        for (int i = 0; i < 7; i++) {
            entrar(email, "senha-errada-porem-longa").andExpect(status().isUnauthorized());
        }
        entrar(email, SENHA).andExpect(status().isOk());
    }

    @Test
    @DisplayName("pedir recuperação de senha em série é barrado")
    void recuperacaoEmSerieEhBarrada() throws Exception {
        String email = criarConta();

        // Tolera 3 por hora: cada uma dispara um e-mail, e sem teto o formulário público
        // serviria para inundar a caixa de outra pessoa.
        for (int i = 0; i < 3; i++) {
            recuperar(email).andExpect(status().isAccepted());
        }
        recuperar(email).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("o limite é por e-mail: barrar um não barra os outros")
    void limiteEhPorChaveNaoGlobal() throws Exception {
        String alvo = criarConta();
        String outro = criarConta();

        for (int i = 0; i < 3; i++) {
            recuperar(alvo).andExpect(status().isAccepted());
        }
        recuperar(alvo).andExpect(status().isTooManyRequests());

        // Um limitador global transformaria a proteção em negação de serviço: bastaria um
        // atacante martelar uma conta para travar a recuperação de senha de todo mundo.
        recuperar(outro).andExpect(status().isAccepted());
    }

    // --------------------------------------------------------------- correlação

    @Test
    @DisplayName("toda resposta carrega o identificador de correlação")
    void respostaTemCorrelacao() throws Exception {
        MvcResult resultado = mockMvc.perform(post("/api/v1/sessoes")
                        .with(origem())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDeLogin("ninguem@exemplo.invalido", SENHA)))
                .andReturn();

        assertThat(resultado.getResponse().getHeader(CorrelacaoDeRequisicao.CABECALHO))
                .isNotBlank();
    }

    @Test
    @DisplayName("o corpo de erro traz a correlação, para o usuário citar no suporte")
    void erroCarregaCorrelacao() throws Exception {
        MvcResult resultado = mockMvc.perform(post("/api/v1/sessoes")
                        .with(origem())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDeLogin("ninguem@exemplo.invalido", SENHA)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.correlacaoId").exists())
                .andReturn();

        assertThat(json.readTree(resultado.getResponse().getContentAsString())
                .get("correlacaoId").asString())
                .as("o mesmo valor do cabeçalho, para casar log e reclamação")
                .isEqualTo(resultado.getResponse().getHeader(CorrelacaoDeRequisicao.CABECALHO));
    }

    @Test
    @DisplayName("correlação enviada pelo cliente é reaproveitada, se tiver formato aceitável")
    void correlacaoDoClienteEhAceita() throws Exception {
        String meu = "rastro-de-teste-12345";

        MvcResult resultado = mockMvc.perform(post("/api/v1/sessoes")
                        .with(origem())
                        .header(CorrelacaoDeRequisicao.CABECALHO, meu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDeLogin("ninguem@exemplo.invalido", SENHA)))
                .andReturn();

        assertThat(resultado.getResponse().getHeader(CorrelacaoDeRequisicao.CABECALHO))
                .isEqualTo(meu);
    }

    @Test
    @DisplayName("correlação fora do formato é descartada e substituída")
    void correlacaoInvalidaEhDescartada() throws Exception {
        // Valor do cliente entra em log. Aceitá-lo cru abriria injeção de log e poluição de
        // índice; quebras de linha e payloads longos são o vetor clássico.
        String malicioso = "abc\ndef\rINFO: login bem-sucedido de administrador";

        MvcResult resultado = mockMvc.perform(post("/api/v1/sessoes")
                        .with(origem())
                        .header(CorrelacaoDeRequisicao.CABECALHO, malicioso)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoDeLogin("ninguem@exemplo.invalido", SENHA)))
                .andReturn();

        String devolvido = resultado.getResponse().getHeader(CorrelacaoDeRequisicao.CABECALHO);
        assertThat(devolvido).isNotEqualTo(malicioso);
        assertThat(devolvido).doesNotContain("\n", "\r");
    }

    // ------------------------------------------------------------------ apoio

    private String criarConta() throws Exception {
        String email = "pessoa-" + UUID.randomUUID() + "@exemplo.invalido";
        mockMvc.perform(post("/api/v1/contas")
                        .with(origem())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nomeDoEscritorio": "Escritorio Ficticio de Teste",
                                 "email": "%s", "senha": "%s"}
                                """.formatted(email, SENHA)))
                .andExpect(status().isCreated());
        return email;
    }

    private org.springframework.test.web.servlet.ResultActions entrar(String email, String senha)
            throws Exception {
        return mockMvc.perform(post("/api/v1/sessoes")
                .with(origem())
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoDeLogin(email, senha)));
    }

    private org.springframework.test.web.servlet.ResultActions recuperar(String email)
            throws Exception {
        return mockMvc.perform(post("/api/v1/senha/recuperacao")
                .with(origem())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s"}
                        """.formatted(email)));
    }

    private static String corpoDeLogin(String email, String senha) {
        return """
                {"email": "%s", "senha": "%s"}
                """.formatted(email, senha);
    }
}
