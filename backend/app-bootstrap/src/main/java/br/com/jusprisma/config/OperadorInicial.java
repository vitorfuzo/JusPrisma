package br.com.jusprisma.config;

import br.com.jusprisma.aplicacao.admin.RepositorioAdministrativo;
import br.com.jusprisma.aplicacao.porta.CodificadorDeSenha;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Cria o primeiro operador da plataforma, se configurado e se ainda não houver nenhum.
 *
 * <p>O problema que isto resolve: o painel exige credencial de operador, e não há como
 * criar a primeira por dentro do próprio painel. As alternativas seriam piores — uma senha
 * fixa numa migration ficaria versionada e igual em todos os ambientes, e um endpoint
 * público de criação de operador seria a porta de entrada mais óbvia do sistema.
 *
 * <p>Roda só quando a tabela está vazia. Depois disso, operador novo é criado por operador
 * existente, e mudar a variável de ambiente não tem efeito nenhum.
 */
@Component
public class OperadorInicial implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OperadorInicial.class);

    private final RepositorioAdministrativo repositorio;
    private final CodificadorDeSenha codificador;
    private final String email;
    private final String senha;
    private final String nome;

    public OperadorInicial(RepositorioAdministrativo repositorio,
                           CodificadorDeSenha codificador,
                           @Value("${jusprisma.operador-inicial.email:}") String email,
                           @Value("${jusprisma.operador-inicial.senha:}") String senha,
                           @Value("${jusprisma.operador-inicial.nome:Operador}") String nome) {
        this.repositorio = repositorio;
        this.codificador = codificador;
        this.email = email;
        this.senha = senha;
        this.nome = nome;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        if (email.isBlank() || senha.isBlank()) {
            return;
        }

        if (senha.length() < 12) {
            // Mais exigente que a senha de cliente: esta credencial abre todos os escritórios.
            throw new IllegalStateException(
                    "a senha do operador inicial precisa de ao menos 12 caracteres");
        }

        boolean criou = repositorio.criarPrimeiroOperador(
                email.trim().toLowerCase(java.util.Locale.ROOT),
                codificador.codificar(senha),
                nome);

        if (criou) {
            log.warn("operador inicial criado. Remova JUSPRISMA_OPERADOR_INICIAL_SENHA do ambiente.");
        }
        // Quando ja existia operador, nada acontece e nada e' registrado: reiniciar com a
        // variavel ainda definida e' normal e nao deve trocar a senha de quem ja opera.
    }
}
