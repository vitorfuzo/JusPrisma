package br.com.jusprisma.aplicacao.admin;

import br.com.jusprisma.aplicacao.credito.Creditos;
import br.com.jusprisma.dominio.credito.TipoCredito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operações da equipe do JusPrisma sobre contas de clientes.
 *
 * <p>Tudo aqui atravessa a fronteira de tenant por necessidade — é o único lugar do
 * sistema que deve fazer isso de forma ampla — e por isso tudo aqui deixa trilha. Conceder
 * crédito mexe em algo que vale dinheiro; sem registro de quem fez, quando e por quê, não
 * há como distinguir correção legítima de fraude interna, nem responder a um cliente que
 * questione o próprio extrato.
 */
@Service
public class PainelAdministrativo {

    private static final Logger log = LoggerFactory.getLogger(PainelAdministrativo.class);

    private final RepositorioAdministrativo repositorio;
    private final Creditos creditos;

    public PainelAdministrativo(RepositorioAdministrativo repositorio, Creditos creditos) {
        this.repositorio = repositorio;
        this.creditos = creditos;
    }

    public record ResumoDoTenant(
            UUID tenantId,
            String nome,
            String status,
            Instant criadoEm,
            String planoCodigo,
            String statusAssinatura,
            long usuarios) {
    }

    public record ConsumoDeCredito(TipoCredito tipo, long saldo, long consumido) {
    }

    public List<ResumoDoTenant> listarTenants(int limite, int deslocamento) {
        // Teto no tamanho da página: um painel que aceita "limite=1000000" vira um jeito
        // fácil de derrubar o banco a partir da própria interface interna.
        return repositorio.listarTenants(Math.clamp(limite, 1, 200), Math.max(0, deslocamento));
    }

    public List<ConsumoDeCredito> consumoDoTenant(UUID tenantId) {
        return repositorio.consumoDoTenant(tenantId);
    }

    /**
     * Concede crédito manualmente a um escritório.
     *
     * <p>Existe para o caso concreto de falha nossa: um perfil que não gerou por erro de
     * pipeline, uma cobrança que entrou errado. O motivo é obrigatório e vai para o extrato
     * do cliente — quem lê o próprio extrato precisa entender de onde veio aquele crédito.
     */
    public void concederCredito(UUID administradorId, UUID tenantId, TipoCredito tipo,
                                int quantidade, String motivo) {
        if (quantidade <= 0) {
            throw new IllegalArgumentException("a concessão precisa ser positiva");
        }
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("toda concessão precisa de motivo");
        }

        creditos.creditar(tenantId, tipo, quantidade, motivo, "concessao-manual");

        repositorio.registrarAcao(administradorId, "CONCEDER_CREDITO", tenantId, Map.of(
                "tipo", tipo.name(),
                "quantidade", quantidade,
                "motivo", motivo));

        log.warn("concessão manual de {} {} ao tenant {} pelo administrador {}: {}",
                quantidade, tipo, tenantId, administradorId, motivo);
    }
}
