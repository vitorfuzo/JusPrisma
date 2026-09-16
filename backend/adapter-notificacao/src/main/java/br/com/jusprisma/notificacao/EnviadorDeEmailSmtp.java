package br.com.jusprisma.notificacao;

import br.com.jusprisma.aplicacao.porta.EnviadorDeEmail;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class EnviadorDeEmailSmtp implements EnviadorDeEmail {

    private static final Logger log = LoggerFactory.getLogger(EnviadorDeEmailSmtp.class);

    private final JavaMailSender remetente;
    private final TemplateEngine templates;
    private final String remetenteEndereco;
    private final String baseDaAplicacao;

    public EnviadorDeEmailSmtp(JavaMailSender remetente,
                               TemplateEngine templates,
                               @Value("${jusprisma.email.remetente}") String remetenteEndereco,
                               @Value("${jusprisma.app.url-base}") String baseDaAplicacao) {
        this.remetente = remetente;
        this.templates = templates;
        this.remetenteEndereco = remetenteEndereco;
        this.baseDaAplicacao = baseDaAplicacao.replaceAll("/+$", "");
    }

    @Override
    public void enviarVerificacaoDeEmail(String destinatario, String segredoDoToken) {
        enviar(destinatario,
                "Confirme seu e-mail no JusPrisma",
                "email/verificacao",
                link("/verificar-email", segredoDoToken),
                48);
    }

    @Override
    public void enviarRecuperacaoDeSenha(String destinatario, String segredoDoToken) {
        enviar(destinatario,
                "Redefinição de senha no JusPrisma",
                "email/recuperacao",
                link("/redefinir-senha", segredoDoToken),
                1);
    }

    @Override
    public void enviarConvite(String destinatario, String segredoDoConvite) {
        enviar(destinatario,
                "Voce foi convidado para um escritorio no JusPrisma",
                "email/convite",
                link("/aceitar-convite", segredoDoConvite),
                168);
    }

    @Override
    public void enviarAvisoDeFimDaDegustacao(String destinatario, java.time.Instant fimDoPeriodo) {
        Context contexto = new Context(Locale.of("pt", "BR"));
        contexto.setVariable("fimDoPeriodo", java.time.format.DateTimeFormatter
                .ofPattern("d 'de' MMMM", Locale.of("pt", "BR"))
                .withZone(java.time.ZoneId.of("America/Sao_Paulo"))
                .format(fimDoPeriodo));
        contexto.setVariable("link", baseDaAplicacao + "/planos");

        enviarComContexto(destinatario, "Sua degustacao do JusPrisma esta terminando",
                "email/fim-da-degustacao", contexto);
    }

    private void enviar(String destinatario, String assunto, String template,
                        String link, int validadeEmHoras) {
        Context contexto = new Context(Locale.of("pt", "BR"));
        contexto.setVariable("link", link);
        contexto.setVariable("validadeEmHoras", validadeEmHoras);

        enviarComContexto(destinatario, assunto, template, contexto);
    }

    private void enviarComContexto(String destinatario, String assunto, String template,
                                   Context contexto) {
        try {
            MimeMessage mensagem = remetente.createMimeMessage();
            MimeMessageHelper ajudante = new MimeMessageHelper(
                    mensagem, false, StandardCharsets.UTF_8.name());
            ajudante.setFrom(remetenteEndereco);
            ajudante.setTo(destinatario);
            ajudante.setSubject(assunto);
            ajudante.setText(templates.process(template, contexto), true);

            remetente.send(mensagem);
        } catch (Exception e) {
            // O link já está persistido: a falha de envio não invalida o token, e o usuário
            // pode pedir outro. Registrar sem o destinatário e sem o link — o log não é
            // lugar para nem um nem outro.
            log.error("falha ao enviar e-mail transacional ({})", template, e);
            throw new FalhaNoEnvioDeEmailException(e);
        }
    }

    private String link(String caminho, String segredo) {
        return "%s%s?token=%s".formatted(
                baseDaAplicacao, caminho, URLEncoder.encode(segredo, StandardCharsets.UTF_8));
    }
}
