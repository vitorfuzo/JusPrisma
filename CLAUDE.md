# CLAUDE.md — JusPrisma

Constituição do repositório. Lida em toda sessão. Se algo aqui conflitar com um pedido,
**pare e pergunte** antes de implementar.

---

## O que é este projeto

SaaS jurídico brasileiro. O módulo central é o **Perfil de Magistrado**: ingerimos decisões
públicas dos tribunais, calculamos estatística determinística em cima dos dados
estruturados, usamos IA para extrair padrões de fundamentação dos textos, e entregamos ao
advogado um relatório de **como aquele julgador decide** — com cada afirmação linkada à
decisão que a sustenta.

Em volta disso, uma plataforma de trabalho para o advogado (jurisprudência, monitoramento
de processos, IA jurídica, peças, assinatura, financeiro) — fases 2 a 4.

Público: advogado autônomo e escritório pequeno, no Brasil.

Estratégia, unit economics e precificação: `docs/planejamento.md`.
Briefing original de construção: `docs/briefing-original.md`.

---

## Regras invioláveis

Requisitos legais e de auditoria. Valem sobre qualquer consideração de produto ou
performance. Cada uma tem teste próprio; se você tocar numa delas, o teste vem junto.

1. **Sem fonte, não publica.** Afirmação gerada por IA sem `fonteId` + trecho literal é
   descartada antes de persistir. O trecho tem que casar literalmente com o texto que
   guardamos da decisão. Validação em código, nunca confiada ao modelo.
2. **Sempre o `n`.** Todo percentual exibe a amostra que o gerou. Abaixo de
   `MIN_AMOSTRA = 20`, não gera percentual — só números absolutos, marcados como amostra
   insuficiente. **O gate é por métrica, não global:** um relator com 4.000 acórdãos ainda
   cai abaixo de 20 quando se fatia por assunto.
3. **Nunca score, nota, ranking ou previsão de resultado.** Só estatística descritiva sobre
   decisões públicas. Se pedirem "probabilidade de ganho" ou "ranking de juízes", lembre
   esta regra antes de implementar.
4. **Prompts versionados.** Todo prompt em `adapter-ai/src/main/resources/prompts/`, com
   `id` e `version`. Hash do prompt + modelo gravados em cada análise. Precisa ser possível
   reconstruir a origem de qualquer relatório um ano depois.
5. **Partes são pseudonimizadas** na ingestão, desde a primeira migration. Magistrado é
   agente público — guarde nome, órgão e decisões; nunca inferência sobre a pessoa.
6. **Disclaimer** na tela do relatório e no rodapé de todo PDF: ferramenta de apoio, não
   substitui juízo profissional, não garante resultado.
7. **Roda no Brasil.** A API do DJEN geobloqueia (403 fora do país).
8. **Processo com `segredoJustica` / `nivelSigilo > 0` não entra na base analítica.**
   Filtre na ingestão, não na exibição.

---

## Stack

Java 21 · Spring Boot 4.1 · Gradle 9.7 multi-módulo · arquitetura hexagonal
PostgreSQL 16 + pgvector + tsvector/pg_trgm (`portuguese`) · Flyway
Resilience4j · WebClient
React 19 + Vite 8 + TypeScript + TanStack Query + Tailwind 4 + oxlint
JUnit 5 + Testcontainers + WireMock + AssertJ · Vitest + Testing Library
Docker Compose no dev

**Não** suba Elasticsearch. **Não** use `ddl-auto`. **Não** faça scraping de portal.

---

## Estrutura

```
backend/
  core-domain/          entidades e regras. ZERO dependência de framework.
  core-application/     casos de uso e ports (interfaces)
  adapter-persistence/  JPA, Flyway, repositórios, pgvector
  adapter-web/          controllers, DTOs, OpenAPI
  adapter-ingestion/    clients TJDFT, DataJud, DJEN
  adapter-ai/           Anthropic, Batch API, prompts
  adapter-notificacao/  e-mail transacional (SMTP + templates)
  adapter-billing/      Asaas, webhooks
  worker-pipeline/      jobs de ingestão e análise
  app-bootstrap/        main, config, security
frontend/
docs/adr/               uma ADR por decisão arquitetural relevante
docs/dominio.md         glossário jurídico + modelo
docs/fontes-de-dados.md contrato REAL de cada API externa
```

**A regra do hexágono:** o domínio não sabe que TJDFT existe. Fonte de dados é adapter
atrás de porta. Tribunal derrubar API não pode significar tocar em regra de negócio.

---

## Decisões já tomadas (não reabra sem conversar)

Detalhamento e alternativas descartadas em `docs/adr/0001-arquitetura.md`.

