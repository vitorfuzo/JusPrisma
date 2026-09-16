package br.com.jusprisma.aplicacao.porta;

/**
 * Envio de e-mail transacional.
 *
 * <p>Porta estreita de propósito: o caso de uso diz para quem, com que finalidade e com que
 * token — nunca monta assunto nem corpo. Texto de e-mail muda por razão de produto, e não
 * deve ser motivo para tocar em regra de negócio.
 */
public interface EnviadorDeEmail {

    void enviarVerificacaoDeEmail(String destinatario, String segredoDoToken);

    void enviarRecuperacaoDeSenha(String destinatario, String segredoDoToken);

    void enviarConvite(String destinatario, String segredoDoConvite);

    /**
     * Avisa que a degustacao esta terminando e que a cobranca vai comecar.
     *
     * <p>Converter sem avisar gera chargeback e reclamacao publica. Custa um e-mail evitar.
     */
    void enviarAvisoDeFimDaDegustacao(String destinatario, java.time.Instant fimDoPeriodo);
}
