package br.com.jusprisma.aplicacao.conta;

import br.com.jusprisma.aplicacao.porta.EscopoDeTenant;
import br.com.jusprisma.dominio.conta.Papel;
import br.com.jusprisma.dominio.conta.SessaoRefresh;
import br.com.jusprisma.dominio.conta.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Abre, renova e encerra sessões de acesso.
 *
 * <p>A renovação é rotativa: cada token de renovação vale uma única vez e é trocado por
 * outro. Isso limita a janela de um token vazado e, principalmente, cria o sinal que
 * permite detectar o vazamento.
 */
@Service
public class SessoesDeAcesso {

    /**
     * Trinta dias sem precisar digitar a senha de novo. É longo porque a rotação e a
     * revogação por família dão a segurança que um prazo curto tentaria dar sozinho,
     * e forçar login semanal em ferramenta de trabalho só gera senha fraca e anotada.
     */
    public static final Duration VALIDADE = Duration.ofDays(30);

    private static final Logger log = LoggerFactory.getLogger(SessoesDeAcesso.class);

    private final RepositorioDeSessao sessoes;
    private final RepositorioDeConta contas;
    private final EscopoDeTenant escopo;

    public SessoesDeAcesso(RepositorioDeSessao sessoes,
                           RepositorioDeConta contas,
                           EscopoDeTenant escopo) {
        this.sessoes = sessoes;
        this.contas = contas;
        this.escopo = escopo;
    }

    public record SessaoAberta(UUID sessaoId, UUID familiaId, Instant expiraEm) {
    }

    public record SessaoRenovada(
            UUID sessaoId, UUID familiaId, UUID usuarioId, Papel papel,
            boolean emailVerificado, Instant expiraEm) {
    }

    public SessaoAberta abrir(UUID tenantId, UUID usuarioId) {
        Instant expiraEm = Instant.now().plus(VALIDADE);
        SessaoRefresh primeira = SessaoRefresh.abrirFamilia(tenantId, usuarioId, expiraEm);

        escopo.executarComo(tenantId, () -> sessoes.registrar(primeira));

        return new SessaoAberta(primeira.id(), primeira.familiaId(), expiraEm);
    }

    /**
     * Resultado interno da tentativa de renovação.
     *
     * <p>Existe para que o caso de reuso saia da transação <em>sem exceção</em>. Lançar de
     * dentro do escopo faria rollback — inclusive da revogação da família, que é justamente
     * a única coisa que precisava sobreviver. A revogação acontece depois, em transação
     * própria, e só então a requisição falha.
     */
    private record Tentativa(SessaoRenovada renovada, UUID familiaParaRevogar) {

        static Tentativa sucesso(SessaoRenovada renovada) {
            return new Tentativa(renovada, null);
        }

        static Tentativa reuso(UUID familiaId) {
            return new Tentativa(null, familiaId);
        }

        static Tentativa recusa() {
            return new Tentativa(null, null);
        }
    }

    public SessaoRenovada renovar(UUID tenantId, UUID usuarioId, UUID familiaId, UUID sessaoId) {
        Tentativa tentativa = escopo.executarComo(tenantId,
                () -> tentarRenovar(usuarioId, familiaId, sessaoId));

        if (tentativa.familiaParaRevogar() != null) {
            UUID familia = tentativa.familiaParaRevogar();
            int revogadas = escopo.executarComo(tenantId,
                    () -> sessoes.revogarFamilia(familia,
                            SessaoRefresh.MotivoDeRevogacao.REUSO_DETECTADO));

            log.warn("reuso de token de renovação detectado; família {} revogada ({} sessões)",
                    familia, revogadas);
            throw new SessaoInvalidaException();
        }

        if (tentativa.renovada() == null) {
            throw new SessaoInvalidaException();
        }
        return tentativa.renovada();
    }

    private Tentativa tentarRenovar(UUID usuarioId, UUID familiaId, UUID sessaoId) {
        SessaoRefresh sessao = sessoes.buscarPorId(sessaoId).orElse(null);
        if (sessao == null) {
            return Tentativa.recusa();
        }

        // O token é assinado, então divergência aqui indica manipulação deliberada,
        // não erro de cliente.
        if (!sessao.familiaId().equals(familiaId) || !sessao.usuarioId().equals(usuarioId)) {
            log.warn("token de renovação com vínculos divergentes; sessão {}", sessaoId);
            return Tentativa.reuso(sessao.familiaId());
        }

        if (sessao.jaConsumida()) {
            return Tentativa.reuso(sessao.familiaId());
        }
        if (!sessao.utilizavel(Instant.now())) {
            return Tentativa.recusa();
        }

        // Consome de forma condicional. Duas renovações concorrentes com o mesmo token
        // fazem exatamente uma vencer; a perdedora cai no mesmo tratamento de reuso,
        // porque do ponto de vista do servidor é indistinguível de roubo.
        if (!sessoes.consumir(sessao.id())) {
            return Tentativa.reuso(sessao.familiaId());
        }

        Instant expiraEm = Instant.now().plus(VALIDADE);
        SessaoRefresh proxima = sessao.proximoElo(expiraEm);
        sessoes.registrar(proxima);

        Usuario usuario = contas.buscarPorId(usuarioId).orElse(null);
        if (usuario == null) {
            return Tentativa.recusa();
        }

        return Tentativa.sucesso(new SessaoRenovada(
                proxima.id(), proxima.familiaId(), usuarioId, usuario.papel(),
                usuario.emailVerificado(), expiraEm));
    }

    public void encerrar(UUID tenantId, UUID familiaId) {
        escopo.executarComo(tenantId,
                () -> sessoes.revogarFamilia(familiaId, SessaoRefresh.MotivoDeRevogacao.LOGOUT));
    }

}
