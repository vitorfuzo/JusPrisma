# JusPrisma — Engenharia reversa do Jusfy + planejamento do SaaS

> **Documento de estratégia e produto.** O prompt para o Claude Code está no arquivo
> `JusPrisma-02-PROMPT-CLAUDE-CODE.md`. A constituição do repositório está em
> `JusPrisma-03-CLAUDE.md`.
>
> Pesquisa feita em 15–16/09/2026. Preços e endpoints são os publicados nessa data —
> valide antes de usar em produção. Conversões a USD/BRL 5,40.

---

## 0. A tese em um parágrafo

O Jusfy vende **conveniência**: 20+ ferramentas medianas por uma assinatura só, para o
advogado solo não precisar assinar seis coisas. É um bom negócio e um fosso raso.
O que ele **não tem** é a única coisa que um advogado litigante pagaria caro para saber:
*como esse juiz específico decide*. Quem faz isso hoje (Turivius, Data Lawyer) vende
enterprise, por demonstração, sem preço público — invisível para o advogado de R$117/mês.

**JusPrisma = jurimetria de magistrado com preço e usabilidade de super-app.**
Paridade de features com o Jusfy para não perder venda por checklist, e um módulo-estrela
que ele não consegue copiar rápido porque exige pipeline de dados, não mais uma tela.

---

## 1. Engenharia reversa do Jusfy

### 1.1 Posicionamento

- Slogan: *"De advogados para advogados"* / *"o jeito mais fácil de advogar"*.
- Público: advogado autônomo e escritório pequeno (1–5 pessoas).
- Promessa: 20+ ferramentas integradas numa assinatura única.
- Aquisição: Google Ads PMax em campanha *non-brand* (o link que você mandou é
  `google_nonbrand_vendas_pmax` com `utm_content=Calculadora-RMC`) + convênios com
  seccionais da OAB/CAA + conteúdo/SEO + WhatsApp como canal de atendimento e de produto.

**Leitura do funil deles:** anúncio em cima de uma *calculadora específica* (RMC/RCC) →
landing da calculadora → usa 1x → paywall → trial de R$9,90. O produto de entrada não é a
plataforma, é **uma calculadora que resolve um problema pontual hoje**. Copie isso.

### 1.2 Inventário completo de módulos

**IA e conteúdo**

| Módulo | O que é |
|---|---|
| JusGPT | Assistente jurídico em chat: petições, contratos, cláusulas, análise e revisão de documentos, upload de arquivos, comparação de teses. Roda na web **e no WhatsApp, com comando de voz**. Transcrição de áudio com IA. |
| JusFile | Biblioteca de modelos de petição e documentos (ilimitado em todos os planos) |
| Jurisprudências | Base de 50M+ decisões, busca ilimitada em todos os planos |

**Calculadoras (JusCalc) — 14 calculadoras, consomem *créditos de cálculo***

| Calculadora | Créditos |
|---|---|
| Revisional (financiamento abusivo) | 5 |
| Atualização de valores | 5 |
| Correção de FGTS | 5 |
| Pensão alimentícia | 5 |
| Aluguel (débitos locatícios) | 5 |
| Superendividamento | 5 |
| PASEP | 3 |
| Dosimetria da pena | 3 |
| Progressão de regime | 3 |
| RMC/RCC | 1 |
| Trabalhista | 1 |
| INSS | 1 |
| Divórcio | 1 |
| Previdenciária acima do teto | 1 |

**Consultas / due diligence (JusFinder) — 18 consultas, consomem *créditos de consulta***

| Consulta | Créditos |
|---|---|
| Localização (nome, telefone, e-mail, endereço por CPF) | 1 |
| Situação cadastral de CPF (inclui verificação de óbito) | 1 |
| Dados profissionais (histórico de vínculos via Receita/RAIS) | 1 |
| Sociedades (participação societária) | 1 |
| Grupo econômico | 1 |
| Marcas e patentes | 1 |
| Buscador processual | 1 |
| Empresa completo (quadro societário) | 3 |
| Restrição de crédito | 3 |
| Relacionamentos | 3 |
| Dados de veículos (por placa) | 3 |
| Imóveis rurais | 2 |
| Dados por telefone | 2 |
| Débitos veiculares | 2–3 |
| Dados da CNH | 2–3 |
| Veículos (por CPF/CNPJ) | 5 |
| Imóveis financiados | 5 |
| Rastreamento de veículo | 5 |

Consultas avulsas: **R$12,99** (veículos) / **R$2,99** (demais).

**Processos**

