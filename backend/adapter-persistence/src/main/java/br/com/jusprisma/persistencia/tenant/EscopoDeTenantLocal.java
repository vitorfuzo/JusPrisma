package br.com.jusprisma.persistencia.tenant;

import br.com.jusprisma.aplicacao.porta.EscopoDeTenant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Publica o tenant no contexto da thread e só então abre a transação, para que o
 * {@link GerenciadorDeTransacaoComTenant} o encontre no {@code doBegin}.
 *
 * <p>Usa propagação {@code REQUIRES_NEW}: se houvesse uma transação já aberta, ela teria
 * começado sem o tenant publicado, e aderir a ela devolveria zero linhas em toda consulta.
 * Exigir transação nova torna impossível herdar uma transação cega por engano.
 */
@Component
public class EscopoDeTenantLocal implements EscopoDeTenant {

    private final TransactionTemplate transacao;

    public EscopoDeTenantLocal(PlatformTransactionManager gerenciadorDeTransacao) {
        this.transacao = new TransactionTemplate(gerenciadorDeTransacao);
        this.transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T executarComo(UUID tenantId, Supplier<T> acao) {
        if (tenantId == null) {
            throw new IllegalArgumentException("escopo de tenant exige um tenant");
        }
        return ContextoDeTenant.executarCom(tenantId,
                () -> transacao.execute(status -> acao.get()));
    }
}
