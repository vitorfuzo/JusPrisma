package br.com.jusprisma.aplicacao.plano;

import br.com.jusprisma.dominio.plano.Assinatura;

import java.util.Optional;
import java.util.UUID;

public interface RepositorioDeAssinatura {

    void registrar(Assinatura assinatura);

    /** A assinatura que dá direito de uso agora, se houver. Sujeita a RLS. */
    Optional<Assinatura> vigenteDoTenant(UUID tenantId);

    void atualizarStatus(UUID assinaturaId, Assinatura.Status status);
}
