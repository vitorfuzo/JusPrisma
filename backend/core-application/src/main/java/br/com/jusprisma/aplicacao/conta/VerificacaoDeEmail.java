package br.com.jusprisma.aplicacao.conta;

import br.com.jusprisma.aplicacao.porta.EnviadorDeEmail;
import br.com.jusprisma.aplicacao.porta.EscopoDeTenant;
import br.com.jusprisma.dominio.conta.Email;
import br.com.jusprisma.dominio.conta.TokenDeConta;
import br.com.jusprisma.dominio.conta.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Emite e confirma o link de verificação de endereço de e-mail.
 */
@Service
public class VerificacaoDeEmail {

    private static final Logger log = LoggerFactory.getLogger(VerificacaoDeEmail.class);

    private final RepositorioDeTokenDeConta tokens;
    private final RepositorioDeConta contas;
    private final EnviadorDeEmail email;
    private final EscopoDeTenant escopo;

    public VerificacaoDeEmail(RepositorioDeTokenDeConta tokens,
                              RepositorioDeConta contas,
                              EnviadorDeEmail email,
                              EscopoDeTenant escopo) {
        this.tokens = tokens;
        this.contas = contas;
        this.email = email;
        this.escopo = escopo;
    }

    /** Emite um link novo e o envia, invalidando os pendentes do mesmo usuário. */
    public void enviar(UUID tenantId, UUID usuarioId, Email destinatario) {
        String segredo = SegredoDeToken.gerar();
        TokenDeConta token = TokenDeConta.novo(
                tenantId, usuarioId, TokenDeConta.Finalidade.VERIFICACAO_EMAIL);

        escopo.executarComo(tenantId, () -> {
            tokens.invalidarPendentes(usuarioId, TokenDeConta.Finalidade.VERIFICACAO_EMAIL);
            tokens.registrar(token, SegredoDeToken.hash(segredo));
        });

        // Enviado depois do commit. Enviar dentro da transação arriscaria o e-mail sair e a
        // transação ser desfeita em seguida, deixando circulando um link que não existe.
        email.enviarVerificacaoDeEmail(destinatario.valor(), segredo);
    }

    /**
     * Confirma o endereço a partir do segredo apresentado no link.
     *
     * @throws TokenInvalidoException para token desconhecido, expirado, já usado ou de
     *         outra finalidade — sem distinguir os casos.
     */
    public void confirmar(String segredo) {
        TokenDeConta token = tokens.buscarPorHash(SegredoDeToken.hash(segredo))
                .orElseThrow(TokenInvalidoException::new);

        // A finalidade é conferida porque as duas espécies de token vivem na mesma tabela.
        // Sem esta checagem, um link de recuperação de senha serviria para verificar e-mail.
        if (token.finalidade() != TokenDeConta.Finalidade.VERIFICACAO_EMAIL
                || !token.utilizavel(Instant.now())) {
            throw new TokenInvalidoException();
        }

        boolean confirmado = escopo.executarComo(token.tenantId(), () -> {
            if (!tokens.consumir(token.id())) {
                return false;
            }
            contas.marcarEmailVerificado(token.usuarioId());
            return true;
        });

        if (!confirmado) {
            throw new TokenInvalidoException();
        }
        log.info("e-mail verificado para usuário {}", token.usuarioId());
    }

    /** Reenvia para um usuário já autenticado que não recebeu ou deixou expirar. */
    public void reenviar(UUID tenantId, UUID usuarioId) {
        Usuario usuario = escopo.executarComo(tenantId,
                () -> contas.buscarPorId(usuarioId).orElse(null));

        if (usuario == null || usuario.emailVerificado()) {
            // Nada a fazer, e nada a informar: quem já verificou não precisa de link novo.
            return;
        }
        enviar(tenantId, usuarioId, usuario.email());
    }
}
