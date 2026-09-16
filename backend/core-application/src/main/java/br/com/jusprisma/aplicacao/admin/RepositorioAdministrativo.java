package br.com.jusprisma.aplicacao.admin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Leitura e escrita administrativa, atravessando a fronteira de tenant.
 *
 * <p>Implementado sobre funções {@code SECURITY DEFINER} estreitas — ver V7. Elas devolvem
 * o que o painel mostra e nada do conteúdo de trabalho dos clientes: nenhuma decisão,
 * nenhum processo, nenhum documento. A equipe da plataforma precisa saber quanto um
 * escritório consumiu, não o que ele está advogando.
 */
public interface RepositorioAdministrativo {

    List<PainelAdministrativo.ResumoDoTenant> listarTenants(int limite, int deslocamento);

    List<PainelAdministrativo.ConsumoDeCredito> consumoDoTenant(UUID tenantId);

    Optional<CredenciaisDeAdministrador> buscarCredenciais(String email);

    void registrarUltimoAcesso(UUID administradorId);

    void registrarAcao(UUID administradorId, String acao, UUID tenantAlvo, Map<String, Object> detalhe);

    /**
     * Cria o primeiro operador, somente se ainda nao houver nenhum.
     *
     * @return true se criou. Falso significa que ja existia operador, o que e' o caso
     *         normal em toda subida depois da primeira.
     */
    boolean criarPrimeiroOperador(String email, String senhaHash, String nome);

    /** Como {@code CredenciaisDeAcesso}, carrega hash e por isso não circula pela aplicação. */
    record CredenciaisDeAdministrador(UUID id, String nome, String senhaHash, boolean ativo) {

        @Override
        public String toString() {
            return "CredenciaisDeAdministrador[id=%s, nome=%s]".formatted(id, nome);
        }
    }
}
