package br.com.jusprisma.aplicacao.conta;

import br.com.jusprisma.dominio.conta.Papel;

import java.util.UUID;

/**
 * O mínimo necessário para decidir se um login procede.
 *
 * <p>Tipo separado de {@code Usuario} de propósito: carrega o hash da senha e portanto não
 * pode circular pela aplicação nem virar resposta HTTP. Vive apenas entre o repositório e o
 * caso de uso de autenticação.
 */
public record CredenciaisDeAcesso(
        UUID usuarioId,
        UUID tenantId,
        String senhaHash,
        Papel papel,
        boolean emailVerificado,
        boolean tenantOperacional) {

    /** Evita que o hash escape por log ou por mensagem de erro que imprima o objeto. */
    @Override
    public String toString() {
        return "CredenciaisDeAcesso[usuarioId=%s, tenantId=%s, papel=%s]"
                .formatted(usuarioId, tenantId, papel);
    }
}
