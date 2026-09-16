package br.com.jusprisma.persistencia.conta;

import br.com.jusprisma.aplicacao.conta.RepositorioDeTokenDeConta;
import br.com.jusprisma.dominio.conta.TokenDeConta;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RepositorioDeTokenDeContaJdbc implements RepositorioDeTokenDeConta {

    private final JdbcClient jdbc;

    public RepositorioDeTokenDeContaJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void registrar(TokenDeConta token, byte[] hash) {
        jdbc.sql("""
                INSERT INTO token_conta (id, tenant_id, usuario_id, finalidade, token_hash, criado_em, expira_em)
                VALUES (:id, :tenantId, :usuarioId, :finalidade, :hash, :criadoEm, :expiraEm)
                """)
                .param("id", token.id())
                .param("tenantId", token.tenantId())
                .param("usuarioId", token.usuarioId())
                .param("finalidade", token.finalidade().name())
                .param("hash", hash)
                .param("criadoEm", Timestamp.from(token.criadoEm()))
                .param("expiraEm", Timestamp.from(token.expiraEm()))
                .update();
    }

    @Override
    public Optional<TokenDeConta> buscarPorHash(byte[] hash) {
        // Função SECURITY DEFINER: ver V4__tokens_de_conta.sql.
        return jdbc.sql("""
                SELECT id, tenant_id, usuario_id, finalidade, expira_em, usado_em
                  FROM token_de_conta_por_hash(:hash)
                """)
                .param("hash", hash)
                .query(RepositorioDeTokenDeContaJdbc::mapear)
                .optional();
    }

    @Override
    public boolean consumir(UUID tokenId) {
        // Condição no próprio UPDATE: dois cliques simultâneos no mesmo link fazem
        // exatamente um vencer.
        int afetadas = jdbc.sql("""
                UPDATE token_conta
                   SET usado_em = now()
                 WHERE id = :id
                   AND usado_em IS NULL
                   AND expira_em > now()
                """)
                .param("id", tokenId)
                .update();

        return afetadas == 1;
    }

    @Override
    public int invalidarPendentes(UUID usuarioId, TokenDeConta.Finalidade finalidade) {
        return jdbc.sql("""
                UPDATE token_conta
                   SET usado_em = now()
                 WHERE usuario_id = :usuarioId
                   AND finalidade = :finalidade
                   AND usado_em IS NULL
                """)
                .param("usuarioId", usuarioId)
                .param("finalidade", finalidade.name())
                .update();
    }

    private static TokenDeConta mapear(ResultSet rs, int linha) throws SQLException {
        return new TokenDeConta(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("usuario_id", UUID.class),
                TokenDeConta.Finalidade.valueOf(rs.getString("finalidade")),
                // criadoEm não é devolvido pela função: ninguém decide nada com ele, e
                // função de exceção ao RLS devolve o mínimo.
                null,
                instante(rs, "expira_em"),
                instante(rs, "usado_em"));
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        Timestamp valor = rs.getTimestamp(coluna);
        return valor == null ? null : valor.toInstant();
    }
}
