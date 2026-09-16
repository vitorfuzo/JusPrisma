package br.com.jusprisma.conta;

import br.com.jusprisma.aplicacao.porta.EnviadorDeEmail;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

/**
 * Substitui o envio de e-mail e guarda o que teria sido enviado.
 *
 * <p>Preserva o segredo <em>gerado pela aplicação</em>, em vez de o teste inventar um. Assim
 * os testes exercitam geração, hash e conferência de verdade: se o valor gravado deixasse de
 * corresponder ao enviado, eles quebrariam — que é o bug clássico desses fluxos e o que faz
 * o usuário ver "link inválido" num link legítimo.
 */
public class CaixaDeSaidaDeTeste implements EnviadorDeEmail {

    public static final String VERIFICACAO = "VERIFICACAO";
    public static final String RECUPERACAO = "RECUPERACAO";
    public static final String CONVITE = "CONVITE";
    public static final String FIM_DA_DEGUSTACAO = "FIM_DA_DEGUSTACAO";

    public record Enviado(String destinatario, String finalidade, String segredo) {
    }

    private final List<Enviado> enviados = new ArrayList<>();

    @Override
    public synchronized void enviarVerificacaoDeEmail(String destinatario, String segredo) {
        enviados.add(new Enviado(destinatario, VERIFICACAO, segredo));
    }

    @Override
    public synchronized void enviarRecuperacaoDeSenha(String destinatario, String segredo) {
        enviados.add(new Enviado(destinatario, RECUPERACAO, segredo));
    }

    @Override
    public synchronized void enviarConvite(String destinatario, String segredo) {
        enviados.add(new Enviado(destinatario, CONVITE, segredo));
    }

    @Override
    public synchronized void enviarAvisoDeFimDaDegustacao(
            String destinatario, java.time.Instant fimDoPeriodo) {
        // O segredo aqui e' a data do fim, que e' o que o teste do ciclo precisa conferir.
        enviados.add(new Enviado(destinatario, FIM_DA_DEGUSTACAO, fimDoPeriodo.toString()));
    }

    public synchronized void limpar() {
        enviados.clear();
    }

    public synchronized Enviado ultimo(String finalidade) {
        return enviados.reversed().stream()
                .filter(e -> e.finalidade().equals(finalidade))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nenhum e-mail de " + finalidade));
    }

    public synchronized List<Enviado> todos() {
        return List.copyOf(enviados);
    }

    @TestConfiguration
    public static class Configuracao {
        @Bean
        @Primary
        public CaixaDeSaidaDeTeste caixaDeSaida() {
            return new CaixaDeSaidaDeTeste();
        }
    }
}