| Módulo | O que é |
|---|---|
| JusProcessos | Monitoramento **ilimitado** em todos os planos, todos os tribunais, alertas por e-mail e push, gestão de prazos, integração com Google Agenda, múltiplas OABs numa conta. Vendido também standalone: Starter 50 / Master 200 / Ultimate 500 / Enterprise 500+ processos, promo R$9,90 no 1º mês. |

**Operação do escritório**

| Módulo | O que é |
|---|---|
| JusSign | Assinatura eletrônica com validade jurídica (por créditos) |
| JusPay | Gestão financeira e recebimento de honorários |
| Drive do Advogado | Armazenamento de 1 a 100 GB |
| Subusuários | 0 a 5 conforme plano |
| E-mail personalizado | Exclusivo do plano Black |

**Aquisição de clientes**

| Módulo | O que é |
|---|---|
| JusMatch | Duas coisas num módulo: (a) **oportunidades de clientes** — leads gerados pelas simulações e conteúdos da própria Jusfy, com contato parcialmente oculto até o "desbloquear" por crédito; (b) **diligências** — advogado contratando advogado para audiência/protocolo local, com desbloqueio ilimitado |
| JusPage | Criação de site para advogado com IA (incluso em todos os planos) |
| Marketing jurídico | Conteúdo e material de apoio |

### 1.3 A tabela de planos (setembro/2026)

| | Experimente | One | Ultimate *(mais popular)* | Black |
|---|---|---|---|---|
| **Preço** | **R$9,90 / 30 dias** | **R$47/mês** | **R$117/mês** | **R$227/mês** |
| IA jurídica (msg/mês) | 30 | 30 | 150 | ilimitado |
| Cálculos (créditos) | 2 | 5 | 20 | ilimitado |
| Consultas legais (créditos) | 2 | 5 | 20 | 60 |
| Assinaturas digitais | 2 | 5 | 20 | 60 |
| Prospecção de clientes | 2 | 5 | 20 | 40 |
| Monitoramento de processos | ilimitado | ilimitado | ilimitado | ilimitado |
| Jurisprudências | ilimitado | ilimitado | ilimitado | ilimitado |
| Modelos de petição | ilimitado | ilimitado | ilimitado | ilimitado |
| Site com IA | ✓ | ✓ | ✓ | ✓ |
| Drive | 1 GB | 5 GB | 15 GB | 100 GB |
| Subusuários | 0 | 0 | 3 | 5 |
| E-mail personalizado | — | — | — | ✓ |

**Convênios OAB/CAA (RJ, MG, PI, MS, AM, PR, SP, RS, DF, PA, AC, PE, RR): −30%**
→ One R$32,90 · Ultimate R$81,90 · Black R$158,90. Em MG, trial estendido de 90 dias (3× R$9,90).

Regras de assinatura: trial **converte automaticamente** em plano pago se não houver
cancelamento em 30 dias; **créditos não acumulam** para o mês seguinte; créditos extras
podem ser comprados avulsos; cancelamento a qualquer tempo, com cláusula de fidelidade
e reembolso proporcional nos planos anuais.

---

## 2. Por que eles cobram exatamente esses valores

Essa é a parte que importa. Nada aqui é arbitrário — cada número resolve um problema
de negócio específico.

### 2.1 R$9,90 não é preço, é captura de cartão

O trial pago faz quatro coisas de uma vez: pega o **cartão** (o maior atrito do funil),
filtra curioso e estudante, amortiza parte do CAC de mídia paga, e — o ponto central —
**converte por omissão**: se você não cancelar em 30 dias, vira plano pago. É opt-out,
não opt-in.

E os créditos do trial (2 de tudo) estão calibrados para *provar valor uma vez e travar
na segunda*. Você faz o cálculo do cliente que está na sua mesa hoje, vê funcionar, e
quando vai fazer o terceiro já está com cartão cadastrado.

### 2.2 Três degraus, com o do meio sendo o produto real

R$47 → R$117 → R$227 (razões de 2,49× e 1,94×). O **Ultimate é o produto**; os outros
dois existem para sustentá-lo:

- **O One é um chamariz, não um plano.** 30 mensagens de IA — *o mesmo do trial* — e
  zero subusuários. É deliberadamente insustentável para quem trabalha de verdade.
  Sua função é fazer o Ultimate parecer barato por unidade: One = R$9,40 por cálculo;
  Ultimate = R$5,85 por cálculo. **38% mais "barato" pagando 2,5× mais.**
- **O Black é âncora.** Faz R$117 parecer moderado e captura o escritório que precisa de
  subusuário e e-mail próprio.

