package br.com.jusprisma.aplicacao.conta;

import br.com.jusprisma.aplicacao.porta.CodificadorDeSenha;
import br.com.jusprisma.aplicacao.plano.RepositorioDeAssinatura;
import br.com.jusprisma.aplicacao.porta.EscopoDeTenant;
import br.com.jusprisma.dominio.conta.Email;
import br.com.jusprisma.dominio.conta.Tenant;
import br.com.jusprisma.dominio.conta.Usuario;
import br.com.jusprisma.dominio.plano.Assinatura;
import org.springframework.stereotype.Service;

/**
 * Cria uma conta nova: o tenant e o usuário dono, numa transação só.
 */
@Service
public class CadastrarConta {

    private static final int TAMANHO_MINIMO_DA_SENHA = 10;

    /** Toda conta nova comeca na degustacao paga de 14 dias. */
    private static final String PLANO_INICIAL = "DEGUSTACAO";
    private static final java.time.Duration DURACAO_DO_TRIAL = java.time.Duration.ofDays(14);

    private final RepositorioDeConta repositorio;
    private final CodificadorDeSenha codificador;
    private final EscopoDeTenant escopo;
    private final VerificacaoDeEmail verificacao;
    private final RepositorioDeAssinatura assinaturas;

    public CadastrarConta(RepositorioDeConta repositorio,
                          CodificadorDeSenha codificador,
                          EscopoDeTenant escopo,
                          VerificacaoDeEmail verificacao,
                          RepositorioDeAssinatura assinaturas) {
        this.repositorio = repositorio;
        this.codificador = codificador;
        this.escopo = escopo;
        this.verificacao = verificacao;
        this.assinaturas = assinaturas;
    }

    public record Comando(
            String nomeDoEscritorio,
            String cnpj,
            String email,
            String senha,
            String oab,
            String ufOab) {
    }

    /**
     * Sem {@code @Transactional} de propósito: quem abre a transação é o
     * {@link EscopoDeTenant}, depois de o tenant já ser conhecido. Anotar este método
     * abriria a transação cedo demais, antes de haver tenant para publicar, e todo INSERT
     * seria recusado pelas policies.
     */
    public Usuario executar(Comando comando) {
        Email email = Email.de(comando.email());
        validarSenha(comando.senha());

        Tenant tenant = Tenant.novo(comando.nomeDoEscritorio(), comando.cnpj());
        Usuario dono = Usuario.donoDaConta(tenant.id(), email, comando.oab(), comando.ufOab());
        String senhaHash = codificador.codificar(comando.senha());

        // O escopo é aberto antes de qualquer INSERT: as policies de RLS exigem que a
        // transação já declare a qual tenant pertence o que está sendo escrito. Foi por
        // isso que o id do tenant nasceu na aplicação, e não no DEFAULT da coluna.
        escopo.executarComo(tenant.id(), () -> {
            repositorio.salvarTenant(tenant);
            repositorio.salvarUsuario(dono, senhaHash);
            // Assinatura na mesma transacao: conta sem assinatura nao consegue fazer nada,
            // porque toda cota e resolvida a partir do plano vigente.
            assinaturas.registrar(Assinatura.iniciarTrial(
                    tenant.id(), PLANO_INICIAL, java.time.Instant.now().plus(DURACAO_DO_TRIAL)));
        });

        // Depois do commit, e dentro do proprio caso de uso: cadastro sem e-mail de
        // verificacao e cadastro pela metade, e deixar o envio a cargo de quem chama faria
        // um segundo ponto de entrada esquece-lo mais cedo ou mais tarde.
        verificacao.enviar(tenant.id(), dono.id(), email);
        return dono;
    }

    private void validarSenha(String senha) {
        if (senha == null || senha.length() < TAMANHO_MINIMO_DA_SENHA) {
            throw new SenhaFracaException(
                    "a senha deve ter ao menos %d caracteres".formatted(TAMANHO_MINIMO_DA_SENHA));
        }
        if (senha.isBlank()) {
            throw new SenhaFracaException("a senha não pode ser só espaços");
        }
    }
}
