package br.com.jusprisma.ingestao.datajud;

import br.com.jusprisma.aplicacao.ingestao.FonteDeMetadadosProcessuais;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Adaptador da API pública do DataJud (CNJ).
 *
 * <p>O contrato verificado está em {@code docs/fontes-de-dados.md}. O que molda este código:
 * número sem máscara; um registro por grau; data de ajuizamento em {@code yyyyMMddHHmmss};
 * órgão julgador com acentuação corrompida na origem (por isso nem é lido); e um cluster que,
 * sobrecarregado, responde 200 com shards falhos — resultado parcial com cara de completo.
 */
@Component
public class DataJudMetadados implements FonteDeMetadadosProcessuais {

    private static final Logger log = LoggerFactory.getLogger(DataJudMetadados.class);

    /** Uma página de acórdãos do TJDFT cabe com folga; lote maior só alonga a consulta lenta. */
    static final int LOTE_MAXIMO = 100;

    /** Um processo raramente tem mais de dois registros (G1 e G2); a folga cobre os demais graus. */
    private static final int REGISTROS_POR_PROCESSO = 4;

    /**
     * Consultas legítimas levam dezenas de segundos, e o proxy do CNJ corta em 60 s com 504.
     * Esperar além disso não traz resposta.
     */
    private static final Duration TEMPO_LIMITE = Duration.ofSeconds(75);