### 2.3 A regra de ouro: ilimite o custo fixo, taxe o custo variável

Olhe o que é ilimitado **em todos os planos, inclusive no trial de R$9,90**:
monitoramento de processos, jurisprudência, modelos de petição, site com IA.
Todos têm custo marginal ≈ zero — o dado já foi ingerido e indexado, o modelo é estático,
o site é gerado uma vez. *"50 milhões de jurisprudências, ilimitado"* é a manchete mais
barata que existe.

E o que é medido por crédito é exatamente onde existe custo por unidade: IA (tokens),
cálculo (compute + valor percebido), consulta (dado comprado de bureau), assinatura
(custo por envelope), prospecção (lead tem custo de mídia).

### 2.4 Os pesos dos créditos são o custo deles, exposto

O detalhe mais revelador da plataforma inteira: **no plano Black, cálculos são ilimitados,
mas consultas param em 60.** Por quê? Cálculo é software — custo marginal zero, dá para
liberar. Consulta é dado **comprado** de terceiro — tem fatura. Um único campo da tabela
de planos entrega a estrutura de custo toda.

O mesmo vale dentro das consultas: veículos custa 5 créditos e R$12,99 avulso, consulta
simples custa 1 crédito e R$2,99 — isso espelha o preço de aquisição junto ao fornecedor
(Detran e bureaus são caros). E nos cálculos o peso segue o **ticket do advogado**, não a
complexidade: Revisional, FGTS e Superendividamento valem 5 créditos porque são as teses
de maior honorário; RMC, Trabalhista, INSS e Divórcio valem 1 porque são alto volume e
servem de isca e de hábito (e RMC é justamente o que eles anunciam no Google).

### 2.5 O breakage sustenta o preço

"Créditos não acumulam" é escolha de precificação, não detalhe de termos de uso. Em SaaS
medido, o consumo real fica tipicamente em 30–50% da cota. **Crédito não usado é margem.**
O R$117 só fecha a conta porque a maioria dos assinantes nunca usa os 20 cálculos, as 20
consultas e as 150 mensagens no mesmo mês.

### 2.6 Estimativa de unit economics do Ultimate (R$117)

> Estimativa minha, a partir de preços públicos de setembro/2026. Serve para dimensionar,
> não para auditar.

| Item | Custo estimado/mês |
|---|---|
| Gateway (cartão ~3% + R$0,39) | R$3,90 |
| IA — 150 msgs × ~3k in / 1k out, Claude Sonnet 5 a US$2/US$10 por MTok | R$13 *(cai para R$4–6 com Haiku nas fáceis + cache de prompt)* |
| Consultas — 20 créditos a R$0,60–1,50 de custo, com uso real de ~40% | R$6–12 |
| Assinaturas — 20 envelopes a R$0,30–1,00, uso parcial | R$3–8 |
| Infra + 15 GB + suporte | R$8–15 |
| **COGS total** | **R$35–55** |
| **Margem bruta** | **~55–70%** |

Isso é **baixo** para SaaS (o normal é 80–90%) e explica tudo: o sistema de créditos não
é monetização, é **defesa de margem**. Sem ele, um único usuário pesado zera o lucro de dez.

### 2.7 Por que R$47 / R$117 / R$227 e não 49 / 119 / 229

Preço terminado em 7 é padrão de marketing direto no Brasil e testa melhor que o final 9
nesse público. R$117 fica logo abaixo da barreira dos R$120; R$227 abaixo de R$230.
E o desconto de 30% do convênio cai em R$32,90 / R$81,90 / R$158,90 — abaixo das barreiras
de R$35, R$85 e R$160. O número final de cada cenário foi escolhido, não calculado.

### 2.8 O movimento mais inteligente deles

**13 convênios com seccionais da OAB/CAA a −30%.** A seccional divulga para a base inteira
de advogados do estado; a Jusfy troca 30% de margem por distribuição em massa e
legitimidade institucional. CAC próximo de zero e um selo que nenhum anúncio compra.

Você está em Brasília e **OAB-DF/CAA-DF já estão na lista deles** — o caminho está
pavimentado e é replicável. É o primeiro canal que eu buscaria, não o último.

---

## 3. A brecha: o que o Jusfy não faz

1. **Nenhuma análise de comportamento de magistrado.** Eles têm 50M de decisões e
   entregam *busca*. Ninguém transforma isso em **perfil decisório**.
