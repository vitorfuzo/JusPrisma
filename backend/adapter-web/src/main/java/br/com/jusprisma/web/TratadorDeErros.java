package br.com.jusprisma.web;

import br.com.jusprisma.aplicacao.conta.ContaIndisponivelException;
import br.com.jusprisma.aplicacao.conta.CredenciaisInvalidasException;
import br.com.jusprisma.aplicacao.conta.EmailJaCadastradoException;
import br.com.jusprisma.aplicacao.conta.SenhaFracaException;
import br.com.jusprisma.aplicacao.conta.SessaoInvalidaException;
import br.com.jusprisma.aplicacao.conta.ConviteInvalidoException;
import br.com.jusprisma.aplicacao.plano.CotaExcedidaException;
import br.com.jusprisma.aplicacao.plano.SemAssinaturaVigenteException;
import br.com.jusprisma.web.observabilidade.CorrelacaoDeRequisicao;
import br.com.jusprisma.web.seguranca.TentativasExcedidasException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import br.com.jusprisma.aplicacao.conta.OperacaoNaoPermitidaException;
import br.com.jusprisma.aplicacao.conta.TokenInvalidoException;
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

    @ExceptionHandler(TokenInvalidoException.class)
    public ProblemDetail tokenInvalido(TokenInvalidoException e) {
        // Um unico motivo para desconhecido, expirado, ja usado e de outra finalidade:
        // "este link ja foi usado" confirma a quem interceptou o e-mail que ele existiu.
        return problema(HttpStatus.GONE, "Link inválido",
                "Este link é inválido ou expirou. Solicite outro.");
    }

    @ExceptionHandler(ConviteInvalidoException.class)
    public ProblemDetail conviteInvalido(ConviteInvalidoException e) {
        return problema(HttpStatus.GONE, "Convite inválido",
                "Este convite é inválido, expirou ou já foi usado.");
    }

    @ExceptionHandler(OperacaoNaoPermitidaException.class)
    public ProblemDetail operacaoNaoPermitida(OperacaoNaoPermitidaException e) {
        // 403, nao 401: quem chegou aqui esta autenticado, so nao tem o papel necessario.
        return problema(HttpStatus.FORBIDDEN, "Operação não permitida", e.getMessage());
    }

    @ExceptionHandler(TentativasExcedidasException.class)
    public ResponseEntity<ProblemDetail> tentativasExcedidas(TentativasExcedidasException e) {
        ProblemDetail problema = problema(HttpStatus.TOO_MANY_REQUESTS, "Tentativas demais",
                "Aguarde alguns minutos antes de tentar novamente.");

        // Retry-After diz ao cliente quando voltar, em vez de deixa-lo martelar a rota.
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.esperar().toSeconds()))
                .body(problema);
    }

    @ExceptionHandler(CotaExcedidaException.class)
    public ProblemDetail cotaExcedida(CotaExcedidaException e) {
        // 402 e nao 403: a operacao e legitima e o usuario tem o papel certo. O que falta e
        // plano. A distincao importa para a interface, que deve oferecer upgrade em vez de
        // dizer "voce nao tem permissao".
        ProblemDetail problema = problema(HttpStatus.PAYMENT_REQUIRED,
                "Limite do plano atingido", e.getMessage());
        if (e.cota() != null) {
            problema.setProperty("cota", e.cota().name());
        }
        return problema;
    }

    @ExceptionHandler(SemAssinaturaVigenteException.class)
    public ProblemDetail semAssinatura(SemAssinaturaVigenteException e) {
        return problema(HttpStatus.PAYMENT_REQUIRED, "Sem assinatura vigente",
                "Este escritório não tem um plano ativo.");
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

        // O identificador da requisição vai em toda resposta de erro para que o usuário
        // possa citá-lo no suporte. É a diferença entre "deu erro ontem à tarde" e um
        // ponteiro exato para a linha de log.
        String correlacao = CorrelacaoDeRequisicao.atual();
        if (correlacao != null) {
            problema.setProperty("correlacaoId", correlacao);
        }
        return problema;
    }
}
