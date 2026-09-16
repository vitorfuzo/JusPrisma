package br.com.jusprisma.plano;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Troca o plano de um escritório nos testes, direto no banco.
 *
 * <p>Usa conexão de dono do schema de propósito: mudar plano é operação administrativa, não
 * existe rota para isso e não deveria existir — a assinatura é movida pelo gateway de
 * cobrança (F0-9). Simular a troca por SQL é mais honesto do que abrir um endpoint só para
 * o teste usar.
 */
public final class PlanoDeTeste {

    private PlanoDeTeste() {
    }

    public static void promover(Connection dono, UUID tenantId, String planoCodigo)
            throws SQLException {
        try (PreparedStatement ps = dono.prepareStatement("""
                UPDATE assinatura SET plano_codigo = ?
                 WHERE tenant_id = ? AND status IN ('TRIAL', 'ATIVA', 'INADIMPLENTE')
                """)) {
            ps.setString(1, planoCodigo);
            ps.setObject(2, tenantId);

            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "nenhuma assinatura vigente para o tenant " + tenantId);
            }
        }
    }
}
