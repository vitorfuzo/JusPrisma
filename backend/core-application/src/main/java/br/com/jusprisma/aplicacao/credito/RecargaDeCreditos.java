package br.com.jusprisma.aplicacao.credito;

import br.com.jusprisma.aplicacao.plano.LimitesVigentes;
import br.com.jusprisma.dominio.credito.TipoCredito;
import br.com.jusprisma.dominio.plano.LimitesDoPlano;
import br.com.jusprisma.dominio.plano.Plano;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Abastece o ledger com as cotas do plano no início de cada período.
 *
 * <p>Isto resolve uma ambiguidade que existia entre dois mecanismos: a cota declarada em
 * {@code Plano.limites} e o saldo do ledger. Eles não são concorrentes — a cota diz quanto
 * o plano dá, e o ledger é onde isso vira saldo movimentável, com extrato auditável.
 *
 * <p>A divisão entre os dois tipos de limite é a seguinte:
 * <ul>
 *   <li><strong>Cotas de consumo</strong> (perfis, IA, cálculos, consultas, assinaturas)
 *       viram crédito aqui. Gastar é um lançamento, e o extrato explica a conta ao cliente.
 *   <li><strong>Cotas de estado</strong> (subusuários, armazenamento, comparações
 *       simultâneas) não viram crédito, porque não são consumidas e sim ocupadas. São
 *       conferidas contra a situação atual, em {@code LimitesVigentes}.
 * </ul>
 *
 * <p>Cota ilimitada não gera lançamento: não existe número que represente infinito num
 * saldo. O débito de um tipo ilimitado é registrado para auditoria, mas não exige saldo —
 * ver {@link Creditos#debitar}.
 */
@Service
public class RecargaDeCreditos {

    private static final Logger log = LoggerFactory.getLogger(RecargaDeCreditos.class);

    private final Creditos creditos;
    private final LimitesVigentes limites;

    public RecargaDeCreditos(Creditos creditos, LimitesVigentes limites) {
        this.creditos = creditos;
        this.limites = limites;
    }

    /**
     * Credita as cotas do plano vigente.
     *
     * @param motivo aparece no extrato do cliente. Use algo que explique a origem —
     *               "início da degustação", "renovação de setembro" —, porque um extrato
     *               com lançamentos sem contexto é impossível de justificar meses depois.
     */
    public void abastecer(UUID tenantId, String motivo) {
        Plano plano = limites.planoDe(tenantId);
        LimitesDoPlano cotas = plano.limites();

        for (TipoCredito tipo : TipoCredito.values()) {
            if (cotas.ilimitada(tipo.cota()) || !cotas.configurada(tipo.cota())) {
                // Ilimitada não tem número para creditar; não configurada é negação, e
                // creditar zero só poluiria o extrato com linhas sem efeito.
                continue;
            }

            int quantidade = cotas.teto(tipo.cota()).orElse(0);
            if (quantidade > 0) {
                creditos.creditar(tenantId, tipo, quantidade, motivo, plano.codigo());
            }
        }

        log.info("créditos do plano {} abastecidos para o tenant {}: {}",
                plano.codigo(), tenantId, motivo);
    }
}
