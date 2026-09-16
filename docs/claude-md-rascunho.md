# CLAUDE.md — JusPrisma

> Coloque este arquivo na raiz do repositório com o nome `CLAUDE.md`.
> Ele é lido automaticamente pelo Claude Code em toda sessão — é a constituição do projeto.

---

## O que é este projeto

SaaS jurídico brasileiro. O módulo central é o **Perfil de Magistrado**: ingerimos decisões
públicas dos tribunais, calculamos estatística determinística em cima dos dados
estruturados, usamos IA para extrair padrões de fundamentação dos inteiros teores, e
entregamos ao advogado um relatório de **como aquele julgador decide** — com cada afirmação
linkada à decisão que a sustenta.

Em volta disso, uma plataforma de trabalho para o advogado (jurisprudência, monitoramento
de processos, IA jurídica, peças, assinatura, financeiro).

Público: advogado autônomo e escritório pequeno, no Brasil.

---

## Regras invioláveis

Estas são requisitos legais e de auditoria. Valem sobre qualquer outra consideração de
produto ou performance. Se uma implementação conflitar com elas, **pare e pergunte**.

1. **Sem fonte, não publica.** Afirmação gerada por IA sem `fonte_id` + trecho literal é
   descartada antes de persistir. Validação em código, nunca confiada ao modelo.
2. **Sempre o `n`.** Todo percentual exibe a amostra. Abaixo de `MIN_AMOSTRA = 20`, não
   gera percentual — só números absolutos, marcados como amostra insuficiente.
3. **Nunca score, nota, ranking ou previsão de resultado.** Só estatística descritiva sobre
   decisões públicas. Se pedirem "probabilidade de ganho" ou "ranking de juízes", lembre
   esta regra antes de implementar.
4. **Prompts versionados.** Todo prompt em `adapter-ai/src/main/resources/prompts/`, com
   `id` e `version`. Hash do prompt + modelo gravados em cada análise. Precisa ser possível
   reconstruir a origem de qualquer relatório um ano depois.
5. **Partes são pseudonimizadas** na ingestão. Magistrado é agente público — guarde nome,
   órgão e decisões; nunca inferência sobre a pessoa.
6. **Disclaimer** na tela do relatório e no rodapé de todo PDF.
7. **Roda no Brasil.** A API do DJEN geobloqueia (403 fora do país).

---

## Stack

Java 21 · Spring Boot 3.3+ · Gradle multi-módulo · arquitetura hexagonal
PostgreSQL 16 + pgvector + tsvector/pg_trgm (`portuguese`) · Flyway
RabbitMQ · Resilience4j · WebClient
React 18 + Vite + TypeScript + TanStack Query + Tailwind + shadcn/ui
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
  adapter-billing/      Asaas, webhooks
  worker-pipeline/      jobs de ingestão e análise
  app-bootstrap/        main, config, security
frontend/
docs/adr/               uma ADR por decisão arquitetural relevante
docs/dominio.md         glossário jurídico
docs/fontes-de-dados.md contrato real de cada API externa
```

**A regra do hexágono:** o domínio não sabe que TJDFT existe. Fonte de dados é adapter
atrás de porta. Tribunal derrubar API não pode significar tocar em regra de negócio.

---

## Decisões já tomadas (não reabra sem conversar)

- **`CreditoLancamento` é append-only.** Saldo = `SUM(delta)`. Nunca um campo mutável.
  Débito com `SELECT ... FOR UPDATE` ou reserva otimista. Estorno é lançamento novo.
- **`Decisao` tem chave natural única `(tribunalSigla, identificadorExterno)`.**
  Ingestão é idempotente por upsert. Reprocessar não duplica.
- **`PerfilMagistrado` é compartilhado entre tenants**; `PerfilAcesso` é por tenant.
  **Perfil em cache não consome crédito.** É daqui que sai a margem — não modele perfil
  como recurso por usuário.
- **Limites de plano vêm de `Plano.limites` (JSONB)**, nunca de `if` hardcoded.
- **Multi-tenant com Row Level Security no Postgres**, além do filtro no repositório.
- **Geração de perfil é assíncrona**, com status em tempo real. Nunca segure HTTP esperando.
- **Batch API da Anthropic sempre que a latência permitir** — 50% de desconto, e o map é
  onde mora 80% do custo.
- **Nomes de domínio em português.** `Magistrado`, `Decisao`, `OrgaoJulgador`,
  `CreditoLancamento`. O domínio é jurídico brasileiro; traduzir só cria ambiguidade.

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
- **Sigilo.** Processos com `nivelSigilo > 0` não entram na base analítica. Filtre na
  ingestão, não na exibição.

---

## Fontes de dados

| Fonte | Uso | Auth | Cuidado |
|---|---|---|---|
| TJDFT `POST jurisdf.tjdft.jus.br/api/v1/pesquisa` | acórdãos com `nomeRelator`, `ementa`, `inteiroTeor` | nenhuma | fonte primária da v1 |
| DataJud `POST api-publica.datajud.cnj.jus.br/api_publica_{alias}/_search` | metadados + `movimentos` codificados de 182 tribunais | chave do CNJ | sem nome de juiz, sem texto |
| DJEN `GET comunicaapi.pje.jus.br/api/v1/comunicacao` | publicações, prazos, decisões de 1º grau | nenhuma | geobloqueio; máx 50/página; ~500ms entre chamadas |

Contrato real de cada uma em `docs/fontes-de-dados.md` — mantenha atualizado quando a API
mudar. Toda chamada externa passa por Resilience4j (retry com backoff, circuit breaker,
rate limiter). Sem exceção.

---

## Definition of done

- Teste que falha sem a implementação (Testcontainers para banco, WireMock para externo)
- `./gradlew build` limpo, sem warning novo
- Zero segredo em código; `.env.example` atualizado
- Migration criada e testada
- Endpoint no OpenAPI
- Log estruturado com correlation id nos caminhos de erro
- Se tocou em regra inviolável, teste específico cobrindo a regra

---

## Como trabalhar aqui

- Português do Brasil em domínio, comentários, commits e conversa
- Commits pequenos, mensagem no imperativo
- Tarefa grande: mostre o plano e espere aprovação
- Achou algo errado ou desatualizado no briefing (endpoint mudou, campo não existe):
  **pare e avise**, não contorne em silêncio
- Fixture de teste é explicitamente fictícia — nada que pareça dado real de processo
- Na dúvida entre simples e completo: simples, e diga o que ficou de fora

## Comandos

```bash
docker compose up -d          # postgres, rabbit, adminer, mailhog
./gradlew bootRun             # backend
./gradlew build               # build + testes
./gradlew test --tests '*X*'  # teste específico
cd frontend && npm run dev    # frontend
```
