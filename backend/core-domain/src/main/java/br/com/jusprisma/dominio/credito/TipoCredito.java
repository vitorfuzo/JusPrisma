package br.com.jusprisma.dominio.credito;

import br.com.jusprisma.dominio.plano.Cota;

/**
 * As moedas do sistema.
 *
 * <p>São separadas porque têm custos marginais diferentes: crédito de IA gasta tokens,
 * crédito de consulta gasta dado comprado de terceiro, crédito de perfil gasta um pipeline
 * que depois vira acervo reaproveitável. Somar tudo num saldo só apagaria essa diferença,
 * que é justamente o que sustenta a margem.
 */
public enum TipoCredito {

    PERFIL(Cota.PERFIS_NOVOS_MES),
    IA(Cota.IA_MENSAGENS_MES),
    CALCULO(Cota.CALCULOS),
    CONSULTA(Cota.CONSULTAS),
    ASSINATURA(Cota.ASSINATURAS);

    private final Cota cota;

    TipoCredito(Cota cota) {
        this.cota = cota;
    }

    /**
     * A cota do plano que abastece este tipo de credito.
     *
     * <p>O vinculo existe aqui, e nao espalhado em cada caso de uso, porque e' ele que
     * define quanto entra no ledger a cada periodo. Cota e credito sao dois lados da mesma
     * coisa: a cota diz quanto o plano da, o ledger registra o que foi usado.
     */
    public Cota cota() {
        return cota;
    }
}
