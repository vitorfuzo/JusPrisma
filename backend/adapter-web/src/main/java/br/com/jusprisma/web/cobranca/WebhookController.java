package br.com.jusprisma.web.cobranca;

import br.com.jusprisma.aplicacao.cobranca.EventoDeCobranca;
import br.com.jusprisma.aplicacao.cobranca.GatewayDePagamento;
import br.com.jusprisma.aplicacao.cobranca.ProcessarEventoDeCobranca;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Recebe as notificações do gateway de cobrança.
 *
 * <p>Três decisões governam esta rota, e todas vêm de como gateways se comportam:
 *
 * <ol>
 *   <li>Autenticação por token próprio. A rota é pública — o gateway não faz login — e sem
 *       verificação qualquer um enviaria "pagamento confirmado" e ganharia acesso pago.
 *   <li>Idempotência por identificador do evento. Reentrega é rotina, e o efeito não pode
 *       repetir.
 *   <li>Responder 200 sempre que o evento tiver sido recebido e registrado, inclusive
 *       quando não houver o que fazer com ele. Devolver erro para evento que não nos
 *       interessa faz o gateway reentregar indefinidamente algo que nunca vai mudar.
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Webhooks", description = "Notificações do gateway de cobrança")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final GatewayDePagamento gateway;
    private final ProcessarEventoDeCobranca processar;

    public WebhookController(GatewayDePagamento gateway, ProcessarEventoDeCobranca processar) {
        this.gateway = gateway;
        this.processar = processar;
    }

    public record Resposta(String situacao) {
    }

    @PostMapping("/cobranca")
    @Operation(summary = "Recebe notificação de cobrança do gateway")
    public ResponseEntity<Resposta> receber(
            @RequestHeader(value = "asaas-access-token", required = false) String token,
            @RequestBody String corpo) {

        if (!gateway.notificacaoAutentica(token)) {
            log.warn("notificação de cobrança recusada por token inválido");
            // 401 sem detalhe: a resposta não deve ajudar a descobrir o formato esperado.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<EventoDeCobranca> evento = gateway.interpretar(corpo);
        if (evento.isEmpty()) {
            // Corpo que não conseguimos interpretar não melhora com reentrega. Aceitar e
            // registrar em log é melhor que provocar retentativa perpétua.
            return ResponseEntity.ok(new Resposta("ignorado"));
        }

        ProcessarEventoDeCobranca.Resultado resultado = processar.processar(evento.get());
        return ResponseEntity.ok(new Resposta(resultado.name().toLowerCase(java.util.Locale.ROOT)));
    }
}
