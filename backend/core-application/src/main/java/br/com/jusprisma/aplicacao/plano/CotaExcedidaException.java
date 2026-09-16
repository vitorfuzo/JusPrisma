package br.com.jusprisma.aplicacao.plano;

import br.com.jusprisma.dominio.plano.Cota;

/**
 * A operação foi recusada porque estouraria a cota do plano.
 */
public class CotaExcedidaException extends RuntimeException {

    private final transient Cota cota;

    public CotaExcedidaException(Cota cota, String detalhe) {
        super(detalhe);
        this.cota = cota;
    }

    public Cota cota() {
        return cota;
    }
}