- **Orquestração é tabela de jobs no Postgres**, com worker usando
  `SELECT ... FOR UPDATE SKIP LOCKED`. Sem RabbitMQ. O pipeline é uma máquina de estados
  que fica horas parada esperando o Batch API, não mensageria. Porta abstraída para trocar
  quando houver volume.
- **`CreditoLancamento` é append-only.** Saldo = `SUM(delta)`. Nunca um campo mutável.
  Débito com `SELECT ... FOR UPDATE`. Estorno é lançamento novo, não delete.
- **Crédito de perfil debita no disparo** e é estornado automaticamente, como lançamento
  novo, em falha terminal da geração.
- **`Decisao` tem chave natural única `(tribunalSigla, identificadorExterno)`.**
  Ingestão idempotente por upsert. Reprocessar não duplica.
- **`PerfilMagistrado` é compartilhado entre tenants**; `PerfilAcesso` é por tenant.
  **Perfil em cache não consome crédito.** É daqui que sai a margem — não modele perfil
  como recurso por usuário.
- **`recorte` é enumerado e canonizado em hash determinístico.** Período só em 12/24/36/60
  meses; assunto e classe vêm de listas CNJ controladas. Dois recortes equivalentes escritos
  de formas diferentes têm que resolver para o mesmo hash, ou o cache vaza margem e o
  cliente é cobrado duas vezes pela mesma coisa. Tem teste.
- **Perfil vencido é repago por quem dispara a recomputação**; os demais tenants recebem a
  versão nova de graça.
- **Limites de plano vêm de `Plano.limites` (JSONB)**, nunca de `if` hardcoded.
- **Multi-tenant com Row Level Security no Postgres**, além do filtro no repositório.
  A aplicação usa role própria, distinta do owner das tabelas, com
  `FORCE ROW LEVEL SECURITY` e `SET LOCAL app.tenant_id` dentro da transação.
- **Geração de perfil é assíncrona**, com status em tempo real. Nunca segure HTTP esperando.
- **Batch API da Anthropic sempre que a latência permitir** — 50% de desconto, e o map é
  onde mora 80% do custo. Exceção: o primeiro perfil de cada tenant roda síncrono, porque
  esperar até 24h no momento da primeira compra mata a conversão.
- **Nomes de domínio em português.** `Magistrado`, `Decisao`, `OrgaoJulgador`,
  `CreditoLancamento`. O domínio é jurídico brasileiro; traduzir só cria ambiguidade.
- **Exceção ao RLS é sempre função `SECURITY DEFINER` estreita e nomeada**, com
  `search_path` fixo, devolvendo o mínimo. Nunca afrouxe a policy da tabela. Já existem
  para login, tokens de e-mail, convite, painel administrativo e jobs de cobrança — cada
  uma documentada na própria migration com a razão de existir.
- **Cota de plano tem duas naturezas.** Cota de consumo (perfis, IA, cálculos, consultas,
  assinaturas) vira crédito no ledger pela `RecargaDeCreditos`; cota de estado
  (subusuários, armazenamento) é conferida contra a situação atual por `LimitesVigentes`.
  Confundir as duas foi um bug real: a conta nascia com saldo zero.
- **Escopo de token separa cliente de operador** na cadeia de filtros, por autoridade —
  não dentro do controller. Rota nova entra protegida por padrão.

---

## Armadilhas do Spring Boot 4

Custaram tempo mais de uma vez. Todas têm o mesmo sintoma: compila, sobe, e falha em
runtime ou simplesmente não faz nada.

- **As auto-configurações saíram de `spring-boot-autoconfigure`** e foram para módulos
  próprios. A biblioteca no classpath não basta. Já precisamos de `spring-boot-flyway`
  (sem ele as migrations nunca rodam, e o erro aparece como "relation does not exist") e
  `spring-boot-webclient` (sem ele não existe bean de `WebClient.Builder`). Ao adicionar
  integração nova, procure o módulo `spring-boot-<coisa>` correspondente.
- **Jackson 3:** a raiz de pacote é `tools.jackson`, não `com.fasterxml.jackson`.
  `JsonNode.asText()` virou `asString()`, e `propertyNames()` devolve `Set`, não `Iterator`.
- **Testcontainers 2.x** consolidou os módulos: `postgresql` e `junit-jupiter` viraram
  `testcontainers-postgresql` e `testcontainers-junit-jupiter`. Os nomes antigos ainda
  existem no Maven Central, parados no 1.x, e misturá-los com o core 2.x quebra em runtime
  por classe sombreada removida. `PostgreSQLContainer` mudou de pacote e deixou de ser
  genérica.
