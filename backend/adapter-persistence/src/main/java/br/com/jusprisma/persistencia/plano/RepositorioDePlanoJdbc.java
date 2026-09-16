package br.com.jusprisma.persistencia.plano;

import br.com.jusprisma.aplicacao.plano.RepositorioDePlano;
import br.com.jusprisma.dominio.plano.Cota;
import br.com.jusprisma.dominio.plano.LimitesDoPlano;
import br.com.jusprisma.dominio.plano.Plano;
import br.com.jusprisma.dominio.plano.Recurso;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class RepositorioDePlanoJdbc implements RepositorioDePlano {

    private static final Logger log = LoggerFactory.getLogger(RepositorioDePlanoJdbc.class);

    /**
     * Leitor próprio, e não o ObjectMapper da aplicação.
     *
     * <p>Duas razões. A primeira é acoplamento: injetar o mapeador global obrigaria este
     * módulo a exigir a autoconfiguração de JSON para subir, mesmo num contexto que não
     * fala HTTP. A segunda é previsibilidade: o mapeador global é ajustado para os DTOs da
     * API, e uma mudança lá — estratégia de nomes, módulo novo — não deve alterar em
     * silêncio como as cotas de um plano são interpretadas.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;

    public RepositorioDePlanoJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Plano> buscar(String codigo) {
        return jdbc.sql("SELECT codigo, nome, preco_centavos, limites FROM plano WHERE codigo = :codigo")
                .param("codigo", codigo)
                .query(this::mapear)
                .optional();
    }

    @Override
    public List<Plano> listarAtivos() {
        return jdbc.sql("""
                SELECT codigo, nome, preco_centavos, limites
                  FROM plano WHERE ativo ORDER BY preco_centavos
                """)
                .query(this::mapear)
                .list();
    }

    private Plano mapear(ResultSet rs, int linha) throws SQLException {
        return new Plano(
                rs.getString("codigo"),
                rs.getString("nome"),
                rs.getInt("preco_centavos"),
                lerLimites(rs.getString("codigo"), rs.getString("limites")));
    }

    /**
     * Converte o JSONB em limites tipados.
     *
     * <p>Chave desconhecida é ignorada com aviso, e não faz a leitura falhar: um plano novo
     * cadastrado com uma cota que esta versao do codigo ainda nao conhece nao pode derrubar
     * o sistema inteiro. O contrario - cota conhecida ausente no JSON - ja e tratado como
     * negacao em LimitesDoPlano.
     */
    private LimitesDoPlano lerLimites(String codigoDoPlano, String bruto) {
        JsonNode raiz = JSON.readTree(bruto);

        Map<Cota, Integer> cotas = new EnumMap<>(Cota.class);
        JsonNode noCotas = raiz.path("cotas");
        for (Map.Entry<String, JsonNode> entrada : noCotas.properties()) {
            try {
                JsonNode valor = entrada.getValue();
                // null no JSON significa ilimitado, e por isso o mapa aceita valor nulo.
                cotas.put(Cota.valueOf(entrada.getKey()), valor.isNull() ? null : valor.asInt());
            } catch (IllegalArgumentException desconhecida) {
                log.warn("cota desconhecida '{}' no plano {}; ignorada",
                        entrada.getKey(), codigoDoPlano);
            }
        }

        Set<Recurso> recursos = EnumSet.noneOf(Recurso.class);
        for (JsonNode no : raiz.path("recursos")) {
            try {
                recursos.add(Recurso.valueOf(no.asString()));
            } catch (IllegalArgumentException desconhecido) {
                log.warn("recurso desconhecido '{}' no plano {}; ignorado",
                        no.asString(), codigoDoPlano);
            }
        }

        return new LimitesDoPlano(cotas, recursos);
    }
}
