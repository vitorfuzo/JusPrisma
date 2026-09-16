package br.com.jusprisma.aplicacao.conta;

import br.com.jusprisma.aplicacao.porta.CodificadorDeSenha;
import br.com.jusprisma.aplicacao.porta.EnviadorDeEmail;
import br.com.jusprisma.aplicacao.porta.EscopoDeTenant;
import br.com.jusprisma.dominio.conta.Email;
import br.com.jusprisma.dominio.conta.SessaoRefresh;
import br.com.jusprisma.dominio.conta.TokenDeConta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Redefinição de senha por link enviado ao e-mail cadastrado.
 */
@Service
public class RecuperacaoDeSenha {

    private static final int TAMANHO_MINIMO_DA_SENHA = 10;

    private static final Logger log = LoggerFactory.getLogger(RecuperacaoDeSenha.class);

    private final RepositorioDeTokenDeConta tokens;
    private final RepositorioDeConta contas;
    private final RepositorioDeSessao sessoes;
    private final CodificadorDeSenha codificador;
    private final EnviadorDeEmail email;
    private final EscopoDeTenant escopo;

    public RecuperacaoDeSenha(RepositorioDeTokenDeConta tokens,
                              RepositorioDeConta contas,
                              RepositorioDeSessao sessoes,
                              CodificadorDeSenha codificador,
                              EnviadorDeEmail email,
                              EscopoDeTenant escopo) {
        this.tokens = tokens;
        this.contas = contas;
        this.sessoes = sessoes;
        this.codificador = codificador;
        this.email = email;
        this.escopo = escopo;
    }

    /**
     * Solicita o link de redefinição.
     *
     * <p>Não devolve nada e não lança quando o e-mail não tem conta. A resposta HTTP é
     * idêntica nos dois casos, de propósito: um formulário de "esqueci minha senha" que
     * responde diferente para e-mail conhecido vira um verificador de cadastro — qualquer
     * um descobre quem é cliente da plataforma.
     */
    public void solicitar(String emailInformado) {
        Email destinatario;
        try {
            destinatario = Email.de(emailInformado);
        } catch (IllegalArgumentException e) {
            return;
        }

        Optional<RepositorioDeConta.ContaLocalizada> conta = contas.localizarPorEmail(destinatario);
        if (conta.isEmpty()) {
            log.info("recuperação de senha solicitada para e-mail sem conta");
            return;
        }

        RepositorioDeConta.ContaLocalizada localizada = conta.get();
        String segredo = SegredoDeToken.gerar();
        TokenDeConta token = TokenDeConta.novo(
                localizada.tenantId(), localizada.usuarioId(),
                TokenDeConta.Finalidade.RECUPERACAO_SENHA);

        escopo.executarComo(localizada.tenantId(), () -> {
            // Pedir o link três vezes não pode deixar três links válidos em caixas de e-mail.
            tokens.invalidarPendentes(
                    localizada.usuarioId(), TokenDeConta.Finalidade.RECUPERACAO_SENHA);
            tokens.registrar(token, SegredoDeToken.hash(segredo));
        });

        email.enviarRecuperacaoDeSenha(destinatario.valor(), segredo);
    }

    /**
     * Redefine a senha e derruba todas as sessões do usuário.
     *
     * @throws TokenInvalidoException token desconhecido, expirado, já usado ou de outra
     *         finalidade.
     * @throws SenhaFracaException senha nova abaixo do mínimo.
     */
    public void redefinir(String segredo, String senhaNova) {
        if (senhaNova == null || senhaNova.isBlank() || senhaNova.length() < TAMANHO_MINIMO_DA_SENHA) {
            throw new SenhaFracaException(
                    "a senha deve ter ao menos %d caracteres".formatted(TAMANHO_MINIMO_DA_SENHA));
        }

        TokenDeConta token = tokens.buscarPorHash(SegredoDeToken.hash(segredo))
                .orElseThrow(TokenInvalidoException::new);

        if (token.finalidade() != TokenDeConta.Finalidade.RECUPERACAO_SENHA
                || !token.utilizavel(Instant.now())) {
            throw new TokenInvalidoException();
        }

        String hashNovo = codificador.codificar(senhaNova);

        boolean redefinida = escopo.executarComo(token.tenantId(), () -> {
            if (!tokens.consumir(token.id())) {
                return false;
            }
            contas.trocarSenha(token.usuarioId(), hashNovo);

            // Derrubar as sessões é parte da redefinição, não um extra. Quem troca a senha
            // normalmente o faz porque suspeita de acesso indevido; se as sessões de
            // renovação sobrevivessem, o invasor continuaria dentro por mais trinta dias
            // com a senha nova que ele nem precisa conhecer.
            int derrubadas = sessoes.revogarTodasDoUsuario(
                    token.usuarioId(), SessaoRefresh.MotivoDeRevogacao.TROCA_DE_SENHA);

            log.info("senha redefinida para usuário {}; {} sessões revogadas",
                    token.usuarioId(), derrubadas);
            return true;
        });

        if (!redefinida) {
            throw new TokenInvalidoException();
        }
    }
}
