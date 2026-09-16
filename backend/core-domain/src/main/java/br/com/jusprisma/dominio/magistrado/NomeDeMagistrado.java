package br.com.jusprisma.dominio.magistrado;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Nome de magistrado, normalizado para servir de chave.
 *
 * <p>É o eixo do produto inteiro. O mesmo desembargador aparece nas fontes como
 * {@code "Des. João da Silva"}, {@code "JOAO DA SILVA"}, {@code "Silva, João da"} e
 * {@code "JOÃO DA SILVA NETO"} — e tratar essas formas como pessoas diferentes fragmenta o
 * acervo, derruba a amostra abaixo do mínimo e produz um perfil sobre metade das decisões
 * de alguém. O erro não aparece como falha: aparece como estatística plausível e errada.
 *
 * <p>A normalização é agressiva de propósito, e é acompanhada de uma tabela de aliases na
 * base — nenhum algoritmo resolve todos os casos, e a tabela é onde a intervenção humana
 * entra sem virar remendo no código.
 */
public record NomeDeMagistrado(String exibicao, String normalizado) {

    /**
     * Títulos e tratamentos que aparecem grudados no nome nas fontes.
     *
     * <p>Removidos porque não identificam a pessoa: o mesmo magistrado é "Des." numa fonte,
     * "Desembargador" noutra e vem sem título numa terceira.
     */
    private static final Set<String> TITULOS = Set.of(
            "DES", "DESA", "DESEMBARGADOR", "DESEMBARGADORA",
            "DR", "DRA", "DOUTOR", "DOUTORA",
            "JUIZ", "JUIZA", "JUIZ DE DIREITO",
            "MIN", "MINISTRO", "MINISTRA",
            "EXMO", "EXMA", "SR", "SRA",
            "RELATOR", "RELATORA");

    /**
     * Partículas de ligação.
     *
     * <p>Mantidas no nome — "Maria de Souza" e "Maria Souza" podem ser pessoas diferentes —
     * mas reconhecidas para que a inversão "Souza, Maria de" seja desfeita corretamente.
     */
    private static final Set<String> PARTICULAS = Set.of("DA", "DE", "DO", "DAS", "DOS", "E", "D");

    private static final Pattern ACENTOS = Pattern.compile("\\p{M}");
    private static final Pattern NAO_NOME = Pattern.compile("[^A-Z ]");
    private static final Pattern ESPACOS = Pattern.compile("\\s+");

    public NomeDeMagistrado {
        if (exibicao == null || exibicao.isBlank()) {
            throw new IllegalArgumentException("nome de magistrado não pode ser vazio");
        }
        if (normalizado == null || normalizado.isBlank()) {
            throw new IllegalArgumentException("normalização resultou em nome vazio: " + exibicao);
        }
    }

    public static NomeDeMagistrado de(String bruto) {
        return new NomeDeMagistrado(limparParaExibicao(bruto), normalizar(bruto));
    }

    /**
     * Reduz o nome à forma canônica usada como chave.
     *
     * <p>Passos, nesta ordem: maiúsculas, remoção de acentos, remoção de pontuação e
     * dígitos, desfazer inversão por vírgula, remoção de títulos, colapso de espaços.
     *
     * <p>A ordem importa. A vírgula precisa ser tratada <em>antes</em> de a pontuação ser
     * removida, senão "Silva, João" vira "SILVA JOAO" e a inversão se perde para sempre.
     */
    public static String normalizar(String bruto) {
        if (bruto == null) {
            return "";
        }

        String texto = bruto.toUpperCase(Locale.ROOT).trim();
        texto = semAcentos(texto);
        texto = desfazerInversao(texto);
        texto = NAO_NOME.matcher(texto).replaceAll(" ");

        List<String> partes = ESPACOS.splitAsStream(texto.trim())
                .filter(parte -> !parte.isBlank())
                .filter(parte -> !TITULOS.contains(parte))
                .toList();

        return String.join(" ", partes);
    }

    /**
     * Desfaz "SOBRENOME, PRENOMES" e devolve "PRENOMES SOBRENOME".
     *
     * <p>Só age quando há exatamente uma vírgula: duas ou mais indicam formato que não
     * sabemos interpretar, e inverter no escuro produziria um nome pior que o original.
     */
    private static String desfazerInversao(String texto) {
        int primeira = texto.indexOf(',');
        if (primeira < 0 || texto.indexOf(',', primeira + 1) >= 0) {
            return texto;
        }

        String sobrenome = texto.substring(0, primeira).trim();
        String prenomes = texto.substring(primeira + 1).trim();

        if (sobrenome.isEmpty() || prenomes.isEmpty()) {
            return texto.replace(',', ' ');
        }
        return prenomes + " " + sobrenome;
    }

    private static String semAcentos(String texto) {
        String decomposto = Normalizer.normalize(texto, Normalizer.Form.NFD);
        return ACENTOS.matcher(decomposto).replaceAll("");
    }

    /** Forma legível, para exibir na interface: títulos fora, acentos e caixa preservados. */
    private static String limparParaExibicao(String bruto) {
        if (bruto == null) {
            return "";
        }
        String texto = desfazerInversao(bruto.trim());
        texto = ESPACOS.matcher(texto.replace(",", " ")).replaceAll(" ").trim();

        List<String> partes = ESPACOS.splitAsStream(texto)
                .filter(parte -> !parte.isBlank())
                .filter(parte -> !TITULOS.contains(
                        semAcentos(parte.toUpperCase(Locale.ROOT)).replaceAll("[^A-Z]", "")))
                .toList();

        return String.join(" ", partes);
    }

    /**
     * Iniciais das partes significativas, para desambiguação visual.
     *
     * <p>Partículas ficam de fora: "Maria de Souza" tem iniciais MS, não MDS.
     */
    public String iniciais() {
        return ESPACOS.splitAsStream(normalizado)
                .filter(parte -> !PARTICULAS.contains(parte))
                .filter(parte -> !parte.isEmpty())
                .map(parte -> parte.substring(0, 1))
                .reduce("", String::concat);
    }

    /** Verdadeiro quando os dois nomes resolvem para a mesma chave. */
    public boolean mesmaPessoaQue(NomeDeMagistrado outro) {
        return outro != null && normalizado.equals(outro.normalizado());
    }
}