2. **Quem faz jurimetria não atende esse público.** Turivius (130M+ decisões, taxas de
   procedência por tribunal, mapeamento de relator, divergência entre câmaras, tendência
   temporal) e Data Lawyer (taxa de provimento, concessão de tutela de urgência,
   homologação de acordo, valor médio de condenação, tempo médio) são plataformas de
   demonstração e proposta comercial — **nenhuma publica preço**. Mercado sem âncora de
   preço é mercado onde quem ancora primeiro define a régua.
3. **O ativo deles não se valoriza com o uso.** Cada resposta do JusGPT é descartável.
   Um perfil de magistrado, não: é gerado uma vez e servido para todos os assinantes —
   e o acervo só cresce. É a diferença entre custo recorrente e ativo acumulado
   (detalhe em 5.4).

---

## 4. Fontes de dados: o que dá para fazer de verdade, hoje, de graça

Aqui é onde a ideia vive ou morre. A boa notícia: **dá para construir o núcleo inteiro
sem pagar por dado**, começando pelo tribunal do seu próprio estado.

### 4.1 TJDFT — API pública de jurisprudência ← **comece aqui**

```
POST https://jurisdf.tjdft.jus.br/api/v1/pesquisa
Sem autenticação.
Body: { "query": "...", "pagina": 0, "tamanho": 20,
        "termosAcessorios": [ { "campo": "nomeRelator", "valor": "..." } ] }
```

Campos filtráveis: `base`, `subbase`, `origem`, `uuid`, `identificador`,
`identificadorOrdenacao`, `processo`, **`nomeRelator`**, `nomeRevisor`,
`nomeRelatorDesignado`, **`descricaoOrgaoJulgador`**, **`dataJulgamento`**,
`dataPublicacao`, `descricaoClasseCnj`.

Resposta: `hits` (total) + `registros[]`, cada um com `uuid`, `identificador`, `processo`,
`nomeRelator`, `descricaoOrgaoJulgador`, **`ementa`**, **`inteiroTeor`**, `dataJulgamento`,
`dataPublicacao`, `codigoClasseCnj`, `descricaoClasseCnj`, `marcadores`.

**Isso é literalmente o produto.** Filtra por relator → baixa N acórdãos com inteiro teor
→ analisa → gera o perfil. Sem contrato, sem custo, sem scraping. E o mercado que essa API
cobre é o mesmo mercado que você consegue visitar pessoalmente.

### 4.2 DataJud (CNJ) — a espinha dorsal quantitativa, grátis

```
POST https://api-publica.datajud.cnj.jus.br/api_publica_{alias}/_search
Requer chave (cadastro no CNJ). Corpo em Elasticsearch DSL.
Paginação com search_after ordenando por @timestamp.
```

Cobre os 182 tribunais. Campos: `numeroProcesso`, `id`, `tribunal`, `classe` (código/nome),
`assuntos[]`, `orgaoJulgador` (código, nome, `codigoMunicipioIBGE`), `dataAjuizamento`,
`grau`, `nivelSigilo`, `formato`, `sistema`, e **`movimentos[]`** com código da Tabela
Processual Unificada do CNJ, nome, data/hora e complementos.

**Não tem nome de juiz nem texto de decisão.** Mas tem algo subestimado: os **códigos de
movimento** incluem o resultado do julgamento (procedente, improcedente, procedência em
parte, extinção sem resolução de mérito…). Dá para calcular **taxa de procedência e tempo
médio de tramitação por órgão julgador sem ler uma única decisão** — de graça, com base
estatística de verdade. É o alicerce quantitativo do relatório.

### 4.3 DJEN / API Comunica (CNJ) — publicações, grátis e sem autenticação

```
GET https://comunicaapi.pje.jus.br/api/v1/comunicacao
Params: numeroOab, ufOab, numeroProcesso, siglaTribunal,
        dataDisponibilizacaoInicio, dataDisponibilizacaoFim, pagina, itensPorPagina (máx 50)
GET https://comunicaapi.pje.jus.br/api/v1/comunicacao/{hash}/certidao
```

Dois usos: **(a)** motor de monitoramento de processos e prazos — paridade com o
JusProcessos, custo zero; **(b)** fonte de decisões de **1º grau**, que a base de
jurisprudência dos tribunais quase não cobre.

⚠️ **Dois avisos que mudam arquitetura:** a API **geobloqueia** (403 fora do Brasil) e não
tem SLA publicado (500 sob pico). Espaçar ~500ms entre chamadas, backoff exponencial, e
**hospedar no Brasil**. Isso não é detalhe de deploy, é requisito.

### 4.4 Distinção de produto que você precisa entender agora

