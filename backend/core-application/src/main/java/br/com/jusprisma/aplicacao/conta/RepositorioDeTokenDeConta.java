package br.com.jusprisma.aplicacao.conta;

import br.com.jusprisma.dominio.conta.TokenDeConta;

import java.util.Optional;
import java.util.UUID;

public interface RepositorioDeTokenDeConta {

    void registrar(TokenDeConta token, byte[] hash);

    /**
     * Localiza um token pelo hash do segredo apresentado.
     *
     * <p>Atravessa a fronteira de tenant por necessidade: quem clica no link do e-mail não
     * está autenticado. Implementado sobre função {@code SECURITY DEFINER} estreita, que
     * recebe hash e devolve apenas identificadores — ver V4.
     */
    Optional<TokenDeConta> buscarPorHash(byte[] hash);

    /**
     * Marca o token como usado, apenas se ainda não estiver.
     *
     * @return true se esta chamada foi quem consumiu. Dois cliques no mesmo link fazem
     *         exatamente um vencer.
     */
    boolean consumir(UUID tokenId);

    /**
     * Invalida tokens pendentes da mesma finalidade para o usuário.
     *
     * <p>Chamado ao emitir um token novo: pedir "esqueci minha senha" três vezes não deve
     * deixar três links válidos circulando por caixas de e-mail.
     */
    int invalidarPendentes(UUID usuarioId, TokenDeConta.Finalidade finalidade);
}
