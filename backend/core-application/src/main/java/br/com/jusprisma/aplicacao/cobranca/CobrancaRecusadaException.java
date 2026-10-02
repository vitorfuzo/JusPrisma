package br.com.jusprisma.aplicacao.cobranca;

/**
 * O gateway respondeu e recusou: dado inválido do lado dele. Repetir não adianta; quem
 * contrata precisa corrigir algo.
 */
public class CobrancaRecusadaException extends RuntimeException {

    public CobrancaRecusadaException(String mensagem) {
        super(mensagem);
    }
}
