package br.com.jusprisma.web.plano;

import br.com.jusprisma.aplicacao.cobranca.ContratarPlano;
import br.com.jusprisma.aplicacao.credito.Creditos;
import br.com.jusprisma.aplicacao.plano.LimitesVigentes;
import br.com.jusprisma.aplicacao.plano.RepositorioDePlano;
import br.com.jusprisma.dominio.credito.TipoCredito;
import br.com.jusprisma.dominio.plano.Cota;
import br.com.jusprisma.dominio.plano.LimitesDoPlano;
import br.com.jusprisma.dominio.plano.Plano;
import br.com.jusprisma.dominio.plano.Recurso;
import br.com.jusprisma.web.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Planos", description = "Catálogo de planos e situação da assinatura")
public class PlanoController {

    private final RepositorioDePlano planos;
    private final LimitesVigentes limites;
    private final Creditos creditos;
    private final ContratarPlano contratarPlano;

    public PlanoController(RepositorioDePlano planos, LimitesVigentes limites, Creditos creditos,
                           ContratarPlano contratarPlano) {
        this.planos = planos;
        this.limites = limites;
        this.creditos = creditos;
        this.contratarPlano = contratarPlano;
    }

    public record CotaResponse(String chave, Integer teto, boolean ilimitada, String descricao) {
    }

    public record PlanoResponse(
            String codigo,
            String nome,
            int precoCentavos,
            List<CotaResponse> cotas,
            List<String> recursos) {
    }

    @GetMapping("/planos")
    @Operation(summary = "Lista os planos disponíveis")
    public List<PlanoResponse> listar() {
        // Catálogo é público por natureza — é a tabela de preços. Exigir autenticação
        // impediria mostrar planos a quem ainda não tem conta, que é justamente quem
        // precisa vê-los.
        return planos.listarAtivos().stream().map(PlanoController::paraResposta).toList();
    }

    public record SituacaoResponse(
            PlanoResponse plano,
            Map<String, Integer> saldos,
            ContratarPlano.Situacao contratacao) {
    }

    @GetMapping("/assinatura")
    @Operation(summary = "Plano vigente do escritório e saldos de crédito")
    public SituacaoResponse situacao(Authentication autenticacao) {
        UsuarioAutenticado atual = UsuarioAutenticado.de(autenticacao);
        Plano plano = limites.planoDe(atual.tenantId());

        Map<String, Integer> saldos = new LinkedHashMap<>();
        for (TipoCredito tipo : TipoCredito.values()) {
            saldos.put(tipo.name(), creditos.saldo(atual.tenantId(), tipo));
        }

        return new SituacaoResponse(paraResposta(plano), saldos,
                contratarPlano.situacao(atual.tenantId()));
    }

    public record ContratacaoRequest(
            @NotBlank String planoCodigo,
            // Teto folgado para a máscara; a validação de verdade é o dígito verificador.
            @NotBlank @Size(max = 32) String documento) {

        @Override
        public String toString() {
            return "ContratacaoRequest[plano=%s]".formatted(planoCodigo);
        }
    }

    public record ContratacaoResponse(String planoCodigo, String documento, LocalDate primeiraCobranca) {
    }

    @PostMapping("/assinatura/contratacao")
    @Operation(summary = "Contrata um plano pago informando o CPF ou CNPJ de cobrança",
            description = "O plano passa a valer quando o primeiro pagamento é confirmado. "
                    + "Durante a degustação, a primeira cobrança vence no fim dela.")
    public ContratacaoResponse contratar(Authentication autenticacao,
                                         @Valid @RequestBody ContratacaoRequest requisicao) {
        UsuarioAutenticado atual = UsuarioAutenticado.de(autenticacao);
        ContratarPlano.Contratacao contratacao = contratarPlano.contratar(new ContratarPlano.Comando(
                atual.tenantId(), atual.papel(), requisicao.planoCodigo(), requisicao.documento()));
        // O documento volta mascarado: o suficiente para o dono reconhecer o que digitou.
        return new ContratacaoResponse(contratacao.planoCodigo(), contratacao.documentoMascarado(),
                contratacao.primeiraCobranca());
    }

    private static PlanoResponse paraResposta(Plano plano) {
        LimitesDoPlano limites = plano.limites();

        Map<Cota, CotaResponse> cotas = new EnumMap<>(Cota.class);
        for (Cota cota : Cota.values()) {
            if (!limites.configurada(cota)) {
                // Cota não configurada é negada, e a interface não deve anunciar como
                // "0" algo que o plano simplesmente não oferece.
                continue;
            }
            cotas.put(cota, new CotaResponse(
                    cota.name(),
                    limites.teto(cota).isPresent() ? limites.teto(cota).getAsInt() : null,
                    limites.ilimitada(cota),
                    limites.descricao(cota)));
        }

        return new PlanoResponse(
                plano.codigo(),
                plano.nome(),
                plano.precoCentavos(),
                List.copyOf(cotas.values()),
                limites.recursos().stream().map(Recurso::name).toList());
    }
}
