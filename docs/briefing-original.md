# PROMPT PARA O CLAUDE CODE — JusPrisma

> **Como usar:** crie uma pasta vazia (`mkdir jusprisma && cd jusprisma`), rode `claude`,
> e cole **tudo** que está abaixo da linha. Deixe o Claude Code responder com o plano antes
> de aprovar qualquer código.

---

Você vai construir comigo um SaaS jurídico brasileiro chamado **JusPrisma**, do zero.
Leia este briefing inteiro antes de escrever uma linha de código.

## 0. Antes de tudo

Não comece a codar. Sua primeira resposta deve ser:

1. Um plano de execução da **Fase 0 + Fase 1** quebrado em tarefas pequenas e ordenadas.
2. As dúvidas de arquitetura que você tem, se tiver.
3. Os riscos que você enxerga no briefing que eu não listei.

Depois que eu aprovar, crie nesta ordem: `CLAUDE.md`, `docs/adr/0001-arquitetura.md`,
`docs/dominio.md`, e só então o esqueleto do projeto.

Trabalhe **incrementalmente**: uma fatia vertical funcionando de ponta a ponta vale mais
que cinco camadas pela metade. Rode os testes a cada fatia. Commit pequeno e descritivo.
Quando uma decisão tiver mais de um caminho razoável, **pergunte antes de escolher**.

---

## 1. O que é o JusPrisma

Um SaaS para advogados brasileiros. O módulo-estrela — e a razão de existir do produto —
é o **Perfil de Magistrado**: o usuário digita o nome de um relator/desembargador (ou
escolhe um órgão julgador), e a plataforma:

1. Busca as decisões públicas dele nas APIs oficiais dos tribunais
2. Calcula estatísticas **determinísticas** em cima dos dados estruturados
3. Usa IA para extrair padrões de fundamentação dos inteiros teores
4. Entrega um **relatório de como aquele julgador decide**, com cada afirmação linkada à
   decisão que a sustenta

Em volta disso, uma plataforma completa de trabalho (jurisprudência, monitoramento de
processos, IA jurídica, peças, assinatura, financeiro) — as fases 2 a 4.

**Concorrente de referência:** Jusfy (jusfy.com.br). Ele tem 20+ ferramentas e **nenhuma
análise de magistrado**. Nós temos a análise de magistrado e vamos alcançar a paridade de
ferramentas depois.

---

## 2. REGRAS NÃO NEGOCIÁVEIS

Estas regras são requisitos legais e de auditoria. Elas entram no **código**, não só nos
termos de uso. Se alguma implementação conflitar com elas, pare e me avise.

1. **Sem fonte, não publica.** Toda afirmação gerada por IA no relatório carrega
   `fonte_id` + trecho literal da decisão. Valide isso **programaticamente** antes de
   persistir o relatório — não confie no modelo. Afirmação sem fonte válida é descartada
   e logada.
2. **Sempre mostre o `n`.** Todo percentual exibe a amostra que o gerou. Abaixo de
   `MIN_AMOSTRA = 20` decisões, o sistema **não gera percentual** — marca como
   "amostra insuficiente" e mostra só os números absolutos.
3. **Nunca gere score, nota, ranking ou previsão.** Nada de "juiz 82% favorável",
   "probabilidade de ganho", "ranking de magistrados". Só estatística descritiva sobre
   decisões públicas. Isso é a diferença entre produto defensável e processo judicial.
   Se eu pedir um feature que viole isso, me lembre desta regra antes de implementar.
4. **Prompts versionados.** Todo prompt de IA vive em arquivo versionado
   (`adapter-ai/src/main/resources/prompts/`), com `id` e `version`. O hash do prompt e o
   modelo usado são gravados em cada análise gerada. Precisa ser possível responder
   "qual prompt e quais fontes geraram este relatório?" um ano depois.
5. **Pseudonimize partes.** Nome de parte (pessoa física) não vai para a base analítica —
   substitua por hash na ingestão, desde a primeira migration. Nome de magistrado é dado
   de agente público no exercício da função e pode ser armazenado, mas só o que é função:
   nome, órgão, decisões. Nunca inferência sobre a pessoa.
6. **Disclaimer obrigatório** na tela do relatório e no rodapé de todo PDF exportado:
   ferramenta de apoio, não substitui juízo profissional, não garante resultado.
