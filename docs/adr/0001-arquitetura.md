# ADR 0001 — Arquitetura de base do JusPrisma

- **Status:** aceita
- **Data:** 15/09/2026
- **Contexto do produto:** `docs/planejamento.md`

Esta ADR consolida as decisões estruturais tomadas antes da primeira linha de código.
Cada seção registra a alternativa descartada, porque a razão de descartar é o que evita
reabrir a discussão daqui a seis meses.

---

## 1. Arquitetura hexagonal (ports & adapters)

**Decisão.** `core-domain` sem nenhuma dependência de framework. `core-application` define
portas; os módulos `adapter-*` as implementam.

**Por quê.** As fontes de dados vão mudar várias vezes: tribunal derruba API, entra
agregador pago, muda formato. Já aconteceu antes de escrevermos código — o briefing
descrevia um campo `inteiroTeor` que não existe (seção 6). O domínio não pode saber que
TJDFT existe: `PortalDecisoes` é porta, `TjdftDecisoesAdapter` é detalhe substituível.

**Custo aceito.** Mais módulos, mais cerimônia de mapeamento entre camadas, build mais
lento. Vale para as fronteiras de fonte de dados e de gateway de pagamento; não vamos
inventar porta para coisa que não tem chance de trocar.

---

## 2. Orquestração: tabela de jobs no Postgres, não RabbitMQ

**Decisão.** Tabela `job` + worker com `SELECT ... FOR UPDATE SKIP LOCKED`. Sem broker.

**Por quê.** O pipeline de perfil não é mensageria — é uma máquina de estados de vida
longa. O passo `map` submete um lote ao Batch API da Anthropic e fica **horas** parado
esperando. Uma fila AMQP resolve o problema oposto: entrega rápida com *visibility
timeout*, que nesse cenário ou expira e reentrega trabalho já pago em tokens, ou exige
heartbeat artificial. Além disso:

- O status do job vira um `SELECT`, o que alimenta o SSE de progresso sem tabela espelho.
- Retry, backoff e contagem de tentativas ficam em SQL, auditáveis.
- Idempotência e transação de negócio compartilham a mesma conexão — sem *dual write*.
- Um serviço a menos no `docker-compose` e na conta de infra.

**Alternativa descartada.** RabbitMQ, como sugeria o briefing. Traria mensageria de
verdade, mas dividiria o estado do job entre fila e banco e exigiria a tabela espelho de
qualquer forma para o SSE.

**Gatilho de revisão.** Quando houver mais de um worker em máquinas distintas com
contenção medida na tabela, ou fan-out para consumidores independentes. A porta
`FilaDeTrabalho` existe para que a troca seja um adapter novo.

---

## 3. Crédito debita no disparo, estorna em falha

**Decisão.** `CreditoLancamento` append-only; saldo é `SUM(delta)`. O débito acontece
quando a geração é disparada, sob `SELECT ... FOR UPDATE` da linha de saldo do tenant.
Falha terminal gera **lançamento novo** de estorno, nunca delete nem update.

**Por quê.** Cada geração custa dinheiro real em tokens antes de qualquer cobrança. Debitar
só na conclusão abriria janela para disparar N gerações concorrentes com saldo de 1.
Append-only é exigência de auditoria: precisamos reconstruir o extrato de qualquer tenant
em qualquer data.

**Custo aceito.** O usuário vê o saldo cair antes de receber o relatório. Mitigado por
estorno automático e pela exibição do estado do job.

**Alternativa descartada.** Reserva + confirmação em dois lançamentos. Contabilmente mais
correto, mas dobra as linhas do ledger e adiciona uma máquina de estados para manter.
Se o extrato ao cliente exigir essa granularidade, revisitamos.

---

## 4. O perfil é artefato compartilhado; o recorte é enumerado

**Decisão.** `PerfilMagistrado` é único por `(magistradoId, recorteHash, versao)` e servido
a todos os tenants. `PerfilAcesso` registra o acesso por tenant e se houve consumo de
crédito. **Perfil em cache não consome crédito.**

O `recorte` **não é texto livre**. É um registro de dimensões fechadas — período em
12/24/36/60 meses, assunto e classe vindos de listas CNJ controladas — canonizado em hash
determinístico.

**Por quê.** É daqui que sai a margem do negócio: o custo é por perfil, não por consulta.
Mas o cache só funciona se pedidos equivalentes colidirem. Com recorte livre,
"consumidor / 24 meses" e "Direito do Consumidor / 2 anos" viram dois artefatos, dois
débitos, e um cliente convencido de que foi cobrado duas vezes pela mesma coisa. A
canonização é o que protege simultaneamente a margem e a confiança.

**Invariantes com teste obrigatório.**
1. Segundo acesso ao mesmo perfil não gera lançamento de débito.
2. Recortes equivalentes escritos de formas diferentes resolvem para o mesmo hash.

**Recomputação.** Perfil tem `validoAte`. Vencido, o primeiro tenant que pedir paga a
versão nova; os demais recebem de graça. A versão anterior é preservada — um relatório
citado em petição precisa continuar recuperável.

