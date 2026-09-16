package br.com.jusprisma.dominio.conta;

/**
 * Papel do usuário dentro do escritório.
 *
 * <p>Permissões granulares são da Fase 3. Aqui a distinção que importa é quem pode
 * administrar a conta — convidar, remover e mexer em assinatura — e quem apenas trabalha.
 */
public enum Papel {

    /** Dono da conta. Administra assinatura, cobrança e membros. */
    OWNER,

    /** Membro convidado. Usa a plataforma, não administra a conta. */
    MEMBRO;

    public boolean administraAConta() {
        return this == OWNER;
    }
}
