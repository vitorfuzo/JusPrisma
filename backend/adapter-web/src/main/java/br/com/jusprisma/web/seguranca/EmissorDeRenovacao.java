package br.com.jusprisma.web.seguranca;

import br.com.jusprisma.aplicacao.conta.SessaoInvalidaException;
import br.com.jusprisma.aplicacao.conta.SessoesDeAcesso;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Emite e interpreta o token de renovação.
 *
 * <p>O token é um JWT assinado, e não um valor opaco, por um motivo concreto: ele carrega o
 * tenant. Sem isso, encontrar a sessão exigiria consultar a tabela antes de saber o tenant —
 * e sob Row Level Security essa consulta não acha nada, o que obrigaria a mais uma função
 * {@code SECURITY DEFINER} atravessando a fronteira de isolamento. Carregar o tenant no
 * token assinado mantém a renovação inteiramente dentro do escopo do tenant.
 *
 * <p>Ele não é uma credencial de acesso: nada aqui autoriza requisição nenhuma. Só serve
 * para localizar a sessão, que é verificada contra o banco a cada uso e é revogável.
 */
@Component
public class EmissorDeRenovacao {

    private static final String EMISSOR = "jusprisma";
    private static final String TIPO = "renovacao";
    private static final String CLAIM_TIPO = "tipo";
    private static final String CLAIM_TENANT = "tenant";
    private static final String CLAIM_FAMILIA = "familia";

    private final JwtEncoder codificador;
    private final JwtDecoder validador;

    public EmissorDeRenovacao(JwtEncoder codificador, JwtDecoder validador) {
        this.codificador = codificador;
        this.validador = validador;
    }

    public record TokenDeRenovacao(String valor, Instant expiraEm) {
    }

    public record Vinculos(UUID sessaoId, UUID usuarioId, UUID tenantId, UUID familiaId) {
    }

    public TokenDeRenovacao emitir(UUID sessaoId, UUID usuarioId, UUID tenantId,
                                   UUID familiaId, Instant expiraEm) {
        JwtClaimsSet reivindicacoes = JwtClaimsSet.builder()
                .issuer(EMISSOR)
                .issuedAt(Instant.now())
                .expiresAt(expiraEm)
                .subject(usuarioId.toString())
                .id(sessaoId.toString())
                .claim(CLAIM_TIPO, TIPO)
                .claim(CLAIM_TENANT, tenantId.toString())
                .claim(CLAIM_FAMILIA, familiaId.toString())
                .build();

        String valor = codificador.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), reivindicacoes)).getTokenValue();

        return new TokenDeRenovacao(valor, expiraEm);
    }

    /**
     * Valida assinatura e expiração e extrai os vínculos do token.
     *
     * @throws SessaoInvalidaException para qualquer problema, sem distinguir os casos.
     */
    public Vinculos interpretar(String token) {
        if (token == null || token.isBlank()) {
            throw new SessaoInvalidaException();
        }
        try {
            Jwt jwt = validador.decode(token);

            // Sem esta checagem, um token de acesso — que é assinado com a mesma chave —
            // seria aceito como token de renovação. Ele não tem jti nem família, mas a
            // confusão de tipos é uma classe de falha que não se deve deixar em aberto.
            if (!TIPO.equals(jwt.getClaimAsString(CLAIM_TIPO))) {
                throw new SessaoInvalidaException();
            }

            return new Vinculos(
                    UUID.fromString(jwt.getId()),
                    UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString(CLAIM_TENANT)),
                    UUID.fromString(jwt.getClaimAsString(CLAIM_FAMILIA)));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            throw new SessaoInvalidaException();
        }
    }

    public java.time.Duration validade() {
        return SessoesDeAcesso.VALIDADE;
    }
}
