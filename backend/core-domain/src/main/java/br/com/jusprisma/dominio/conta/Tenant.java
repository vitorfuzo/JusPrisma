package br.com.jusprisma.dominio.conta;

import java.time.Instant;
import java.util.UUID;

/**
 * A conta contratante — na prática, o escritório de advocacia.
 *
 * <p>É a fronteira de isolamento do sistema inteiro: todo dado de trabalho pertence a um
 * tenant, e o Postgres recusa leitura fora dele por Row Level Security.
 */
public record Tenant(
        UUID id,
        String nome,
        String cnpj,
        Status status,
        Instant criadoEm) {

    public enum Status {
        ATIVO,
        SUSPENSO,
        CANCELADO
    }

    public Tenant {
        if (id == null) {
            throw new IllegalArgumentException("id do tenant é obrigatório");
        }
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("nome do tenant é obrigatório");
        }
        nome = nome.trim();
        if (status == null) {
            throw new IllegalArgumentException("status do tenant é obrigatório");
        }
    }

    /**
     * Cria um tenant novo com id já definido.
     *
     * <p>O id nasce na aplicação, e não no {@code DEFAULT} da coluna, porque o cadastro
     * precisa declarar o tenant à transação — via {@code app.tenant_id} — antes de inserir
     * a linha. Sem o id em mãos de antemão, a própria policy de RLS recusaria o INSERT.
     */
    public static Tenant novo(String nome, String cnpj) {
        return new Tenant(UUID.randomUUID(), nome, cnpj, Status.ATIVO, Instant.now());
    }

    public boolean operacional() {
        return status == Status.ATIVO;
    }
}
