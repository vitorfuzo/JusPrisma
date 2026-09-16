package br.com.jusprisma.aplicacao.credito;

import br.com.jusprisma.aplicacao.porta.EscopoDeTenant;
import br.com.jusprisma.dominio.credito.CreditoLancamento;
import br.com.jusprisma.dominio.credito.TipoCredito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Movimenta o ledger de créditos.
 *
 * <p>Saldo é sempre {@code SUM(delta)} — nunca um campo. Não há como o saldo divergir do
 * extrato, porque o saldo <em>é</em> o extrato somado.
 */
@Service
public class Creditos {

    private static final Logger log = LoggerFactory.getLogger(Creditos.class);

    private final RepositorioDeCredito repositorio;
    private final EscopoDeTenant escopo;

    public Creditos(RepositorioDeCredito repositorio, EscopoDeTenant escopo) {
        this.repositorio = repositorio;
        this.escopo = escopo;
    }

    public int saldo(UUID tenantId, TipoCredito tipo) {
        return escopo.executarComo(tenantId, () -> repositorio.saldo(tenantId, tipo));
    }

    public int consumidoNoPeriodo(UUID tenantId, TipoCredito tipo, Instant desde) {
        return escopo.executarComo(tenantId,
                () -> repositorio.consumidoDesde(tenantId, tipo, desde));
    }

    public CreditoLancamento creditar(UUID tenantId, TipoCredito tipo, int quantidade,
                                      String motivo, String referenciaId) {
        return escopo.executarComo(tenantId, () -> repositorio.registrar(
                CreditoLancamento.credito(tenantId, tipo, quantidade, motivo, referenciaId)));
    }

    /**
     * Debita, recusando se não houver saldo.
     *
     * <p>A trava vem antes da leitura, dentro da mesma transação. Ler o saldo e depois
     * gravar, sem trava, deixa duas requisições simultâneas verem o mesmo saldo, ambas
     * concluírem que há crédito e ambas debitarem — e o cliente usa duas vezes o que pagou
     * uma. Com poucos créditos por plano, isso não é caso raro de laboratório: são dois
     * cliques no mesmo botão.
     *
     * @throws SaldoInsuficienteException se o saldo não cobre a quantidade.
     */
    public CreditoLancamento debitar(UUID tenantId, TipoCredito tipo, int quantidade,
                                     String motivo, String referenciaId) {
        return escopo.executarComo(tenantId, () -> {
            repositorio.travarParaAtualizacao(tenantId);

            int saldo = repositorio.saldo(tenantId, tipo);
            if (saldo < quantidade) {
                throw new SaldoInsuficienteException(tipo, saldo, quantidade);
            }

            CreditoLancamento debito = repositorio.registrar(
                    CreditoLancamento.debito(tenantId, tipo, quantidade, motivo, referenciaId));

            log.info("débito de {} {} no tenant {}; saldo anterior {}",
                    quantidade, tipo, tenantId, saldo);
            return debito;
        });
    }

    /**
     * Estorna um lançamento anterior.
     *
     * <p>Lançamento novo, de sinal oposto, apontando para o original — nunca alteração nem
     * remoção do original. O banco recusa alteração e remoção por gatilho, de forma que
     * este é o único caminho possível.
     *
     * <p>Um índice único sobre {@code estorno_de} impede estornar duas vezes o mesmo
     * lançamento, o que devolveria crédito em dobro.
     */
    public CreditoLancamento estornar(UUID tenantId, long lancamentoId, String motivo) {
        return escopo.executarComo(tenantId, () -> {
            CreditoLancamento original = repositorio.buscar(lancamentoId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "lançamento inexistente: " + lancamentoId));

            CreditoLancamento estorno = repositorio.registrar(original.estornar(motivo));
            log.info("estorno do lançamento {} no tenant {}: {}", lancamentoId, tenantId, motivo);
            return estorno;
        });
    }
}
