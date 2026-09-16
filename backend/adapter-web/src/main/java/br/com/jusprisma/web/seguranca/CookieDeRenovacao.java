package br.com.jusprisma.web.seguranca;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Entrega e recolhe o token de renovação por cookie.
 *
 * <p>Cookie, e não corpo da resposta, porque o token de renovação é o alvo mais valioso da
 * aplicação: com ele se emite acesso por trinta dias. Em {@code localStorage} ele fica ao
 * alcance de qualquer script injetado na página; marcado como {@code HttpOnly}, o JavaScript
 * não o enxerga nem para vazá-lo.
 *
 * <p>A escolha traria risco de CSRF, e é por isso que ele vem com {@code SameSite=Strict}
 * <em>e</em> com {@code Path} restrito às rotas de sessão. O navegador não o anexa a
 * requisição vinda de outro site, e nem sequer o envia para o resto da API — de forma que a
 * superfície de CSRF fica limitada às duas rotas que o consomem, ambas já protegidas pelo
 * SameSite.
 *
 * <p>O token de acesso, esse sim, vai no corpo: é curto, não é revogável e o cliente precisa
 * lê-lo para montar o cabeçalho Authorization.
 */
@Component
public class CookieDeRenovacao {

    public static final String NOME = "jusprisma_renovacao";
    private static final String CAMINHO = "/api/v1/sessoes";

    private final boolean exigirHttps;

    public CookieDeRenovacao(@Value("${jusprisma.cookie.seguro:true}") boolean exigirHttps) {
        this.exigirHttps = exigirHttps;
    }

    public void anexar(HttpHeaders cabecalhos, String token, Duration validade) {
        cabecalhos.add(HttpHeaders.SET_COOKIE, construir(token, validade).toString());
    }

    /** Apaga o cookie: mesmo nome, mesmo caminho, validade zero. */
    public void limpar(HttpHeaders cabecalhos) {
        cabecalhos.add(HttpHeaders.SET_COOKIE, construir("", Duration.ZERO).toString());
    }

    public Optional<String> ler(HttpServletRequest requisicao) {
        Cookie[] cookies = requisicao.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> NOME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(valor -> valor != null && !valor.isBlank())
                .findFirst();
    }

    private ResponseCookie construir(String token, Duration validade) {
        return ResponseCookie.from(NOME, token)
                .httpOnly(true)
                // Em desenvolvimento o navegador recusaria um cookie Secure sobre http.
                // Fora de dev isto é sempre true, e o .env.example não expõe a chave.
                .secure(exigirHttps)
                .sameSite("Strict")
                .path(CAMINHO)
                .maxAge(validade)
                .build();
    }
}
