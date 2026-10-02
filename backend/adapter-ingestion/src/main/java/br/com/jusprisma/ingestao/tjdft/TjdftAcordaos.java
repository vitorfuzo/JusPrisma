package br.com.jusprisma.ingestao.tjdft;

import br.com.jusprisma.aplicacao.ingestao.FonteDeAcordaos;
import br.com.jusprisma.aplicacao.ingestao.FonteIndisponivelException;
import br.com.jusprisma.aplicacao.ingestao.RespostaInesperadaDaFonteException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptador da pesquisa de jurisprudência do TJDFT.
 *
 * <p>O contrato verificado está em {@code docs/fontes-de-dados.md}. Três pontos dele moldam
 * este código: o {@code tamanho} acima de 40 devolve 200 sem {@code registros}; o inteiro
 * teor vem indisponível, então nem é lido; e o sigilo vem marcado por registro.
 */
@Component
public class TjdftAcordaos implements FonteDeAcordaos {

    private static final Logger log = LoggerFactory.getLogger(TjdftAcordaos.class);

    /** Máximo efetivo da API. Acima disso a resposta vem vazia sem aviso. */
    static final int TAMANHO_MAXIMO = 40;

    private static final ZoneId FUSO_DO_TRIBUNAL = ZoneId.of("America/Sao_Paulo");
    private static final Duration TEMPO_LIMITE = Duration.ofSeconds(30);

    private final WebClient cliente;

    public TjdftAcordaos(WebClient.Builder construtor,
                         @Value("${jusprisma.tjdft.url-base}") String urlBase) {
        this.cliente = construtor
                .baseUrl(urlBase)
                // Uma página de 40 ementas passa de 300 KB; o limite padrão de 256 KB do
                // WebClient cortaria a resposta no meio.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    @Override
    public String tribunal() {
        return "TJDFT";
    }

    @Override
    @Retry(name = "tjdft")
    @CircuitBreaker(name = "tjdft", fallbackMethod = "circuitoAberto")
    @RateLimiter(name = "tjdft")
    public PaginaDeAcordaos buscar(ConsultaDeAcordaos consulta) {
        if (consulta.tamanho() > TAMANHO_MAXIMO) {
            // Repassar seria pior que recusar: a API responderia 200 sem registros, e a
            // ingestão leria "este relator não tem acórdãos".
            throw new IllegalArgumentException(
                    "o TJDFT aceita no máximo %d registros por página".formatted(TAMANHO_MAXIMO));
        }

        JsonNode resposta = chamar(corpo(consulta));
        return interpretar(resposta, consulta.pagina());
    }

    @SuppressWarnings("unused")
    private PaginaDeAcordaos circuitoAberto(ConsultaDeAcordaos consulta, CallNotPermittedException e) {
        throw new FonteIndisponivelException("TJDFT em pausa após falhas seguidas", e);
    }

    private static Map<String, Object> corpo(ConsultaDeAcordaos consulta) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("query", consulta.termo());
        corpo.put("pagina", consulta.pagina());
        corpo.put("tamanho", consulta.tamanho());
        if (consulta.relator() != null && !consulta.relator().isBlank()) {
            corpo.put("termosAcessorios",
                    List.of(Map.of("campo", "nomeRelator", "valor", consulta.relator())));
        }
        return corpo;
    }

    private JsonNode chamar(Map<String, Object> corpo) {
        try {
            return cliente.post()
                    .uri("/api/v1/pesquisa")
                    .bodyValue(corpo)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(TEMPO_LIMITE);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                // 4xx é a nossa requisição fora do contrato: repetir não muda nada.
                throw new RespostaInesperadaDaFonteException(
                        "TJDFT recusou a consulta com " + e.getStatusCode().value());
            }
            throw new FonteIndisponivelException("TJDFT respondeu " + e.getStatusCode().value(), e);
        } catch (WebClientRequestException e) {
            throw new FonteIndisponivelException("TJDFT inacessível", e);
        } catch (IllegalStateException e) {
            // block(Duration) sinaliza o tempo esgotado com IllegalStateException.
            throw new FonteIndisponivelException("TJDFT não respondeu a tempo", e);
        }
    }

    static PaginaDeAcordaos interpretar(JsonNode resposta, int pagina) {
        if (resposta == null || !resposta.path("registros").isArray()) {
            // É a falha silenciosa documentada: 200 OK sem a chave. Lista vazia de verdade
            // vem como "registros": [].
            throw new RespostaInesperadaDaFonteException("resposta do TJDFT sem a lista de registros");
        }

        List<AcordaoDaFonte> acordaos = new ArrayList<>();
        int sigilosos = 0;
        for (JsonNode registro : resposta.path("registros")) {
            // Sem a marca, não dá para afirmar que é público. Na dúvida, não entra.
            if (!registro.path("segredoJustica").isBoolean()
                    || registro.path("segredoJustica").asBoolean()) {
                sigilosos++;
                continue;
            }
            acordaos.add(acordao(registro));
        }

        if (sigilosos > 0) {
            log.info("{} acórdão(s) sob segredo de justiça descartados na página {}", sigilosos, pagina);
        }
        return new PaginaDeAcordaos(resposta.path("hits").path("value").asLong(0), pagina,
                List.copyOf(acordaos), sigilosos);
    }

    private static AcordaoDaFonte acordao(JsonNode r) {
        return new AcordaoDaFonte(
                exigir(r, "identificador"),
                exigir(r, "processo"),
                texto(r, "nomeRelator"),
                r.path("relatorAtivo").asBoolean(false),
                texto(r, "descricaoOrgaoJulgador"),
                r.path("codigoClasseCnj").isNumber() ? r.path("codigoClasseCnj").asInt() : null,
                texto(r, "ementa"),
                texto(r, "decisao"),
                dataCivil(texto(r, "dataJulgamento")),
                instante(texto(r, "dataPublicacao")));
    }

    private static String exigir(JsonNode registro, String campo) {
        String valor = texto(registro, campo);
        if (valor == null) {
            throw new RespostaInesperadaDaFonteException("registro do TJDFT sem '%s'".formatted(campo));
        }
        return valor;
    }

    private static String texto(JsonNode registro, String campo) {
        JsonNode no = registro.path(campo);
        if (no.isMissingNode() || no.isNull()) {
            return null;
        }
        String valor = no.asString();
        return valor == null || valor.isBlank() ? null : valor;
    }

    /**
     * O julgamento vem como meia-noite de Brasília escrita em UTC ({@code ...T03:00:00Z}).
     * Ler a data direto do texto daria o dia certo hoje e o errado no dia em que o TJDFT
     * mudar o horário gravado; convertendo pelo fuso, o dia civil não depende disso.
     */
    private static LocalDate dataCivil(String iso) {
        Instant instante = instante(iso);
        return instante == null ? null : instante.atZone(FUSO_DO_TRIBUNAL).toLocalDate();
    }

    private static Instant instante(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            throw new RespostaInesperadaDaFonteException("data fora do formato ISO-8601 no TJDFT");
        }
    }
}
