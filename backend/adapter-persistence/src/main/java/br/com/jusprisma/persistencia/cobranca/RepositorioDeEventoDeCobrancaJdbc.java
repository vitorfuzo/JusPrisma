package br.com.jusprisma.persistencia.cobranca;

import br.com.jusprisma.aplicacao.cobranca.EventoDeCobranca;
import br.com.jusprisma.aplicacao.cobranca.RepositorioDeEventoDeCobranca;
import br.com.jusprisma.dominio.plano.Assinatura;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RepositorioDeEventoDeCobrancaJdbc implements RepositorioDeEventoDeCobranca {

    private final JdbcClient jdbc;

    public RepositorioDeEventoDeCobrancaJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public boolean registrarSeNovo(String gateway, EventoDeCobranca evento) {
        try {
            jdbc.sql("""
                    INSERT INTO evento_gateway (gateway, evento_id_externo, tipo, payload)
                    VALUES (:gateway, :idExterno, :tipo, :payload::jsonb)
                    """)
                    .param("gateway", gateway)
                    .param("idExterno", evento.idExterno())
                    .param("tipo", evento.tipo().name())
                    .param("payload", evento.corpoOriginal())
                    .update();
            return true;
        } catch (DuplicateKeyException reentrega) {
            // A violação de unicidade é o mecanismo, não um acidente: é ela que detecta a
            // reentrega. Um SELECT prévio perderia a corrida entre duas entregas simultâneas
            // da mesma notificação, que é exatamente o caso que o gateway produz ao
            // reprocessar uma fila.
            return false;
        }
    }

    @Override
    @Transactional
    public void marcarProcessado(String gateway, String idExterno) {
        jdbc.sql("""
                UPDATE evento_gateway SET processado_em = now(), erro = NULL
                 WHERE gateway = :gateway AND evento_id_externo = :idExterno
                """)
                .param("gateway", gateway)
                .param("idExterno", idExterno)
                .update();
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void marcarErro(String gateway, String idExterno, String erro) {
        // Transação própria: o registro do erro precisa sobreviver ao rollback da transação
        // que falhou. Sem isso, o evento ficaria marcado como pendente sem nenhuma pista do
        // que aconteceu.
        jdbc.sql("""
                UPDATE evento_gateway SET erro = :erro
                 WHERE gateway = :gateway AND evento_id_externo = :idExterno
                """)
                .param("erro", erro.length() > 1000 ? erro.substring(0, 1000) : erro)
                .param("gateway", gateway)
                .param("idExterno", idExterno)
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AssinaturaLocalizada> porIdentificadorDoGateway(String assinaturaNoGateway) {
        if (assinaturaNoGateway == null || assinaturaNoGateway.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT assinatura_id, tenant_id, plano_codigo, status
                  FROM cobranca_assinatura_por_gateway(:id)
                """)
                .param("id", assinaturaNoGateway)
                .query((rs, linha) -> new AssinaturaLocalizada(
                        rs.getObject("assinatura_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("plano_codigo"),
                        rs.getString("status")))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TrialPendente> trialsAAvisar(Instant limite) {
        return jdbc.sql("""
                SELECT assinatura_id, tenant_id, fim_do_periodo
                  FROM cobranca_trials_a_avisar(:limite)
                """)
                .param("limite", Timestamp.from(limite))
                .query((rs, linha) -> new TrialPendente(
                        rs.getObject("assinatura_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getTimestamp("fim_do_periodo").toInstant()))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TrialPendente> trialsAConverter(Instant agora) {
        return jdbc.sql("""
                SELECT assinatura_id, tenant_id FROM cobranca_trials_a_converter(:agora)
                """)
                .param("agora", Timestamp.from(agora))
                .query((rs, linha) -> new TrialPendente(
                        rs.getObject("assinatura_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        null))
                .list();
    }

    @Override
    @Transactional
    public void marcarAvisoEnviado(UUID assinaturaId) {
        jdbc.sql("SELECT cobranca_marcar_aviso(:id)")
                .param("id", assinaturaId)
                .query(String.class)
                .optional();
    }

    @Override
    @Transactional
    public void atualizarStatus(UUID assinaturaId, Assinatura.Status status, Instant proximaCobranca) {
        jdbc.sql("SELECT cobranca_atualizar_status(:id, :status, :proxima)")
                .param("id", assinaturaId)
                .param("status", status.name())
                .param("proxima", proximaCobranca == null ? null : Timestamp.from(proximaCobranca))
                .query(String.class)
                .optional();
    }

    @Override
    @Transactional
    public Optional<String> efetivarPlanoContratado(UUID assinaturaId) {
        // A função devolve nulo quando não havia pendência — a renovação mensal comum. O
        // mapeador de coluna única do JdbcClient recusa nulo, por isso a leitura é manual.
        return jdbc.sql("SELECT cobranca_efetivar_plano_contratado(:id)")
                .param("id", assinaturaId)
                .query((rs, linha) -> rs.getString(1))
                .list()
                .stream()
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }
}
