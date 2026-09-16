package br.com.jusprisma.web.observabilidade;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Dá a cada requisição um identificador que aparece em toda linha de log dela.
 *
 * <p>Sem isso, investigar um erro em produção significa correlacionar por horário — e com
 * requisições concorrentes de escritórios diferentes, as linhas se intercalam e a
 * reconstrução vira adivinhação. Com o identificador, uma consulta recupera a requisição
 * inteira, do recebimento ao erro.
 *
 * <p>O valor também volta no cabeçalho da resposta e entra no corpo de erro, para que o
 * usuário possa citá-lo no suporte: é a diferença entre "deu erro ontem à tarde" e um
 * ponteiro exato.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelacaoDeRequisicao extends OncePerRequestFilter {

    public static final String CABECALHO = "X-Correlation-Id";
    public static final String CHAVE_MDC = "correlacaoId";

    /**
     * Aceitamos um identificador vindo do cliente para permitir rastrear uma chamada
     * através de vários serviços. Mas ele entra em log, então é validado antes: um valor
     * arbitrário do cliente num log estruturado é vetor de injeção de log e de poluição de
     * índice. Fora do formato, geramos o nosso.
     */
    private static final Pattern FORMATO_ACEITO = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao,
                                    HttpServletResponse resposta,
                                    FilterChain cadeia) throws ServletException, IOException {
        String correlacao = identificarOuGerar(requisicao.getHeader(CABECALHO));

        MDC.put(CHAVE_MDC, correlacao);
        resposta.setHeader(CABECALHO, correlacao);
        try {
            cadeia.doFilter(requisicao, resposta);
        } finally {
            // Obrigatório: threads são reaproveitadas por pool, e um MDC não limpo faria a
            // próxima requisição logar com o identificador da anterior.
            MDC.remove(CHAVE_MDC);
        }
    }

    private static String identificarOuGerar(String informado) {
        if (informado != null && FORMATO_ACEITO.matcher(informado).matches()) {
            return informado;
        }
        return UUID.randomUUID().toString();
    }

    /** O identificador da requisição corrente, para quem precisa incluí-lo numa resposta. */
    public static String atual() {
        return MDC.get(CHAVE_MDC);
    }
}
