package br.com.jusprisma.dominio.plano;

/**
 * Recurso medido por quantidade.
 *
 * <p>O enum existe para que a chave seja tipada e um erro de digitação vire erro de
 * compilação. Os <em>valores</em> continuam sendo dado: mudar a cota do Pro é um UPDATE em
 * {@code plano.limites}, nunca uma alteração de código.
 */
public enum Cota {
    PERFIS_NOVOS_MES,
    COMPARACAO_MAGISTRADOS,
    IA_MENSAGENS_MES,
    CALCULOS,
    ASSINATURAS,
    CONSULTAS,
    MONITORAMENTO_PROCESSOS,
    DRIVE_GB,
    SUBUSUARIOS,
    ROLLOVER_MESES
}
