package br.com.jusprisma.dominio.plano;

/**
 * Um plano do catálogo. Igual para todos os tenants — não é dado de escritório.
 */
public record Plano(String codigo, String nome, int precoCentavos, LimitesDoPlano limites) {

    public Plano {
        if (codigo == null || codigo.isBlank()) {
            throw new IllegalArgumentException("código do plano é obrigatório");
        }
        if (precoCentavos < 0) {
            throw new IllegalArgumentException("preço não pode ser negativo");
        }
        if (limites == null) {
            throw new IllegalArgumentException("plano sem limites não existe");
        }
    }

    /** Preço em reais, para exibição. O cálculo continua sendo em centavos. */
    public java.math.BigDecimal precoEmReais() {
        return java.math.BigDecimal.valueOf(precoCentavos, 2);
    }
}
