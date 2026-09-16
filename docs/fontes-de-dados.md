# Fontes de dados — contrato real

> Verificado contra as APIs de produção em **16/09/2026**. Este documento descreve o que
> as APIs realmente fazem, e diverge do briefing original em pontos que mudam desenho.
> Quando a API mudar, atualize aqui primeiro.

---

## 1. TJDFT — jurisprudência de 2º grau

```http
POST https://jurisdf.tjdft.jus.br/api/v1/pesquisa
Content-Type: application/json
```

Sem autenticação. É a fonte primária da Fase 1: o único lugar com nome de relator
estruturado, texto substancial e custo zero.

### Requisição

```json
{
  "query": "dano moral",
  "pagina": 0,
  "tamanho": 20,
  "termosAcessorios": [
    { "campo": "nomeRelator", "valor": "ALFEU MACHADO" }
  ]
}
```

### Divergências verificadas em relação ao briefing

| O briefing dizia | O que a API faz |
|---|---|
| campo `inteiroTeor` com o texto integral | o campo é **`inteiroTeorHtml`** e vem com `"Inteiro Teor indisponível."`, mesmo com `possuiInteiroTeor: true` |
| `tamanho` livre | **máximo efetivo 40.** Acima disso a resposta vem `200 OK` **sem a chave `registros`** — falha silenciosa, como no DJEN |
| campos `descricaoClasseCnj` e `assuntosCnj` | só existe `codigoClasseCnj`. **Não há assunto CNJ** — ele precisa vir do DataJud, por `numeroProcesso` |

### O que existe e o briefing não previa

Três achados que trabalham a favor do produto:

**`decisao`** — o dispositivo do acórdão, em texto curto. Exemplo:
`"CONHECIDO. PARCIALMENTE PROVIDO. UNÂNIME."`
É daqui que sai a Camada 1 de 2º grau, sem IA e sem depender da Tabela Processual
Unificada do CNJ. Ver a ressalva sobre normalização abaixo.

**`agregacoes`** — facetas com contagem, na mesma resposta da busca:
`relator`, `relatorDesignado`, `revisor`, `orgaoJulgador`, `base`, `segredoJustica`,
`classe`. Uma chamada devolve o catálogo de magistrados **com o `n` de cada um** — é o que
alimenta o autocomplete e o gate de `MIN_AMOSTRA` sem varrer a base.

**`segredoJustica`** e **`relatorAtivo`** — booleanos por registro. O primeiro é o filtro
de sigilo exigido pela regra inviolável 8; o segundo diz se o desembargador ainda julga.

### Ressalva importante: `decisao` é semi-estruturado, não controlado

Eu descrevi este campo como "vocabulário controlado" na avaliação inicial. **Está errado.**
Uma amostra de 25 registros já mostra o mesmo resultado escrito de quatro formas:

```
DESPROVIDO
DESPROVIDOS
NEGAR PROVIMENTO
NEGAR PROVIMENTO AO RECURSO
```

E também `PARCIALMENTE PROVIDO` convivendo com `DAR PARCIAL PROVIMENTO AO RECURSO`.

O campo continua muito melhor que texto livre — é um conjunto pequeno de formas, com
plural, com e sem o objeto explícito. Mas exige **tabela de normalização versionada e
testada**, exatamente como a de nome de magistrado. Tratar as variantes como resultados
distintos produziria estatística errada, que é o pior defeito possível neste produto.

### Formato da resposta

```
hits.value                  total de resultados
paginacao                   { pagina, tamanho }
agregacoes                  facetas com contagem
registros[]                 os acórdãos da página
```

Campos de cada registro:

| Campo | Observação |
|---|---|
| `uuid`, `identificador` | `identificador` é a chave natural com o tribunal |
| `processo` | número único CNJ — junção com o DataJud |
| `nomeRelator`, `relatorAtivo` | nome vem em caixa alta, sem título |
| `descricaoOrgaoJulgador` | ex.: `6ª TURMA CÍVEL` |
| `ementa` | ~9 mil caracteres. **Base da Camada 2** |
| `decisao` | dispositivo; ver ressalva acima |
| `dataJulgamento`, `dataPublicacao` | ISO-8601 com fuso |
| `codigoClasseCnj` | só o código |
| `segredoJustica`, `turmaRecursal` | booleanos |
| `inteiroTeorHtml`, `possuiInteiroTeor` | ver divergências |

