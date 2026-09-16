package br.com.jusprisma.aplicacao.admin;

import br.com.jusprisma.aplicacao.conta.CredenciaisInvalidasException;
import br.com.jusprisma.aplicacao.porta.CodificadorDeSenha;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Autenticação da equipe da plataforma.
 *
 * <p>Separada da autenticação de cliente de propósito, e não por simetria: credencial de
 * operador dá acesso a todos os escritórios. Compartilhar o fluxo com o login público
 * significaria que qualquer falha na rota de cliente — um bypass, uma confusão de token —
 * passaria a valer também para o acesso irrestrito.
 */
@Service
public class AutenticarAdministrador {

    private static final Logger log = LoggerFactory.getLogger(AutenticarAdministrador.class);

    private final RepositorioAdministrativo repositorio;
    private final CodificadorDeSenha codificador;

    public AutenticarAdministrador(RepositorioAdministrativo repositorio,
                                   CodificadorDeSenha codificador) {
        this.repositorio = repositorio;
        this.codificador = codificador;
    }

    public record Autenticado(UUID administradorId, String nome) {
    }

    public Autenticado executar(String email, String senha) {
        Optional<RepositorioAdministrativo.CredenciaisDeAdministrador> encontradas =
                repositorio.buscarCredenciais(email == null ? "" : email);

        if (encontradas.isEmpty()) {
            // Mesmo cuidado do login de cliente: gasta o tempo da conferência para que a
            // resposta não revele, pelo relógio, quais e-mails são de operadores. Aqui a
            // informação é ainda mais sensível — sabe-se quem atacar.
            codificador.confere(senha == null ? "" : senha, codificador.hashDeReferencia());
            log.warn("tentativa de acesso administrativo com e-mail desconhecido");
            throw new CredenciaisInvalidasException();
        }

        RepositorioAdministrativo.CredenciaisDeAdministrador credenciais = encontradas.get();
        if (!codificador.confere(senha, credenciais.senhaHash())) {
            log.warn("senha incorreta no acesso administrativo do operador {}", credenciais.id());
            throw new CredenciaisInvalidasException();
        }
        if (!credenciais.ativo()) {
            log.warn("acesso administrativo recusado: operador {} inativo", credenciais.id());
            throw new CredenciaisInvalidasException();
        }

        repositorio.registrarUltimoAcesso(credenciais.id());
        log.info("acesso administrativo concedido ao operador {}", credenciais.id());

        return new Autenticado(credenciais.id(), credenciais.nome());
    }
}
