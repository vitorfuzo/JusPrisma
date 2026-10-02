package br.com.jusprisma.aplicacao.plano;

import br.com.jusprisma.dominio.plano.Assinatura;

import java.util.Optional;
import java.util.UUID;

public interface RepositorioDeAssinatura {

    void registrar(Assinatura assinatura);

    /** A assinatura que dá direito de uso agora, se houver. Sujeita a RLS. */
    Optional<Assinatura> vigenteDoTenant(UUID tenantId);

    void atualizarStatus(UUID assinaturaId, Assinatura.Status status);

    /**
     * Lê a assinatura vigente travando a linha até o fim da transação. Sujeita a RLS.
     *
     * <p>Serializa contratações simultâneas do mesmo escritório — o clique duplo no botão.
     */
    Optional<Assinatura> travarVigenteDoTenant(UUID tenantId);

    /** Registra os vínculos com o gateway e o plano escolhido, ainda pendente de pagamento. */
    void registrarContratacao(UUID assinaturaId, String clienteNoGateway, String assinaturaNoGateway,
                              String planoContratado, java.time.Instant proximaCobranca);
}