7. **Hospedagem no Brasil.** A API do DJEN geobloqueia requisições de fora do país (403).
   Isso é requisito de arquitetura, não detalhe de deploy.

---

## 3. Stack

- **Backend:** Java 21, Spring Boot 3.3+, Gradle multi-módulo, arquitetura hexagonal
  (ports & adapters)
- **Banco:** PostgreSQL 16 + **pgvector** (busca semântica) + `tsvector`/`pg_trgm` com
  dicionário `portuguese` (busca textual). **Não** suba Elasticsearch — o Postgres resolve
  no volume do ano 1.
- **Migrations:** Flyway. Nada de `ddl-auto`.
- **Fila:** RabbitMQ (ou Spring Events + tabela de outbox se você preferir começar mais leve —
  me proponha e justifique)
- **Batch:** Spring Batch ou `@Scheduled` + worker dedicado
- **HTTP client:** `WebClient` + **Resilience4j** (retry com backoff exponencial,
  circuit breaker, rate limiter). Toda fonte externa passa por isso, sem exceção.
- **Frontend:** React 18 + Vite + TypeScript + TanStack Query + Tailwind + shadcn/ui
- **Auth:** Spring Security + JWT (access curto + refresh), 2FA opcional por TOTP
- **PDF:** biblioteca Java que aceite template (sugestão: Thymeleaf → HTML → Playwright/
  wkhtmltopdf). Me proponha a que você acha melhor.
- **Testes:** JUnit 5 + Testcontainers (Postgres real) + WireMock (APIs externas) +
  AssertJ. Frontend: Vitest + Testing Library.
- **Observabilidade:** Spring Actuator + Micrometer + logs estruturados em JSON com
  correlation id
- **Dev:** Docker Compose (postgres, rabbit, adminer, mailhog). `docker compose up` +
  `./gradlew bootRun` tem que subir tudo.

### Estrutura

```
jusprisma/
├─ CLAUDE.md
├─ docker-compose.yml
├─ docs/
│  ├─ adr/                      decisões arquiteturais
│  ├─ dominio.md                glossário jurídico + modelo
│  └─ fontes-de-dados.md        contrato de cada API externa
├─ backend/
│  ├─ core-domain/              entidades e regras. ZERO dependência de framework.
│  ├─ core-application/         casos de uso, ports (interfaces)
│  ├─ adapter-persistence/      JPA, Flyway, repositórios, pgvector
│  ├─ adapter-web/              controllers REST, DTOs, OpenAPI
│  ├─ adapter-ingestion/        clients TJDFT, DataJud, DJEN
│  ├─ adapter-ai/               client Anthropic, Batch API, prompts versionados
│  ├─ adapter-billing/          Asaas + webhooks
│  ├─ worker-pipeline/          jobs de ingestão e análise
│  └─ app-bootstrap/            main, config, security
└─ frontend/
```

**Por que hexagonal:** as fontes de dados vão mudar várias vezes (tribunal derruba API,
entra agregador pago, muda formato). O domínio **não pode saber** que TJDFT existe.
`PortalDecisoes` é uma porta; `TjdftDecisoesAdapter` é um detalhe substituível.

---

## 4. Modelo de domínio

Entidades centrais (nomes em português — o domínio é jurídico brasileiro, não traduza):

