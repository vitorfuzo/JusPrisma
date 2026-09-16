package br.com.jusprisma.aplicacao.credito;

import br.com.jusprisma.dominio.credito.CreditoLancamento;
import br.com.jusprisma.dominio.credito.TipoCredito;

import java.util.Optional;
import java.util.UUID;

public interface RepositorioDeCredito {

    /**
     * Serializa as operações de crédito do escritório na transação corrente.
     *
     * <p>Sem isso, duas requisições simultâneas leem o mesmo saldo, ambas concluem que há
     * crédito e ambas debitam — o cliente usa duas vezes o que pagou uma. Como o ledger é
     * append-only, não existe linha de saldo para travar; travamos a linha do escritório.
     *
     * <p>Precisa ser chamado <em>antes</em> de ler o saldo, dentro da mesma transação.
     */
    void travarParaAtualizacao(UUID tenantId);

    int saldo(UUID tenantId, TipoCredito tipo);

    /** @return o lançamento com o id atribuído pelo banco. */
    CreditoLancamento registrar(CreditoLancamento lancamento);

    Optional<CreditoLancamento> buscar(long lancamentoId);

    /** Quanto foi consumido de um tipo desde um instante — base para cota mensal. */
    int consumidoDesde(UUID tenantId, TipoCredito tipo, java.time.Instant desde);
}