- **O starter de teste web é `spring-boot-starter-webmvc-test`**, e `AutoConfigureMockMvc`
  mudou para `org.springframework.boot.webmvc.test.autoconfigure`.
- **O índice de busca do Maven Central está defasado** em relação ao que existe de fato.
  Consulte `maven-metadata.xml` direto quando a versão importar.

---

## Armadilhas do domínio jurídico

- **Nome de magistrado vem sujo.** "Des. João da Silva", "JOAO DA SILVA", "Silva, João da",
  com e sem acento, com e sem título. Normalização agressiva + tabela de aliases. É o eixo
  do produto inteiro — trate como problema de primeira classe, não como detalhe de string.
- **`dataDisponibilizacao` ≠ data de publicação.** Art. 224 do CPC: publicação é o primeiro
  dia útil seguinte à disponibilização; o prazo começa no dia útil seguinte a essa.
  Campos separados, cálculo em cima do certo, feriados forenses considerados.
  **Errar isso faz advogado perder prazo.**
- **1º grau e 2º grau são mundos diferentes.** Base de jurisprudência é feita de acórdãos
  (2º grau, com relator estruturado). Sentença de 1º grau raramente está lá — vem do DJEN
  ou de agregador, com cobertura irregular. Nunca prometa na interface cobertura que não
  temos: mostre a cobertura real por tribunal.
- **Magistrado aposentado.** O TJDFT marca `relatorAtivo`. Vender perfil de desembargador
  que não julga mais sem avisar é reclamação certa. Exiba o estado.

---

## Fontes de dados

| Fonte | Uso | Auth | Cuidado |
|---|---|---|---|
| TJDFT `POST jurisdf.tjdft.jus.br/api/v1/pesquisa` | acórdãos: `nomeRelator`, `ementa`, `decisao`, agregações | nenhuma | **não devolve inteiro teor** (ver abaixo) |
| DataJud `POST api-publica.datajud.cnj.jus.br/api_publica_{alias}/_search` | metadados + `movimentos` codificados de 182 tribunais | chave publica do wiki | sem nome de juiz, sem texto |
| DJEN `GET comunicaapi.pje.jus.br/api/v1/comunicacao` | publicações, prazos, decisões de 1º grau | nenhuma | geobloqueio; máx 50/página; ~500ms entre chamadas |

**O contrato real e verificado de cada uma está em `docs/fontes-de-dados.md`** — e ele
diverge do briefing original em pontos que importam. Mantenha-o atualizado quando a API
mudar. Toda chamada externa passa por Resilience4j (retry com backoff, circuit breaker,
rate limiter). Sem exceção.

Dois fatos do TJDFT que mudam desenho, verificados em 15/09/2026:

- O campo é `inteiroTeorHtml` e vem com `"Inteiro Teor indisponível."` na busca, mesmo com
  `possuiInteiroTeor: true`. **A Camada 2 opera sobre a `ementa`** (~10 mil caracteres, traz
  tese, fundamento e dispositivo) até que exista fonte confirmada de inteiro teor. A
  limitação é declarada na interface e no rodapé metodológico do relatório.
- O campo `decisao` traz o dispositivo em vocabulário controlado
  (`"CONHECIDO. PARCIALMENTE PROVIDO. UNÂNIME."`). **A Camada 1 de 2º grau sai daí**, sem
  IA e sem depender da Tabela Processual Unificada do CNJ.

---

## Definition of done

- Teste que falha sem a implementação (Testcontainers para banco, WireMock para externo)
- `./gradlew build` limpo, sem warning novo
- Zero segredo em código; `.env.example` atualizado
- Migration criada e testada
- Endpoint no OpenAPI
- Log estruturado com correlation id nos caminhos de erro
- Se tocou em regra inviolável, teste **específico** cobrindo a regra

---

## Como trabalhar aqui

- Português do Brasil em domínio, comentários, commits e conversa
- Commits pequenos, mensagem no imperativo
- Fatia vertical funcionando ponta a ponta vale mais que cinco camadas pela metade
- Tarefa grande: mostre o plano e espere aprovação
- Achou algo errado ou desatualizado no briefing (endpoint mudou, campo não existe):
  **pare e avise**, não contorne em silêncio
- Fixture de teste é explicitamente fictícia — nada que pareça dado real de processo
- Na dúvida entre simples e completo: simples, e diga o que ficou de fora

---

## Comandos

```bash
docker compose up -d          # postgres (5432), adminer (8081), mailpit (8025)
./gradlew bootRun             # backend
./gradlew build               # build + testes
./gradlew test --tests "*X*"  # teste específico
./gradlew test -PcomSmoke     # inclui smoke contra API real (rede; não roda no CI)
cd frontend && npm run dev    # frontend
```
