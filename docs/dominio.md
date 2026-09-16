# Domínio — glossário jurídico e modelo

Referência de vocabulário e de modelagem. O domínio é jurídico brasileiro e os nomes em
código são em português: traduzir `acórdão` para `ruling` só cria ambiguidade com
`decisão`, `sentença` e `despacho`, que são coisas diferentes.

---

## 1. Glossário

### Estrutura judiciária

| Termo | O que é | Por que importa aqui |
|---|---|---|
| **Grau de jurisdição** | 1º grau (juiz singular, vara) e 2º grau (colegiado, tribunal) | Mundos diferentes de cobertura de dados. Ver seção 4. |
| **Vara** | Unidade de 1º grau, com um juiz titular | Sentenças raramente entram em base de jurisprudência |
| **Câmara / Turma** | Órgão colegiado de 2º grau | É o `OrgaoJulgador` da v1 |
| **Magistrado** | Juiz, desembargador ou ministro | Agente público no exercício da função. Guardamos só o que é função. |
| **Relator** | Magistrado sorteado para conduzir e relatar o recurso | **É o eixo do produto.** É o campo que o TJDFT expõe estruturado. |
| **Revisor** | Magistrado que confere o voto do relator, em certas classes | Campo disponível, não usado na v1 |
| **Relator designado** | Assume a redação quando o voto do relator é vencido | Sinal de divergência no colegiado. Interessante para a seção "comparação com o órgão". |

### Tipos de pronunciamento

| Termo | O que é |
|---|---|
| **Sentença** | Decisão do juiz de 1º grau que encerra a fase de conhecimento |
| **Acórdão** | Decisão de órgão colegiado (2º grau). **É o que ingerimos na v1.** |
| **Decisão monocrática** | Decisão individual do relator, sem levar ao colegiado |
| **Despacho** | Ato de mero andamento, sem conteúdo decisório |
| **Ementa** | Resumo estruturado do acórdão: tese, fundamentação e dispositivo. No TJDFT tem ~10 mil caracteres — é a base analítica da Camada 2 na v1. |
| **Inteiro teor** | Texto integral do acórdão, com relatório, votos e debates |
| **Dispositivo** | A parte que decide. No TJDFT vem no campo `decisao`, em vocabulário controlado. |

### Resultado do julgamento

O vocabulário difere entre 1º e 2º grau. Confundir os dois é erro de domínio.

**1º grau — sobre o pedido:**

| Termo | Significado |
|---|---|
| **Procedente** | O pedido do autor foi acolhido |
| **Improcedente** | O pedido foi rejeitado no mérito |
| **Parcialmente procedente** | Parte do pedido acolhida |
| **Extinto sem resolução de mérito** | Encerrado sem julgar o pedido (art. 485 do CPC) |

**2º grau — sobre o recurso:**

| Termo | Significado |
|---|---|
| **Conhecido / não conhecido** | Juízo de admissibilidade: o recurso passou ou não pelos requisitos formais. **Não conhecido não diz nada sobre o mérito.** |
| **Provido** | Recurso acolhido — a decisão anterior foi reformada |
| **Improvido / não provido / desprovido** | Recurso rejeitado — a decisão anterior foi mantida |
| **Parcialmente provido** | Reforma parcial |
| **Unânime / por maioria** | Se houve divergência no colegiado |

> **Armadilha.** "Provido" é bom para quem recorreu, que pode ser autor ou réu. Um relator
> com alta taxa de provimento **não** é "favorável ao autor" — é frequentemente reformador.
> Nunca traduza provimento em favorecimento a um polo. Isso é regra inviolável 3 na prática.

### Classificação processual

| Termo | O que é |
|---|---|
| **Classe CNJ** | Tipo de processo (Apelação Cível, Agravo de Instrumento…), da Tabela Processual Unificada |
| **Assunto CNJ** | Matéria discutida, hierárquica (Direito Civil → Responsabilidade Civil → Dano Moral). **Não existe no TJDFT** — vem do DataJud. |
| **Movimento CNJ** | Evento codificado do andamento. Certos códigos carregam o resultado do julgamento. |
| **Número único (CNJ)** | `NNNNNNN-DD.AAAA.J.TR.OOOO`, formato nacional. É a chave de junção entre TJDFT e DataJud. |
| **Segredo de justiça / nível de sigilo** | Restrição de acesso. **Não entra na base analítica** — filtrado na ingestão. |

