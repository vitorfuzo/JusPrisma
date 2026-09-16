package br.com.jusprisma.persistencia.credito;

import br.com.jusprisma.aplicacao.credito.RepositorioDeCredito;
import br.com.jusprisma.dominio.credito.CreditoLancamento;
import br.com.jusprisma.dominio.credito.TipoCredito;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RepositorioDeCreditoJdbc implements RepositorioDeCredito {

    private final JdbcClient jdbc;

    public RepositorioDeCreditoJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void travarParaAtualizacao(UUID tenantId) {
        // O ledger é append-only e não tem linha de saldo para travar, então travamos a
        // linha do escritório. Isso serializa as operações de crédito daquele tenant, e é
        // aceitável porque a tabela tenant quase não sofre escrita e um escritório não gera
        // operações de crédito concorrentes em volume.
        //
        // Se um dia virar gargalo, o passo seguinte é pg_advisory_xact_lock por
        // (tenant, tipo): mesma garantia, granularidade menor.
        jdbc.sql("SELECT id FROM tenant WHERE id = :tenantId FOR UPDATE")
                .param("tenantId", tenantId)
                .query(UUID.class)
                .optional();
    }

    @Override
    public int saldo(UUID tenantId, TipoCredito tipo) {
        return jdbc.sql("""
                SELECT coalesce(sum(delta), 0)
                  FROM credito_lancamento
                 WHERE tenant_id = :tenantId AND tipo_credito = :tipo
                """)
                .param("tenantId", tenantId)
                .param("tipo", tipo.name())
                .query(Integer.class)
                .single();
    }

    @Override
    public CreditoLancamento registrar(CreditoLancamento lancamento) {
        Long id = jdbc.sql("""
                INSERT INTO credito_lancamento (tenant_id, tipo_credito, delta, motivo,
                                                referencia_id, estorno_de, criado_em)
                VALUES (:tenantId, :tipo, :delta, :motivo, :referenciaId, :estornoDe, :criadoEm)
                RETURNING id
                """)
                .param("tenantId", lancamento.tenantId())
                .param("tipo", lancamento.tipo().name())
                .param("delta", lancamento.delta())
                .param("motivo", lancamento.motivo())
                .param("referenciaId", lancamento.referenciaId())
                .param("estornoDe", lancamento.estornoDe())
                .param("criadoEm", Timestamp.from(lancamento.criadoEm()))
                .query(Long.class)
                .single();

        return new CreditoLancamento(
                id, lancamento.tenantId(), lancamento.tipo(), lancamento.delta(),
                lancamento.motivo(), lancamento.referenciaId(), lancamento.estornoDe(),
                lancamento.criadoEm());
    }

    @Override
    public Optional<CreditoLancamento> buscar(long lancamentoId) {
        return jdbc.sql("""
                SELECT id, tenant_id, tipo_credito, delta, motivo, referencia_id,
                       estorno_de, criado_em
                  FROM credito_lancamento WHERE id = :id
                """)
                .param("id", lancamentoId)
                .query(RepositorioDeCreditoJdbc::mapear)
                .optional();
    }

    @Override
    public int consumidoDesde(UUID tenantId, TipoCredito tipo, Instant desde) {
        // Consumo líquido do período: débitos menos os estornos que os reverteram.
        // Créditos de recarga não entram — recarregar não é consumir, e somá-los aqui
        // faria a cota mensal parecer maior do que foi usada.
        return jdbc.sql("""
                SELECT coalesce(-sum(delta), 0)
                  FROM credito_lancamento
                 WHERE tenant_id = :tenantId
                   AND tipo_credito = :tipo
                   AND criado_em >= :desde
                   AND (delta < 0 OR estorno_de IS NOT NULL)
                """)
                .param("tenantId", tenantId)
                .param("tipo", tipo.name())
                .param("desde", Timestamp.from(desde))
                .query(Integer.class)
                .single();
    }

    private static CreditoLancamento mapear(ResultSet rs, int linha) throws SQLException {
        return new CreditoLancamento(
                rs.getLong("id"),
                rs.getObject("tenant_id", UUID.class),
                TipoCredito.valueOf(rs.getString("tipo_credito")),
                rs.getInt("delta"),
                rs.getString("motivo"),
                rs.getString("referencia_id"),
                rs.getObject("estorno_de", Long.class),
                rs.getTimestamp("criado_em").toInstant());
    }
}
