package br.com.jusprisma.web.admin;

import br.com.jusprisma.aplicacao.admin.AutenticarAdministrador;
import br.com.jusprisma.aplicacao.admin.PainelAdministrativo;
import br.com.jusprisma.dominio.credito.TipoCredito;
import br.com.jusprisma.web.seguranca.EmissorDeAcesso;
import br.com.jusprisma.web.seguranca.LimitadorDeTentativas;
import br.com.jusprisma.web.seguranca.OrigemDaRequisicao;
import br.com.jusprisma.web.seguranca.PoliticaDeTentativas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Painel da equipe da plataforma.
 *
 * <p>Todas as rotas exigem token com escopo administrativo, emitido por um login próprio.
 * Um token de cliente não abre nada aqui, e um token administrativo não abre as rotas de
 * cliente: a cadeia de segurança separa os dois por autoridade, antes de chegar ao
 * controller.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Administração", description = "Painel interno da plataforma")
public class AdminController {

    private final AutenticarAdministrador autenticar;
    private final PainelAdministrativo painel;
    private final EmissorDeAcesso emissor;
    private final LimitadorDeTentativas limitador;
    private final OrigemDaRequisicao origem;

    public AdminController(AutenticarAdministrador autenticar,
                           PainelAdministrativo painel,
                           EmissorDeAcesso emissor,
                           LimitadorDeTentativas limitador,
                           OrigemDaRequisicao origem) {
        this.autenticar = autenticar;
        this.painel = painel;
        this.emissor = emissor;
        this.limitador = limitador;
        this.origem = origem;
    }

    // ------------------------------------------------------------------ acesso

    public record LoginRequest(@NotBlank String email, @NotBlank String senha) {
    }

    public record SessaoResponse(String tokenDeAcesso, String tipo, String nome, Instant expiraEm) {
    }

    @PostMapping("/sessoes")
    @Operation(summary = "Autentica um operador da plataforma")
    public SessaoResponse entrar(@Valid @RequestBody LoginRequest requisicao,
                                 HttpServletRequest http) {
        limitador.registrar(PoliticaDeTentativas.LOGIN_POR_IP, origem.ipDe(http));
        limitador.registrar(PoliticaDeTentativas.LOGIN_POR_EMAIL, requisicao.email());

        AutenticarAdministrador.Autenticado operador =
                autenticar.executar(requisicao.email(), requisicao.senha());

        limitador.zerar(PoliticaDeTentativas.LOGIN_POR_EMAIL, requisicao.email());

        // Não há token de renovação para operador. A sessão dura o que dura o acesso e
        // acaba: credencial que abre todos os escritórios não deve ficar renovável por
        // trinta dias num cookie.
        EmissorDeAcesso.TokenDeAcesso token =
                emissor.emitirParaAdministrador(operador.administradorId());

        return new SessaoResponse(token.valor(), "Bearer", operador.nome(), token.expiraEm());
    }

    // ------------------------------------------------------------------ painel

    @GetMapping("/tenants")
    @Operation(summary = "Lista os escritórios cadastrados")
    public List<PainelAdministrativo.ResumoDoTenant> listarTenants(
            @RequestParam(defaultValue = "50") @Min(1) int limite,
            @RequestParam(defaultValue = "0") @Min(0) int deslocamento) {
        return painel.listarTenants(limite, deslocamento);
    }

    @GetMapping("/tenants/{tenantId}/consumo")
    @Operation(summary = "Saldo e consumo de créditos de um escritório")
    public List<PainelAdministrativo.ConsumoDeCredito> consumo(@PathVariable UUID tenantId) {
        return painel.consumoDoTenant(tenantId);
    }

    public record ConcessaoRequest(
            @NotNull TipoCredito tipo,
            @Min(1) int quantidade,
            @NotBlank @Size(max = 300) String motivo) {
    }

    @PostMapping("/tenants/{tenantId}/creditos")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Concede crédito manualmente, com registro em trilha de auditoria")
    public void concederCredito(Authentication autenticacao,
                                @PathVariable UUID tenantId,
                                @Valid @RequestBody ConcessaoRequest requisicao) {
        UUID operador = UUID.fromString(((Jwt) autenticacao.getPrincipal()).getSubject());
        painel.concederCredito(operador, tenantId, requisicao.tipo(),
                requisicao.quantidade(), requisicao.motivo());
    }
}
