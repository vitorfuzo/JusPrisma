package br.com.jusprisma.aplicacao.ingestao;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Porta das fontes de acórdãos de 2º grau.
 *
 * <p>O domínio não sabe que o TJDFT existe: cada tribunal com API de jurisprudência é um
 * adaptador atrás desta porta. Tribunal derrubar ou mudar a API significa mexer no
 * adaptador dele, nunca em regra de negócio.
 */
public interface FonteDeAcordaos {

    /** Sigla do tribunal, a primeira metade da chave natural de {@code Decisao}. */
    String tribunal();

    /**
     * Uma página de acórdãos.
     *
     * <p>Processo sob segredo de justiça não sai daqui (regra inviolável 8): o adaptador o
     * descarta e só informa quantos foram descartados.
     *
     * @throws IllegalArgumentException se a consulta excede o que a fonte aceita.
     * @throws FonteIndisponivelException se a fonte não respondeu.
     * @throws RespostaInesperadaDaFonteException se a fonte respondeu fora do contrato.
     */
    PaginaDeAcordaos buscar(ConsultaDeAcordaos consulta);

    /**
     * @param termo   texto livre da busca; obrigatório na API do TJDFT.
     * @param relator nome do relator como a fonte o escreve, ou nulo para todos.
     * @param pagina  índice base zero.
     */
    record ConsultaDeAcordaos(String termo, String relator, int pagina, int tamanho) {

        public ConsultaDeAcordaos {
            if (termo == null || termo.isBlank()) {
                throw new IllegalArgumentException("informe o termo da busca");
            }
            if (pagina < 0) {
                throw new IllegalArgumentException("a página começa em zero");
            }
            if (tamanho < 1) {
                throw new IllegalArgumentException("o tamanho da página é pelo menos 1");
            }
        }
    }

    /**
     * @param total              resultados da busca na fonte, incluindo os sigilosos.
     * @param descartadosPorSigilo registros desta página que não foram entregues.
     */
    record PaginaDeAcordaos(long total, int pagina, List<AcordaoDaFonte> acordaos,
                            int descartadosPorSigilo) {
    }

    /**
     * Acórdão como a fonte o entrega, antes de normalização e pseudonimização.
     *
     * @param identificadorExterno chave do acórdão no tribunal.
     * @param numeroProcesso       número CNJ como a fonte o formata.
     * @param nomeRelator          cru; a normalização é de {@code NomeDeMagistrado}.
     * @param dispositivo          o campo de resultado, semiestruturado.
     * @param dataJulgamento       data civil do julgamento, no fuso do tribunal.
     */
    record AcordaoDaFonte(
            String identificadorExterno,
            String numeroProcesso,
            String nomeRelator,
            boolean relatorAtivo,
            String orgaoJulgador,
            Integer codigoClasseCnj,
            String ementa,
            String dispositivo,
            LocalDate dataJulgamento,
            Instant dataPublicacao) {
    }
}