```
Tenant                  id, nome, cnpj?, plano, status, criadoEm
Usuario                 id, tenantId, email, senhaHash, papel (OWNER/MEMBRO), oab?, ufOab?
Plano                   codigo (DEGUSTACAO/SOLO/PRO/ESCRITORIO), precoCentavos, limites (JSONB)
Assinatura              id, tenantId, planoCodigo, status, inicioEm, proximaCobranca,
                        gatewayCustomerId, gatewaySubscriptionId
CreditoLancamento       id, tenantId, tipoCredito (PERFIL/IA/CALCULO/CONSULTA/ASSINATURA),
                        delta (+/-), motivo, referenciaId, criadoEm
                        ← APPEND-ONLY. Saldo = SUM(delta). Nunca um campo mutável.

Tribunal                sigla, nome, grau[], fontesDisponiveis[], coberturaInicio
Magistrado              id, nomeNormalizado, nomeExibicao, tribunalSigla, orgaoJulgador,
                        grau, aliases[]   ← nomes vêm sujos das APIs; normalize agressivo
OrgaoJulgador           id, tribunalSigla, nome, codigoCnj?, tipo (CAMARA/TURMA/VARA)

Decisao                 id, tribunalSigla, identificadorExterno, numeroProcesso,
                        magistradoId, orgaoJulgadorId, classeCnj, assuntosCnj[],
                        dataJulgamento, dataPublicacao, ementa, inteiroTeor,
                        urlFonte, hashConteudo, ingeridoEm
                        ← UNIQUE (tribunalSigla, identificadorExterno)  [idempotência]
DecisaoEmbedding        decisaoId, embedding vector(1536)

ProcessoMetadado        numeroProcesso, tribunalSigla, classeCnj, assuntosCnj[],
                        orgaoJulgadorCodigo, dataAjuizamento, grau, movimentos JSONB
                        ← vem do DataJud

AnaliseDecisao          id, decisaoId, promptId, promptHash, modelo, resultado JSONB,
                        custoTokens, geradoEm
                        ← saída do "map": tese, resultado, fundamentos, precedentes,
                          requisitos probatórios, tom

PerfilMagistrado        id, magistradoId, recorte JSONB (assunto/classe/período),
                        versao, statusGeracao, estatisticas JSONB, relatorio JSONB,
                        amostraN, decisoesAnalisadas[], promptHash, modelo,
                        geradoEm, validoAte
                        ← O ARTEFATO. Gerado uma vez, servido a todos os tenants.

PerfilAcesso            id, tenantId, perfilId, consumiuCredito (bool), acessadoEm
                        ← perfil novo consome crédito; perfil em cache, não
```

**Pontos que você não pode errar:**

- `CreditoLancamento` é ledger append-only. Saldo é soma, nunca campo. Débito usa
  `SELECT ... FOR UPDATE` ou reserva otimista. Estorno é lançamento novo, não delete.
- `Decisao` tem chave natural única `(tribunal, identificadorExterno)` — reprocessar a
  ingestão **não pode duplicar nada**. Upsert idempotente.
- `PerfilMagistrado` é compartilhado entre tenants. `PerfilAcesso` é o que é por tenant.
  **É daqui que sai a margem do negócio** — não modele perfil como algo por usuário.
- Multi-tenant por `tenant_id` com **Row Level Security no Postgres**, não só filtro no
  repositório. Cinto e suspensório.

---

## 5. Fontes de dados

⚠️ **Antes de codar contra qualquer uma delas, escreva um smoke test que bata na API real
e me mostre a resposta crua.** As especificações abaixo vêm da documentação oficial de
setembro/2026 e podem estar desatualizadas. Documente o que você encontrar em
`docs/fontes-de-dados.md`.

### 5.1 TJDFT — jurisprudência com inteiro teor (PRIORIDADE 1)

```http
POST https://jurisdf.tjdft.jus.br/api/v1/pesquisa
Content-Type: application/json

{
  "query": "termo de busca",
  "pagina": 0,
  "tamanho": 20,
  "termosAcessorios": [
    { "campo": "nomeRelator", "valor": "NOME DO RELATOR" },
    { "campo": "dataJulgamento", "valor": "..." }
  ]
}
```

Sem autenticação. Campos filtráveis: `base`, `subbase`, `origem`, `uuid`, `identificador`,
`identificadorOrdenacao`, `processo`, `nomeRelator`, `nomeRevisor`, `nomeRelatorDesignado`,
`descricaoOrgaoJulgador`, `dataJulgamento`, `dataPublicacao`, `descricaoClasseCnj`.

Resposta: `hits` (total) + `registros[]` com `uuid`, `identificador`, `processo`,
`nomeRelator`, `descricaoOrgaoJulgador`, **`ementa`**, **`inteiroTeor`**, `dataJulgamento`,
`dataPublicacao`, `codigoClasseCnj`, `descricaoClasseCnj`, `marcadores`.

Documentação: https://www.tjdft.jus.br/transparencia/tecnologia-da-informacao-e-comunicacao/dados-abertos/documentos/documentacao_api_seti_transparencia.pdf

**Este é o coração da Fase 1.** É o único lugar onde temos nome de relator estruturado
+ inteiro teor + sem custo + sem autenticação.

### 5.2 DataJud (CNJ) — estatística processual de 182 tribunais

