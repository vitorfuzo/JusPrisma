package br.com.jusprisma.persistencia.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * O tenant da requisição corrente.
 *
 * <p>É preenchido pela camada web a partir do token de autenticação e lido pelo
 * {@link GerenciadorDeTransacaoComTenant} no início de cada transação, que o repassa ao
 * Postgres. Daí em diante quem faz o isolamento é o banco, via Row Level Security.
 *
 * <p>Fica vazio de propósito em fluxos que ainda não têm tenant — cadastro de conta nova e
 * autenticação, que por natureza acontecem antes de existir um. Nesses casos o banco não
 * enxerga nada, que é o comportamento correto: as exceções são explícitas e nomeadas, via
 * funções SECURITY DEFINER, e não um relaxamento geral das policies.
 */
public final class ContextoDeTenant {

    private static final ThreadLocal<UUID> ATUAL = new ThreadLocal<>();

    private ContextoDeTenant() {
    }

    public static Optional<UUID> atual() {
        return Optional.ofNullable(ATUAL.get());
    }

    public static void definir(UUID tenantId) {
        ATUAL.set(tenantId);
    }

    /**
     * Executa a ação com o tenant informado e restaura o valor anterior ao final.
     *
     * <p>Restaurar em vez de limpar importa porque threads são reaproveitadas por pool:
     * limpar cegamente faria uma chamada aninhada apagar o tenant da chamada externa.
     */
    public static <T> T executarCom(UUID tenantId, java.util.function.Supplier<T> acao) {
        UUID anterior = ATUAL.get();
        ATUAL.set(tenantId);
        try {
            return acao.get();
        } finally {
            if (anterior == null) {
                ATUAL.remove();
            } else {
                ATUAL.set(anterior);
            }
        }
    }

    public static void limpar() {
        ATUAL.remove();
    }
}
