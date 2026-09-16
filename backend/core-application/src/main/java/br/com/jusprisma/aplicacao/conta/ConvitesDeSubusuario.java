package br.com.jusprisma.aplicacao.conta;

import br.com.jusprisma.aplicacao.porta.CodificadorDeSenha;
import br.com.jusprisma.aplicacao.porta.EnviadorDeEmail;
import br.com.jusprisma.aplicacao.plano.LimitesVigentes;
import br.com.jusprisma.aplicacao.porta.EscopoDeTenant;
import br.com.jusprisma.dominio.conta.Convite;
import br.com.jusprisma.dominio.conta.Email;
import br.com.jusprisma.dominio.conta.Papel;
import br.com.jusprisma.dominio.plano.Cota;
import br.com.jusprisma.dominio.conta.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Convida alguém para um escritório existente e processa o aceite.
 */
@Service
public class ConvitesDeSubusuario {

    private static final int TAMANHO_MINIMO_DA_SENHA = 10;

    private static final Logger log = LoggerFactory.getLogger(ConvitesDeSubusuario.class);

    private final RepositorioDeConvite convites;
    private final RepositorioDeConta contas;
    private final CodificadorDeSenha codificador;
    private final EnviadorDeEmail email;
    private final EscopoDeTenant escopo;
    private final LimitesVigentes limites;

    public ConvitesDeSubusuario(RepositorioDeConvite convites,
                                RepositorioDeConta contas,
                                CodificadorDeSenha codificador,
                                EnviadorDeEmail email,
                                EscopoDeTenant escopo,
                                LimitesVigentes limites) {
        this.convites = convites;
        this.contas = contas;
        this.codificador = codificador;
        this.email = email;
        this.escopo = escopo;
        this.limites = limites;
    }

    public record Comando(UUID tenantId, UUID convidadoPor, Papel papelDeQuemConvida,
                          String emailDoConvidado, Papel papelDoConvidado) {
    }

    /**
     * Emite e envia um convite, respeitando a cota de subusuários do plano.
     */
    public Convite convidar(Comando comando) {
        exigirDono(comando.papelDeQuemConvida(), "convidar pessoas para o escritório");
        exigirVagaDeSubusuario(comando.tenantId());

        Email convidado = Email.de(comando.emailDoConvidado());
        String segredo = SegredoDeToken.gerar();
        Convite convite = Convite.novo(
                comando.tenantId(), comando.convidadoPor(), convidado, comando.papelDoConvidado());

        // O e-mail é único no sistema inteiro. Se já existe conta, o convite não tem como
        // ser aceito — melhor recusar agora, com mensagem clara, do que enviar um link que
        // só vai falhar quando a pessoa clicar.
        if (contas.localizarPorEmail(convidado).isPresent()) {
            throw new EmailJaCadastradoException(convidado);
        }

        Convite registrado = escopo.executarComo(comando.tenantId(), () -> {
            // Convidar de novo reenvia em vez de acumular: dois links vivos para o mesmo
            // e-mail são duas portas de entrada para o mesmo lugar.
            convites.revogarPendentePara(convidado.valor());
            convites.registrar(convite, SegredoDeToken.hash(segredo));
            return convite;
        });

        email.enviarConvite(convidado.valor(), segredo);
        log.info("convite emitido para o tenant {} pelo usuário {}",
                comando.tenantId(), comando.convidadoPor());

        return registrado;
    }

    public List<Convite> listarPendentes(UUID tenantId, Papel papelDeQuemPede) {
        exigirDono(papelDeQuemPede, "ver os convites do escritório");
        return escopo.executarComo(tenantId, convites::listarPendentes);
    }

    public void revogar(UUID tenantId, Papel papelDeQuemPede, UUID conviteId) {
        exigirDono(papelDeQuemPede, "revogar convites");
        boolean revogado = escopo.executarComo(tenantId, () -> convites.revogar(conviteId));
        if (!revogado) {
            throw new ConviteInvalidoException();
        }
    }

    /** Dados que a tela de aceite mostra antes de a pessoa decidir. */
    public record ConvitePendente(String nomeDoEscritorio, String email, Papel papel) {
    }

    public ConvitePendente examinar(String segredo) {
        Convite convite = localizarPendente(segredo);
        return new ConvitePendente(
                convite.nomeDoEscritorio(), convite.email().valor(), convite.papel());
    }

    /**
     * Aceita o convite criando o usuário dentro do escritório que convidou.
     */
    public Usuario aceitar(String segredo, String senha, String oab, String ufOab) {
        if (senha == null || senha.isBlank() || senha.length() < TAMANHO_MINIMO_DA_SENHA) {
            throw new SenhaFracaException(
                    "a senha deve ter ao menos %d caracteres".formatted(TAMANHO_MINIMO_DA_SENHA));
        }

        Convite convite = localizarPendente(segredo);
        String senhaHash = codificador.codificar(senha);

        Usuario novo = escopo.executarComo(convite.tenantId(), () -> {
            if (!convites.marcarAceito(convite.id())) {
                return null;
            }
            // Quem clicou no link provou ter acesso à caixa de e-mail, que é exatamente o
            // que a verificação de endereço existe para provar. Pedir de novo seria ritual.
            Usuario usuario = Usuario.membroVerificado(
                    convite.tenantId(), convite.email(), convite.papel(), oab, ufOab);
            contas.salvarUsuario(usuario, senhaHash);
            return usuario;
        });

        if (novo == null) {
            throw new ConviteInvalidoException();
        }
        log.info("convite aceito; usuário {} criado no tenant {}", novo.id(), novo.tenantId());
        return novo;
    }

    private Convite localizarPendente(String segredo) {
        if (segredo == null || segredo.isBlank()) {
            throw new ConviteInvalidoException();
        }
        Convite convite = convites.buscarPorHash(SegredoDeToken.hash(segredo))
                .orElseThrow(ConviteInvalidoException::new);

        if (!convite.pendente(Instant.now())) {
            throw new ConviteInvalidoException();
        }
        return convite;
    }

    /**
     * Confere a cota de subusuários do plano.
     *
     * <p>O dono não conta: a cota é de pessoas <em>além</em> dele. Convites pendentes
     * contam, porque cada um é uma vaga já prometida — não contá-los permitiria emitir dez
     * convites num plano de três e estourar a cota no momento em que todos aceitassem,
     * quando já não haveria como recusar sem desfazer o que foi prometido.
     */
    private void exigirVagaDeSubusuario(UUID tenantId) {
        int ocupadas = escopo.executarComo(tenantId,
                () -> Math.max(0, contas.contarUsuarios() - 1) + convites.contarPendentes());

        limites.exigirEspacoEm(tenantId, Cota.SUBUSUARIOS, ocupadas);
    }

    private static void exigirDono(Papel papel, String acao) {
        if (!papel.administraAConta()) {
            throw new OperacaoNaoPermitidaException("apenas o dono da conta pode " + acao);
        }
    }
}
