package br.com.jusprisma.dominio.conta;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Pessoa que acessa a plataforma, sempre vinculada a um tenant.
 *
 * <p>A senha não mora aqui. O domínio trata de identidade e papel; o material secreto fica
 * restrito ao fluxo de autenticação, para que uma entidade de uso corriqueiro não carregue
 * hash de senha por toda a aplicação e acabe em log, em resposta HTTP ou em mensagem de
 * erro.
 */
public record Usuario(
        UUID id,
        UUID tenantId,
        Email email,
        Papel papel,
        String oab,
        String ufOab,
        Instant emailVerificadoEm,
        Instant criadoEm) {

    public Usuario {
        if (id == null) {
            throw new IllegalArgumentException("id do usuário é obrigatório");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("usuário sem tenant não existe");
        }
        if (email == null) {
            throw new IllegalArgumentException("e-mail é obrigatório");
        }
        if (papel == null) {
            throw new IllegalArgumentException("papel é obrigatório");
        }
        if (ufOab != null && !ufOab.isBlank() && !ufOab.matches("^[A-Za-z]{2}$")) {
            throw new IllegalArgumentException("UF da OAB deve ter duas letras: " + ufOab);
        }
        ufOab = ufOab == null || ufOab.isBlank() ? null : ufOab.toUpperCase(java.util.Locale.ROOT);
    }

    /** Cria o primeiro usuário de um tenant recém-criado, que é sempre o dono. */
    public static Usuario donoDaConta(UUID tenantId, Email email, String oab, String ufOab) {
        return new Usuario(
                UUID.randomUUID(), tenantId, email, Papel.OWNER, oab, ufOab, null, Instant.now());
    }

    public boolean emailVerificado() {
        return emailVerificadoEm != null;
    }

    public Optional<String> inscricaoOab() {
        if (oab == null || oab.isBlank() || ufOab == null) {
            return Optional.empty();
        }
        return Optional.of("%s/%s".formatted(oab, ufOab));
    }
}
