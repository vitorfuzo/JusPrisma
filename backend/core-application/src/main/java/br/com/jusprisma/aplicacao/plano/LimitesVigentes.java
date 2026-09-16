package br.com.jusprisma.aplicacao.plano;

import br.com.jusprisma.aplicacao.porta.EscopoDeTenant;
import br.com.jusprisma.dominio.plano.Assinatura;
import br.com.jusprisma.dominio.plano.Cota;
import br.com.jusprisma.dominio.plano.LimitesDoPlano;
import br.com.jusprisma.dominio.plano.Plano;
import br.com.jusprisma.dominio.plano.Recurso;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Responde o que o plano do escritório permite agora.
 *
 * <p>É o único caminho por onde uma cota deve ser consultada. Concentrar aqui garante que
 * nenhum caso de uso acabe comparando com um número escrito no código — mudar o preço ou a
 * cota de um plano tem que continuar sendo um {@code UPDATE}.
 */
@Service
public class LimitesVigentes {

    private final RepositorioDeAssinatura assinaturas;
    private final RepositorioDePlano planos;
    private final EscopoDeTenant escopo;

    public LimitesVigentes(RepositorioDeAssinatura assinaturas,
                           RepositorioDePlano planos,
                           EscopoDeTenant escopo) {
        this.assinaturas = assinaturas;
        this.planos = planos;
        this.escopo = escopo;
    }

    /**
     * @throws SemAssinaturaVigenteException se o escritório não tem plano em vigor. Falhar
     *         aqui é melhor que assumir um plano padrão: assumir liberaria recurso pago de
     *         graça, e o erro só apareceria na conta do mês.
     */
    public LimitesDoPlano de(UUID tenantId) {
        return planoDe(tenantId).limites();
    }

    public Plano planoDe(UUID tenantId) {
        Assinatura assinatura = escopo.executarComo(tenantId,
                () -> assinaturas.vigenteDoTenant(tenantId).orElse(null));

        if (assinatura == null || !assinatura.vigente()) {
            throw new SemAssinaturaVigenteException();
        }

        return planos.buscar(assinatura.planoCodigo())
                .orElseThrow(() -> new IllegalStateException(
                        "assinatura aponta para plano inexistente: " + assinatura.planoCodigo()));
    }

    /**
     * Confere se cabe mais uma unidade e recusa se não couber.
     *
     * @param jaConsumido quanto já foi usado da cota no período corrente
     */
    public void exigirEspacoEm(UUID tenantId, Cota cota, int jaConsumido) {
        LimitesDoPlano limites = de(tenantId);
        if (limites.cabeMais(cota, jaConsumido)) {
            return;
        }

        // Duas situações diferentes, e a mensagem precisa distinguir: o plano não oferece
        // o recurso, ou oferece e a cota acabou. Dizer "permite 0 e já há 0 em uso" para o
        // primeiro caso confunde exatamente quem está decidindo se faz upgrade.
        throw new CotaExcedidaException(cota, limites.teto(cota).orElse(0) == 0
                ? "seu plano não inclui este recurso"
                : "seu plano permite %d e você já usa %d"
                        .formatted(limites.teto(cota).getAsInt(), jaConsumido));
    }

    public void exigirRecurso(UUID tenantId, Recurso recurso) {
        if (!de(tenantId).permite(recurso)) {
            throw new CotaExcedidaException(null,
                    "este recurso não está incluído no plano do escritório");
        }
    }
}