    private static final DateTimeFormatter AJUIZAMENTO =
            DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);

    /** Movimentos ficam de fora: são a maior parte do documento e ninguém os consome ainda. */
    private static final List<String> CAMPOS = List.of(
            "numeroProcesso", "grau", "classe", "assuntos", "dataAjuizamento", "nivelSigilo");

    private final WebClient cliente;

    public DataJudMetadados(WebClient.Builder construtor,
                            @Value("${jusprisma.datajud.url-base}") String urlBase,
                            @Value("${jusprisma.datajud.chave}") String chave) {
        this.cliente = construtor
                .baseUrl(urlBase)
                // "APIKey", não "Bearer": com Bearer a API responde 401.
                .defaultHeader("Authorization", "APIKey " + chave)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    @Override
    @Retry(name = "datajud")
    @CircuitBreaker(name = "datajud", fallbackMethod = "circuitoAberto")
    @RateLimiter(name = "datajud")
    public LoteDeMetadados buscar(String tribunal, Collection<String> numeros, Grau grau) {
        String indice = indice(tribunal);
        Set<String> semMascara = new LinkedHashSet<>();
        for (String numero : numeros) {
            semMascara.add(FonteDeMetadadosProcessuais.numeroSemMascara(numero));
        }
        if (semMascara.isEmpty()) {
            return new LoteDeMetadados(List.of(), 0);
        }
        if (semMascara.size() > LOTE_MAXIMO) {
            throw new IllegalArgumentException(
                    "o lote do DataJud aceita no máximo %d processos".formatted(LOTE_MAXIMO));
        }

        Map<String, Object> corpo = Map.of(
                "size", semMascara.size() * REGISTROS_POR_PROCESSO,
                "_source", CAMPOS,
                "query", Map.of("terms", Map.of("numeroProcesso", List.copyOf(semMascara))));

        return interpretar(chamar(indice, corpo), grau, semMascara);
    }

    @SuppressWarnings("unused")
    private LoteDeMetadados circuitoAberto(String tribunal, Collection<String> numeros, Grau grau,
                                           CallNotPermittedException e) {
        throw new FonteIndisponivelException("DataJud em pausa após falhas seguidas", e);
    }

    private static String indice(String tribunal) {
        // A sigla vira parte do caminho; só letras e dígitos evitam montar outra rota.
        if (tribunal == null || !tribunal.matches("[A-Za-z0-9]{2,10}")) {
            throw new IllegalArgumentException("sigla de tribunal inválida");
        }
        return "api_publica_" + tribunal.toLowerCase(Locale.ROOT);
    }

    private JsonNode chamar(String indice, Map<String, Object> corpo) {
        try {
            return cliente.post()
                    .uri("/{indice}/_search", indice)
                    .bodyValue(corpo)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(TEMPO_LIMITE);
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                // A chave é pública e o CNJ a troca sem aviso. Repetir não adianta.
                log.error("DataJud recusou a chave pública ({}); confira o wiki do DataJud", status);
                throw new RespostaInesperadaDaFonteException("DataJud recusou a chave de acesso");
            }
            if (e.getStatusCode().is4xxClientError()) {
                throw new RespostaInesperadaDaFonteException("DataJud recusou a consulta com " + status);
            }
            throw new FonteIndisponivelException("DataJud respondeu " + status, e);
        } catch (WebClientRequestException e) {
            throw new FonteIndisponivelException("DataJud inacessível", e);
        } catch (IllegalStateException e) {
            // block(Duration) sinaliza o tempo esgotado com IllegalStateException.
            throw new FonteIndisponivelException("DataJud não respondeu a tempo", e);
        }
    }

    static LoteDeMetadados interpretar(JsonNode resposta, Grau grau, Set<String> pedidos) {
        if (resposta == null || !resposta.path("hits").path("hits").isArray()) {
            throw new RespostaInesperadaDaFonteException("resposta do DataJud sem hits.hits");
        }
        // Com o cluster sobrecarregado, parte dos shards rejeita a busca e a resposta vem
        // 200 com o que sobrou. Aceitar seria dizer "este processo não existe" sem saber.
        if (resposta.path("timed_out").asBoolean(false)
                || resposta.path("_shards").path("failed").asInt(0) > 0) {
            throw new FonteIndisponivelException("DataJud respondeu com resultado parcial", null);
        }

        List<MetadadosDoProcesso> processos = new ArrayList<>();
        int sigilosos = 0;
        for (JsonNode hit : resposta.path("hits").path("hits")) {
            JsonNode fonte = hit.path("_source");
            if (!grau.name().equals(fonte.path("grau").asString())) {
                continue;
            }
            String numero = fonte.path("numeroProcesso").asString();
            if (numero == null || !pedidos.contains(numero)) {
                // terms em campo analisado pode trazer vizinho; só entra o que foi pedido.
                continue;
            }
            // Sem o nível, não dá para afirmar que é público. Na dúvida, não entra.
            if (!fonte.path("nivelSigilo").isNumber() || fonte.path("nivelSigilo").asInt() > 0) {
                sigilosos++;
                continue;
            }
            processos.add(metadados(fonte, numero, grau));
        }

        if (sigilosos > 0) {
            log.info("{} processo(s) com sigilo descartados do lote do DataJud", sigilosos);
        }
        return new LoteDeMetadados(List.copyOf(processos), sigilosos);
    }

    private static MetadadosDoProcesso metadados(JsonNode fonte, String numero, Grau grau) {
        List<AssuntoCnj> assuntos = new ArrayList<>();
        for (JsonNode assunto : fonte.path("assuntos")) {
            if (assunto.path("codigo").isNumber()) {
                assuntos.add(new AssuntoCnj(assunto.path("codigo").asInt(), texto(assunto, "nome")));
            }
        }
        JsonNode classe = fonte.path("classe");
        return new MetadadosDoProcesso(
                numero,
                grau,
                classe.path("codigo").isNumber() ? classe.path("codigo").asInt() : null,
                texto(classe, "nome"),
                List.copyOf(assuntos),
                ajuizamento(texto(fonte, "dataAjuizamento")));
    }

    /**
     * {@code yyyyMMddHHmmss}, não ISO-8601. Estrito de propósito: um parser leniente
     * transformaria "20231345..." numa data plausível e errada.
     */
    static LocalDateTime ajuizamento(String valor) {
        if (valor == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(valor, AJUIZAMENTO);
        } catch (DateTimeParseException e) {
            throw new RespostaInesperadaDaFonteException("dataAjuizamento fora do formato yyyyMMddHHmmss");
        }
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.path(campo);
        if (valor.isMissingNode() || valor.isNull()) {
            return null;
        }
        String texto = valor.asString();
        return texto == null || texto.isBlank() ? null : texto;
    }
}
