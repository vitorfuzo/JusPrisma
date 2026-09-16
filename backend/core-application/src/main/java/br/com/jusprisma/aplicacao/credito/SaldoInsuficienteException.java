package br.com.jusprisma.aplicacao.credito;

import br.com.jusprisma.dominio.credito.TipoCredito;

public class SaldoInsuficienteException extends RuntimeException {

    private final transient TipoCredito tipo;
    private final int saldo;
    private final int solicitado;

    public SaldoInsuficienteException(TipoCredito tipo, int saldo, int solicitado) {
        super("saldo de %s insuficiente: há %d e foram solicitados %d"
                .formatted(tipo, saldo, solicitado));
        this.tipo = tipo;
        this.saldo = saldo;
        this.solicitado = solicitado;
    }

    public TipoCredito tipo() {
        return tipo;
    }

    public int saldo() {
        return saldo;
    }

    public int solicitado() {
        return solicitado;
    }
}
