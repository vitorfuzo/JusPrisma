package br.com.jusprisma.dominio.decisao;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Interpreta o dispositivo do acórdão e devolve o resultado do recurso.
 *
 * <p>O campo {@code decisao} do TJDFT traz o dispositivo em texto curto, do tipo
 * {@code "CONHECIDO. PARCIALMENTE PROVIDO. UNÂNIME."}. Parece vocabulário controlado e não
 * é: numa amostra de 25 registros, o mesmo resultado aparece como {@code DESPROVIDO},
 * {@code DESPROVIDOS}, {@code NEGAR PROVIMENTO} e {@code NEGAR PROVIMENTO AO RECURSO}.
 *
 * <p>Daí este classificador existir, e ser tratado com o mesmo cuidado da normalização de
 * nome: tratar as variantes como resultados distintos produziria estatística errada, que é
 * o pior defeito possível neste produto. Ver {@code docs/fontes-de-dados.md}.
 *
 * <p><strong>A ordem das regras importa.</strong> "Parcialmente provido" contém "provido";
 * "não provido" contém "provido". Quem testar "provido" primeiro classifica tudo errado.
 * Por isso as formas mais específicas e as negativas vêm antes.
 */
public final class ClassificadorDeDispositivo {

    private static final Pattern ACENTOS = Pattern.compile("\\p{M}");
    private static final Pattern ESPACOS = Pattern.compile("\\s+");

    /**
     * Regras em ordem de precedência. A primeira que casar decide.
     *
     * <p>Cada entrada existe porque a forma foi observada nas fontes, não por simetria.
     * O smoke test de cobertura ({@code CoberturaDoClassificadorSmokeTest}) mede quanto
     * destas regras cobre a realidade e falha quando a cobertura cai.
     *
     * <p><strong>As formas são escritas já normalizadas:</strong> maiúsculas, sem acento e
     * <em>sem hífen</em>. A primeira versão tinha {@code "NEGAR-SE PROVIMENTO"} com hífen e
     * nunca casava, porque o texto comparado já tem o hífen trocado por espaço — uma regra
     * que existia e não funcionava. Há teste garantindo que toda forma esteja normalizada.
     */
    private static final List<Regra> REGRAS = List.of(
            // Admissibilidade primeiro: quando o recurso não é conhecido, o que vier
            // depois sobre mérito costuma referir-se a outro recurso do mesmo acórdão.
            new Regra(ResultadoDoRecurso.NAO_CONHECIDO,
                    "NAO CONHECIDO", "NAO CONHECIDOS", "NAO CONHECIMENTO",
                    "NAO SE CONHECE", "NAO CONHECER"),

            new Regra(ResultadoDoRecurso.SEM_RESOLUCAO_DE_MERITO,
                    "PREJUDICADO", "PREJUDICADOS", "EXTINTO SEM RESOLUCAO",
                    "EXTINCAO SEM RESOLUCAO", "HOMOLOGADA A DESISTENCIA",
                    "DESISTENCIA HOMOLOGADA", "PERDA DE OBJETO",
                    // Prescrição reconhecida de ofício encerra sem julgar o mérito do recurso.
                    "PRESCRICAO RETROATIVA", "EXTINTA A PUNIBILIDADE"),

            // Parcial antes de provido e de improvido: contém as duas palavras.
            new Regra(ResultadoDoRecurso.PARCIALMENTE_PROVIDO,
                    "PARCIALMENTE PROVIDO", "PARCIALMENTE PROVIDOS",
                    "PARCIAL PROVIMENTO", "PROVIDO EM PARTE", "PROVIDOS EM PARTE",
                    "DAR PARCIAL PROVIMENTO", "PARCIALMENTE DEFERIDO",
                    "PARCIALMENTE PROCEDENTE", "PROCEDENTE EM PARTE"),

            // Negativas antes da afirmativa: "NEGAR PROVIMENTO" contém "PROVIMENTO".
            new Regra(ResultadoDoRecurso.IMPROVIDO,
                    "DESPROVIDO", "DESPROVIDOS", "IMPROVIDO", "IMPROVIDOS",
                    "NAO PROVIDO", "NAO PROVIDOS", "NEGAR PROVIMENTO",
                    "NEGADO PROVIMENTO", "NEGADA PROVIMENTO",
                    // Ênclise: forma dominante na redação forense e a que mais faltava.
                    "NEGAR LHE PROVIMENTO", "NEGAR LHES PROVIMENTO",
                    "NEGOU SE PROVIMENTO", "NEGAR SE PROVIMENTO",
                    "MANTIDA A SENTENCA", "SENTENCA MANTIDA",
                    // Embargos de declaração: conhecidos e rejeitados é o desfecho comum.
                    "REJEITADOS", "REJEITADO", "REJEITAR OS EMBARGOS", "REJEITAR EMBARGOS",
                    // Habeas corpus e mandado de segurança.
                    "DENEGAR A ORDEM", "DENEGADA A ORDEM", "ORDEM DENEGADA",
                    // Ações de competência originária.
                    "IMPROCEDENTE", "IMPROCEDENTES"),

            new Regra(ResultadoDoRecurso.PROVIDO,
                    "PROVIDO", "PROVIDOS", "DAR PROVIMENTO", "DADO PROVIMENTO",
                    "DAR LHE PROVIMENTO", "DAR LHES PROVIMENTO", "DEU SE PROVIMENTO",
                    "DEFERIDO", "ACOLHIDO", "ACOLHIDOS",
                    "REFORMADA A SENTENCA", "SENTENCA REFORMADA",
                    "CONCEDER A ORDEM", "CONCEDIDA A ORDEM", "ORDEM CONCEDIDA",
                    "PROCEDENTE", "PROCEDENTES"));

