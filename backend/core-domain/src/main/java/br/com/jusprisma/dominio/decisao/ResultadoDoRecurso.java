package br.com.jusprisma.dominio.decisao;

/**
 * O que o colegiado decidiu sobre o recurso.
 *
 * <p>Atenção ao que este enum <em>não</em> significa. "Provido" é bom para quem recorreu,
 * e quem recorre tanto pode ser autor quanto réu. Um relator com alta taxa de provimento
 * não é "favorável ao autor" — é frequentemente reformador. Traduzir provimento em
 * favorecimento a um polo seria inventar um dado que não existe, e é exatamente o tipo de
 * inferência que a regra inviolável 3 proíbe.
 *
 * <p>Admissibilidade e mérito são eixos separados de propósito: um recurso não conhecido
 * não diz nada sobre o mérito, e misturá-los produziria estatística sem sentido.
 */
public enum ResultadoDoRecurso {

    /** Recurso acolhido: a decisão anterior foi reformada. */
    PROVIDO,

    /** Reforma parcial. */
    PARCIALMENTE_PROVIDO,

    /** Recurso rejeitado: a decisão anterior foi mantida. */
    IMPROVIDO,

    /**
     * Não passou pelos requisitos formais de admissibilidade.
     *
     * <p>Separado dos demais porque não é resultado de mérito. Somá-lo a "improvido"
     * inflaria artificialmente a taxa de manutenção de sentença.
     */
    NAO_CONHECIDO,

    /** Encerrado sem julgar o pedido — extinção, prejudicialidade, desistência. */
    SEM_RESOLUCAO_DE_MERITO,

    /**
     * O dispositivo não foi reconhecido.
     *
     * <p>Existe para que o desconhecido seja contado como desconhecido, e não empurrado
     * para a categoria mais próxima. Uma decisão mal classificada entra na estatística
     * como se fosse outra coisa; uma decisão marcada como indeterminada apenas reduz a
     * amostra, o que é honesto e visível.
     */
    INDETERMINADO;

    public boolean ehDeMerito() {
        return this == PROVIDO || this == PARCIALMENTE_PROVIDO || this == IMPROVIDO;
    }
}
