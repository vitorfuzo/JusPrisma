package br.com.jusprisma.aplicacao.plano;

/**
 * O escritório não tem assinatura que lhe dê direito de uso.
 */
public class SemAssinaturaVigenteException extends RuntimeException {

    public SemAssinaturaVigenteException() {
        super("não há assinatura vigente para este escritório");
    }
}