```http
POST https://api-publica.datajud.cnj.jus.br/api_publica_{alias}/_search
Authorization: <chave obtida no cadastro em cnj.jus.br/sistemas/datajud/api-publica/>
Content-Type: application/json

{ "query": { "bool": { "must": [...] } }, "size": 100, "sort": [{"@timestamp": "asc"}] }
```

Elasticsearch DSL. Paginação com `search_after` ordenando por `@timestamp`.

Campos: `numeroProcesso`, `id`, `tribunal`, `classe{codigo,nome}`, `assuntos[]`,
`orgaoJulgador{codigo,nome,codigoMunicipioIBGE}`, `dataAjuizamento`, `grau`, `nivelSigilo`,
`formato`, `sistema`, `movimentos[]{codigo,nome,dataHora,complementosTabelados}`.

**Não tem nome de juiz nem texto de decisão.** O valor está nos `movimentos`: os códigos da
Tabela Processual Unificada do CNJ incluem o resultado do julgamento (procedente,
improcedente, procedência em parte, extinção sem resolução de mérito). Dá para calcular
**taxa de procedência e tempo de tramitação por órgão julgador sem ler uma decisão**.

Tarefa: baixe a Tabela Processual Unificada do CNJ e crie um mapa
`codigoMovimento → categoriaDeResultado` em arquivo versionado. Esse mapa é ativo do produto.

### 5.3 DJEN / API Comunica (CNJ) — publicações (Fase 2)

```http
GET https://comunicaapi.pje.jus.br/api/v1/comunicacao
    ?numeroOab=&ufOab=&numeroProcesso=&siglaTribunal=
    &dataDisponibilizacaoInicio=&dataDisponibilizacaoFim=&pagina=&itensPorPagina=
GET https://comunicaapi.pje.jus.br/api/v1/comunicacao/{hash}/certidao
```

Sem autenticação. `itensPorPagina` máximo efetivo **50** (valores maiores retornam lista
vazia sem erro). Resposta: `{count, items[]}` com o texto da publicação,
`dataDisponibilizacao` e `hash`.

⚠️ **Geobloqueio (403 fora do Brasil)** e sem SLA publicado (500 sob pico).
Espaçamento mínimo de ~500ms entre chamadas + backoff exponencial + circuit breaker.

Cuidado jurídico: `dataDisponibilizacao` ≠ data de publicação. Pelo art. 224 do CPC,
a publicação é o primeiro dia útil seguinte à disponibilização, e o prazo começa no dia
útil seguinte a essa. **Modele os dois campos separados e calcule prazo em cima do certo** —
errar isso faz advogado perder prazo, e é o tipo de bug que mata a empresa.

### 5.4 Ainda não

Agregadores pagos (Escavador, Judit, jurisprudencias.ai) entram só quando um cliente pedir
um tribunal que não cobrimos. Deixe a porta `PortalDecisoes` pronta para receber um adapter
novo sem tocar em nada mais.

**Não implemente scraping de portal.** Se eu pedir, me lembre de que HTML muda, tem CAPTCHA,
bloqueio de IP e termos de uso.

---

## 6. O pipeline do Perfil de Magistrado

```
┌─ INGESTÃO ────────────────────────────────────────────────┐
│ TJDFT (relator → acórdãos + inteiroTeor)                  │
│ DataJud (órgão julgador → metadados + movimentos)         │
│ → fila → normalizador → upsert idempotente no Postgres    │
└───────────────────────────────────────────────────────────┘
                          ↓
┌─ CAMADA 1 — DETERMINÍSTICA (SQL puro, zero IA) ───────────┐
│ · taxa de procedência/improcedência/parcial por assunto    │
│   (a partir dos códigos de movimento do CNJ)               │
│ · tempo médio, mediana e p90 de ajuizamento → julgamento   │
│ · distribuição por classe e assunto CNJ                    │
│ · série temporal em janelas de 6 meses (mudou o            │
│   entendimento?)                                           │
│ · n de cada métrica, sempre                                │
└───────────────────────────────────────────────────────────┘
                          ↓
┌─ CAMADA 2 — IA COM CITAÇÃO OBRIGATÓRIA (map-reduce) ──────┐
│ MAP: cada decisão → JSON estruturado                       │
│   { tese, resultado, fundamentosPrincipais[],              │
│     precedentesInvocados[], requisitosProbatorios[],        │
│     dosimetria?, tom, trechosChave[{texto, offset}] }      │
│   Modelo: claude-haiku-4-5 via BATCH API (50% off)         │
│   ← é aqui que mora 80% do custo. Use batch sempre.        │
│                                                            │
│ REDUCE: N JSONs → relatório legível                        │
│   Modelo: claude-sonnet-5                                  │
│   Cada afirmação DEVE vir com decisaoId + trecho.          │
│   Valide em código: afirmação sem fonte é descartada.      │
└───────────────────────────────────────────────────────────┘
                          ↓
┌─ CACHE — o perfil é ARTEFATO, não consulta ───────────────┐
│ Gerado uma vez, versionado, servido a TODOS os tenants.    │
│ Recomputação incremental quando chegam decisões novas.     │
│ 2º ao 1000º usuário que pedir o mesmo perfil: custo ≈ 0.   │
└───────────────────────────────────────────────────────────┘
```

