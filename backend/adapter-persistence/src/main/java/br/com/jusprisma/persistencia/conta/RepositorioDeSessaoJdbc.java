package br.com.jusprisma.persistencia.conta;

import br.com.jusprisma.aplicacao.conta.RepositorioDeSessao;
import br.com.jusprisma.dominio.conta.SessaoRefresh;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RepositorioDeSessaoJdbc implements RepositorioDeSessao {

    private final JdbcClient jdbc;

    public RepositorioDeSessaoJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void registrar(SessaoRefresh sessao) {
        jdbc.sql("""
                INSERT INTO sessao_refresh (id, familia_id, tenant_id, usuario_id, criado_em, expira_em)
                VALUES (:id, :familiaId, :tenantId, :usuarioId, :criadoEm, :expiraEm)
                """)
                .param("id", sessao.id())
                .param("familiaId", sessao.familiaId())
                .param("tenantId", sessao.tenantId())
                .param("usuarioId", sessao.usuarioId())
                .param("criadoEm", Timestamp.from(sessao.criadoEm()))
                .param("expiraEm", Timestamp.from(sessao.expiraEm()))
                .update();
    }

    @Override
    public Optional<SessaoRefresh> buscarPorId(UUID id) {
        return jdbc.sql("""
                SELECT id, familia_id, tenant_id, usuario_id, criado_em, expira_em,
                       usado_em, revogado_em, motivo_revogacao
                  FROM sessao_refresh
                 WHERE id = :id
                """)
                .param("id", id)
                .query(RepositorioDeSessaoJdbc::mapear)
                .optional();
    }

    @Override
    public boolean consumir(UUID id) {
        // A condição "usado_em IS NULL" dentro do próprio UPDATE é o que torna o consumo
        // atômico. Ler e depois gravar abriria janela para duas renovações concorrentes
        // gerarem dois tokens válidos a partir do mesmo token — exatamente o que a rotação
        // existe para impedir.
        int afetadas = jdbc.sql("""
                UPDATE sessao_refresh
                   SET usado_em = now()
                 WHERE id = :id
                   AND usado_em IS NULL
                   AND revogado_em IS NULL
                   AND expira_em > now()
                """)
                .param("id", id)
                .update();

        return afetadas == 1;
    }

    @Override
    public int revogarFamilia(UUID familiaId, SessaoRefresh.MotivoDeRevogacao motivo) {
        return jdbc.sql("""
                UPDATE sessao_refresh
                   SET revogado_em = now(),
                       motivo_revogacao = :motivo
                 WHERE familia_id = :familiaId
                   AND revogado_em IS NULL
                """)
                .param("familiaId", familiaId)
                .param("motivo", motivo.name())
                .update();
    }

    private static SessaoRefresh mapear(ResultSet rs, int linha) throws SQLException {
        String motivo = rs.getString("motivo_revogacao");
        return new SessaoRefresh(
                rs.getObject("id", UUID.class),
                rs.getObject("familia_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("usuario_id", UUID.class),
                instante(rs, "criado_em"),
                instante(rs, "expira_em"),
                instante(rs, "usado_em"),
                instante(rs, "revogado_em"),
                motivo == null ? null : SessaoRefresh.MotivoDeRevogacao.valueOf(motivo));
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        Timestamp valor = rs.getTimestamp(coluna);
        return valor == null ? null : valor.toInstant();
    }
}
