package br.com.jusprisma.aplicacao.ingestao;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Porta da fonte de metadados processuais: classe, assuntos CNJ e data de ajuizamento.
 *
 * <p>Existe porque a fonte de acórdãos não traz o assunto CNJ, e o recorte do perfil
 * depende dele. A junção é pelo número único do processo.
 */
public interface FonteDeMetadadosProcessuais {

    /** Grau de jurisdição do registro. Um mesmo processo tem um registro por grau. */
    enum Grau { G1, G2 }

    /**
     * Metadados dos processos informados, no grau pedido.
     *
     * <p>Processo com sigilo não sai daqui (regra inviolável 8). Processo ausente da
     * resposta é ausente de fato: falha parcial da fonte nunca vira "não encontrado".
     *
     * @param tribunal sigla do tribunal, ex.: {@code TJDFT}.
     * @param numeros  números CNJ, com ou sem máscara.
     * @throws IllegalArgumentException se algum número não tem 20 dígitos, ou o lote é grande demais.
     * @throws FonteIndisponivelException se a fonte não respondeu ou respondeu pela metade.
     * @throws RespostaInesperadaDaFonteException se a fonte respondeu fora do contrato.
     */
    LoteDeMetadados buscar(String tribunal, Collection<String> numeros, Grau grau);

    record LoteDeMetadados(List<MetadadosDoProcesso> processos, int descartadosPorSigilo) {
    }

    /**
     * @param numeroProcesso   os 20 dígitos, sem máscara — a forma de junção entre fontes.
     * @param dataAjuizamento  hora local do tribunal; a fonte não informa fuso.
     */
    record MetadadosDoProcesso(
            String numeroProcesso,
            Grau grau,
            Integer codigoClasse,
            String nomeClasse,
            List<AssuntoCnj> assuntos,
            LocalDateTime dataAjuizamento) {
    }

    record AssuntoCnj(int codigo, String nome) {
    }

    /**
     * Os 20 dígitos do número único do processo.
     *
     * <p>Uma fonte entrega {@code 0709116-94.2022.8.07.0018} e a outra
     * {@code 07091169420228070018}; comparar as formas cruas não casa nenhum processo, e o
     * sintoma é "a fonte não tem esses processos" em vez de erro.
     */
    static String numeroSemMascara(String numero) {
        String digitos = numero == null ? "" : numero.replaceAll("[.\\-\\s]", "");
        if (!digitos.matches("\\d{20}")) {
            throw new IllegalArgumentException("número de processo CNJ deve ter 20 dígitos");
        }
        return digitos;
    }
}