**Estrutura do relatório final** (9 seções, nesta ordem):

1. Ficha — magistrado, órgão, período coberto, quantidade de decisões analisadas
2. Números (Camada 1), cada um com `n`
3. Padrões de fundamentação — o que aparece nas favoráveis e nas contrárias
4. Requisitos práticos — o que esse julgador costuma exigir de prova
5. Precedentes mais invocados — súmulas, temas repetitivos, acórdãos
6. Tendência temporal — mudou nos últimos 24 meses?
7. Comparação com o órgão — alinhado ou divergente da câmara/turma
8. Decisões-chave, linkadas, com o trecho que sustenta cada afirmação acima
9. Rodapé metodológico — fonte, recorte, data, limitações, versão do prompt

**Geração é assíncrona.** O usuário dispara, recebe status em tempo real
(SSE ou polling) e é notificado quando termina. Perfil grande leva minutos — nunca segure
uma requisição HTTP esperando.

---

## 7. Planos, créditos e billing

| | Degustação | Solo | Pro | Escritório |
|---|---|---|---|---|
| Preço | R$9,90/14 dias | R$57/mês | R$137/mês | R$297/mês |
| Perfis **novos**/mês | 2 | 10 | 50 | 200 |
| Perfis **em cache** | ilimitado | ilimitado | ilimitado | ilimitado |
| Comparar magistrados | — | 2 | 5 | ilimitado |
| Alerta de virada de entendimento | — | — | ✓ | ✓ |
| PDF white-label | — | — | ✓ | ✓ |
| IA jurídica (msg/mês) | 30 | 150 | 600 | ilimitado (fair use) |
| Jurisprudência | ilimitado | ilimitado | ilimitado | ilimitado |
| Monitoramento de processos | 10 | ilimitado | ilimitado | ilimitado |
| Cálculos | 2 | 10 | 40 | ilimitado |
| Assinatura eletrônica | 2 | 10 | 40 | 150 |
| Consultas de dados | 0 | 5 | 20 | 60 |
| Drive | 1 GB | 10 GB | 50 GB | 200 GB |
| Subusuários | 0 | 0 | 3 | 10 |
| API | — | — | — | ✓ |
| Rollover de créditos | — | — | 1 mês | 1 mês |

**Regras de crédito:**

- **Perfil em cache não consome crédito.** Só a geração de perfil novo consome.
  Isso é regra de negócio central, não otimização — implemente explicitamente e teste.
- Limites vêm de `Plano.limites` (JSONB), nunca hardcoded em `if`. Mudar preço ou cota
  tem que ser mudança de dado.
- Solo não acumula créditos; Pro e Escritório acumulam por 1 mês.
- Trial converte automaticamente ao fim dos 14 dias, **com e-mail de aviso no dia 11**.

**Billing:** Asaas (2,99% cartão, R$0,49 Pix, R$1,99 boleto, D+2). Webhooks idempotentes
por `event_id`, com tabela de eventos recebidos. Deixe a interface de gateway abstraída —
vamos migrar para **Pix Automático** (0,22–0,35%, sem tarifa fixa) quando houver volume,
e não quero reescrever o módulo inteiro.

---

## 8. Escopo da Fase 0 e da Fase 1

### Fase 0 — Fundação