- **2º grau (relator/desembargador):** fácil. As bases de jurisprudência dos tribunais
  são feitas de acórdãos, com nome de relator estruturado.
- **1º grau (juiz de vara):** difícil. Sentença raramente entra na base de jurisprudência.
  Vem do DJEN, de consulta processual ou de agregador pago — cobertura irregular.

Portanto: **venda "perfil de relator e de órgão julgador" na v1**, com 1º grau entrando
por tribunal conforme a cobertura permitir. Prometer perfil de qualquer juiz do país no
lançamento é a forma mais rápida de queimar a marca.

### 4.5 Agregadores pagos — só quando um cliente pagar por eles

| Fornecedor | O que entrega | Preço |
|---|---|---|
| jurisprudencias.ai | Inteiro teor, 50+ tribunais (STF, STJ, TST, TRTs, TRFs, TJs, CARF) | Free tier: 5 buscas + 10 consultas/dia. Pago: a partir de 500 buscas / 10k consultas por dia |
| Escavador | Plataforma R$9,90 / R$29,90 / R$49,90/mês; API por crédito e por serviço | Preço da API no painel; pacotes por volume |
| Judit | 90+ tribunais, movimentações, sentenças, decisões, dados de partes | Planos mensais sob consulta |
| Jusbrasil API | 96 tribunais | Enterprise, sem preço público |

**Regra:** nunca pague por dado que você ainda não vendeu. Comece com TJDFT + DataJud +
DJEN (custo zero), e plugue pago só quando um cliente pedir um tribunal que você não cobre —
com o custo repassado em crédito.

**Não** faça scraping de portal como estratégia primária: HTML muda, CAPTCHA, bloqueio de
IP e termos de uso. Último recurso, com robots.txt respeitado.

---

## 5. O núcleo do JusPrisma: como o perfil é construído

### 5.1 Pipeline

```
INGESTÃO
  TJDFT API      → acórdãos com ementa + inteiroTeor + nomeRelator
  DataJud        → metadados + movimentos codificados (CNJ)
  DJEN           → publicações e decisões de 1º grau
        ↓ fila (RabbitMQ) → normalizador → Postgres + pgvector
        ↓
CAMADA 1 — DETERMINÍSTICA (SQL puro, zero IA, zero alucinação)
  · taxa de procedência / improcedência / parcial, por magistrado e por assunto
  · tempo médio e mediana de ajuizamento → julgamento, p90
  · distribuição por classe e assunto CNJ
  · série temporal: o entendimento está se movendo?
  · volume e amostra (n) de cada número — sempre visível
        ↓
CAMADA 2 — IA COM CITAÇÃO OBRIGATÓRIA (map-reduce)
  map    · cada acórdão → JSON estruturado: tese, resultado, fundamentos,
           precedentes invocados, requisitos que o julgador exige, dosimetria, tom
         · Claude Haiku 4.5 via Batch API (50% de desconto) ← é aqui que mora o custo
  reduce · N JSONs → relatório de perfil legível
         · Claude Sonnet 5, cada afirmação com trecho + link da fonte
        ↓
CACHE — o perfil é um ARTEFATO, não uma consulta
  gerado uma vez, versionado, servido a todos os assinantes, recomputado incrementalmente
```

### 5.2 O que o relatório entrega

1. **Ficha do magistrado** — órgão julgador, período coberto, quantidade de decisões analisadas
2. **Números duros** (Camada 1), cada um com `n` e intervalo de confiança quando aplicável
3. **Padrões de fundamentação** — os argumentos que aparecem nas decisões favoráveis e nas contrárias
4. **Requisitos práticos** — o que esse julgador costuma exigir na prova (laudo, perícia, prova documental específica)
5. **Precedentes mais invocados** — súmulas, temas repetitivos, acórdãos citados
6. **Tendência temporal** — o entendimento mudou nos últimos 24 meses?
7. **Comparação com o órgão** — esse relator está alinhado ou divergente da câmara/turma?
8. **Decisões-chave**, linkadas, com o trecho que sustenta cada afirmação acima
9. **Rodapé metodológico** — fonte, recorte, data de geração, limitações, versão do prompt

### 5.3 Custo real de um perfil

| Etapa | Cálculo | Custo |
|---|---|---|
| Map | 200 acórdãos × ~8k tokens = 1,6M in; ~160k out. Haiku 4.5 em Batch (US$0,50 / US$2,50 por MTok) | ~US$1,20 |
| Reduce | 200 JSONs ≈ 150k in + 4k out. Sonnet 5 (US$2 / US$10) | ~US$0,34 |
| **Total** | | **≈ US$1,55 ≈ R$8,40, uma única vez** |