### Precedentes

| Termo | O que é |
|---|---|
| **Súmula** | Enunciado consolidando entendimento reiterado de um tribunal |
| **Tema repetitivo** | Tese fixada pelo STJ/STF em recurso repetitivo, de observância obrigatória |
| **Repercussão geral** | Filtro de admissibilidade do recurso extraordinário no STF |

### Prazos

| Termo | O que é |
|---|---|
| **Disponibilização** | Data em que o ato é publicado no Diário Eletrônico (`dataDisponibilizacao`) |
| **Publicação** | **Primeiro dia útil seguinte** à disponibilização (art. 224, §2º do CPC) |
| **Início do prazo** | Primeiro dia útil seguinte à publicação |
| **Dias úteis** | Prazos processuais em dias úteis (art. 219 do CPC), excluídos feriados forenses |

> Os três campos são distintos e guardados separadamente. Errar isso faz advogado perder
> prazo. Entra em cena na Fase 2, mas o modelo já nasce com os campos separados.

---

## 2. Modelo

Notação: `?` = opcional. Tipos indicativos, não DDL — o DDL é a migration.

### Conta e cobrança

```
Tenant              id, nome, cnpj?, status, criadoEm
Usuario             id, tenantId, email, senhaHash, papel (OWNER|MEMBRO),
                    oab?, ufOab?, emailVerificadoEm?
Plano               codigo (DEGUSTACAO|SOLO|PRO|ESCRITORIO), precoCentavos,
                    limites JSONB
Assinatura          id, tenantId, planoCodigo, status, inicioEm, proximaCobranca,
                    gatewayCustomerId, gatewaySubscriptionId
CreditoLancamento   id, tenantId, tipoCredito (PERFIL|IA|CALCULO|CONSULTA|ASSINATURA),
                    delta, motivo, referenciaId?, criadoEm
EventoGateway       id, gateway, eventIdExterno, payload JSONB, processadoEm?
```

**Invariantes**
- `CreditoLancamento` é append-only. Saldo = `SUM(delta)` por `(tenantId, tipoCredito)`.
  Estorno é lançamento novo com `delta` de sinal oposto e `referenciaId` apontando para o
  débito original. Nunca `UPDATE`, nunca `DELETE`.
- `Plano.limites` é a única fonte de cota. Nenhum `if` com nome de plano no código.
- `EventoGateway` tem UNIQUE em `(gateway, eventIdExterno)`: webhook repetido não reprocessa.

### Judiciário

```
Tribunal            sigla (PK), nome, graus[], fontesDisponiveis[], coberturaInicio
OrgaoJulgador       id, tribunalSigla, nome, codigoCnj?, codigoInterno?,
                    tipo (CAMARA|TURMA|VARA)
Magistrado          id, nomeNormalizado, nomeExibicao, tribunalSigla, orgaoJulgadorId?,
                    grau, ativo, totalDecisoesConhecidas
MagistradoAlias     id, magistradoId, aliasNormalizado, origem
```

**Invariantes**
- `nomeNormalizado` é resultado determinístico do normalizador (maiúsculas, sem acento,
  sem títulos, ordem direta). UNIQUE em `(tribunalSigla, nomeNormalizado)`.
- `MagistradoAlias` guarda cada grafia vista em fonte externa. Toda ingestão consulta
  alias antes de criar magistrado novo — é o que impede duplicar "ALFEU MACHADO" e
  "Des. Alfeu Machado".
- `ativo` reflete `relatorAtivo` do TJDFT e é exibido no relatório.

### Decisões

```
Decisao             id, tribunalSigla, identificadorExterno, uuidExterno?,
                    numeroProcesso, magistradoId, orgaoJulgadorId,
                    codigoClasseCnj?, assuntosCnj[]?,
                    dataJulgamento, dataPublicacao,
                    ementa, dispositivoBruto, categoriaResultado, unanime?,
                    inteiroTeor?, urlFonte, hashConteudo, ingeridoEm
DecisaoEmbedding    decisaoId, embedding vector(1536)
ProcessoMetadado    numeroProcesso, tribunalSigla, codigoClasseCnj, assuntosCnj[],
                    orgaoJulgadorCodigo, dataAjuizamento, grau, movimentos JSONB
```

