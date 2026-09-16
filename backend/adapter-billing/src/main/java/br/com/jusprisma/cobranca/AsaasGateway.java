package br.com.jusprisma.cobranca;

import br.com.jusprisma.aplicacao.cobranca.EventoDeCobranca;
import br.com.jusprisma.aplicacao.cobranca.GatewayDePagamento;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Adaptador do Asaas.
 *
 * <p>Escolhido para começar por ter recorrência nativa, Pix e boleto, e taxa razoável para
 * o ticket (2,99% no cartão). A intenção declarada é migrar para Pix Automático quando
 * houver volume — por isso tudo aqui fica atrás da porta {@code GatewayDePagamento}, e o
 * vocabulário do fornecedor não vaza para o domínio.
 *
 * <p>Toda chamada passa por retry com backoff e circuit breaker. Gateway de pagamento fora
 * do ar não pode virar exceção não tratada em cima de um cliente tentando assinar.
 */
@Component
public class AsaasGateway implements GatewayDePagamento {

    private static final Logger log = LoggerFactory.getLogger(AsaasGateway.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NOME = "asaas";

    private final WebClient cliente;
    private final String tokenDoWebhook;

    public AsaasGateway(WebClient.Builder construtor,
                        @Value("${jusprisma.asaas.url-base}") String urlBase,
                        @Value("${jusprisma.asaas.chave:}") String chave,
                        @Value("${jusprisma.asaas.token-webhook:}") String tokenDoWebhook) {
        this.cliente = construtor
                .baseUrl(urlBase)
                .defaultHeader("access_token", chave)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.tokenDoWebhook = tokenDoWebhook;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    @Retry(name = "asaas")
    @CircuitBreaker(name = "asaas")
    public ClienteNoGateway criarCliente(DadosDoCliente dados) {
        JsonNode resposta = cliente.post()
                .uri("/customers")
                .bodyValue(Map.of(
                        "name", dados.nome(),
                        "email", dados.email(),
                        "cpfCnpj", dados.cnpj() == null ? "" : dados.cnpj()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(20));

        return new ClienteNoGateway(exigir(resposta, "id"));
    }

    @Override
    @Retry(name = "asaas")
    @CircuitBreaker(name = "asaas")
    public AssinaturaNoGateway assinar(String clienteNoGateway, String planoCodigo, int valorCentavos) {
        // O Asaas trabalha com valor em reais decimais, não em centavos. A conversão fica
        // aqui, na borda: o domínio inteiro raciocina em centavos, porque valor monetário
        // em ponto flutuante acumula erro e vira divergência de fatura.
        String valor = new java.math.BigDecimal(valorCentavos)
                .movePointLeft(2).toPlainString();

        JsonNode resposta = cliente.post()
                .uri("/subscriptions")
                .bodyValue(Map.of(
                        "customer", clienteNoGateway,
                        "billingType", "UNDEFINED",
                        "value", valor,
                        "nextDueDate", LocalDate.now().plusDays(1).toString(),
                        "cycle", "MONTHLY",
                        "description", "JusPrisma " + planoCodigo,
                        "externalReference", planoCodigo))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(20));

        return new AssinaturaNoGateway(
                exigir(resposta, "id"),
                proximaCobranca(resposta));
    }

    @Override
    @Retry(name = "asaas")
    @CircuitBreaker(name = "asaas")
    public void cancelarAssinatura(String assinaturaNoGateway) {
        cliente.delete()
                .uri("/subscriptions/{id}", assinaturaNoGateway)
                .retrieve()
                .bodyToMono(Void.class)
                .block(Duration.ofSeconds(20));
    }

    @Override
    public boolean notificacaoAutentica(String tokenRecebido) {
        if (tokenDoWebhook.isBlank()) {
            // Sem token configurado, a rota ficaria aberta a qualquer um enviar "pagamento
            // confirmado" e ganhar acesso pago. Recusar tudo é o único padrão seguro.
            log.error("token de webhook do Asaas não configurado; notificações serão recusadas");
            return false;
        }
        if (tokenRecebido == null) {
            return false;
        }
        // Comparação de tempo constante: comparar com equals vaza, pelo tempo de resposta,
        // quantos caracteres iniciais o atacante acertou.
        return MessageDigest.isEqual(
                tokenRecebido.getBytes(StandardCharsets.UTF_8),
                tokenDoWebhook.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Optional<EventoDeCobranca> interpretar(String corpo) {
        try {
            JsonNode raiz = JSON.readTree(corpo);
            String id = raiz.path("id").asString();
            String tipoAsaas = raiz.path("event").asString();

            if (id == null || id.isBlank()) {
                log.warn("notificação do Asaas sem identificador de evento; descartada");
                return Optional.empty();
            }

            JsonNode pagamento = raiz.path("payment");
            return Optional.of(new EventoDeCobranca(
                    id,
                    traduzir(tipoAsaas),
                    pagamento.path("subscription").asString(),
                    proximoVencimento(pagamento),
                    corpo));
        } catch (RuntimeException e) {
            log.warn("notificação do Asaas em formato inesperado; descartada", e);
            return Optional.empty();
        }
    }

    /**
     * Traduz o vocabulário do fornecedor para o do domínio.
     *
     * <p>É aqui que a troca de gateway deixa de ser reescrita: o caso de uso reage a
     * "pagamento confirmado", e não a {@code PAYMENT_CONFIRMED}.
     */
    private static EventoDeCobranca.Tipo traduzir(String eventoAsaas) {
        if (eventoAsaas == null) {
            return EventoDeCobranca.Tipo.DESCONHECIDO;
        }
        return switch (eventoAsaas) {
            case "PAYMENT_CONFIRMED", "PAYMENT_RECEIVED" ->
                    EventoDeCobranca.Tipo.PAGAMENTO_CONFIRMADO;
            case "PAYMENT_OVERDUE", "PAYMENT_REFUSED" ->
                    EventoDeCobranca.Tipo.PAGAMENTO_FALHOU;
            case "SUBSCRIPTION_DELETED", "SUBSCRIPTION_INACTIVATED" ->
                    EventoDeCobranca.Tipo.ASSINATURA_CANCELADA;
            default -> EventoDeCobranca.Tipo.DESCONHECIDO;
        };
    }

    private static Instant proximaCobranca(JsonNode resposta) {
        return proximoVencimento(resposta);
    }

    private static Instant proximoVencimento(JsonNode no) {
        String data = no.path("nextDueDate").asString();
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(data)
                    .atStartOfDay(java.time.ZoneId.of("America/Sao_Paulo"))
                    .toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String exigir(JsonNode resposta, String campo) {
        String valor = resposta == null ? null : resposta.path(campo).asString();
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(
                    "resposta do Asaas sem o campo obrigatório '%s'".formatted(campo));
        }
        return valor;
    }
}
