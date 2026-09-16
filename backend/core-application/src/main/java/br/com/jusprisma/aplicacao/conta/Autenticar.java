package br.com.jusprisma.aplicacao.conta;

import br.com.jusprisma.aplicacao.porta.CodificadorDeSenha;
import br.com.jusprisma.dominio.conta.Email;
import br.com.jusprisma.dominio.conta.Papel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Confere e-mail e senha.
 */
@Service
public class Autenticar {

    private static final Logger log = LoggerFactory.getLogger(Autenticar.class);

    private final RepositorioDeConta repositorio;
    private final CodificadorDeSenha codificador;

    public Autenticar(RepositorioDeConta repositorio, CodificadorDeSenha codificador) {
        this.repositorio = repositorio;
        this.codificador = codificador;
    }

    public record Autenticado(UUID usuarioId, UUID tenantId, Papel papel, boolean emailVerificado) {
    }

    @Transactional(readOnly = true)
    public Autenticado executar(String emailInformado, String senhaInformada) {
        Email email;
        try {
            email = Email.de(emailInformado);
        } catch (IllegalArgumentException e) {
            // E-mail malformado recebe a mesma resposta de e-mail inexistente: dizer
            // "formato inválido" versus "credenciais inválidas" já é informação sobre a base.
            gastarTempoDeConferencia(senhaInformada);
            throw new CredenciaisInvalidasException();
        }

        Optional<CredenciaisDeAcesso> encontradas = repositorio.buscarCredenciais(email);

        if (encontradas.isEmpty()) {
            // Confere contra um hash descartável para que a resposta demore o mesmo tanto
            // de um login com senha errada. Sem isso, o tempo de resposta revela quais
            // e-mails têm conta — que é enumeração de usuários.
            gastarTempoDeConferencia(senhaInformada);
            log.info("tentativa de login para e-mail sem conta");
            throw new CredenciaisInvalidasException();
        }

        CredenciaisDeAcesso credenciais = encontradas.get();
        if (!codificador.confere(senhaInformada, credenciais.senhaHash())) {
            log.info("senha incorreta para usuário {}", credenciais.usuarioId());
            throw new CredenciaisInvalidasException();
        }

        if (!credenciais.tenantOperacional()) {
            log.info("login recusado: tenant {} não está ativo", credenciais.tenantId());
            throw new ContaIndisponivelException("a conta do escritório não está ativa");
        }

        return new Autenticado(
                credenciais.usuarioId(),
                credenciais.tenantId(),
                credenciais.papel(),
                credenciais.emailVerificado());
    }

    private void gastarTempoDeConferencia(String senhaInformada) {
        codificador.confere(
                senhaInformada == null ? "" : senhaInformada,
                codificador.hashDeReferencia());
    }
}