    private ClassificadorDeDispositivo() {
    }

    private record Regra(ResultadoDoRecurso resultado, String... formas) {
    }

    /** As formas de todas as regras, para o teste que verifica se estão normalizadas. */
    public static List<String> formasConfiguradas() {
        return REGRAS.stream().flatMap(r -> java.util.Arrays.stream(r.formas())).toList();
    }

    /**
     * Classifica o dispositivo.
     *
     * @return {@link ResultadoDoRecurso#INDETERMINADO} quando nenhuma forma é reconhecida.
     *         Nunca chuta a categoria mais próxima: decisão mal classificada entra na
     *         estatística como se fosse outra coisa, e decisão indeterminada apenas reduz
     *         a amostra — o que é honesto e fica visível no {@code n}.
     */
    public static ResultadoDoRecurso classificar(String dispositivo) {
        String texto = normalizar(dispositivo);
        if (texto.isBlank()) {
            return ResultadoDoRecurso.INDETERMINADO;
        }

        for (Regra regra : REGRAS) {
            for (String forma : regra.formas()) {
                if (texto.contains(forma)) {
                    return regra.resultado();
                }
            }
        }
        return ResultadoDoRecurso.INDETERMINADO;
    }

    /**
     * Se a decisão foi unânime.
     *
     * <p>Interessa para a seção de comparação com o órgão: divergência é sinal de que o
     * relator se afasta da câmara, e é justamente isso que o advogado quer saber.
     *
     * @return {@code null} quando o dispositivo não informa — ausência de dado não é
     *         "não foi unânime".
     */
    public static Boolean unanime(String dispositivo) {
        String texto = normalizar(dispositivo);
        if (texto.contains("UNANIME")) {
            // "NAO UNANIME" e "POR MAIORIA" descrevem a mesma coisa; a primeira contém
            // "UNANIME" e precisa ser testada antes.
            return !texto.contains("NAO UNANIME");
        }
        if (texto.contains("MAIORIA") || texto.contains("VENCIDO")) {
            return false;
        }
        return null;
    }

    private static String normalizar(String bruto) {
        if (bruto == null) {
            return "";
        }
        String texto = bruto.toUpperCase(Locale.ROOT);
        texto = ACENTOS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("");
        // Pontuação vira espaço para que "CONHECIDO.PROVIDO" não seja lido como uma palavra
        // só, e o colapso seguinte normaliza o espaçamento.
        texto = texto.replaceAll("[^A-Z ]", " ");
        return ESPACOS.matcher(texto).replaceAll(" ").trim();
    }
}
