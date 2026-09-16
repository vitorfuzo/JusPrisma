package br.com.jusprisma.web;

import br.com.jusprisma.aplicacao.conta.ContaIndisponivelException;
import br.com.jusprisma.aplicacao.conta.CredenciaisInvalidasException;
import br.com.jusprisma.aplicacao.conta.EmailJaCadastradoException;
import br.com.jusprisma.aplicacao.conta.SenhaFracaException;
import br.com.jusprisma.aplicacao.conta.SessaoInvalidaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converte exceções em respostas no formato de problema (RFC 9457).
 *
 * <p>Regra que vale para tudo aqui: a resposta diz o que o cliente precisa para se corrigir
 * e nada além. Detalhe interno vai para o log, não para o corpo da resposta.
 */
@RestControllerAdvice
public class TratadorDeErros {

    private static final Logger log = LoggerFactory.getLogger(TratadorDeErros.class);

    @ExceptionHandler(CredenciaisInvalidasException.class)
    public ProblemDetail credenciaisInvalidas(CredenciaisInvalidasException e) {
        // Mensagem propositalmente igual para e-mail inexistente e senha errada.
        return problema(HttpStatus.UNAUTHORIZED, "Credenciais inválidas",
                "E-mail ou senha inválidos.");
    }

    @ExceptionHandler(SessaoInvalidaException.class)
    public ProblemDetail sessaoInvalida(SessaoInvalidaException e) {
        // Mensagem unica: distinguir "token ja usado" de "token desconhecido" informaria
        // a quem capturou um token se ele chegou a circular de verdade.
        return problema(HttpStatus.UNAUTHORIZED, "Sessão inválida",
                "Faça login novamente.");
    }

    @ExceptionHandler(ContaIndisponivelException.class)
    public ProblemDetail contaIndisponivel(ContaIndisponivelException e) {
        return problema(HttpStatus.FORBIDDEN, "Conta indisponível", e.getMessage());
    }

    @ExceptionHandler(EmailJaCadastradoException.class)
    public ProblemDetail emailJaCadastrado(EmailJaCadastradoException e) {
        // Aqui o vazamento é inevitável: o cadastro precisa dizer que o e-mail está em uso.
        // É o mesmo comportamento de qualquer cadastro, e o mitigante correto é limitar
        // tentativas por origem, não fingir que o cadastro funcionou.
        return problema(HttpStatus.CONFLICT, "E-mail já cadastrado",
                "Já existe uma conta com este e-mail.");
    }

    @ExceptionHandler(SenhaFracaException.class)
    public ProblemDetail senhaFraca(SenhaFracaException e) {
        return problema(HttpStatus.UNPROCESSABLE_CONTENT, "Senha fraca", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validacao(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(erro -> campos.putIfAbsent(erro.getField(), erro.getDefaultMessage()));

        ProblemDetail problema = problema(HttpStatus.BAD_REQUEST, "Requisição inválida",
                "Um ou mais campos estão inválidos.");
        problema.setProperty("campos", campos);
        return problema;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail argumentoInvalido(IllegalArgumentException e) {
        return problema(HttpStatus.BAD_REQUEST, "Requisição inválida", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail inesperado(Exception e) {
        // O stack trace vai para o log, com correlation id. A resposta não carrega nada
        // que ajude alguém a mapear a implementação.
        log.error("erro não tratado", e);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                "Não foi possível concluir a operação.");
    }

    private ProblemDetail problema(HttpStatus status, String titulo, String detalhe) {
        ProblemDetail problema = ProblemDetail.forStatus(status);
        problema.setTitle(titulo);
        problema.setDetail(detalhe);
        return problema;
    }
}
