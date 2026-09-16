package br.com.jusprisma.persistencia.tenant;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Publica o tenant corrente na transação do Postgres, para que as policies de Row Level
 * Security tenham o que avaliar.
 *
 * <p>O ajuste é feito em {@code doBegin}, logo depois de a transação abrir, porque é o
 * único ponto em que temos a garantia de estar na mesma conexão que as consultas usarão.
 * Fazê-lo fora de uma transação seria pior do que não fazer: com pool de conexões, um
 * {@code SET} de sessão sobreviveria à devolução da conexão ao pool e vazaria o tenant para
 * a próxima requisição que a pegasse — falha de isolamento silenciosa e intermitente.
 *
 * <p>Por isso o terceiro argumento de {@code set_config} é {@code true} (escopo local):
 * o valor morre junto com a transação, no commit ou no rollback.
 *
 * <p>Quando não há tenant no contexto — cadastro e autenticação — nada é definido e
 * {@code app_tenant_atual()} devolve NULL, que faz as policies negarem tudo. É o
 * comportamento desejado: falha fechado.
 */
public final class GerenciadorDeTransacaoComTenant extends JpaTransactionManager {

    private static final String DEFINIR_TENANT = "SELECT set_config('app.tenant_id', ?, true)";

    public GerenciadorDeTransacaoComTenant(EntityManagerFactory emf, DataSource dataSource) {
        super(emf);
        // Necessário para que DataSourceUtils devolva a conexão ligada a esta transação,
        // e não uma nova do pool.
        setDataSource(dataSource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        UUID tenantId = ContextoDeTenant.atual().orElse(null);
        if (tenantId == null) {
            return;
        }

        DataSource dataSource = obterDataSource();
        Connection conexao = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement ps = conexao.prepareStatement(DEFINIR_TENANT)) {
            ps.setString(1, tenantId.toString());
            ps.execute();
        } catch (SQLException e) {
            // Abortar é obrigatório. Seguir sem o tenant definido faria a transação
            // enxergar zero linhas e o erro apareceria como "sumiu meu dado", muito longe
            // da causa real.
            throw new CannotCreateTransactionException(
                    "não foi possível definir o tenant na transação", e);
        }
    }

    private DataSource obterDataSource() {
        DataSource dataSource = getDataSource();
        if (dataSource == null) {
            throw new IllegalStateException(
                    "dataSource não configurado no gerenciador de transação com tenant");
        }
        return dataSource;
    }
}
