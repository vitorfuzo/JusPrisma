package br.com.jusprisma.aplicacao.porta;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Executa uma ação numa transação que pertence a um tenant específico.
 *
 * <p>O escopo abre a transação, e não o contrário. A ordem não é estilo: quem define o
 * tenant é uma variável de escopo local da transação do Postgres, publicada no instante em
 * que a transação começa. Se um {@code @Transactional} abrisse a transação antes de o
 * tenant ser conhecido, a variável não seria publicada e todas as consultas dali em diante
 * enxergariam zero linhas — falha silenciosa e difícil de rastrear.
 *
 * <p>Concentrar as duas coisas nesta porta faz a ordem certa ser a única possível.
 *
 * <p>Os fluxos que acontecem antes de existir tenant — cadastro, antes de criar a conta, e
 * autenticação — não passam por aqui: usam funções {@code SECURITY DEFINER} estreitas e
 * nomeadas.
 */
public interface EscopoDeTenant {

    <T> T executarComo(UUID tenantId, Supplier<T> acao);

    default void executarComo(UUID tenantId, Runnable acao) {
        executarComo(tenantId, () -> {
            acao.run();
            return null;
        });
    }
}
