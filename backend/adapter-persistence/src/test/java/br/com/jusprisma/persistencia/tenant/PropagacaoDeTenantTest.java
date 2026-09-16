package br.com.jusprisma.persistencia.tenant;

import br.com.jusprisma.persistencia.AplicacaoDeTeste;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova que o isolamento provado em {@code IsolamentoPorTenantTest} sobrevive à travessia
 * pelo Spring: o tenant sai do contexto da requisição, entra na transação e chega ao
 * Postgres a tempo de as policies o enxergarem.
 *
 * <p>Sem este teste, o anterior mediria só o banco. É perfeitamente possível ter RLS
 * correto no schema e uma aplicação que nunca informa o tenant — e nesse caso tudo devolve
 * zero linhas, o que aparece como bug funcional e tenta o próximo desenvolvedor a
 * "resolver" desligando o RLS.
 */
@SpringBootTest(classes = AplicacaoDeTeste.class)
@Testcontainers
class PropagacaoDeTenantTest {

    private static final String SENHA_DONO = "owner_teste";
    private static final String SENHA_APP = "app_teste";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("jusprisma")
            .withUsername("jusprisma_owner")
            .withPassword(SENHA_DONO)
            .withInitScript("db/roles-teste.sql");

    private static UUID tenantAlfa;
    private static UUID tenantBeta;

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry registro) {
        // A aplicação conecta como jusprisma_app, que sofre RLS.
        registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registro.add("spring.datasource.username", () -> "jusprisma_app");
        registro.add("spring.datasource.password", () -> SENHA_APP);

        // O Flyway conecta como dono, que tem permissão de alterar o schema.
        registro.add("spring.flyway.user", POSTGRES::getUsername);
        registro.add("spring.flyway.password", () -> SENHA_DONO);
    }

    @BeforeAll
    static void semear() {
        // Semeadura acontece antes do contexto Spring subir, como dono, para montar o
        // cenário. O Flyway já terá rodado quando o contexto iniciar.
        POSTGRES.start();
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager gerenciadorDeTransacao;

    @Test
    @DisplayName("o tenant do contexto chega até as policies do Postgres")
    void tenantDoContextoChegaAoBanco() throws SQLException {
        prepararCenario();
        TransactionTemplate transacao = new TransactionTemplate(gerenciadorDeTransacao);

        List<String> deAlfa = ContextoDeTenant.executarCom(tenantAlfa,
                () -> transacao.execute(status ->
                        jdbc.queryForList("SELECT email FROM usuario", String.class)));

        List<String> deBeta = ContextoDeTenant.executarCom(tenantBeta,
                () -> transacao.execute(status ->
                        jdbc.queryForList("SELECT email FROM usuario", String.class)));

        assertThat(deAlfa).containsExactly("ana@alfa.exemplo.invalido");
        assertThat(deBeta).containsExactly("carla@beta.exemplo.invalido");
    }

    @Test
    @DisplayName("sem tenant no contexto a transação não enxerga nada")
    void semTenantNoContextoNaoEnxergaNada() throws SQLException {
        prepararCenario();
        TransactionTemplate transacao = new TransactionTemplate(gerenciadorDeTransacao);

        ContextoDeTenant.limpar();
        List<String> emails = transacao.execute(status ->
                jdbc.queryForList("SELECT email FROM usuario", String.class));

        assertThat(emails)
                .as("falha fechado: sem tenant, zero linhas — nunca a base inteira")
                .isEmpty();
    }

    @Test
    @DisplayName("o tenant não vaza da transação para a conexão devolvida ao pool")
    void tenantNaoVazaEntreTransacoes() throws SQLException {
        prepararCenario();
        TransactionTemplate transacao = new TransactionTemplate(gerenciadorDeTransacao);

        // Primeira transação define o tenant. Se o set_config fosse de sessão em vez de
        // local, o valor sobreviveria à devolução da conexão ao pool.
        ContextoDeTenant.executarCom(tenantAlfa,
                () -> transacao.execute(status ->
                        jdbc.queryForList("SELECT email FROM usuario", String.class)));

        ContextoDeTenant.limpar();
        List<String> depois = transacao.execute(status ->
                jdbc.queryForList("SELECT email FROM usuario", String.class));

        assertThat(depois)
                .as("o tenant da transação anterior não pode sobreviver nesta")
                .isEmpty();
    }

    // ------------------------------------------------------------------ apoio

    private static synchronized void prepararCenario() throws SQLException {
        if (tenantAlfa != null) {
            return;
        }
        try (Connection dono = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), SENHA_DONO)) {
            tenantAlfa = inserirTenant(dono, "Escritorio Ficticio Alfa");
            tenantBeta = inserirTenant(dono, "Escritorio Ficticio Beta");
            inserirUsuario(dono, tenantAlfa, "ana@alfa.exemplo.invalido");
            inserirUsuario(dono, tenantBeta, "carla@beta.exemplo.invalido");
        }
    }

    private static UUID inserirTenant(Connection conexao, String nome) throws SQLException {
        try (PreparedStatement ps = conexao.prepareStatement(
                "INSERT INTO tenant (nome, status) VALUES (?, 'ATIVO') RETURNING id")) {
            ps.setString(1, nome);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static void inserirUsuario(Connection conexao, UUID tenantId, String email)
            throws SQLException {
        try (PreparedStatement ps = conexao.prepareStatement(
                "INSERT INTO usuario (tenant_id, email, senha_hash, papel) "
                        + "VALUES (?, ?, 'hash-ficticio', 'OWNER')")) {
            ps.setObject(1, tenantId);
            ps.setString(2, email);
            ps.executeUpdate();
        }
    }
}
