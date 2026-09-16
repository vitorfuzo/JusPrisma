package br.com.jusprisma.config;

import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Configuration
public class ConfiguracaoDeSeguranca {

    /** Um HS256 com chave curta é quebrável por força bruta; abaixo disso recusamos subir. */
    private static final int BYTES_MINIMOS_DA_CHAVE = 32;

    private final byte[] chave;

    public ConfiguracaoDeSeguranca(@Value("${jusprisma.jwt.segredo}") String segredo) {
        this.chave = decodificar(segredo);
        if (chave.length < BYTES_MINIMOS_DA_CHAVE) {
            throw new IllegalStateException(
                    "JUSPRISMA_JWT_SECRET precisa de ao menos %d bytes; gere com: openssl rand -base64 64"
                            .formatted(BYTES_MINIMOS_DA_CHAVE));
        }
    }

    @Bean
    public SecurityFilterChain cadeiaDeFiltros(HttpSecurity http) throws Exception {
        http
                // API sem sessão e sem cookie de autenticação: não há o que forjar por CSRF.
                // Quando o refresh entrar por cookie httpOnly, este ponto muda e a proteção
                // volta especificamente para a rota de refresh.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(rotas -> rotas
                        .requestMatchers(HttpMethod.POST, "/api/v1/contas", "/api/v1/sessoes").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));

        return http.build();
    }

    @Bean
    public PasswordEncoder codificadorDeSenha() {
        // Custo 12: cerca de 250ms por conferência em hardware atual. É o ponto em que
        // ataque offline fica caro sem que o login fique perceptivelmente lento.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public JwtEncoder emissorDeJwt() {
        var jwk = new OctetSequenceKey.Builder(chave).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    @Bean
    public JwtDecoder validadorDeJwt() {
        return NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(chave, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Aceita a chave em base64 ou em texto puro. O {@code .env.example} sugere
     * {@code openssl rand -base64 64}, que produz base64.
     */
    private static byte[] decodificar(String segredo) {
        if (segredo == null || segredo.isBlank()) {
            throw new IllegalStateException(
                    "JUSPRISMA_JWT_SECRET não definido; gere com: openssl rand -base64 64");
        }
        try {
            return Base64.getDecoder().decode(segredo.trim());
        } catch (IllegalArgumentException naoEhBase64) {
            return segredo.getBytes(StandardCharsets.UTF_8);
        }
    }
}
