package br.com.jusprisma.config;

import br.com.jusprisma.aplicacao.porta.CodificadorDeSenha;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class CodificadorDeSenhaBCrypt implements CodificadorDeSenha {

    /**
     * Hash de uma senha que ninguém tem, usado só para gastar o mesmo tempo de CPU quando
     * o e-mail informado não existe. Gerado uma vez na subida para não custar nada depois.
     */
    private final String hashDeReferencia;
    private final PasswordEncoder codificador;

    public CodificadorDeSenhaBCrypt(PasswordEncoder codificador) {
        this.codificador = codificador;
        this.hashDeReferencia = codificador.encode(
                "senha-que-nao-pertence-a-ninguem-" + java.util.UUID.randomUUID());
    }

    @Override
    public String codificar(String senhaEmClaro) {
        return codificador.encode(senhaEmClaro);
    }

    @Override
    public boolean confere(String senhaEmClaro, String hash) {
        return codificador.matches(senhaEmClaro, hash);
    }

    @Override
    public String hashDeReferencia() {
        return hashDeReferencia;
    }
}
