package br.com.jusprisma.dominio.credito;

/**
 * As moedas do sistema.
 *
 * <p>São separadas porque têm custos marginais diferentes: crédito de IA gasta tokens,
 * crédito de consulta gasta dado comprado de terceiro, crédito de perfil gasta um pipeline
 * que depois vira acervo reaproveitável. Somar tudo num saldo só apagaria essa diferença,
 * que é justamente o que sustenta a margem.
 */
public enum TipoCredito {
    PERFIL,
    IA,
    CALCULO,
    CONSULTA,
    ASSINATURA
}