> Conversões deste documento a USD/BRL 5,40 (cotação de 14/09/2026).

### 5.4 Por que isso é uma vantagem estrutural, não só um feature

O custo acima é **por perfil, não por consulta**. Se 50 advogados consultarem o mesmo
desembargador do TJDFT, o custo por consulta cai para R$0,17. Aos 500, vira ruído contábil.

Compare com o Jusfy: cada mensagem do JusGPT custa tokens novos e o resultado é
descartável. **O módulo mais caro deles é o mais barato seu**, porque o seu output é
reutilizável. Quanto mais o produto roda, melhor a margem e maior o acervo — e "consulte
qualquer perfil já processado, ilimitado" passa a ser uma promessa que você pode fazer
de verdade, e eles não.

---

## 6. Guardrails jurídicos — leia antes de escrever código

### 6.1 Resolução CNJ 615/2025

Regula IA **no Judiciário** e nos fornecedores contratados por tribunais. **Não vincula
lawtech privada diretamente**, mas define o que é aceitável e será a régua de qualquer
discussão futura:

- O anexo trata **detecção de padrões decisórios e relatórios jurimétricos de apoio** como
  **baixo risco**, desde que *não substituam a avaliação humana*.
- O art. 10 **veda** sistemas que classifiquem ou pontuem pessoas com base em comportamento
  para avaliar mérito ou credibilidade.
- O art. 30 exige anonimização/pseudonimização na origem para compartilhamento de dados do Judiciário.

### 6.2 A linha que separa produto defensável de processo

| ✅ Pode | ❌ Não pode |
|---|---|
| "Em 147 acórdãos analisados, o laudo pericial foi citado como fundamento em 82% das concessões — [ver as 147 decisões]" | "Esse juiz é 82% favorável ao autor" |
| Estatística descritiva de decisões públicas, com fonte e trecho verificáveis | Score, nota ou ranking de magistrado |
| "Padrão observado no recorte X, no período Y" | Probabilidade de ganho da sua causa |
| Tendência de entendimento do órgão | Previsão de resultado |

**Regras que entram no código, não só nos termos de uso:**

- Toda afirmação da IA carrega `fonte_id` + trecho. **Sem fonte, não sai no relatório** —
  validado programaticamente, não confiado ao modelo.
- Todo número mostra o `n`. Abaixo de um mínimo (sugestão: 20 decisões), o relatório sai
  marcado como amostra insuficiente e **não gera percentual**.
- Prompts versionados, com hash gravado em cada análise. Se alguém questionar um relatório
  daqui a um ano, você precisa saber qual prompt e quais fontes o geraram. Isso é requisito
  de auditoria.
- Nome de **parte** é pseudonimizado na base analítica. Nome de **magistrado** é dado de
  agente público no exercício da função — guarde só o que é função (nome, órgão, decisões),
  nunca inferência pessoal.
- Aviso permanente na interface e no PDF: ferramenta de apoio, não substitui juízo
  profissional, não garante resultado.

### 6.3 Provimento 205/2021 CFOAB — cuidado com o clone do JusMatch

O Provimento veda mercantilização e captação de clientela, referência a valores de
honorários, promessa de resultado, e **pagamento para figurar em publicação de
comparação ou ranking de advogados**. Publicidade tem de ter caráter meramente informativo.

O Jusfy vende desbloqueio de lead de cliente final por crédito. Ele sobrevive, mas é risco
regulatório que você **não precisa assumir no ano 1**.

**Substitua por "JusPrisma Diligências"** — advogado contratando advogado para audiência,
protocolo e acompanhamento local. É a metade defensável do JusMatch (o próprio Jusfy libera
diligências com crédito ilimitado, o que sugere que é a parte que gera menos atrito), e o
crescimento vem de conteúdo e SEO em cima dos perfis, não de venda de lead.

### 6.4 JusFinder é o módulo mais perigoso

Revenda de CPF, localização, veículos, CNH e restrição de crédito exige base legal sob a
LGPD, contrato com bureau idôneo, finalidade declarada e trilha de auditoria por consulta.
**Fase 4, e só com fornecedor formal e contrato assinado.** Não improvise com "achei uma API".

---

## 7. Planos e preços do JusPrisma

Aplicando a regra de ouro do Jusfy, agora com um ativo que a favorece:

