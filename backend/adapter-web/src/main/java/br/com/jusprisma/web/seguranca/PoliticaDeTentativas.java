package br.com.jusprisma.web.seguranca;

import java.time.Duration;

/**
 * Quantas tentativas cada operação sensível tolera, e em que janela.
 *
 * <p>Os números são conservadores de propósito: apertar demais transforma advogado que
 * errou a senha duas vezes em ticket de suporte, e afrouxar demais deixa força bruta
 * viável. A régua adotada é "atrapalha um roteiro automatizado, não atrapalha uma pessoa".
 */
public enum PoliticaDeTentativas {

    /**
     * Por IP. Generoso porque escritório inteiro costuma sair pelo mesmo IP, e travar um
     * escritório porque um estagiário errou a senha seria pior que o ataque.
     */
    LOGIN_POR_IP(30, Duration.ofMinutes(5)),

    /**
     * Por e-mail. Este é o que efetivamente barra força bruta: o atacante pode trocar de
     * IP à vontade, mas a conta-alvo continua a mesma. O contador é zerado no login
     * bem-sucedido, para que quem errou e acertou não fique com crédito queimado.
     */
    LOGIN_POR_EMAIL(8, Duration.ofMinutes(15)),

    /**
     * Recuperação de senha. Apertado porque cada tentativa dispara um e-mail: sem limite,
     * o formulário público vira ferramenta de inundar a caixa de outra pessoa.
     */
    RECUPERACAO_POR_EMAIL(3, Duration.ofHours(1)),

    /** Cadastro por IP: limita criação de contas em massa. */
    CADASTRO_POR_IP(10, Duration.ofHours(1)),

    /** Reenvio de verificação, pelo mesmo motivo da recuperação: cada um manda e-mail. */
    VERIFICACAO_POR_USUARIO(5, Duration.ofHours(1));

    private final int tentativas;
    private final Duration janela;

    PoliticaDeTentativas(int tentativas, Duration janela) {
        this.tentativas = tentativas;
        this.janela = janela;
    }

    public int tentativas() {
        return tentativas;
    }

    public Duration janela() {
        return janela;
    }
}
