package br.com.jusprisma.web.conta;

import br.com.jusprisma.aplicacao.conta.RecuperacaoDeSenha;
import br.com.jusprisma.aplicacao.conta.VerificacaoDeEmail;
import br.com.jusprisma.web.seguranca.LimitadorDeTentativas;
import br.com.jusprisma.web.seguranca.PoliticaDeTentativas;
import br.com.jusprisma.web.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verificação de e-mail e recuperação de senha.
 *
 * <p>Nenhuma rota aqui confirma ou nega a existência de uma conta. Todas respondem o mesmo
 * para endereço cadastrado e não cadastrado, e a mesma coisa para link válido e inválido.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Credenciais", description = "Verificação de e-mail e recuperação de senha")
public class CredenciaisController {

    private final VerificacaoDeEmail verificacao;
    private final RecuperacaoDeSenha recuperacao;
    private final LimitadorDeTentativas limitador;

    public CredenciaisController(VerificacaoDeEmail verificacao,
                                 RecuperacaoDeSenha recuperacao,
                                 LimitadorDeTentativas limitador) {
        this.verificacao = verificacao;
        this.recuperacao = recuperacao;
        this.limitador = limitador;
    }

    // ------------------------------------------------------ verificação de e-mail

    public record TokenRequest(@NotBlank String token) {
    }

    @PostMapping("/contas/verificacao")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Confirma o endereço de e-mail a partir do link recebido")
    public void confirmarEmail(@Valid @RequestBody TokenRequest requisicao) {
        verificacao.confirmar(requisicao.token());
    }

    @PostMapping("/contas/verificacao/reenvio")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Reenvia o link de verificação para o usuário autenticado")
    public void reenviarVerificacao(Authentication autenticacao) {
        UsuarioAutenticado usuario = UsuarioAutenticado.de(autenticacao);
        limitador.registrar(PoliticaDeTentativas.VERIFICACAO_POR_USUARIO,
                usuario.usuarioId().toString());
        verificacao.reenviar(usuario.tenantId(), usuario.usuarioId());
    }

    // ------------------------------------------------------ recuperação de senha

    public record RecuperacaoRequest(@NotBlank @Size(max = 254) String email) {
    }

    @PostMapping("/senha/recuperacao")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Envia o link de redefinição, se houver conta para o e-mail")
    public void solicitarRecuperacao(@Valid @RequestBody RecuperacaoRequest requisicao) {
        // Limite por e-mail: cada tentativa dispara uma mensagem, e sem teto o formulario
        // publico vira ferramenta de inundar a caixa de outra pessoa.
        limitador.registrar(PoliticaDeTentativas.RECUPERACAO_POR_EMAIL, requisicao.email());

        // 202 sempre, exista ou nao a conta. Responder 404 para e-mail desconhecido
        // transformaria este formulario publico num verificador de quem e cliente.
        recuperacao.solicitar(requisicao.email());
    }

    public record RedefinicaoRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 10, max = 200) String senha) {
    }

    @PostMapping("/senha/redefinicao")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Redefine a senha e encerra todas as sessões abertas")
    public void redefinirSenha(@Valid @RequestBody RedefinicaoRequest requisicao) {
        recuperacao.redefinir(requisicao.token(), requisicao.senha());
    }
}
