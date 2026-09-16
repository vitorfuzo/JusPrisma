package br.com.jusprisma.aplicacao.conta;

import br.com.jusprisma.dominio.conta.Convite;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepositorioDeConvite {

    void registrar(Convite convite, byte[] hash);

    /**
     * Localiza o convite pelo hash do segredo apresentado.
     *
     * <p>Atravessa a fronteira de tenant porque quem recebeu o convite ainda não tem conta.
     * Implementado sobre função {@code SECURITY DEFINER} estreita — ver V5.
     */
    Optional<Convite> buscarPorHash(byte[] hash);

    List<Convite> listarPendentes();

    /**
     * Marca o convite como aceito, apenas se ainda estiver pendente.
     *
     * @return true se esta chamada foi quem aceitou. Dois cliques no mesmo link fazem
     *         exatamente um vencer, o que impede criar dois usuários pelo mesmo convite.
     */
    boolean marcarAceito(UUID conviteId);

    boolean revogar(UUID conviteId);

    /** Revoga o convite pendente daquele e-mail, se houver, para que convidar de novo reenvie. */
    int revogarPendentePara(String email);
}
