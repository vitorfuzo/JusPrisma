package br.com.jusprisma.aplicacao.porta;

/**
 * Transforma senha em hash e confere senha contra hash.
 *
 * <p>Porta, e não uso direto da biblioteca, por dois motivos: o algoritmo vai mudar (hoje
 * bcrypt, amanhã argon2) e a troca precisa ser transparente; e assim nenhum caso de uso
 * toca em API de criptografia.
 */
public interface CodificadorDeSenha {

    String codificar(String senhaEmClaro);

    boolean confere(String senhaEmClaro, String hash);

    /**
     * Hash descartável usado para gastar tempo quando o usuário não existe.
     *
     * <p>Sem isso, "e-mail não cadastrado" responde muito mais rápido que "senha errada",
     * e a diferença de tempo entrega quais e-mails têm conta na plataforma.
     */
    String hashDeReferencia();
}
