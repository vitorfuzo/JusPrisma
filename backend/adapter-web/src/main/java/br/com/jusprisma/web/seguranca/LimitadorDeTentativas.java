package br.com.jusprisma.web.seguranca;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Conta tentativas por chave e recusa quando estouram a política.
 *
 * <p>Guarda o estado em memória do processo. É uma escolha consciente: com uma instância,
 * isso basta, e a alternativa — contador no Postgres — adicionaria uma escrita a cada
 * tentativa de login, inclusive às falhadas, que é exatamente o caminho que um ataque
 * exercita em volume.
 *
 * <p>O que isso custa está registrado: ao subir para mais de uma instância, o limite passa
 * a valer por instância, e quem reiniciar o processo zera os contadores. Nenhum dos dois
 * inviabiliza a proteção contra força bruta — atrapalhar o roteiro automatizado já basta —
 * mas quando houver segunda instância o passo seguinte é mover para um contador
 * compartilhado.
 */
@Component
public class LimitadorDeTentativas {

    private static final Logger log = LoggerFactory.getLogger(LimitadorDeTentativas.class);

    /**
     * Teto de chaves distintas por política. Existe para que um atacante variando o e-mail
     * a cada requisição não transforme o limitador num vazamento de memória — o que tornaria
     * a defesa um vetor de negação de serviço por si só.
     */
    private static final long MAXIMO_DE_CHAVES = 100_000;

    private final Map<PoliticaDeTentativas, Cache<String, AtomicInteger>> contadores =
            new EnumMap<>(PoliticaDeTentativas.class);

    /**
     * Interruptor de emergencia.
     *
     * <p>Existe para dois casos reais. O primeiro e producao: se um cliente legitimo com
     * muitos usuarios atras do mesmo IP comecar a ser barrado, desligar e mais rapido que
     * fazer deploy de numeros novos. O segundo sao os testes automatizados, que criam
     * dezenas de contas da mesma origem e nao deveriam esbarrar numa protecao contra abuso.
     *
     * <p>Padrao ligado: quem desliga precisa faze-lo de proposito.
     */
    private final boolean habilitado;

    public LimitadorDeTentativas(
            @Value("${jusprisma.limite-de-tentativas.habilitado:true}") boolean habilitado) {
        this.habilitado = habilitado;
        if (!habilitado) {
            log.warn("limite de tentativas DESLIGADO; rotas de autenticacao sem protecao contra abuso");
        }
        for (PoliticaDeTentativas politica : PoliticaDeTentativas.values()) {
            contadores.put(politica, Caffeine.newBuilder()
                    // expireAfterWrite, e não afterAccess: a janela conta a partir da
                    // primeira tentativa. Com afterAccess, tentar sem parar renovaria a
                    // expiração e o bloqueio nunca terminaria.
                    .expireAfterWrite(politica.janela())
                    .maximumSize(MAXIMO_DE_CHAVES)
                    .build());
        }
    }

    /**
     * Registra uma tentativa.
     *
     * @throws TentativasExcedidasException quando a política é estourada.
     */
    public void registrar(PoliticaDeTentativas politica, String chave) {
        if (!habilitado || chave == null || chave.isBlank()) {
            return;
        }

        Cache<String, AtomicInteger> cache = contadores.get(politica);
        int usadas = cache.get(chave.toLowerCase(java.util.Locale.ROOT),
                        ignorada -> new AtomicInteger())
                .incrementAndGet();

        if (usadas > politica.tentativas()) {
            log.warn("limite de tentativas excedido em {} ({} tentativas na janela de {})",
                    politica, usadas, politica.janela());
            throw new TentativasExcedidasException(politica.janela());
        }
    }

    /**
     * Zera o contador de uma chave.
     *
     * <p>Chamado no login bem-sucedido: quem errou a senha duas vezes e acertou na terceira
     * não deve ficar com crédito queimado pelo resto da janela.
     */
    public void zerar(PoliticaDeTentativas politica, String chave) {
        if (chave != null && !chave.isBlank()) {
            contadores.get(politica).invalidate(chave.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** Quanto falta para a janela reabrir, para o cabeçalho Retry-After. */
    public static Duration janelaDe(PoliticaDeTentativas politica) {
        return politica.janela();
    }
}