**Invariantes**
- UNIQUE em `(tribunalSigla, identificadorExterno)`. Ingestão é upsert idempotente.
- `hashConteudo` detecta alteração de texto na origem entre reingestões.
- Decisão com segredo de justiça **não é persistida**. Filtro na ingestão.
- Nome de parte nunca é persistido em claro. Pseudonimizado desde a primeira migration.
- `dispositivoBruto` guarda o texto original do campo `decisao` do TJDFT;
  `categoriaResultado` é o resultado da classificação por vocabulário controlado. Os dois
  coexistem para que a classificação seja auditável e reprocessável.

### Análise e perfil

```
AnaliseDecisao      id, decisaoId, promptId, promptVersao, promptHash, modelo,
                    resultado JSONB, tokensEntrada, tokensSaida, geradoEm
PerfilMagistrado    id, magistradoId, recorte JSONB, recorteHash, versao,
                    statusGeracao, estatisticas JSONB, relatorio JSONB,
                    amostraN, decisoesAnalisadas[], promptHash, modelo,
                    geradoEm, validoAte
PerfilAcesso        id, tenantId, perfilId, consumiuCredito, acessadoEm
Job                 id, tipo, payload JSONB, status, tentativas, proximaTentativaEm,
                    tenantId?, erro?, criadoEm, atualizadoEm
```

**Invariantes**
- `PerfilMagistrado` é UNIQUE em `(magistradoId, recorteHash, versao)` e **não tem
  `tenantId`** — é artefato compartilhado. Se alguém adicionar `tenantId` aqui, a margem do
  negócio acabou.
- `recorteHash` é função determinística do recorte canonizado.
- `PerfilAcesso.consumiuCredito` é o registro de auditoria da regra de cache.
- Toda `AnaliseDecisao` carrega `promptHash` + `modelo`: é o que permite responder
  "qual prompt e quais fontes geraram este relatório?" um ano depois.
- `Job` é a fila. Consumo por `SELECT ... FOR UPDATE SKIP LOCKED`.

---

## 3. Recorte

O recorte define o corte analítico do perfil. É **fechado e canonizado**, nunca texto livre
— ver ADR 0001, seção 4.

| Dimensão | Valores admitidos |
|---|---|
| `periodoMeses` | 12, 24, 36, 60 |
| `assuntoCnj` | código da tabela CNJ, ou ausente (todos) |
| `classeCnj` | código da tabela CNJ, ou ausente (todas) |

Canonização: campos ausentes normalizados para nulo, ordem fixa, serialização estável,
hash sobre o resultado. Recortes semanticamente iguais **têm** que colidir.

---

## 4. Cobertura: 1º grau e 2º grau

Distinção que precisa aparecer na interface, não só na documentação.

| | 2º grau | 1º grau |
|---|---|---|
| Pronunciamento | Acórdão | Sentença |
| Relator estruturado | Sim | Não há relator |
| Está em base de jurisprudência | Sim | Raramente |
| Fonte na v1 | TJDFT | — |
| Fonte futura | — | DJEN, agregador |
| Cobertura | Boa | Irregular |

**A v1 vende perfil de relator e de órgão julgador de 2º grau, do TJDFT.** Prometer perfil
de qualquer juiz do país no lançamento é a forma mais rápida de queimar a marca.

---

## 5. O que nunca modelamos

Consequência direta da regra inviolável 3. Não existe — e não deve ser criado — campo,
endpoint ou tela que represente:

- score, nota, pontuação ou ranking de magistrado
- probabilidade de êxito, chance de ganho ou previsão de resultado
- "favorabilidade" a autor ou réu
- qualquer inferência sobre a pessoa do magistrado fora do exercício da função

O que entregamos é estatística descritiva sobre decisões públicas, com `n` visível e fonte
verificável em cada afirmação.
