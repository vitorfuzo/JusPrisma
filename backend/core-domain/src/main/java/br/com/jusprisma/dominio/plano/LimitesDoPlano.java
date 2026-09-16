package br.com.jusprisma.dominio.plano;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * O que um plano permite.
 *
 * <p>Os valores vêm de {@code plano.limites} (JSONB) e nunca de {@code if} no código:
 * mudar preço ou cota tem que ser mudança de dado. O que é código aqui é apenas a forma —
 * quais cotas existem e como se lê uma.
 *
 * <p>Três estados, e a distinção entre eles importa:
 * <ul>
 *   <li>cota com número: o teto é aquele;
 *   <li>cota com {@code null}: ilimitada — é assim que se representa "jurisprudência
 *       ilimitada em todos os planos", cujo custo marginal é zero;
 *   <li>cota <strong>ausente</strong>: negada.
 * </ul>
 *
 * <p>Ausente significar negação é deliberado, e é a mesma escolha feita no Row Level
 * Security: falhar fechado. Se alguém adicionar uma cota nova ao enum e esquecer de
 * incluí-la nos planos, o efeito é bloqueio visível em vez de liberação silenciosa —
 * e liberação silenciosa de recurso pago é prejuízo que ninguém percebe.
 */
public record LimitesDoPlano(Map<Cota, Integer> cotas, Set<Recurso> recursos) {

    public LimitesDoPlano {
        cotas = cotas == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new EnumMap<>(cotas));
        recursos = recursos == null || recursos.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(Recurso.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(recursos));
    }

    /**
     * O teto da cota.
     *
     * @return o número quando há teto; vazio quando a cota é ilimitada <em>ou</em> quando
     *         não está configurada. Para distinguir os dois casos use {@link #configurada}.
     */
    public OptionalInt teto(Cota cota) {
        Integer valor = cotas.get(cota);
        return valor == null ? OptionalInt.empty() : OptionalInt.of(valor);
    }

    public boolean configurada(Cota cota) {
        return cotas.containsKey(cota);
    }

    public boolean ilimitada(Cota cota) {
        return cotas.containsKey(cota) && cotas.get(cota) == null;
    }

    /**
     * Quantas unidades ainda cabem, dado o quanto já foi consumido.
     *
     * @return vazio quando ilimitada; zero quando a cota não está configurada.
     */
    public OptionalInt disponivel(Cota cota, int jaConsumido) {
        if (ilimitada(cota)) {
            return OptionalInt.empty();
        }
        if (!configurada(cota)) {
            return OptionalInt.of(0);
        }
        return OptionalInt.of(Math.max(0, cotas.get(cota) - jaConsumido));
    }

    /** Verdadeiro se ainda cabe mais uma unidade. Ilimitada sempre cabe. */
    public boolean cabeMais(Cota cota, int jaConsumido) {
        OptionalInt disponivel = disponivel(cota, jaConsumido);
        return disponivel.isEmpty() || disponivel.getAsInt() > 0;
    }

    public boolean permite(Recurso recurso) {
        return recursos.contains(recurso);
    }

    /** Descrição para interface: número, "ilimitado" ou "indisponível". */
    public String descricao(Cota cota) {
        if (ilimitada(cota)) {
            return "ilimitado";
        }
        return Optional.ofNullable(cotas.get(cota))
                .map(String::valueOf)
                .orElse("indisponível");
    }
}
