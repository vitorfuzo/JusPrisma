package br.com.jusprisma.aplicacao.conta;

import br.com.jusprisma.dominio.conta.Email;
import br.com.jusprisma.dominio.conta.Tenant;
import br.com.jusprisma.dominio.conta.Usuario;

import java.util.Optional;

/**
 * Persistência de contas.
 *
 * <p>Tenant e usuário ficam no mesmo repositório porque são criados na mesma transação
 * indivisível: um tenant sem dono não é estado válido do sistema.
 */
public interface RepositorioDeConta {

    void salvarTenant(Tenant tenant);

    /**
     * @throws EmailJaCadastradoException se o e-mail já pertence a outra conta.
     *         A checagem é a constraint única do banco, e não um SELECT prévio: um SELECT
     *         não enxergaria usuários de outros tenants sob RLS, perderia a corrida entre
     *         dois cadastros simultâneos e ainda daria um oráculo para descobrir quais
     *         e-mails têm conta.
     */
    void salvarUsuario(Usuario usuario, String senhaHash);

    /**
     * Busca o material necessário para autenticar, por e-mail.
     *
     * <p>É a única leitura do sistema que atravessa a fronteira de tenant, porque na hora
     * do login ainda não se sabe a qual tenant o e-mail pertence. Por isso o adaptador a
     * implementa sobre uma função {@code SECURITY DEFINER} estreita, que devolve só estes
     * campos, em vez de afrouxar as policies da tabela.
     */
    Optional<CredenciaisDeAcesso> buscarCredenciais(Email email);

    /**
     * Busca um usuário dentro do tenant corrente. Sujeito a RLS: usuário de outro
     * escritório não é encontrado, mesmo com o id correto em mãos.
     */
    Optional<Usuario> buscarPorId(java.util.UUID id);
}
