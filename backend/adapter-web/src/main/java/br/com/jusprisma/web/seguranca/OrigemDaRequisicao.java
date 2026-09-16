package br.com.jusprisma.web.seguranca;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Descobre o IP de origem de uma requisição.
 *
 * <p>Parece trivial e não é. Atrás de um proxy reverso, o IP da conexão é o do proxy, e o
 * do cliente vem em {@code X-Forwarded-For}. Mas esse cabeçalho é texto que o cliente
 * envia: confiar nele sem um proxy na frente permite que qualquer um mande um valor
 * diferente a cada requisição e contorne inteiramente o limite por IP.
 *
 * <p>Daí a configuração ser explícita e vir desligada. Ligar sem proxy é pior que não ter
 * limite nenhum, porque dá a impressão de proteção.
 */
@Component
public class OrigemDaRequisicao {

    private static final String CABECALHO_PROXY = "X-Forwarded-For";

    private final boolean atrasDeProxy;

    public OrigemDaRequisicao(
            @Value("${jusprisma.rede.atras-de-proxy:false}") boolean atrasDeProxy) {
        this.atrasDeProxy = atrasDeProxy;
    }

    public String ipDe(HttpServletRequest requisicao) {
        if (atrasDeProxy) {
            String encaminhado = requisicao.getHeader(CABECALHO_PROXY);
            if (encaminhado != null && !encaminhado.isBlank()) {
                // O cabeçalho é uma lista "cliente, proxy1, proxy2". O primeiro elemento é
                // o cliente original — e é o único que interessa.
                return encaminhado.split(",")[0].trim();
            }
        }
        return requisicao.getRemoteAddr();
    }
}