---

## 5. Multi-tenant com RLS de verdade

**Decisão.** Row Level Security no Postgres **além** do filtro no repositório. A aplicação
conecta com role própria, distinta do owner das tabelas, e as políticas usam
`FORCE ROW LEVEL SECURITY`. O tenant corrente vai em `SET LOCAL app.tenant_id` dentro da
transação.

**Por quê.** RLS quebra silenciosamente: se a role da aplicação for dona das tabelas, ela
ignora a policy por padrão e o sistema *parece* isolado sem estar. Por isso a separação de
roles e o `FORCE` são parte da decisão, não detalhe de implementação — e por isso o
primeiro teste de integração do projeto prova que o tenant A não enxerga linha do tenant B.

`SET LOCAL` (e não `SET`) porque o escopo tem que morrer com a transação: com pool de
conexões, um `SET` vazaria o tenant para a próxima requisição que pegasse aquela conexão.

---

## 6. A Camada 2 opera sobre a ementa até haver fonte de inteiro teor

**Decisão.** O map-reduce analisa a `ementa` do TJDFT. A limitação é declarada na interface
e no rodapé metodológico do relatório.

**Por quê.** Verificado em 15/09/2026 contra a API real: o campo é `inteiroTeorHtml` e
retorna `"Inteiro Teor indisponível."` na busca, mesmo quando `possuiInteiroTeor` é `true`.
Sete rotas plausíveis de recuperação retornaram 404. A ementa do TJDFT tem cerca de 10 mil
caracteres e contém tese, fundamentação e dispositivo — é base analítica legítima, ainda
que mais fraca em requisitos probatórios e dosimetria.

**Alternativas descartadas.** Segurar a Fase 1 até achar a fonte (prazo indefinido, fora do
nosso controle); contratar agregador pago na v1 (viola "nunca pague por dado que você ainda
não vendeu" e cria custo variável por decisão antes do primeiro cliente).

**Consequência favorável.** O mesmo levantamento achou o campo `decisao`, com o dispositivo
em vocabulário controlado (`"CONHECIDO. PARCIALMENTE PROVIDO. UNÂNIME."`). A Camada 1 de
2º grau sai dele — sem IA, sem inteiro teor e sem depender da Tabela Processual Unificada
do CNJ, que fica como reforço e como caminho para 1º grau via DataJud.

**Gatilho de revisão.** Achar endpoint oficial de inteiro teor, ou primeiro cliente pagando
por tribunal que exija agregador.

---

## 7. Busca no Postgres, sem Elasticsearch

**Decisão.** `tsvector` com dicionário `portuguese` + `pg_trgm` para busca textual;
`pgvector` para semântica.

**Por quê.** No volume do ano 1 — um tribunal, centenas de milhares de acórdãos — o
Postgres resolve. Elasticsearch adicionaria um cluster para operar, sincronizar e pagar,
além de um segundo lugar onde a verdade pode divergir.

**Gatilho de revisão.** Latência de busca medida acima do aceitável com índices já
ajustados, ou necessidade de agregações que o Postgres não sustente.

---

## 8. PDF por `openhtmltopdf` + Thymeleaf

**Decisão.** Renderização puramente JVM, a partir de template Thymeleaf.

**Por quê.** Sem binário externo e sem Chromium de ~400 MB no container. Saída
determinística, fontes embutidas, viável para PDF/A. O `wkhtmltopdf` está sem manutenção
desde 2023 e não entra em projeto novo.

**Custo aceito.** CSS 2.1 apenas — sem flexbox nem grid. Na prática isso empurra para o
desenho certo: o PDF é documento de impressão com template próprio, não um print da tela do
relatório. Playwright fica como reserva se o white-label exigir layout moderno.

---

## 9. Modelos de IA e registro de custo

**Decisão.** `claude-haiku-4-5` via Batch API no *map*; `claude-sonnet-5` no *reduce*.
Prompt versionado com `id`, `version` e hash gravados em cada análise, junto do modelo e do
custo em tokens.

**Por quê.** O map é 80% do custo e tolera latência — o Batch dá 50% de desconto.
Preços verificados em 15/09/2026: Haiku 4.5 a US$1/US$5 por MTok (US$0,50/US$2,50 em
batch), Sonnet 5 a US$2/US$10. A conta de ~US$1,55 por perfil de 200 decisões se sustenta.

**Exceção deliberada.** O primeiro perfil de cada tenant roda síncrono. A janela do Batch
vai até 24h, e fazer o cliente esperar um dia logo após pagar é o pior momento possível
para uma espera. O custo extra é de centavos por tenant.

**Restrições técnicas registradas.**
- Haiku 4.5 tem contexto de 200K tokens, não 1M. Ementas longas exigem política de
  truncamento explícita e registrada na análise.
- *Structured outputs* (`output_config.format`) é incompatível com a Citations API. Logo, a
  validação de trecho literal é feita **no nosso código**, por casamento contra o texto
  persistido — que é exatamente o que a regra inviolável 1 exige.
