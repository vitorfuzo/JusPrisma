package br.com.jusprisma.aplicacao.cobranca;

public class AssinaturaJaContratadaException extends RuntimeException {

    public AssinaturaJaContratadaException() {
        super("o escritório já tem um plano contratado; troca de plano ainda não é suportada");
    }
}
