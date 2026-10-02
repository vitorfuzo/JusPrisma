package br.com.jusprisma.ingestao.datajud;

import br.com.jusprisma.aplicacao.ingestao.FonteDeAcordaos.AcordaoDaFonte;
import br.com.jusprisma.aplicacao.ingestao.FonteDeAcordaos.ConsultaDeAcordaos;
import br.com.jusprisma.aplicacao.ingestao.FonteDeMetadadosProcessuais;
import br.com.jusprisma.aplicacao.ingestao.FonteDeMetadadosProcessuais.Grau;
import br.com.jusprisma.aplicacao.ingestao.FonteDeMetadadosProcessuais.LoteDeMetadados;
import br.com.jusprisma.ingestao.tjdft.TjdftAcordaos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A junção real entre as duas fontes: uma página de acórdãos do TJDFT enriquecida com o
 * assunto CNJ do DataJud.
 *
 * <p>Sem número de processo no código: os números vêm da própria busca no TJDFT. Se a junção
 * pelos 20 dígitos parar de casar, é aqui que aparece.
 *
 * <p>Depende de rede e de duas APIs de terceiro, uma delas lenta: fica fora do build padrão.
 * Rode com {@code ./gradlew test -PcomSmoke}.
 */
@Tag("smoke")
class JuncaoTjdftDataJudSmokeTest {

    /**
     * Piso de casamento. Acórdão publicado há poucos dias pode ainda não estar no índice do
     * CNJ, então 100% não é esperado; abaixo da metade, a junção está quebrada.
     */
    private static final double CASAMENTO_MINIMO = 0.5;

    @Test
    @DisplayName("a maioria dos processos de uma página do TJDFT é encontrada no DataJud, com assunto")
    void juncaoReal() throws Exception {
        List<String> numeros = new TjdftAcordaos(WebClient.builder(), "https://jurisdf.tjdft.jus.br")
                .buscar(new ConsultaDeAcordaos("dano moral", null, 0, 40))
                .acordaos().stream().map(AcordaoDaFonte::numeroProcesso).distinct().toList();

        FonteDeMetadadosProcessuais datajud = new DataJudMetadados(WebClient.builder(),
                "https://api-publica.datajud.cnj.jus.br", chavePublica());
        LoteDeMetadados lote = datajud.buscar("TJDFT", numeros, Grau.G2);

        double casamento = (double) (lote.processos().size() + lote.descartadosPorSigilo()) / numeros.size();
        System.out.printf("DataJud casou %d de %d processos (%d por sigilo)%n",
                lote.processos().size(), numeros.size(), lote.descartadosPorSigilo());

        assertThat(casamento).isGreaterThanOrEqualTo(CASAMENTO_MINIMO);
        assertThat(lote.processos()).allSatisfy(processo -> {
            assertThat(processo.assuntos()).isNotEmpty();
            assertThat(processo.codigoClasse()).isNotNull();
            assertThat(processo.dataAjuizamento()).isNotNull();
        });
    }

    /** A chave é pública e documentada no exemplo de configuração; o teste a lê de lá. */
    private static String chavePublica() throws Exception {
        Path exemplo = Path.of(System.getProperty("user.dir")).resolve("../../.env.example").normalize();
        return Files.readAllLines(exemplo).stream()
                .filter(linha -> linha.startsWith("JUSPRISMA_DATAJUD_API_KEY="))
                .map(linha -> linha.substring(linha.indexOf('=') + 1).trim())
                .findFirst().orElseThrow();
    }
}
