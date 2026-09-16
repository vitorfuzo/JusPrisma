package br.com.jusprisma.credito;

import br.com.jusprisma.JusPrismaApplication;
import br.com.jusprisma.aplicacao.credito.Creditos;
import br.com.jusprisma.aplicacao.credito.RecargaDeCreditos;
import br.com.jusprisma.conta.CaixaDeSaidaDeTeste;
import br.com.jusprisma.dominio.credito.TipoCredito;
import br.com.jusprisma.plano.PlanoDeTeste;
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
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A ponte entre a cota declarada no plano e o saldo movimentável do ledger.
 *
 * <p>Este teste existe por causa de um furo que só apareceu ao exercitar o sistema de
 * ponta a ponta: a conta era criada, a assinatura era registrada, o plano dizia "2 perfis"
 * — e o saldo vinha zero. Havia dois mecanismos de cota que não conversavam entre si.
 */
@SpringBootTest(classes = {JusPrismaApplication.class, CaixaDeSaidaDeTeste.Configuracao.class})
@AutoConfigureMockMvc
@Testcontainers
class RecargaDoPlanoTest {

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
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private Creditos creditos;

    @Autowired
    private RecargaDeCreditos recarga;

    @Test
    @DisplayName("a conta nova já nasce com os créditos da degustação")
    void contaNovaNasceComCreditos() throws Exception {
        UUID tenant = criarConta();

        // Valores do seed da Degustação em V6. Se o plano mudar, este teste muda junto —
        // é o ponto: ele afirma que o saldo espelha o plano, não um número escolhido aqui.
        assertThat(creditos.saldo(tenant, TipoCredito.PERFIL)).isEqualTo(2);
        assertThat(creditos.saldo(tenant, TipoCredito.IA)).isEqualTo(30);
        assertThat(creditos.saldo(tenant, TipoCredito.CALCULO)).isEqualTo(2);
        assertThat(creditos.saldo(tenant, TipoCredito.ASSINATURA)).isEqualTo(2);
    }

    @Test
    @DisplayName("cota zerada no plano não vira crédito")
    void cotaZeradaNaoGeraLancamento() throws Exception {
        UUID tenant = criarConta();

        // Degustação tem CONSULTAS: 0. Creditar zero poluiria o extrato com uma linha que
        // não faz nada, e o domínio recusa lançamento de valor zero justamente por isso.
        assertThat(creditos.saldo(tenant, TipoCredito.CONSULTA)).isZero();
    }

    @Test
    @DisplayName("o saldo reflete o plano vigente, não um número fixo no código")
    void saldoEspelhaOPlano() throws Exception {
        UUID tenant = criarConta();
        promover(tenant, "PRO");

        recarga.abastecer(tenant, "troca para Pro");

        // 2 da degustação + 50 do Pro. A soma é o comportamento correto: recarregar não
        // apaga o que sobrou, que é o que permite o rollover prometido nos planos pagos.
        assertThat(creditos.saldo(tenant, TipoCredito.PERFIL)).isEqualTo(52);
    }

    @Test
    @DisplayName("tipo ilimitado não recebe crédito, mas debita sem exigir saldo")
    void ilimitadoDebitaSemSaldo() throws Exception {
        UUID tenant = criarConta();
        promover(tenant, "ESCRITORIO");

        recarga.abastecer(tenant, "troca para Escritório");

        // CALCULOS é ilimitado no Escritório: não há número que represente infinito num
        // saldo, então nada é creditado.
        assertThat(creditos.saldo(tenant, TipoCredito.CALCULO))
                .as("só o que veio da degustação; o ilimitado não credita")
                .isEqualTo(2);

        // E o débito passa mesmo depois de zerar o saldo — o lançamento é registrado para
        // auditoria, que é como se descobre que "ilimitado" virou prejuízo.
        creditos.debitar(tenant, TipoCredito.CALCULO, 2, "cálculo", null);
        assertThat(creditos.saldo(tenant, TipoCredito.CALCULO)).isZero();

        assertThatCode(() -> creditos.debitar(tenant, TipoCredito.CALCULO, 1, "cálculo", null))
                .as("plano ilimitado não pode ser barrado por saldo")
                .doesNotThrowAnyException();

        assertThat(creditos.saldo(tenant, TipoCredito.CALCULO))
                .as("o consumo fica registrado, ainda que negativo")
                .isEqualTo(-1);
    }

    // ------------------------------------------------------------------ apoio

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

    private void promover(UUID tenant, String plano) throws SQLException {
        try (Connection dono = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), SENHA_DONO)) {
            PlanoDeTeste.promover(dono, tenant, plano);
        }
    }
}
