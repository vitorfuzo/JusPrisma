package br.com.jusprisma.persistencia;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prova que o isolamento entre tenants é do banco, não da aplicação.
 *
 * <p>Este é o teste que sustenta a regra de multi-tenant do CLAUDE.md. Ele conecta
 * deliberadamente como {@code jusprisma_app} — a role sem SUPERUSER e sem BYPASSRLS —
 * porque conectar como dono do schema faria tudo passar sem medir nada.
 *
 * <p>Fixtures são explicitamente fictícias: domínio {@code .invalido} não existe e
 * nomes de escritório são inventados.
 */
@Testcontainers
class IsolamentoPorTenantTest {

    private static final String SENHA_DONO = "owner_teste";
    private static final String USUARIO_APP = "jusprisma_app";
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

    @BeforeAll
    static void prepararBase() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), SENHA_DONO)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // Semeado como dono, que é superusuário no container e portanto ignora o RLS.
        // É o único ponto do teste onde isso é aceitável: montar o cenário.
        try (Connection dono = conexaoDono()) {
            tenantAlfa = inserirTenant(dono, "Escritorio Ficticio Alfa");
            tenantBeta = inserirTenant(dono, "Escritorio Ficticio Beta");
            inserirUsuario(dono, tenantAlfa, "ana@alfa.exemplo.invalido");
            inserirUsuario(dono, tenantAlfa, "bruno@alfa.exemplo.invalido");
            inserirUsuario(dono, tenantBeta, "carla@beta.exemplo.invalido");
        }
    }

    @Nested
    @DisplayName("leitura")
    class Leitura {

        @Test
        @DisplayName("o tenant enxerga apenas os próprios usuários")
        void enxergaSomenteOsProprios() throws SQLException {
            assertThat(emailsVisiveisPara(tenantAlfa))
                    .containsExactlyInAnyOrder(
                            "ana@alfa.exemplo.invalido",
                            "bruno@alfa.exemplo.invalido");

            assertThat(emailsVisiveisPara(tenantBeta))
                    .containsExactly("carla@beta.exemplo.invalido");
        }

        @Test
        @DisplayName("sem tenant definido não se enxerga nada — falha fechado")
        void semTenantNaoEnxergaNada() throws SQLException {
            // O cenário perigoso: um caminho de código que esquece de definir o tenant.
            // O resultado correto é zero linhas, jamais a base inteira.
            try (Connection app = conexaoAplicacao()) {
                app.setAutoCommit(false);
                assertThat(contar(app, "usuario")).isZero();
                assertThat(contar(app, "tenant")).isZero();
                app.rollback();
            }
        }

        @Test
        @DisplayName("a linha do próprio tenant é visível; a do outro não")
        void tenantSoEnxergaASiMesmo() throws SQLException {
            try (Connection app = conexaoAplicacao()) {
                app.setAutoCommit(false);
                definirTenant(app, tenantAlfa);

                List<String> nomes = new ArrayList<>();
                try (PreparedStatement ps = app.prepareStatement("SELECT nome FROM tenant");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        nomes.add(rs.getString(1));
                    }
                }
                assertThat(nomes).containsExactly("Escritorio Ficticio Alfa");
                app.rollback();
            }
        }
    }

    @Nested
    @DisplayName("escrita")
    class Escrita {

        @Test
        @DisplayName("não se insere usuário em tenant alheio")
        void naoInsereEmTenantAlheio() throws SQLException {
            try (Connection app = conexaoAplicacao()) {
                app.setAutoCommit(false);
                definirTenant(app, tenantAlfa);

                assertThatThrownBy(() -> inserirUsuario(app, tenantBeta, "invasor@alfa.exemplo.invalido"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("row-level security");

                app.rollback();
            }
        }

        @Test
        @DisplayName("não se transfere usuário para outro tenant por UPDATE")
        void naoTransferePorUpdate() throws SQLException {
            try (Connection app = conexaoAplicacao()) {
                app.setAutoCommit(false);
                definirTenant(app, tenantAlfa);

                try (PreparedStatement ps = app.prepareStatement(
                        "UPDATE usuario SET tenant_id = ? WHERE email = ?")) {
                    ps.setObject(1, tenantBeta);
                    ps.setString(2, "ana@alfa.exemplo.invalido");

                    assertThatThrownBy(ps::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("row-level security");
                }
                app.rollback();
            }
        }

        @Test
        @DisplayName("o DELETE de outro tenant não encontra linha para apagar")
        void deleteAlheioNaoAfetaNada() throws SQLException {
            try (Connection app = conexaoAplicacao()) {
                app.setAutoCommit(false);
                definirTenant(app, tenantAlfa);

                try (PreparedStatement ps = app.prepareStatement(
                        "DELETE FROM usuario WHERE email = ?")) {
                    ps.setString(1, "carla@beta.exemplo.invalido");
                    assertThat(ps.executeUpdate())
                            .as("a linha existe, mas é invisível para este tenant")
                            .isZero();
                }
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("a role da aplicação não pode burlar o RLS")
    void roleDaAplicacaoNaoBurlaRls() throws SQLException {
        // Guarda contra alguém "resolver um problema de permissão" concedendo
        // SUPERUSER ou BYPASSRLS à role da aplicação: isso desligaria todo o
        // isolamento sem quebrar nenhum outro teste.
        try (Connection dono = conexaoDono();
             PreparedStatement ps = dono.prepareStatement(
                     "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = ?")) {
            ps.setString(1, USUARIO_APP);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("rolsuper")).as("rolsuper").isFalse();
                assertThat(rs.getBoolean("rolbypassrls")).as("rolbypassrls").isFalse();
            }
        }
    }

    // ------------------------------------------------------------------ apoio

    private static Connection conexaoDono() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), SENHA_DONO);
    }

    private static Connection conexaoAplicacao() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), USUARIO_APP, SENHA_APP);
    }

    /**
     * Define o tenant da transação corrente.
     *
     * <p>Usa {@code set_config(..., true)}, que é o equivalente parametrizável de
     * {@code SET LOCAL}: o valor morre no fim da transação. Com pool de conexões, um
     * {@code SET} de sessão vazaria o tenant para a próxima requisição que reaproveitasse
     * a conexão — que é uma falha de isolamento silenciosa e intermitente.
     */
    private static void definirTenant(Connection conexao, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = conexao.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenantId.toString());
            ps.execute();
        }
    }

    private static List<String> emailsVisiveisPara(UUID tenantId) throws SQLException {
        try (Connection app = conexaoAplicacao()) {
            app.setAutoCommit(false);
            definirTenant(app, tenantId);

            List<String> emails = new ArrayList<>();
            try (PreparedStatement ps = app.prepareStatement("SELECT email FROM usuario");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    emails.add(rs.getString(1));
                }
            }
            app.rollback();
            return emails;
        }
    }

    private static int contar(Connection conexao, String tabela) throws SQLException {
        try (PreparedStatement ps = conexao.prepareStatement("SELECT count(*) FROM " + tabela);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
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
                        + "VALUES (?, ?, 'hash-ficticio', 'MEMBRO')")) {
            ps.setObject(1, tenantId);
            ps.setString(2, email);
            ps.executeUpdate();
        }
    }
}
