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
    public static final String CLAIM_ESCOPO = "escopo";
    public static final String ESCOPO_ADMIN = "ADMIN";
    public static final String ESCOPO_TENANT = "TENANT";

    /**
     * Sessao administrativa mais curta que a de cliente.
     *
     * <p>Esta credencial abre todos os escritorios. Quinze minutos ja seriam defensaveis,
     * mas o operador nao tem token de renovacao: quando expira, ele faz login de novo. O
     * atrito e' aceitavel para quem acessa o painel algumas vezes por dia, e o ganho e' nao
     * existir credencial de acesso irrestrito circulando por horas.
     */
    public static final Duration VALIDADE_ADMINISTRATIVA = Duration.ofMinutes(30);

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
                .claim(CLAIM_ESCOPO, ESCOPO_TENANT)
                .build();

        String valor = codificador.encode(JwtEncoderParameters.from(
                JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),
                reivindicacoes)).getTokenValue();

        return new TokenDeAcesso(valor, expiraEm, VALIDADE);
    }

    /**
     * Token de operador da plataforma.
     *
     * <p>Sem claim de tenant, de proposito: operador nao pertence a escritorio nenhum, e um
     * token sem tenant nao passa pelas rotas de cliente nem consegue abrir o escopo de RLS.
     * A separacao e' por construcao, nao por verificacao adicional.
     */
    public TokenDeAcesso emitirParaAdministrador(UUID administradorId) {
        Instant agora = Instant.now();
        Instant expiraEm = agora.plus(VALIDADE_ADMINISTRATIVA);

        JwtClaimsSet reivindicacoes = JwtClaimsSet.builder()
                .issuer(EMISSOR)
                .issuedAt(agora)
                .expiresAt(expiraEm)
                .subject(administradorId.toString())
                .claim(CLAIM_ESCOPO, ESCOPO_ADMIN)
                .build();

        String valor = codificador.encode(JwtEncoderParameters.from(
                JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),
                reivindicacoes)).getTokenValue();

        return new TokenDeAcesso(valor, expiraEm, VALIDADE_ADMINISTRATIVA);
    }
}