| | Degustação | **Solo** | **Pro** ★ | **Escritório** |
|---|---|---|---|---|
| **Preço** | **R$9,90 / 14 dias** | **R$57/mês** | **R$137/mês** | **R$297/mês** |
| **Perfis novos de magistrado** | 2 | 10/mês | 50/mês | 200/mês |
| **Perfis já em cache** | ilimitado | ilimitado | ilimitado | ilimitado |
| Comparar magistrados | — | 2 por vez | 5 por vez | ilimitado |
| Alerta de virada de entendimento | — | — | ✓ | ✓ |
| Relatório white-label (PDF com a marca do escritório) | — | — | ✓ | ✓ |
| IA jurídica (msg/mês) | 30 | 150 | 600 | ilimitado (fair use) |
| Busca de jurisprudência | ilimitado | ilimitado | ilimitado | ilimitado |
| Monitoramento de processos | 10 | ilimitado | ilimitado | ilimitado |
| Modelos e peças | ilimitado | ilimitado | ilimitado | ilimitado |
| Cálculos | 2 | 10 | 40 | ilimitado |
| Assinatura eletrônica | 2 | 10 | 40 | 150 |
| Consultas de dados | 0 | 5 | 20 | 60 |
| Drive | 1 GB | 10 GB | 50 GB | 200 GB |
| Subusuários | 0 | 0 | 3 | 10 |
| API | — | — | — | ✓ |
| Rollover de créditos | — | — | 1 mês | 1 mês |

### Racional de cada decisão

**R$57 e não R$37.** Não entre por preço. Entrar abaixo do líder num mercado onde ele tem
convênio com 13 seccionais é convidá-lo a te matar com desconto — ele tem margem para
isso e você não. Entre **20% acima** com um módulo que ele não tem. Quem quer saber como o
desembargador da 3ª Turma decide não está comparando com calculadora de FGTS.

**R$137 é o produto real**, com a mesma razão de 2,4× que funciona no Jusfy. O que faz o
advogado pagar é o **white-label**: ele entrega o relatório ao *cliente dele* com a marca do
escritório, cobrando por isso. Você deixa de ser custo e passa a ser insumo de faturamento —
o melhor lugar onde um SaaS pode estar, e o que derruba churn de verdade.

**"Perfis em cache ilimitados" é a sua melhor jogada de marketing e é honesta.** Perfil já
processado tem custo marginal ≈ zero, então você pode literalmente dar o acervo. E o acervo
cresce sozinho conforme os clientes pedem perfis novos: cada cliente novo financia o
estoque do próximo. Flywheel que o Jusfy não tem.

**Trial de 14 dias, não 30.** Trinta dias só dá tempo de esquecer. Mantenha a conversão
automática, mas **avise por e-mail no dia 11** — é honesto, reduz chargeback e reclamação
no Reclame Aqui, e custa nada.

**Créditos não acumulam no Solo** (o breakage sustenta a margem), mas **rollover de 1 mês
no Pro e Escritório** — custa pouco e mata a objeção "paguei e não usei esse mês".

### Números de referência

COGS estimado no Pro: R$18–30 → margem bruta ~80% (bem acima dos ~60% do Jusfy, pelo
efeito de cache). 100 clientes Pro = **R$13.700 de MRR** com ~R$2.500 de COGS.
Meta realista tocando sozinho: **30 clientes pagantes em 6 meses**, via OAB-DF/CAA-DF,
conteúdo sobre magistrados do TJDFT e indicação boca a boca em fórum.

### Cobrança

Comece com **Asaas** (2,99% cartão, R$0,49 Pix, R$1,99 boleto, D+2, recorrência boa e API
decente) ou **Pagar.me** (3,19%). Stripe cobra 3,99% + R$0,39 no Brasil e não vale para
esse ticket. E planeje **Pix Automático** (Resolução BCB 422/2025, disponível desde
jan/2026, ~85% dos bancos em abr/2026, taxa de **0,22–0,35% sem tarifa fixa**) como método
principal a partir do momento em que houver volume: num ticket de R$137, a diferença entre
0,3% e 3,2% é a sua margem de infra inteira. Asaas, Efí e OpenPix já oferecem.

---

## 8. Roadmap

