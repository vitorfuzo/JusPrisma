package br.com.jusprisma.web.conta;

import br.com.jusprisma.aplicacao.conta.ConvitesDeSubusuario;
import br.com.jusprisma.dominio.conta.Convite;
import br.com.jusprisma.dominio.conta.Papel;
import br.com.jusprisma.dominio.conta.Usuario;
import br.com.jusprisma.web.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Convites", description = "Convite de subusuários")
public class ConviteController {

    private final ConvitesDeSubusuario convites;

    public ConviteController(ConvitesDeSubusuario convites) {
        this.convites = convites;
    }

    // ---------------------------------------------------- administração (OWNER)

    public record ConviteRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull Papel papel) {
    }

    public record ConviteResponse(UUID id, String email, Papel papel, Instant expiraEm) {
    }

    @PostMapping("/convites")
    @Operation(summary = "Convida alguém para o escritório")
    public ResponseEntity<ConviteResponse> convidar(
            Authentication autenticacao,
            @Valid @RequestBody ConviteRequest requisicao) {

        UsuarioAutenticado atual = UsuarioAutenticado.de(autenticacao);
        Convite convite = convites.convidar(new ConvitesDeSubusuario.Comando(
                atual.tenantId(), atual.usuarioId(), atual.papel(),
                requisicao.email(), requisicao.papel()));

        return ResponseEntity
                .created(URI.create("/api/v1/convites/" + convite.id()))
                .body(paraResposta(convite));
    }

    @GetMapping("/convites")
    @Operation(summary = "Lista os convites pendentes do escritório")
    public List<ConviteResponse> listar(Authentication autenticacao) {
        UsuarioAutenticado atual = UsuarioAutenticado.de(autenticacao);
        return convites.listarPendentes(atual.tenantId(), atual.papel())
                .stream()
                .map(ConviteController::paraResposta)
                .toList();
    }

    @DeleteMapping("/convites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoga um convite pendente")
    public void revogar(Authentication autenticacao, @PathVariable UUID id) {
        UsuarioAutenticado atual = UsuarioAutenticado.de(autenticacao);
        convites.revogar(atual.tenantId(), atual.papel(), id);
    }

    // ------------------------------------------------------------ aceite (público)

    public record ConvitePendenteResponse(String nomeDoEscritorio, String email, Papel papel) {
    }

    @GetMapping("/convites/pendente")
    @Operation(summary = "Mostra a que escritório o convite pertence, antes do aceite")
    public ConvitePendenteResponse examinar(@RequestParam String token) {
        // Existe para que ninguém aceite às cegas: entrar num escritório é ganhar acesso a
        // dados de clientes de terceiros, e a pessoa precisa ver de quem antes de decidir.
        ConvitesDeSubusuario.ConvitePendente pendente = convites.examinar(token);
        return new ConvitePendenteResponse(
                pendente.nomeDoEscritorio(), pendente.email(), pendente.papel());
    }

    public record AceiteRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 10, max = 200) String senha,
            @Size(max = 20) String oab,
            @Pattern(regexp = "^[A-Za-z]{2}$", message = "UF deve ter duas letras") String ufOab) {
    }

    public record AceiteResponse(UUID usuarioId, UUID tenantId, String email, Papel papel) {
    }

    @PostMapping("/convites/aceite")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Aceita o convite e cria o usuário no escritório")
    public AceiteResponse aceitar(@Valid @RequestBody AceiteRequest requisicao) {
        Usuario novo = convites.aceitar(
                requisicao.token(), requisicao.senha(), requisicao.oab(), requisicao.ufOab());

        return new AceiteResponse(
                novo.id(), novo.tenantId(), novo.email().valor(), novo.papel());
    }

    private static ConviteResponse paraResposta(Convite convite) {
        return new ConviteResponse(
                convite.id(), convite.email().valor(), convite.papel(), convite.expiraEm());
    }
}
