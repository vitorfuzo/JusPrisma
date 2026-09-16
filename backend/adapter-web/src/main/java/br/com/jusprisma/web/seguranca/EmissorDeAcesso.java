package br.com.jusprisma.web.seguranca;

import br.com.jusprisma.dominio.conta.Papel;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Emite o token de acesso.
 *
 * <p>O token é curto por desenho. Ele carrega o tenant, e o tenant é o que decide o que a
 * requisição enxerga no banco — então um token vazado vale exatamente pelo tempo que falta
 * para expirar. A renovação é problema do refresh, que é revogável; o access não é.
 *
 * <p>Nada de estado de conta vai no token além do indispensável. Cota, plano e status de
 * assinatura mudam a qualquer momento e um token de 15 minutos os carregaria desatualizados.
 */
@Component
public class EmissorDeAcesso {

    public static final Duration VALIDADE = Duration.ofMinutes(15);

    private static final String EMISSOR = "jusprisma";
    static final String CLAIM_TENANT = "tenant";
    static final String CLAIM_PAPEL = "papel";

    private final JwtEncoder codificador;

    public EmissorDeAcesso(JwtEncoder codificador) {
        this.codificador = codificador;
    }

    public record TokenDeAcesso(String valor, Instant expiraEm, Duration validade) {
    }

    public TokenDeAcesso emitir(UUID usuarioId, UUID tenantId, Papel papel) {
        Instant agora = Instant.now();
        Instant expiraEm = agora.plus(VALIDADE);

        JwtClaimsSet reivindicacoes = JwtClaimsSet.builder()
                .issuer(EMISSOR)
                .issuedAt(agora)
                .expiresAt(expiraEm)
                .subject(usuarioId.toString())
                .claim(CLAIM_TENANT, tenantId.toString())
                .claim(CLAIM_PAPEL, papel.name())
                .build();

        String valor = codificador.encode(JwtEncoderParameters.from(
                JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),
                reivindicacoes)).getTokenValue();

        return new TokenDeAcesso(valor, expiraEm, VALIDADE);
    }
}
