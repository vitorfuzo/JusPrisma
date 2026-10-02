package br.com.jusprisma.persistencia.plano;

import br.com.jusprisma.aplicacao.plano.RepositorioDeAssinatura;
import br.com.jusprisma.dominio.plano.Assinatura;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RepositorioDeAssinaturaJdbc implements RepositorioDeAssinatura {

    private static final String COLUNAS = """
            id, tenant_id, plano_codigo, status, inicio_em, fim_do_periodo, proxima_cobranca,
            gateway_customer_id, gateway_subscription_id, plano_contratado""";

    private final JdbcClient jdbc;

    public RepositorioDeAssinaturaJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void registrar(Assinatura assinatura) {
        jdbc.sql("""
                INSERT INTO assinatura (id, tenant_id, plano_codigo, status, inicio_em,
                                        fim_do_periodo, proxima_cobranca,
                                        gateway_customer_id, gateway_subscription_id)
                VALUES (:id, :tenantId, :plano, :status, :inicioEm, :fimDoPeriodo,
                        :proximaCobranca, :customerId, :subscriptionId)
                """)
                .param("id", assinatura.id())
                .param("tenantId", assinatura.tenantId())
                .param("plano", assinatura.planoCodigo())
                .param("status", assinatura.status().name())
                .param("inicioEm", Timestamp.from(assinatura.inicioEm()))
                .param("fimDoPeriodo", timestamp(assinatura.fimDoPeriodo()))
                .param("proximaCobranca", timestamp(assinatura.proximaCobranca()))
                .param("customerId", assinatura.gatewayCustomerId())
                .param("subscriptionId", assinatura.gatewaySubscriptionId())
                .update();
    }

    @Override
    public Optional<Assinatura> vigenteDoTenant(UUID tenantId) {
        // O índice parcial único garante no máximo uma vigente por tenant, então não há
        // ambiguidade de qual devolver.
        return jdbc.sql("""
                SELECT %s
                  FROM assinatura
                 WHERE tenant_id = :tenantId
                   AND status IN ('TRIAL', 'ATIVA', 'INADIMPLENTE')
                """.formatted(COLUNAS))
                .param("tenantId", tenantId)
                .query(RepositorioDeAssinaturaJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<Assinatura> travarVigenteDoTenant(UUID tenantId) {
        // FOR UPDATE serializa duas contratacoes simultaneas do mesmo escritorio: a segunda
        // espera a primeira terminar e ja encontra a assinatura registrada no gateway.
        return jdbc.sql("""
                SELECT %s
                  FROM assinatura
                 WHERE tenant_id = :tenantId
                   AND status IN ('TRIAL', 'ATIVA', 'INADIMPLENTE')
                   FOR UPDATE
                """.formatted(COLUNAS))
                .param("tenantId", tenantId)
                .query(RepositorioDeAssinaturaJdbc::mapear)
                .optional();
    }

    @Override
    public void registrarContratacao(UUID assinaturaId, String clienteNoGateway,
                                     String assinaturaNoGateway, String planoContratado,
                                     Instant proximaCobranca) {
        jdbc.sql("""
                UPDATE assinatura
                   SET gateway_customer_id = :cliente,
                       gateway_subscription_id = :assinatura,
                       plano_contratado = :plano,
                       proxima_cobranca = coalesce(:proxima, proxima_cobranca)
                 WHERE id = :id
                """)
                .param("cliente", clienteNoGateway)
                .param("assinatura", assinaturaNoGateway)
                .param("plano", planoContratado)
                .param("proxima", timestamp(proximaCobranca))
                .param("id", assinaturaId)
                .update();
    }

    @Override
    public void atualizarStatus(UUID assinaturaId, Assinatura.Status status) {
        jdbc.sql("UPDATE assinatura SET status = :status WHERE id = :id")
                .param("status", status.name())
                .param("id", assinaturaId)
                .update();
    }

    private static Assinatura mapear(ResultSet rs, int linha) throws SQLException {
        return new Assinatura(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("plano_codigo"),
                Assinatura.Status.valueOf(rs.getString("status")),
                instante(rs, "inicio_em"),
                instante(rs, "fim_do_periodo"),
                instante(rs, "proxima_cobranca"),
                rs.getString("gateway_customer_id"),
                rs.getString("gateway_subscription_id"),
                rs.getString("plano_contratado"));
    }

    private static Timestamp timestamp(Instant instante) {
        return instante == null ? null : Timestamp.from(instante);
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        Timestamp valor = rs.getTimestamp(coluna);
        return valor == null ? null : valor.toInstant();
    }
}