| Fase | Semanas | Entrega |
|---|---|---|
| **0 — Fundação** | 1–2 | Monorepo, auth, multi-tenant, planos, ledger de créditos, billing + webhooks, painel, observabilidade, CI |
| **1 — Núcleo JusPrisma** | 3–8 | Ingestão TJDFT + DataJud, jurimetria determinística, pipeline map-reduce de IA, relatório de perfil, comparador, PDF white-label |
| **2 — Paridade essencial** | 9–14 | Busca de jurisprudência (texto + semântica), monitoramento via DJEN, prazos e agenda, IA jurídica (chat + documentos), modelos de peças, drive |
| **3 — Operação** | 15–20 | Assinatura eletrônica, financeiro/cobrança de honorários, site com IA, subusuários e permissões |
| **4 — Expansão** | 21+ | 14 calculadoras, consultas de dados (com fornecedor formal), diligências, API pública, novos tribunais, app/WhatsApp |

**Primeiro cliente pagante deve acontecer no fim da Fase 1**, não no fim da Fase 4.
O perfil de magistrado sozinho já é vendável — as fases 2 a 4 existem para reter e para
não perder venda por comparação de checklist.

---

## 9. Riscos e como cada um morre

| Risco | Mitigação |
|---|---|
| Tribunal derruba ou fecha a API | Fonte é adapter atrás de porta no domínio. Trocar fonte não toca regra de negócio. Tenha sempre 2 fontes por tribunal. |
| Cobertura de 1º grau irregular | Venda "relator e órgão julgador" na v1. Mostre a cobertura real por tribunal na própria interface, antes de o cliente pagar. |
| IA alucinar um padrão | Camada 1 é SQL puro. Camada 2 só publica com fonte e trecho, validados em código. `n` mínimo para gerar percentual. |
| Magistrado se incomodar | Só estatística descritiva de decisão pública, com fonte. Nunca score, ranking ou previsão. Canal de contestação com SLA e direito de resposta publicado. |
| Jusfy copiar o módulo | Eles precisam de pipeline de dados e acervo, não de mais uma tela. Sua vantagem é o acervo acumulado e o tempo — use-o para cobrir tribunais e virar referência de conteúdo antes. |
| Você sozinho não dar conta de 5 fases | Fase 1 é vendável isolada. Se travar, você tem produto. Cada fase seguinte é opcional e financiada por receita. |
| LGPD / dado pessoal de parte | Pseudonimizar parte na base analítica desde a primeira migration, não depois. |

---

## 10. Fontes

- [Jusfy — site](https://jusfy.com.br/) · [Planos](https://jusfy.com.br/planos/) · [JusGPT](https://jusfy.com.br/jusgpt/) · [JusFinder](https://jusfy.com.br/jusfinder/) · [JusProcessos](https://page.jusfy.com.br/jusprocessos/) · [Manual JusFinder](https://help.jusfy.com.br/manual-de-uso-jusfinder-da-jusfy) · [Manual JusMatch](https://help.jusfy.com.br/manual-de-uso-do-jusmatch)
- [TJDFT — Webservices e APIs](https://www.tjdft.jus.br/transparencia/tecnologia-da-informacao-e-comunicacao/dados-abertos/webservice-ou-api) · [Documentação da API](https://www.tjdft.jus.br/transparencia/tecnologia-da-informacao-e-comunicacao/dados-abertos/documentos/documentacao_api_seti_transparencia.pdf)
- [CNJ — API Pública DataJud](https://www.cnj.jus.br/sistemas/datajud/api-publica/) · [Tutorial DataJud](https://www.cnj.jus.br/wp-content/uploads/2023/05/tutorial-api-publica-datajud-beta.pdf) · [Datajud Wiki](https://datajud-wiki.cnj.jus.br/api-publica/)
- [API do DJEN — documentação prática](https://chatjuridico.com.br/api-do-djen-consulta-oficial-cnj/)
- [Resolução CNJ 615/2025](https://atos.cnj.jus.br/atos/detalhar/6001) · [Provimento 205/2021 CFOAB — análise](https://www.projuris.com.br/blog/provimento-205-2021/)
- [Turivius — Jurimetria](https://turivius.com/jurimetria/) · [Data Lawyer — perfil de magistrados](https://www.datalawyer.com.br/jurimetria-e-o-perfil-de-magistrados/)
- [Comparativo de APIs de jurisprudência 2026](https://jurisprudencias.ai/blog/melhor-api-jurisprudencias-brasil-2026) · [Escavador — preços](https://www.escavador.com/precos) · [Judit](https://judit.io/)
- [Claude — preços de API](https://platform.claude.com/docs/en/about-claude/pricing)
- [Gateways de pagamento no Brasil 2026](https://mindconsulting.com.br/2026/07/gateways-pagamento-online-brasil-comparativo-2026/) · [Pix Automático para SaaS](https://forjadesistemas.com.br/blog/pix-automatico-recorrencia-saas-proprio-2026/)
