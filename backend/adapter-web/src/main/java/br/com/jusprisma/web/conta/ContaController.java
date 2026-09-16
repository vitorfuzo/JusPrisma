package br.com.jusprisma.web.conta;

import br.com.jusprisma.aplicacao.conta.Autenticar;
import br.com.jusprisma.aplicacao.conta.CadastrarConta;
import br.com.jusprisma.aplicacao.conta.SessoesDeAcesso;
import br.com.jusprisma.dominio.conta.Papel;
import br.com.jusprisma.dominio.conta.Usuario;
import br.com.jusprisma.web.seguranca.CookieDeRenovacao;
import br.com.jusprisma.web.seguranca.EmissorDeAcesso;
import br.com.jusprisma.web.seguranca.EmissorDeRenovacao;
import br.com.jusprisma.web.seguranca.LimitadorDeTentativas;
import br.com.jusprisma.web.seguranca.OrigemDaRequisicao;
import br.com.jusprisma.web.seguranca.PoliticaDeTentativas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Conta", description = "Cadastro, autenticação e sessões")
public class ContaController {

    private final CadastrarConta cadastrarConta;
    private final Autenticar autenticar;
    private final SessoesDeAcesso sessoes;
    private final EmissorDeAcesso emissorDeAcesso;
    private final EmissorDeRenovacao emissorDeRenovacao;
    private final CookieDeRenovacao cookie;
    private final LimitadorDeTentativas limitador;
    private final OrigemDaRequisicao origem;

    public ContaController(CadastrarConta cadastrarConta,
                           Autenticar autenticar,
                           SessoesDeAcesso sessoes,
                           EmissorDeAcesso emissorDeAcesso,
                           EmissorDeRenovacao emissorDeRenovacao,
                           CookieDeRenovacao cookie,
                           LimitadorDeTentativas limitador,
                           OrigemDaRequisicao origem) {
        this.cadastrarConta = cadastrarConta;
        this.autenticar = autenticar;
        this.sessoes = sessoes;
        this.emissorDeAcesso = emissorDeAcesso;
        this.emissorDeRenovacao = emissorDeRenovacao;
        this.cookie = cookie;
        this.limitador = limitador;
        this.origem = origem;
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
    public ResponseEntity<CadastroResponse> cadastrar(
            @Valid @RequestBody CadastroRequest requisicao, HttpServletRequest http) {

        limitador.registrar(PoliticaDeTentativas.CADASTRO_POR_IP, origem.ipDe(http));

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

    // ------------------------------------------------------------------ sessões

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
    @Operation(summary = "Autentica, abre uma sessão e devolve um token de acesso")
    public ResponseEntity<SessaoResponse> abrirSessao(
            @Valid @RequestBody LoginRequest requisicao, HttpServletRequest http) {

        // Duas politicas, e as duas sao necessarias. Por IP barra varredura de muitas contas
        // a partir de um ponto; por e-mail barra forca bruta contra uma conta especifica,
        // que e o que sobrevive a troca de IP.
        limitador.registrar(PoliticaDeTentativas.LOGIN_POR_IP, origem.ipDe(http));
        limitador.registrar(PoliticaDeTentativas.LOGIN_POR_EMAIL, requisicao.email());

        Autenticar.Autenticado autenticado =
                autenticar.executar(requisicao.email(), requisicao.senha());

        // Acertou a senha: o contador do e-mail e zerado, para que quem errou duas vezes e
        // acertou na terceira nao fique com credito queimado pelo resto da janela.
        limitador.zerar(PoliticaDeTentativas.LOGIN_POR_EMAIL, requisicao.email());

        SessoesDeAcesso.SessaoAberta sessao =
                sessoes.abrir(autenticado.tenantId(), autenticado.usuarioId());

        return responderComTokens(
                autenticado.usuarioId(),
                autenticado.tenantId(),
                autenticado.papel(),
                sessao.sessaoId(),
                sessao.familiaId(),
                sessao.expiraEm(),
                autenticado.emailVerificado());
    }

    @PostMapping("/sessoes/renovacao")
    @Operation(summary = "Troca o token de renovação por um par novo")
    public ResponseEntity<SessaoResponse> renovarSessao(HttpServletRequest requisicao) {
        EmissorDeRenovacao.Vinculos vinculos = emissorDeRenovacao.interpretar(
                cookie.ler(requisicao).orElse(null));

        SessoesDeAcesso.SessaoRenovada renovada = sessoes.renovar(
                vinculos.tenantId(), vinculos.usuarioId(), vinculos.familiaId(), vinculos.sessaoId());

        return responderComTokens(
                renovada.usuarioId(),
                vinculos.tenantId(),
                renovada.papel(),
                renovada.sessaoId(),
                renovada.familiaId(),
                renovada.expiraEm(),
                renovada.emailVerificado());
    }

    @DeleteMapping("/sessoes")
    @Operation(summary = "Encerra a sessão e revoga a família de renovação")
    public ResponseEntity<Void> encerrarSessao(HttpServletRequest requisicao) {
        HttpHeaders cabecalhos = new HttpHeaders();

        // O logout sempre limpa o cookie e sempre responde 204, mesmo com token
        // inválido: um logout que falha deixa o usuário achando que saiu quando não saiu.
        cookie.ler(requisicao).ifPresent(token -> {
            try {
                EmissorDeRenovacao.Vinculos vinculos = emissorDeRenovacao.interpretar(token);
                sessoes.encerrar(vinculos.tenantId(), vinculos.familiaId());
            } catch (RuntimeException ignorado) {
                // Token já inválido: não há família para revogar, e o cookie sai de qualquer forma.
            }
        });

        cookie.limpar(cabecalhos);
        return ResponseEntity.noContent().headers(cabecalhos).build();
    }

    // --------------------------------------------------------------------- apoio

    private ResponseEntity<SessaoResponse> responderComTokens(
            UUID usuarioId, UUID tenantId, Papel papel,
            UUID sessaoId, UUID familiaId, Instant expiraEm, boolean emailVerificado) {

        EmissorDeAcesso.TokenDeAcesso acesso = emissorDeAcesso.emitir(usuarioId, tenantId, papel);
        EmissorDeRenovacao.TokenDeRenovacao renovacao =
                emissorDeRenovacao.emitir(sessaoId, usuarioId, tenantId, familiaId, expiraEm);

        HttpHeaders cabecalhos = new HttpHeaders();
        cookie.anexar(cabecalhos, renovacao.valor(), emissorDeRenovacao.validade());

        return ResponseEntity.ok()
                .headers(cabecalhos)
                .body(new SessaoResponse(
                        acesso.valor(),
                        "Bearer",
                        acesso.validade().toSeconds(),
                        acesso.expiraEm(),
                        emailVerificado));
    }
}
