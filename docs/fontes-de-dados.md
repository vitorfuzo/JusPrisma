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
Authorization: APIKey <chave publica do wiki>
```

**Status: verificado em 16/09/2026.**

**Não existe cadastro.** O briefing dizia que a chave exigia registro no CNJ; não exige. É
uma **chave pública única**, publicada em
[datajud-wiki.cnj.jus.br/api-publica/acesso](https://datajud-wiki.cnj.jus.br/api-publica/acesso/),
e o CNJ pode trocá-la a qualquer momento. O cabeçalho é `Authorization: APIKey <chave>` —
**não** `Bearer`.

Corpo em Elasticsearch DSL; paginação por `search_after` ordenando por `@timestamp`.

### Armadilhas verificadas contra a API real

Todas silenciosas: nenhuma gera erro, todas produzem dado errado.

**1. `numeroProcesso` vem sem máscara, e o TJDFT vem com.**

```
DataJud : 07085934820238070018      (20 dígitos)
TJDFT   : 0709116-94.2022.8.07.0018 (25 caracteres, formatado)
```

Esta é a chave de junção entre as duas fontes. Comparar as formas cruas não casa **nenhum**
processo, e o sintoma é "o DataJud não tem esses processos" em vez de erro. A junção tem
que ser feita sobre os 20 dígitos, com a máscara removida dos dois lados.

**2. A acentuação vem corrompida na origem.**

```
"5? VARA DA FAZENDA P?BLICA E SA?DE P?BLICA DO DF"
"3? VARA C?VEL DE TAGUATINGA"
```

Verificado nos bytes: é literalmente `0x3F` (o caractere `?`), não problema de terminal. O
dado está corrompido no índice do CNJ. **Nome de órgão julgador vindo do DataJud não serve
para exibição** — use o do TJDFT, que vem correto, e trate o do DataJud apenas como chave
aproximada de correspondência.

**3. `dataAjuizamento` não é ISO-8601.**

Vem como string `"20230728124939"` — `yyyyMMddHHmmss`. Passar isso para um parser de ISO
falha; passar para um parser leniente pode produzir data errada em silêncio.

**4. `hits.total` para em 10.000 por padrão.**

É o teto do Elasticsearch, não o total real. Sem `"track_total_hits": true`, qualquer
contagem acima disso mente. O TJDFT tem **541.030** processos no índice, não 10.000.

**5. Um processo tem um registro por grau.**

O mesmo `numeroProcesso` aparece como `TJDFT_G1_<número>` e `TJDFT_G2_<número>`, com
classe, órgão e data de ajuizamento próprios de cada grau. Para enriquecer acórdão, o
registro certo é o `G2`; pegar o primeiro que vier mistura a classe da ação de 1º grau com
a do recurso. Verificado em 02/10/2026.

**6. O cluster sobrecarregado responde 200 com resultado parcial — e é lento.**

Verificado em 02/10/2026:

- consultas por `terms` em `numeroProcesso` levaram de **30 a 50 s** (medido pelo próprio
  `took`);
- com o cluster sob carga, a resposta veio `200 OK` com `"_shards": {"failed": 2}` e
  **zero hits** para processos que existem — `es_rejected_execution_exception` nos shards;
- a busca por `ids` caiu em **504 aos 60 s**, corte de um proxy na frente do cluster.

O adaptador trata `_shards.failed > 0` e `timed_out: true` como indisponibilidade (repete
com espera longa), nunca como "processo não encontrado"; consulta em lote de até 100
processos para amortizar a latência; e pede só os campos usados (`_source`), sem os
`movimentos`, que são a maior parte do documento.

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
5. **O domínio não conhece nenhuma destas APIs.** Tudo aqui vive atrás das portas
   `FonteDeAcordaos` (TJDFT) e `FonteDeMetadadosProcessuais` (DataJud); trocar de fonte
   não toca em regra de negócio.

---

## 5. O que ainda precisa ser verificado

| Item | Por quê |
|---|---|
| Endpoint de inteiro teor do TJDFT | Sete rotas plausíveis retornaram 404. Falta ler o PDF oficial de documentação. Enquanto não existir, a Camada 2 opera sobre a ementa e a limitação é declarada no relatório. |
| Taxa de casamento TJDFT × DataJud | Em 02/10/2026, 27 de 36 processos de uma página recente do TJDFT foram encontrados no DataJud (`G2`). Medir em volume e ver se os ausentes são atraso de indexação ou falta de cobertura. |
| Rate limit do TJDFT | Não publicado. Descobrir empiricamente, com cuidado, antes de ingestão em volume. |
| Estabilidade de `identificador` | A chave natural depende de ele não mudar entre versões do acórdão. O campo `versao` sugere que acórdãos são revisados. |
