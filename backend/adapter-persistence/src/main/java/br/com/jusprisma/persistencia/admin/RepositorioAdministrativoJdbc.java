package br.com.jusprisma.persistencia.admin;

import br.com.jusprisma.aplicacao.admin.PainelAdministrativo;
import br.com.jusprisma.aplicacao.admin.RepositorioAdministrativo;
import br.com.jusprisma.dominio.credito.TipoCredito;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RepositorioAdministrativoJdbc implements RepositorioAdministrativo {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;

    public RepositorioAdministrativoJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PainelAdministrativo.ResumoDoTenant> listarTenants(int limite, int deslocamento) {
        return jdbc.sql("""
                SELECT tenant_id, nome, status, criado_em, plano_codigo,
                       status_assinatura, usuarios
                  FROM painel_listar_tenants(:limite, :deslocamento)
                """)
                .param("limite", limite)
                .param("deslocamento", deslocamento)
                .query((rs, linha) -> new PainelAdministrativo.ResumoDoTenant(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("nome"),
                        rs.getString("status"),
                        rs.getTimestamp("criado_em").toInstant(),
                        rs.getString("plano_codigo"),
                        rs.getString("status_assinatura"),
                        rs.getLong("usuarios")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PainelAdministrativo.ConsumoDeCredito> consumoDoTenant(UUID tenantId) {
        return jdbc.sql("""
                SELECT tipo_credito, saldo, consumido FROM painel_consumo_do_tenant(:tenant)
                """)
                .param("tenant", tenantId)
                .query((rs, linha) -> new PainelAdministrativo.ConsumoDeCredito(
                        TipoCredito.valueOf(rs.getString("tipo_credito")),
                        rs.getLong("saldo"),
                        rs.getLong("consumido")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CredenciaisDeAdministrador> buscarCredenciais(String email) {
        // administrador não tem RLS: não pertence a tenant nenhum.
        return jdbc.sql("""
                SELECT id, nome, senha_hash, ativo FROM administrador
                 WHERE lower(email) = lower(:email)
                """)
                .param("email", email)
                .query((rs, linha) -> new CredenciaisDeAdministrador(
                        rs.getObject("id", UUID.class),
                        rs.getString("nome"),
                        rs.getString("senha_hash"),
                        rs.getBoolean("ativo")))
                .optional();
    }

    @Override
    @Transactional
    public void registrarUltimoAcesso(UUID administradorId) {
        jdbc.sql("UPDATE administrador SET ultimo_acesso_em = now() WHERE id = :id")
                .param("id", administradorId)
                .update();
    }

    @Override
    @Transactional
    public boolean criarPrimeiroOperador(String email, String senhaHash, String nome) {
        // A condicao vai dentro do proprio INSERT: ler e depois gravar permitiria que duas
        // instancias subindo ao mesmo tempo criassem dois operadores iniciais.
        int criados = jdbc.sql("""
                INSERT INTO administrador (email, senha_hash, nome)
                SELECT :email, :hash, :nome
                 WHERE NOT EXISTS (SELECT 1 FROM administrador)
                """)
                .param("email", email)
                .param("hash", senhaHash)
                .param("nome", nome)
                .update();

        return criados == 1;
    }

    @Override
    @Transactional
    public void registrarAcao(UUID administradorId, String acao, UUID tenantAlvo,
                              Map<String, Object> detalhe) {
        jdbc.sql("""
                INSERT INTO acao_administrativa (administrador_id, acao, tenant_alvo, detalhe)
                VALUES (:admin, :acao, :tenant, :detalhe::jsonb)
                """)
                .param("admin", administradorId)
                .param("acao", acao)
                .param("tenant", tenantAlvo)
                .param("detalhe", JSON.writeValueAsString(detalhe))
                .update();
    }
}
