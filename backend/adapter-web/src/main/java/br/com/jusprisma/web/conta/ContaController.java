package br.com.jusprisma.web.conta;

import br.com.jusprisma.aplicacao.conta.Autenticar;
import br.com.jusprisma.aplicacao.conta.CadastrarConta;
import br.com.jusprisma.dominio.conta.Usuario;
import br.com.jusprisma.web.seguranca.EmissorDeAcesso;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Conta", description = "Cadastro e autenticação")
public class ContaController {

    private final CadastrarConta cadastrarConta;
    private final Autenticar autenticar;
    private final EmissorDeAcesso emissor;

    public ContaController(CadastrarConta cadastrarConta,
                           Autenticar autenticar,
                           EmissorDeAcesso emissor) {
        this.cadastrarConta = cadastrarConta;
        this.autenticar = autenticar;
        this.emissor = emissor;
    }

    // ----------------------------------------------------------------- cadastro

    public record CadastroRequest(
            @NotBlank @Size(max = 200) String nomeDoEscritorio,
            @Pattern(regexp = "^\\d{14}$", message = "CNPJ deve ter 14 dígitos") String cnpj,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 200) String senha,
            @Size(max = 20) String oab,
            @Pattern(regexp = "^[A-Za-z]{2}$", message = "UF deve ter duas letras") String ufOab) {
    }

    public record CadastroResponse(UUID usuarioId, UUID tenantId, String email) {
    }

    @PostMapping("/contas")
    @Operation(summary = "Cria uma conta nova e o usuário dono")
    public ResponseEntity<CadastroResponse> cadastrar(@Valid @RequestBody CadastroRequest requisicao) {
        Usuario dono = cadastrarConta.executar(new CadastrarConta.Comando(
                requisicao.nomeDoEscritorio(),
                requisicao.cnpj(),
                requisicao.email(),
                requisicao.senha(),
                requisicao.oab(),
                requisicao.ufOab()));

        return ResponseEntity
                .created(URI.create("/api/v1/usuarios/" + dono.id()))
                .body(new CadastroResponse(dono.id(), dono.tenantId(), dono.email().valor()));
    }

    // ------------------------------------------------------------------- sessão

    public record LoginRequest(
            @NotBlank String email,
            @NotBlank String senha) {
    }

    public record SessaoResponse(
            String tokenDeAcesso,
            String tipo,
            long expiraEmSegundos,
            Instant expiraEm,
            boolean emailVerificado) {
    }

    @PostMapping("/sessoes")
    @Operation(summary = "Autentica e devolve um token de acesso")
    public SessaoResponse autenticar(@Valid @RequestBody LoginRequest requisicao) {
        Autenticar.Autenticado autenticado =
                autenticar.executar(requisicao.email(), requisicao.senha());

        EmissorDeAcesso.TokenDeAcesso token = emissor.emitir(
                autenticado.usuarioId(), autenticado.tenantId(), autenticado.papel());

        return new SessaoResponse(
                token.valor(),
                "Bearer",
                token.validade().toSeconds(),
                token.expiraEm(),
                autenticado.emailVerificado());
    }

}