### Paginação

`pagina` é o índice, base zero. Testado até a página 500 com `tamanho` 20 — 10 mil
registros de profundidade — sem degradação nem erro.

### Limites operacionais

- `tamanho` no máximo **40**. O adaptador deve recusar valores maiores em vez de repassar,
  porque a API não reclama: devolve `200 OK` sem `registros`, e o resultado seria
  interpretado como "nenhum acórdão".
- Sem rate limit publicado. O adaptador usa espaçamento conservador e circuit breaker.

---

## 2. DataJud (CNJ) — metadados de 182 tribunais

```http
POST https://api-publica.datajud.cnj.jus.br/api_publica_{alias}/_search
Authorization: <chave obtida no cadastro do CNJ>
```

**Status: não verificado.** A chave exige cadastro no CNJ e ainda não foi obtida. O
adaptador está escrito contra a documentação oficial e precisa de smoke test antes de ser
considerado confiável.

Corpo em Elasticsearch DSL; paginação por `search_after` ordenando por `@timestamp`.

O que o DataJud acrescenta ao que já temos do TJDFT:

- **`assuntos[]`** — o assunto CNJ, que o TJDFT não fornece e de que o recorte do perfil
  depende. Junção por `numeroProcesso`.
- **`dataAjuizamento`** — sem ela não há como calcular tempo de tramitação.
- **`movimentos[]`** com códigos da Tabela Processual Unificada — resultado de julgamento
  de 1º grau, que a base de jurisprudência não cobre.
- **`nivelSigilo`** — filtro da regra inviolável 8 para os dados vindos daqui.

Não tem nome de juiz nem texto de decisão.

---

## 3. DJEN / Comunica (CNJ) — publicações

```http
GET https://comunicaapi.pje.jus.br/api/v1/comunicacao
```

**Status: não verificado — Fase 2.** Sem autenticação, mas com dois cuidados que mudam
arquitetura:

- **Geobloqueio:** responde 403 fora do Brasil. É requisito de hospedagem, não detalhe de
  implantação.
- **`itensPorPagina` máximo efetivo 50.** Valores maiores devolvem lista vazia sem erro —
  mesmo padrão de falha silenciosa do TJDFT.

E um cuidado jurídico que é o mais caro de errar:

> `dataDisponibilizacao` **não é** a data de publicação. Pelo art. 224 do CPC, a publicação
> é o primeiro dia útil seguinte à disponibilização, e o prazo começa no dia útil seguinte
> a essa. Os dois campos são guardados separados, e o cálculo considera feriados forenses.
> **Errar isso faz advogado perder prazo.**

---

## 4. Como o adaptador trata todas elas

Regras que valem para qualquer fonte externa, sem exceção:

1. **Resilience4j em toda chamada** — retry com backoff exponencial, circuit breaker, rate
   limiter. Repetir em intervalo fixo contra um serviço em sobrecarga só prolonga a
   sobrecarga.
2. **Falha silenciosa é tratada como erro.** Duas das três APIs devolvem `200 OK` com
   resposta vazia quando o parâmetro de paginação excede o limite. O adaptador valida o
   limite antes de enviar e trata resposta sem a chave esperada como falha, nunca como
   "zero resultados".
3. **Sigilo é filtrado na ingestão**, não na exibição.
4. **Nome de parte é pseudonimizado** antes de entrar na base analítica.
5. **O domínio não conhece nenhuma destas APIs.** Tudo aqui vive atrás da porta
   `PortalDecisoes`; trocar de fonte não toca em regra de negócio.

---

## 5. O que ainda precisa ser verificado

| Item | Por quê |
|---|---|
| Endpoint de inteiro teor do TJDFT | Sete rotas plausíveis retornaram 404. Falta ler o PDF oficial de documentação. Enquanto não existir, a Camada 2 opera sobre a ementa e a limitação é declarada no relatório. |
| DataJud com chave real | Nada foi exercitado contra a API de verdade. |
| Rate limit do TJDFT | Não publicado. Descobrir empiricamente, com cuidado, antes de ingestão em volume. |
| Estabilidade de `identificador` | A chave natural depende de ele não mudar entre versões do acórdão. O campo `versao` sugere que acórdãos são revisados. |
