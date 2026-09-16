package br.com.jusprisma.ingestao.tjdft;

import br.com.jusprisma.dominio.decisao.ClassificadorDeDispositivo;
import br.com.jusprisma.dominio.decisao.ResultadoDoRecurso;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mede a cobertura do classificador contra dispositivos reais do TJDFT.
 *
 * <p>Os testes de unidade provam que as regras funcionam para as formas que eu conheço.
 * Este prova algo diferente e mais importante: que as formas que eu conheço são as que
 * realmente aparecem. Sem ele, o classificador pode estar perfeito para um vocabulário
 * imaginário.
 *
 * <p>Marcado como {@code smoke}: depende de rede e de uma API pública de terceiro, e por
 * isso fica fora do build padrão e do CI. Rode com {@code ./gradlew test -PcomSmoke}.
 *
 * <p>Ele também serve de alarme: se o TJDFT mudar o vocabulário do campo, a cobertura cai
 * e o teste falha antes de a estatística do produto ficar errada em silêncio.
 */
@Tag("smoke")
class CoberturaDoClassificadorSmokeTest {

    private static final String ENDERECO = "https://jurisdf.tjdft.jus.br/api/v1/pesquisa";

    /**
     * Máximo efetivo do TJDFT. Acima disso a resposta vem sem a chave {@code registros} —
     * ver docs/fontes-de-dados.md.
     */
    private static final int TAMANHO_DA_PAGINA = 40;
    private static final int PAGINAS = 5;

    /**
     * Piso de cobertura.
     *
     * <p>85% é o limite abaixo do qual a Camada 1 deixa de ser confiável: com mais de um
     * dispositivo em sete indeterminado, a amostra de cada recorte encolhe o bastante para
     * derrubar métricas abaixo do mínimo de 20 e distorcer as que sobrarem.
     */
    private static final double COBERTURA_MINIMA = 0.85;

    private static final Pattern CAMPO_DECISAO =
            Pattern.compile("\"decisao\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    @DisplayName("o classificador reconhece a grande maioria dos dispositivos reais")
    void cobreOsDispositivosReais() throws Exception {
        List<String> dispositivos = coletarDispositivos();

        assertThat(dispositivos)
                .as("sem amostra não há o que medir; a API respondeu vazio?")
                .hasSizeGreaterThan(100);

        Map<ResultadoDoRecurso, Integer> contagem = new EnumMap<>(ResultadoDoRecurso.class);
        List<String> naoReconhecidos = new ArrayList<>();

        for (String dispositivo : dispositivos) {
            ResultadoDoRecurso resultado = ClassificadorDeDispositivo.classificar(dispositivo);
            contagem.merge(resultado, 1, Integer::sum);
            if (resultado == ResultadoDoRecurso.INDETERMINADO) {
                naoReconhecidos.add(dispositivo);
            }
        }

        int indeterminados = contagem.getOrDefault(ResultadoDoRecurso.INDETERMINADO, 0);
        double cobertura = 1.0 - (double) indeterminados / dispositivos.size();

        System.out.printf("%n=== Cobertura do classificador: %.1f%% de %d dispositivos reais%n",
                cobertura * 100, dispositivos.size());
        contagem.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry<ResultadoDoRecurso, Integer>::getValue).reversed())
                .forEach(e -> System.out.printf("  %-26s %4d%n", e.getKey(), e.getValue()));

        if (!naoReconhecidos.isEmpty()) {
            System.out.println("\n=== Formas não reconhecidas (candidatas a regra nova):");
            naoReconhecidos.stream().distinct().limit(15)
                    .forEach(d -> System.out.println("  " + d));
        }

        assertThat(cobertura)
                .as("abaixo disso a Camada 1 deixa de ser confiável; as formas não "
                        + "reconhecidas estão listadas na saída")
                .isGreaterThanOrEqualTo(COBERTURA_MINIMA);
    }

    private static List<String> coletarDispositivos() throws Exception {
        HttpClient cliente = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        List<String> dispositivos = new ArrayList<>();
        for (int pagina = 0; pagina < PAGINAS; pagina++) {
            String corpo = """
                    {"query": "", "pagina": %d, "tamanho": %d}
                    """.formatted(pagina, TAMANHO_DA_PAGINA);

            HttpRequest requisicao = HttpRequest.newBuilder(URI.create(ENDERECO))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(corpo))
                    .build();

            HttpResponse<String> resposta =
                    cliente.send(requisicao, HttpResponse.BodyHandlers.ofString());

            if (resposta.statusCode() != 200) {
                break;
            }

            Matcher m = CAMPO_DECISAO.matcher(resposta.body());
            while (m.find()) {
                dispositivos.add(m.group(1));
            }

            // Espaçamento entre chamadas: API pública de tribunal, sem rate limit
            // publicado, e não queremos ser o motivo de ela ganhar um.
            Thread.sleep(600);
        }
        return dispositivos;
    }
}