- [ ] Monorepo, Gradle multi-módulo, Docker Compose, CI (GitHub Actions: build + teste + lint)
- [ ] Flyway, schema inicial, Row Level Security por tenant
- [ ] Auth: cadastro, login, refresh, recuperação de senha, verificação de e-mail
- [ ] Multi-tenant + convite de subusuário + papéis
- [ ] Planos, assinatura, ledger de créditos, integração Asaas + webhooks
- [ ] Painel administrativo mínimo (listar tenants, ver consumo, conceder crédito manual)
- [ ] Observabilidade: healthcheck, métricas, logs estruturados com correlation id
- [ ] Layout base do frontend, rotas protegidas, tela de planos e de billing

### Fase 1 — Núcleo JusPrisma

- [ ] `docs/fontes-de-dados.md` + smoke tests reais contra TJDFT e DataJud
- [ ] Adapter TJDFT com Resilience4j, paginação e rate limit
- [ ] Adapter DataJud idem
- [ ] Mapa `codigoMovimentoCNJ → categoriaDeResultado` (arquivo versionado + testes)
- [ ] Normalizador de nome de magistrado (acentos, abreviaturas, "Des.", "Dr.", ordem
      invertida, grafias divergentes) + tabela de aliases. **Trate como problema sério:**
      nome vem sujo e é o eixo de todo o produto.
- [ ] Ingestão idempotente com upsert por chave natural
- [ ] Camada 1: consultas de jurimetria em SQL + testes com dados fixos
- [ ] Adapter Anthropic: client, Batch API, prompts versionados, contagem e registro de custo
- [ ] Map: análise por decisão com saída em JSON validado por schema
- [ ] Reduce: relatório com validação programática de fonte em cada afirmação
- [ ] Geração assíncrona com status em tempo real
- [ ] Cache/versionamento de perfil + `PerfilAcesso` por tenant
- [ ] Busca de magistrado com autocomplete
- [ ] Tela do relatório (as 9 seções, com decisões linkadas e trechos expandíveis)
- [ ] Comparador de 2 magistrados
- [ ] Exportação em PDF com marca do escritório (Pro+)
- [ ] Consumo de crédito amarrado à geração, com teste de que cache **não** cobra

---

## 9. Backlog das fases seguintes (não implemente agora, só deixe o caminho aberto)

**Fase 2 — paridade essencial:** busca de jurisprudência (textual + semântica com
pgvector), monitoramento de processos via DJEN (com o cálculo de prazo do art. 224 do CPC),
agenda e alertas por e-mail/push, chat de IA jurídica com upload de documentos, biblioteca
de modelos de peças, drive com cota por plano.

**Fase 3 — operação:** assinatura eletrônica com validade jurídica, financeiro e cobrança
de honorários, gerador de site para advogado com IA, permissões granulares de subusuário.

**Fase 4 — expansão:** 14 calculadoras (revisional, atualização de valores, FGTS, pensão,
aluguel, superendividamento, PASEP, dosimetria, progressão de regime, RMC/RCC, trabalhista,
INSS, divórcio, previdenciária acima do teto), consultas de dados via fornecedor formal
(só com contrato e trilha de auditoria por consulta), diligências entre advogados, API
pública, novos tribunais, WhatsApp.

**Não implemente marketplace de leads de cliente final.** O Provimento 205/2021 do CFOAB
veda mercantilização e captação de clientela. Diligências entre advogados, sim.

---

## 10. Definition of done

Uma tarefa só está pronta quando:

- Tem teste que falha sem a implementação (Testcontainers para banco, WireMock para API externa)
- `./gradlew build` passa limpo, sem warning novo
- Nada de segredo em código — tudo em variável de ambiente, com `.env.example` atualizado
- Migration criada e testada de subida (e de descida, quando fizer sentido)
- Endpoint documentado no OpenAPI
- Log estruturado nos caminhos de erro, com correlation id
- Se tocou em regra da seção 2, tem teste **especificamente** cobrindo a regra

---

## 11. Como eu quero trabalhar com você

- Português do Brasil em código de domínio, comentários, commits e conversa
- Commits pequenos, mensagem no imperativo, escopo claro
- Quando a tarefa for grande, me mostre o plano e espere aprovação
- Quando encontrar algo no briefing que está errado ou desatualizado (endpoint que mudou,
  campo que não existe), **pare e me avise** — não contorne silenciosamente
- Não invente dado de exemplo em teste que pareça real: use fixture explicitamente fictícia
- Se estiver na dúvida entre simples e completo, escolha simples e me diga o que deixou de fora

Comece pelo plano.
