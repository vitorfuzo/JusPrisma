package br.com.jusprisma.web.seguranca;

import br.com.jusprisma.dominio.conta.Papel;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * Leitura tipada do token da requisição corrente.
 *
 * <p>Existe para que nenhum controller precise saber o nome das claims nem lidar com
 * {@code Object} vindo do JWT. Um erro de digitação em {@code "tenant"} espalhado por
 * vários controllers seria uma falha de isolamento.
 */
public record UsuarioAutenticado(UUID usuarioId, UUID tenantId, Papel papel) {

    public static UsuarioAutenticado de(Authentication autenticacao) {
        if (autenticacao == null || !(autenticacao.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("requisição sem token válido");
        }
        return new UsuarioAutenticado(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString(EmissorDeAcesso.CLAIM_TENANT)),
                Papel.valueOf(jwt.getClaimAsString(EmissorDeAcesso.CLAIM_PAPEL)));
    }
}
