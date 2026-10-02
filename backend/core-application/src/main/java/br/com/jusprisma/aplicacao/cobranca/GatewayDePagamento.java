package br.com.jusprisma.aplicacao.cobranca;

import java.time.LocalDate;
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

    /**
     * Devolve o cliente com esta referência externa, criando-o se ainda não existir, e
     * garante que o documento esteja registrado nele.
     *
     * <p>"Garantir" e não "criar": a chamada ao gateway não participa da transação do banco.
     * Se uma tentativa anterior criou o cliente e falhou depois, a próxima tem que encontrá-lo
     * em vez de criar um segundo.
     *
     * @throws CobrancaRecusadaException se o gateway recusar os dados.
     * @throws GatewayIndisponivelException se o gateway não responder.
     */
    ClienteNoGateway garantirCliente(DadosDoCliente dados);

    /**
     * Devolve a assinatura ativa com esta referência externa, criando-a se ainda não existir.
     *
     * <p>É aqui que a idempotência evita cobrança em dobro: se o gateway criou a assinatura e
     * o nosso commit falhou, a retentativa encontra a que já existe.
     */
    AssinaturaNoGateway garantirAssinatura(NovaAssinatura dados);

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

    /** {@code documento} são só os dígitos do CPF ou CNPJ, já validados. */
    record DadosDoCliente(String referenciaExterna, String nome, String email, String documento) {

        @Override
        public String toString() {
            return "DadosDoCliente[referencia=%s]".formatted(referenciaExterna);
        }
    }

    record ClienteNoGateway(String id) {
    }

    record NovaAssinatura(
            String referenciaExterna,
            String clienteNoGateway,
            String planoCodigo,
            int valorCentavos,
            LocalDate primeiraCobranca) {
    }

    record AssinaturaNoGateway(String id) {
    }
}
