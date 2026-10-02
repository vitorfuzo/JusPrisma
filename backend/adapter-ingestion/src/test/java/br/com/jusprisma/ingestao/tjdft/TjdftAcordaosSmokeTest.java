package br.com.jusprisma.ingestao.tjdft;

import br.com.jusprisma.aplicacao.ingestao.FonteDeAcordaos.ConsultaDeAcordaos;
import br.com.jusprisma.aplicacao.ingestao.FonteDeAcordaos.PaginaDeAcordaos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O adaptador contra a API real do TJDFT.
 *
 * <p>Os testes com servidor simulado provam que o código segue o contrato que eu escrevi;
 * este prova que o contrato ainda é o da API. Se o TJDFT renomear um campo, é aqui que
 * aparece, e não numa estatística que encolheu sem explicação.
 *
 * <p>Depende de rede e de terceiro: fica fora do build padrão. Rode com
 * {@code ./gradlew test -PcomSmoke}.
 */
@Tag("smoke")
class TjdftAcordaosSmokeTest {

    private final TjdftAcordaos fonte =
            new TjdftAcordaos(WebClient.builder(), "https://jurisdf.tjdft.jus.br");

    @Test
    @DisplayName("uma página cheia da API real chega inteira e com os campos que a ingestão usa")
    void paginaReal() {
        PaginaDeAcordaos pagina = fonte.buscar(
                new ConsultaDeAcordaos("dano moral", null, 0, TjdftAcordaos.TAMANHO_MAXIMO));

        assertThat(pagina.total()).isPositive();
        assertThat(pagina.acordaos().size() + pagina.descartadosPorSigilo())
                .as("com tamanho 40 a API devolve a página cheia")
                .isEqualTo(TjdftAcordaos.TAMANHO_MAXIMO);
        assertThat(pagina.acordaos()).allSatisfy(acordao -> {
            assertThat(acordao.identificadorExterno()).isNotBlank();
            assertThat(acordao.numeroProcesso()).matches("\\d{7}-\\d{2}\\.\\d{4}\\.8\\.07\\.\\d{4}");
            assertThat(acordao.nomeRelator()).isNotBlank();
            assertThat(acordao.ementa()).isNotBlank();
            assertThat(acordao.dispositivo()).isNotBlank();
            assertThat(acordao.dataJulgamento()).isNotNull();
        });
    }
}
