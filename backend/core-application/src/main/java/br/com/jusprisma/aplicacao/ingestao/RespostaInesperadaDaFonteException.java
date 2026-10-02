package br.com.jusprisma.aplicacao.ingestao;

/**
 * A fonte respondeu com sucesso, mas fora do contrato documentado.
 *
 * <p>Separada da indisponibilidade de propósito: repetir não resolve, e tratar como "zero
 * resultados" faria a estatística de um magistrado encolher em silêncio.
 */
public class RespostaInesperadaDaFonteException extends RuntimeException {

    public RespostaInesperadaDaFonteException(String mensagem) {
        super(mensagem);
    }
}
