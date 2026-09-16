package br.com.jusprisma.persistencia;

import br.com.jusprisma.persistencia.tenant.GerenciadorDeTransacaoComTenant;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
public class ConfiguracaoDePersistencia {

    /**
     * Substitui o gerenciador de transação padrão do Spring pelo que publica o tenant no
     * Postgres. Sem esta substituição o Row Level Security continua ligado no schema, mas
     * nenhuma transação informa qual é o tenant — e toda consulta devolve zero linhas.
     */
    @Bean
    public PlatformTransactionManager transactionManager(
            EntityManagerFactory entityManagerFactory, DataSource dataSource) {
        return new GerenciadorDeTransacaoComTenant(entityManagerFactory, dataSource);
    }
}
