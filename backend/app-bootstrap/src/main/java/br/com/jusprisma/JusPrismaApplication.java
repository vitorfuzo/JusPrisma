package br.com.jusprisma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ponto de entrada da aplicação.
 *
 * <p>O scan cobre {@code br.com.jusprisma.**}, que é o prefixo de todos os módulos
 * adaptadores. O agendamento fica habilitado aqui porque o worker de pipeline consome
 * a tabela de jobs por polling — ver ADR 0001, seção 2.
 */
@SpringBootApplication
@EnableScheduling
public class JusPrismaApplication {

    public static void main(String[] args) {
        SpringApplication.run(JusPrismaApplication.class, args);
    }
}
