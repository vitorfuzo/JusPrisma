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
     * A assinatura corrente: a vigente, ou a que aguarda o primeiro pagamento de uma
     * recontratação. No máximo uma por escritório. Sujeita a RLS.
     *
     * <p>Não responde por acesso — para isso, {@link #vigenteDoTenant}.
     */
    Optional<Assinatura> correnteDoTenant(UUID tenantId);

    /**
     * Lê a assinatura corrente travando a linha até o fim da transação. Sujeita a RLS.
     *
     * <p>Serializa contratações simultâneas do mesmo escritório — o clique duplo no botão.
     */
    Optional<Assinatura> travarCorrenteDoTenant(UUID tenantId);

    /**
     * Registra a assinatura se o escritório não tiver uma corrente; se tiver, não faz nada.
     * Duas chamadas simultâneas criam uma só — a unicidade é do banco.
     */
    void registrarSeNaoHaCorrente(Assinatura assinatura);

    /** Registra os vínculos com o gateway e o plano escolhido, ainda pendente de pagamento. */
    void registrarContratacao(UUID assinaturaId, String clienteNoGateway, String assinaturaNoGateway,
                              String planoContratado, java.time.Instant proximaCobranca);
}
