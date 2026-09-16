package br.com.jusprisma.persistencia.conta;

import br.com.jusprisma.aplicacao.conta.RepositorioDeConvite;
import br.com.jusprisma.dominio.conta.Convite;
import br.com.jusprisma.dominio.conta.Email;
import br.com.jusprisma.dominio.conta.Papel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RepositorioDeConviteJdbc implements RepositorioDeConvite {

    private final JdbcClient jdbc;

    public RepositorioDeConviteJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void registrar(Convite convite, byte[] hash) {
        jdbc.sql("""
                INSERT INTO convite (id, tenant_id, convidado_por, email, papel, token_hash,
                                     criado_em, expira_em)
                VALUES (:id, :tenantId, :convidadoPor, :email, :papel, :hash, :criadoEm, :expiraEm)
                """)
                .param("id", convite.id())
                .param("tenantId", convite.tenantId())
                .param("convidadoPor", convite.convidadoPor())
                .param("email", convite.email().valor())
                .param("papel", convite.papel().name())
                .param("hash", hash)
                .param("criadoEm", Timestamp.from(convite.criadoEm()))
                .param("expiraEm", Timestamp.from(convite.expiraEm()))
                .update();
    }

    @Override
    public Optional<Convite> buscarPorHash(byte[] hash) {
        // Função SECURITY DEFINER: ver V5__convite.sql.
        return jdbc.sql("""
                SELECT id, tenant_id, nome_do_escritorio, email, papel,
                       expira_em, aceito_em, revogado_em
                  FROM convite_por_hash(:hash)
                """)
                .param("hash", hash)
                .query((rs, linha) -> new Convite(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("nome_do_escritorio"),
                        null, // convidadoPor não é devolvido: ninguém decide nada com ele aqui
                        Email.de(rs.getString("email")),
                        Papel.valueOf(rs.getString("papel")),
                        null,
                        instante(rs, "expira_em"),
                        instante(rs, "aceito_em"),
                        instante(rs, "revogado_em")))
                .optional();
    }

    @Override
    public List<Convite> listarPendentes() {
        return jdbc.sql("""
                SELECT c.id, c.tenant_id, t.nome AS nome_do_escritorio, c.convidado_por,
                       c.email, c.papel, c.criado_em, c.expira_em, c.aceito_em, c.revogado_em
                  FROM convite c
                  JOIN tenant  t ON t.id = c.tenant_id
                 WHERE c.aceito_em IS NULL
                   AND c.revogado_em IS NULL
                   AND c.expira_em > now()
                 ORDER BY c.criado_em DESC
                """)
                .query(RepositorioDeConviteJdbc::mapearCompleto)
                .list();
    }

    @Override
    public boolean marcarAceito(UUID conviteId) {
        // Condição no próprio UPDATE: dois cliques simultâneos no mesmo link não podem
        // criar dois usuários.
        return jdbc.sql("""
                UPDATE convite
                   SET aceito_em = now()
                 WHERE id = :id
                   AND aceito_em IS NULL
                   AND revogado_em IS NULL
                   AND expira_em > now()
                """)
                .param("id", conviteId)
                .update() == 1;
    }

    @Override
    public boolean revogar(UUID conviteId) {
        return jdbc.sql("""
                UPDATE convite
                   SET revogado_em = now()
                 WHERE id = :id
                   AND aceito_em IS NULL
                   AND revogado_em IS NULL
                """)
                .param("id", conviteId)
                .update() == 1;
    }

    @Override
    public int revogarPendentePara(String email) {
        return jdbc.sql("""
                UPDATE convite
                   SET revogado_em = now()
                 WHERE lower(email) = lower(:email)
                   AND aceito_em IS NULL
                   AND revogado_em IS NULL
                """)
                .param("email", email)
                .update();
    }

    private static Convite mapearCompleto(ResultSet rs, int linha) throws SQLException {
        return new Convite(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("nome_do_escritorio"),
                rs.getObject("convidado_por", UUID.class),
                Email.de(rs.getString("email")),
                Papel.valueOf(rs.getString("papel")),
                instante(rs, "criado_em"),
                instante(rs, "expira_em"),
                instante(rs, "aceito_em"),
                instante(rs, "revogado_em"));
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        Timestamp valor = rs.getTimestamp(coluna);
        return valor == null ? null : valor.toInstant();
    }
}
