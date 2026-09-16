package br.com.jusprisma.aplicacao.cobranca;

import java.util.Optional;

/**
 * Porta do gateway de cobrança.
 *
 * <p>Existe porque a troca já está no roteiro: começamos no Asaas (2,99% no cartão) e a
 * intenção é migrar para Pix Automático (0,22–0,35%, sem tarifa fixa) quando houver volume.
 * Num ticket de R$137, essa diferença é a conta de infraestrutura inteira — e a migração
 * não pode significar reescrever o módulo de assinatura.
 *
 * <p>Por isso a porta fala de assinatura e cliente, não de "customer" e "subscription" do
 * vocabulário de um fornecedor específico.
 */
public interface GatewayDePagamento {

    String nome();

    ClienteNoGateway criarCliente(DadosDoCliente dados);

    AssinaturaNoGateway assinar(String clienteNoGateway, String planoCodigo, int valorCentavos);

    void cancelarAssinatura(String assinaturaNoGateway);

    /**
     * Confere se a notificação veio mesmo do gateway.
     *
     * <p>A rota de webhook é pública por natureza. Sem esta verificação, qualquer um manda
     * um "pagamento confirmado" e ganha acesso pago de graça.
     */
    boolean notificacaoAutentica(String tokenRecebido);

    /** Interpreta o corpo da notificação no vocabulário do domínio. */
    Optional<EventoDeCobranca> interpretar(String corpo);

    record DadosDoCliente(String nome, String email, String cnpj) {
    }

    record ClienteNoGateway(String id) {
    }

    record AssinaturaNoGateway(String id, java.time.Instant proximaCobranca) {
    }
}
