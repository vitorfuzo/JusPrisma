package br.com.jusprisma.cobranca;

import br.com.jusprisma.aplicacao.cobranca.CobrancaRecusadaException;
import br.com.jusprisma.aplicacao.cobranca.EventoDeCobranca;
import br.com.jusprisma.aplicacao.cobranca.GatewayDePagamento;
import br.com.jusprisma.aplicacao.cobranca.GatewayIndisponivelException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

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
    private static final Duration TEMPO_LIMITE = Duration.ofSeconds(20);

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
    @CircuitBreaker(name = "asaas", fallbackMethod = "clienteIndisponivel")
    public ClienteNoGateway garantirCliente(DadosDoCliente dados) {
        // A referência externa é o tenant. Buscar antes de criar é o que torna a chamada
        // repetível: o retry acima, ou uma nova tentativa do advogado, encontra o cliente
        // criado por uma tentativa que falhou depois.
        JsonNode existente = primeiro(chamar(() -> cliente.get()
                .uri(uri -> uri.path("/customers")
                        .queryParam("externalReference", dados.referenciaExterna())
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(TEMPO_LIMITE)));

        Map<String, Object> corpo = Map.of(
                "name", dados.nome(),
                "email", dados.email(),
                "cpfCnpj", dados.documento(),
                "externalReference", dados.referenciaExterna(),
                // Sem isto o Asaas manda e-mail e SMS próprios de cobrança, duplicando os
                // nossos avisos com outra marca.
                "notificationDisabled", true);

        if (existente != null) {
            String id = exigir(existente, "id");
            // Atualiza sempre: o documento pode ter sido corrigido entre uma tentativa e outra.
            chamar(() -> cliente.put()
                    .uri("/customers/{id}", id)
                    .bodyValue(corpo)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(TEMPO_LIMITE));
            return new ClienteNoGateway(id);
        }

        JsonNode criado = chamar(() -> cliente.post()
                .uri("/customers")
                .bodyValue(corpo)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(TEMPO_LIMITE));
        return new ClienteNoGateway(exigir(criado, "id"));
    }

    @Override
    @Retry(name = "asaas")
    @CircuitBreaker(name = "asaas", fallbackMethod = "assinaturaIndisponivel")
    public AssinaturaNoGateway garantirAssinatura(NovaAssinatura dados) {
        // Uma assinatura ativa com esta referência já existe quando o gateway criou e o nosso
        // commit falhou. Criar de novo seria cobrar o escritório duas vezes por mês.
        JsonNode existente = primeiro(chamar(() -> cliente.get()
                .uri(uri -> uri.path("/subscriptions")
                        .queryParam("externalReference", dados.referenciaExterna())
                        .queryParam("status", "ACTIVE")
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(TEMPO_LIMITE)));
        if (existente != null) {
            return new AssinaturaNoGateway(exigir(existente, "id"));
        }

        // O Asaas trabalha com valor em reais decimais, não em centavos. A conversão fica
        // aqui, na borda: o domínio inteiro raciocina em centavos, porque valor monetário
        // em ponto flutuante acumula erro e vira divergência de fatura.
        String valor = new java.math.BigDecimal(dados.valorCentavos())
                .movePointLeft(2).toPlainString();

        JsonNode criada = chamar(() -> cliente.post()
                .uri("/subscriptions")
                .bodyValue(Map.of(
                        "customer", dados.clienteNoGateway(),
                        "billingType", "UNDEFINED",
                        "value", valor,
                        "nextDueDate", dados.primeiraCobranca().toString(),
                        "cycle", "MONTHLY",
                        "description", "JusPrisma " + dados.planoCodigo(),
                        "externalReference", dados.referenciaExterna()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(TEMPO_LIMITE));

        return new AssinaturaNoGateway(exigir(criada, "id"));
    }

    // Circuito aberto: a chamada nem saiu. O tipo da exceção restringe o fallback a este
    // caso; as demais seguem como foram lançadas.
    @SuppressWarnings("unused")
    private ClienteNoGateway clienteIndisponivel(DadosDoCliente dados, CallNotPermittedException e) {
        throw new GatewayIndisponivelException("gateway de cobrança em pausa após falhas seguidas", e);
    }

    @SuppressWarnings("unused")
    private AssinaturaNoGateway assinaturaIndisponivel(NovaAssinatura dados, CallNotPermittedException e) {
        throw new GatewayIndisponivelException("gateway de cobrança em pausa após falhas seguidas", e);
    }

    @Override
    @Retry(name = "asaas")
    @CircuitBreaker(name = "asaas")
    public void cancelarAssinatura(String assinaturaNoGateway) {
        cliente.delete()
                .uri("/subscriptions/{id}", assinaturaNoGateway)
                .retrieve()
                .bodyToMono(Void.class)
                .block(TEMPO_LIMITE);
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
                    pagamento.path("id").asString(),
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
            // Os dois, e não um só: Pix só emite RECEIVED, e no cartão o RECEIVED chega ~32
            // dias depois do CONFIRMED. Boleto e cartão emitem ambos para o mesmo pagamento;
            // quem impede a recarga em dobro é a idempotência por pagamento.
            case "PAYMENT_CONFIRMED", "PAYMENT_RECEIVED" ->
                    EventoDeCobranca.Tipo.PAGAMENTO_CONFIRMADO;
            case "PAYMENT_OVERDUE", "PAYMENT_REFUSED" ->
                    EventoDeCobranca.Tipo.PAGAMENTO_FALHOU;
            case "SUBSCRIPTION_DELETED", "SUBSCRIPTION_INACTIVATED" ->
                    EventoDeCobranca.Tipo.ASSINATURA_CANCELADA;
            default -> EventoDeCobranca.Tipo.DESCONHECIDO;
        };
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

    /**
     * Traduz falha de transporte para o vocabulário da porta.
     *
     * <p>4xx é o Asaas recusando os dados — a descrição dele vai ao advogado, que pode
     * corrigir. 401 e 403 são exceção: chave nossa errada não é algo que o cliente conserte.
     * O resto (5xx, timeout, rede) é indisponibilidade e passa pelo retry.
     */
    private static JsonNode chamar(Supplier<JsonNode> chamada) {
        try {
            return chamada.get();
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                log.error("Asaas recusou a credencial ({}); verifique a chave configurada", status);
                throw new CredencialRecusadaPeloAsaasException(e);
            }
            if (e.getStatusCode().is4xxClientError()) {
                throw new CobrancaRecusadaException(descricaoDoErro(e.getResponseBodyAsString()));
            }
            throw new GatewayIndisponivelException("gateway respondeu " + status, e);
        } catch (WebClientRequestException e) {
            throw new GatewayIndisponivelException("gateway inacessível", e);
        } catch (IllegalStateException e) {
            // block(Duration) sinaliza o tempo esgotado com IllegalStateException.
            throw new GatewayIndisponivelException("gateway não respondeu a tempo", e);
        }
    }

    private static String descricaoDoErro(String corpo) {
        try {
            List<String> descricoes = new ArrayList<>();
            for (JsonNode erro : JSON.readTree(corpo).path("errors")) {
                String descricao = erro.path("description").asString();
                if (descricao != null && !descricao.isBlank()) {
                    descricoes.add(descricao);
                }
            }
            if (!descricoes.isEmpty()) {
                return String.join(" ", descricoes);
            }
        } catch (RuntimeException ignorado) {
            // Corpo fora do formato documentado; cai na mensagem genérica.
        }
        return "o gateway de cobrança recusou os dados informados";
    }

    private static JsonNode primeiro(JsonNode lista) {
        JsonNode dados = lista == null ? null : lista.path("data");
        return dados != null && dados.isArray() && !dados.isEmpty() ? dados.get(0) : null;
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
