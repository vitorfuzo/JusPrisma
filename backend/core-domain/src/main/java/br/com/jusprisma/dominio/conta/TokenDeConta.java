package br.com.jusprisma.dominio.conta;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Token de uso único enviado por e-mail.
 *
 * <p>Este objeto representa o <em>registro</em> do token, não o token. O valor secreto nunca
 * entra aqui nem no banco: guardamos só o hash. Um vazamento da base entrega hashes, e hash
 * não abre link nenhum.
 */
public record TokenDeConta(
        UUID id,
        UUID tenantId,
        UUID usuarioId,
        Finalidade finalidade,
        Instant criadoEm,
        Instant expiraEm,
        Instant usadoEm) {

    public enum Finalidade {

        /**
         * Confirma que o endereço existe e é da pessoa. Prazo folgado porque o custo de
         * expirar é apenas reenviar, e e-mail de cadastro costuma esperar o fim do dia.
         */
        VERIFICACAO_EMAIL(Duration.ofHours(48)),

        /**
         * Permite trocar a senha sem saber a atual. Prazo curto porque, enquanto vale, é
         * equivalente à própria senha — e caixas de e-mail são comprometidas.
         */
        RECUPERACAO_SENHA(Duration.ofHours(1));

        private final Duration validade;

        Finalidade(Duration validade) {
            this.validade = validade;
        }

        public Duration validade() {
            return validade;
        }
    }

    public static TokenDeConta novo(UUID tenantId, UUID usuarioId, Finalidade finalidade) {
        Instant agora = Instant.now();
        return new TokenDeConta(
                UUID.randomUUID(), tenantId, usuarioId, finalidade,
                agora, agora.plus(finalidade.validade()), null);
    }

    public boolean utilizavel(Instant agora) {
        return usadoEm == null && agora.isBefore(expiraEm);
    }
}
