package br.com.jusprisma.aplicacao.conta;

import br.com.jusprisma.dominio.conta.SessaoRefresh;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistência das sessões de renovação.
 *
 * <p>Todas as operações rodam dentro do escopo de um tenant e sofrem RLS: uma sessão de
 * outro escritório simplesmente não é encontrada.
 */
public interface RepositorioDeSessao {

    void registrar(SessaoRefresh sessao);

    Optional<SessaoRefresh> buscarPorId(UUID id);

    /**
     * Marca a sessão como consumida, apenas se ainda não estiver.
     *
     * @return true se esta chamada foi quem consumiu. Duas renovações simultâneas com o
     *         mesmo token fazem exatamente uma receber true — é o que impede que uma corrida
     *         entregue dois tokens válidos a partir de um só.
     */
    boolean consumir(UUID id);

    int revogarFamilia(UUID familiaId, SessaoRefresh.MotivoDeRevogacao motivo);
}
