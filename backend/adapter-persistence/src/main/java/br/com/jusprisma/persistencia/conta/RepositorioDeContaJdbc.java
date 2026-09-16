package br.com.jusprisma.persistencia.conta;

import br.com.jusprisma.aplicacao.conta.CredenciaisDeAcesso;
import br.com.jusprisma.aplicacao.conta.EmailJaCadastradoException;
import br.com.jusprisma.aplicacao.conta.RepositorioDeConta;
import br.com.jusprisma.dominio.conta.Email;
import br.com.jusprisma.dominio.conta.Papel;
import br.com.jusprisma.dominio.conta.Tenant;
import br.com.jusprisma.dominio.conta.Usuario;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class RepositorioDeContaJdbc implements RepositorioDeConta {

    private final JdbcClient jdbc;

    public RepositorioDeContaJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void salvarTenant(Tenant tenant) {
        jdbc.sql("""
                INSERT INTO tenant (id, nome, cnpj, status, criado_em)
                VALUES (:id, :nome, :cnpj, :status, :criadoEm)
                """)
                .param("id", tenant.id())
                .param("nome", tenant.nome())
                .param("cnpj", tenant.cnpj())
                .param("status", tenant.status().name())
                .param("criadoEm", Timestamp.from(tenant.criadoEm()))
                .update();
    }

    @Override
    public void salvarUsuario(Usuario usuario, String senhaHash) {
        try {
            jdbc.sql("""
                    INSERT INTO usuario (id, tenant_id, email, senha_hash, papel, oab, uf_oab, criado_em)
                    VALUES (:id, :tenantId, :email, :senhaHash, :papel, :oab, :ufOab, :criadoEm)
                    """)
                    .param("id", usuario.id())
                    .param("tenantId", usuario.tenantId())
                    .param("email", usuario.email().valor())
                    .param("senhaHash", senhaHash)
                    .param("papel", usuario.papel().name())
                    .param("oab", usuario.oab())
                    .param("ufOab", usuario.ufOab())
                    .param("criadoEm", Timestamp.from(usuario.criadoEm()))
                    .update();
        } catch (DuplicateKeyException e) {
            // Traduz a violação de constraint para a linguagem do domínio. O caso de uso
            // não deve conhecer exceção de infraestrutura.
            throw new EmailJaCadastradoException(usuario.email());
        }
    }

    @Override
    public Optional<CredenciaisDeAcesso> buscarCredenciais(Email email) {
        // Função SECURITY DEFINER: ver V2__consulta_de_autenticacao.sql para o porquê
        // desta ser a única leitura que atravessa a fronteira de tenant.
        return jdbc.sql("""
                SELECT usuario_id, tenant_id, senha_hash, papel, email_verificado, tenant_operacional
                  FROM credenciais_por_email(:email)
                """)
                .param("email", email.valor())
                .query((rs, linha) -> new CredenciaisDeAcesso(
                        rs.getObject("usuario_id", java.util.UUID.class),
                        rs.getObject("tenant_id", java.util.UUID.class),
                        rs.getString("senha_hash"),
                        Papel.valueOf(rs.getString("papel")),
                        rs.getBoolean("email_verificado"),
                        rs.getBoolean("tenant_operacional")))
                .optional();
    }

    @Override
    public Optional<Usuario> buscarPorId(java.util.UUID id) {
        return jdbc.sql("""
                SELECT id, tenant_id, email, papel, oab, uf_oab, email_verificado_em, criado_em
                  FROM usuario
                 WHERE id = :id
                """)
                .param("id", id)
                .query((rs, linha) -> new Usuario(
                        rs.getObject("id", java.util.UUID.class),
                        rs.getObject("tenant_id", java.util.UUID.class),
                        Email.de(rs.getString("email")),
                        Papel.valueOf(rs.getString("papel")),
                        rs.getString("oab"),
                        rs.getString("uf_oab"),
                        instante(rs, "email_verificado_em"),
                        instante(rs, "criado_em")))
                .optional();
    }

    private static java.time.Instant instante(java.sql.ResultSet rs, String coluna)
            throws java.sql.SQLException {
        Timestamp valor = rs.getTimestamp(coluna);
        return valor == null ? null : valor.toInstant();
    }
}
