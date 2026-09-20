# ADR-014: Política de Resiliência de Integração

**Status:** `ACEITO`

**Data:** 2026-09-20

**Supersede:** nada. **Complementa:** ADR-007 (escada de fallback), ADR-012 (Virtual Threads), ADR-013 (transação
programática).

## Contexto

O `ms-sdui-composer` não possui cliente HTTP, listener de mensageria nem tarefa agendada. Suas fronteiras de
integração são três:

1. **Entrada HTTP do plano de leitura** — `GET /v1/surfaces/home`, somente leitura, caminho síncrono de usuário.
2. **Entrada HTTP do plano administrativo** — `/admin/v1/**`, muda estado, operado por humano sob maker-checker.
3. **Portas de saída** (`orchestrator/port/outbound/Stores.kt`) e `SectionHydrator` — hoje adapters em memória,
   amanhã MongoDB e Redis (seção 17 do `AGENTS.md`).

Cada etapa do pipeline já tinha o seu prazo, mas nenhuma conhecia o custo das anteriores. A soma dos tetos era o
pior caso real e ninguém o declarava: a espera de um waiter no singleflight era de 2 000 ms contra um orçamento de
hidratação de 80 ms — vinte e cinco vezes mais — e esse valor sequer era configurável, porque o bean nunca o
passava.

Ao mesmo tempo, os desfechos degradados eram invisíveis. O `503`, que é o pior desfecho do serviço, não emitia
métrica alguma: existia apenas no contador HTTP genérico, sem o motivo. Seis `catch (_: Exception)` engoliam falha
de leitura de cache, de escrita de cache e de leitura do last good sem contador e sem log — um Redis que lê e
recusa gravar produziria miss de 100% indistinguível de operação normal nos painéis.

O last good não tinha prazo de validade nem era invalidado por publicação ou rollback. Um rollback feito para
retirar do ar uma revisão ruim não a removia do last good: na falha seguinte, a revisão retirada voltaria a ser
servida, marcada como `fallback`. Isso é fallback violando invariante de negócio, não degradação de experiência.

No plano administrativo, a idempotência era _check-then-act_: `find` e depois `put`, com o retorno do `putIfAbsent`
descartado. Dois `POST /publish-requests` concorrentes com a mesma chave criavam dois pedidos. A chave era, além
disso, opcional justamente em `open` — a operação que cria estado — e o registro era gravado fora da transação em
`open` e `reject`, deixando a janela em que o efeito já aconteceu e a chave não existe.

Por fim, o ADR-012 trocou pool de threads por Virtual Threads. Com isso o serviço perdeu o bulkhead implícito: não
há mais pool limitado fazendo shedding, e o único limite de concorrência remanescente era o semáforo de fan-out da
hidratação.

## Decisão

### 1. Orçamento de tempo explícito

Toda requisição abre um `TimeBudget` (`core/limit/TimeBudget.kt`), derivado do SLO da borda. Os prazos vivem em
`ComposeBudgets` e são configuráveis sob o prefixo `sdui`:

| Etapa                  | Prazo   | Propriedade                |
|------------------------|---------|----------------------------|
| Requisição (total)     | 250 ms  | `request-budget-ms`        |
| Espera do waiter       | 150 ms  | `singleflight-timeout-ms`  |
| Permissão do bulkhead  | 50 ms   | `read-bulkhead-wait-ms`    |
| Hidratação por section | 80 ms   | `hydration-timeout-ms`     |

Cada etapa recebe `budget.stage(teto)`, que é o menor entre o teto dela e o que resta. A espera do waiter é
deliberadamente menor que o orçamento total: quem espera deve desistir e ir para o last good **antes** de o cliente
desistir, senão a espera só soma ao tempo total sem melhorar o desfecho.

O `TimeBudget` usa `nanoTime` e não relógio de parede, porque o valor é uma diferença e um ajuste de NTP para trás
produziria prazo negativo ou eterno.

**Limite assumido:** o orçamento não interrompe chamada já em andamento. Uma chamada síncrona de store não é
cancelável a partir do orquestrador. Prazo por chamada é responsabilidade do adapter — _socket timeout_ do driver —
quando a persistência deixar de ser em memória. O que o orçamento garante é que nenhuma etapa **inicie** sem prazo
e que as esperas configuráveis nunca ultrapassem o que o cliente aceita aguardar.

### 2. Bulkhead do plano de leitura

As leituras de store do pipeline de composição passam por um `Bulkhead` (`core/limit/Bulkhead.kt`) de 32
permissões, separado do plano administrativo. Lotação devolve `BulkheadOutcome.Rejected` — valor de retorno, não
exceção, porque lotação não é erro, é a resposta correta de um limitador funcionando — e a requisição degrada para
o last good.

A hidratação continua com o semáforo de fan-out próprio. Os dois limites não se misturam: segurar uma permissão de
leitura durante a hidratação faria uma fonte de dados lenta estrangular quem só precisa ler spec e skeleton.

### 3. Sem retry de dependência

O serviço **não** faz retry de nenhuma dependência e não deve passar a fazer. A escada de fallback do ADR-007 já é
a política de degradação; acrescentar retry sobre stores multiplicaria a carga exatamente quando a dependência está
fraca. Retry é responsabilidade do cliente móvel, sobre um `GET` idempotente, segundo o contrato em
`docs/runbooks/contrato-de-retry-clientes-moveis.md`.

### 4. `Retry-After` com jitter

`RetryAfter.jittered` perturba a base em ±40%: `429` sai na faixa 1–3 s e `503` na faixa 3–7 s. O jitter é
simétrico em torno da base, e não apenas aditivo, para que a média do atraso continue sendo o valor configurado.

A razão é que a chave do limitador é `plataforma:build` — uma **coorte**, não um aparelho. Quando um build popular
estoura o bucket, todos os aparelhos daquele build recebem a recusa no mesmo segundo; um `Retry-After` constante os
instruiria a voltar juntos e a repetir o pico que causou a recusa.

### 5. Idade máxima do fallback e invalidação

`LastGoodScreenStore.get` passa a devolver `StoredScreen`, com o instante da gravação. Acima de
`max-fallback-age-seconds` (24 h) o last good é recusado e o serviço prefere `503`. Árvore defasada é melhor que
`503` durante um incidente de minutos; depois de um dia ela já não descreve o produto, e entregá-la seria trocar
indisponibilidade visível por incorreção silenciosa.

`LastGoodScreenStore.invalidate` passa a ser chamado por `PublishService.approve` e `RollbackService.rollback`,
junto com a invalidação do cache de árvore e sempre **depois** do commit (regra 5 da seção 19 do `AGENTS.md`).

### 6. Idempotência por reserva

`IdempotencyStore` deixa de ser `find`/`put` e passa a `find`/`reserve`/`complete`/`release`:

- `reserve` toma a chave antes de a operação começar, com `putIfAbsent` cujo retorno **é conferido**;
- `complete` fecha a reserva com o resultado, no mesmo commit do efeito;
- `release` devolve a chave quando a operação falha sem efeito, para que o operador possa corrigir e reenviar com a
  mesma chave em vez de inventar outra.

`IdempotencyRecord.resultRef` torna-se anulável: nulo significa reserva em voo. Uma requisição que encontra reserva
em voo recebe `409 IDEMPOTENT_IN_FLIGHT`, distinto de `409 CONFLICT` — o operador precisa separar "outro ator mudou
o pedido" de "a sua própria requisição ainda está correndo", porque só o segundo caso se resolve esperando.

`Idempotency-Key` passa a ser obrigatória em `open`, como já era em `approve`, `reject` e `rollback`. `open` e
`reject` passam a executar dentro de `tx.execute`, como `approve` já fazia. O store ganha validade de 24 h e teto de
entradas, pela mesma regra que vale para o cache de árvore e para o limitador (regra 2 da seção 19).

**Limite assumido:** enquanto `InMemoryTransactionalUnitOfWork` não tiver rollback — ele mesmo declara que dá
exclusão mútua, não atomicidade — a ambiguidade de desfecho é reduzida, não eliminada. Maker-checker idempotente de
verdade exige transação real, e essa é uma das justificativas do ADR de persistência que ainda será escrito.

### 7. Observabilidade obrigatória de todo desfecho degradado

Nenhum caminho de degradação fica mudo. Métricas novas, todas com nome constante e dimensão em tag:

| Métrica                     | Tags                                     | Para quê                              |
|-----------------------------|------------------------------------------|---------------------------------------|
| `compose.unavailable`       | `platform`, `schemaVersion`, `channel`, `fallbackReason` | A taxa de `503`. Alerta de página. |
| `compose.fallback.age.ms`   | `platform`, `channel`                    | Defasagem real do que se está servindo |
| `compose.fallback.expired`  | `platform`, `channel`                    | Last good recusado por idade          |
| `store.failure`             | `stage`                                  | Falha de dependência de dados         |
| `cache.write.failure`       | `cache`                                  | Cache que lê e não grava              |
| `compose.deadline.exceeded` | `stage`                                  | Qual etapa estourou o orçamento       |
| `compose.bulkhead.rejected` | `stage`                                  | Lotação do plano de leitura           |

`compose.duration` ganha a tag `outcome` (`hit`, `miss`, `fallback`, `not_modified`, `invalid_headers`,
`rate_limited`, `unavailable`, `error`). Sem essa dimensão, a latência de acerto de cache e a do caminho degradado
caem no mesmo histograma e não há como separar as duas populações — que é exatamente a separação necessária num
incidente.

Os `catch` do pipeline passam a registrar `WARNING` via `System.Logger`, e não SLF4J: o orquestrador não depende de
framework e o JDK basta; o Spring Boot instala a ponte de JUL para o backend de log.

## Consequências

**Positivas**

- O pior desfecho do serviço passa a ser mensurável, com o motivo, o que torna alerta de página possível.
- Um `Redis` que lê e recusa gravar deixa de ser indistinguível de operação normal.
- Um rollback passa a remover de fato a revisão retirada de todos os caminhos de entrega.
- Dois retries concorrentes de uma operação administrativa não produzem mais efeito duplicado.
- O serviço volta a ter um limite de concorrência explícito, que os Virtual Threads haviam retirado.
- Uma coorte recusada volta escalonada, não em bloco.

**Negativas e trade-offs aceitos**

- O `ComposeScreenService` ganhou dois colaboradores (`Bulkhead`) e um agregado de configuração. A alternativa era
  manter prazos espalhados por parâmetros default, que foi o que escondeu a desproporção de 25×.
- O bulkhead introduz um modo de falha novo: lotação. É intencional, é observável e degrada para o last good.
- A idade máxima do fallback troca algumas entregas defasadas por `503`. É a escolha deliberada de preferir
  indisponibilidade visível a incorreção silenciosa.
- A chave de idempotência obrigatória em `open` é quebra de contrato do plano administrativo. Aceita: o plano está
  atrás de barreira de rede e não tem cliente externo.

**Riscos operacionais**

- `read-bulkhead-permits` dimensionado abaixo da concorrência real transforma pico legítimo em fallback. Mitigação:
  `compose.bulkhead.rejected` é a métrica que denuncia isso, e o valor é configurável sem recompilar.
- `request-budget-ms` apertado demais produz `compose.deadline.exceeded` em operação normal. Mesma mitigação.

## Alternativas Consideradas

- **Circuit breaker por dependência.** Descartado. O serviço já tem a resposta que um breaker daria — o last good.
  Um breaker aqui trocaria a forma da falha e acrescentaria um estado a mais para o operador entender, sem oferecer
  desfecho melhor. Timeouts apertados, bulkhead e last good cobrem o caso. Fica como decisão futura, com ADR
  próprio, se a métrica `compose.unavailable` mostrar oscilação que prazos não contenham.
- **Retry com backoff no servidor sobre os stores.** Descartado pela razão da seção 3.
- **Prazo por chamada na assinatura das portas de store.** Descartado: um parâmetro `Duration` que o adapter em
  memória ignora é cerimônia que aparenta garantia sem oferecer nenhuma. Prazo por chamada é do adapter.
- **Expiração do last good no próprio store.** Descartado: quem sabe qual defasagem ainda é aceitável é o pipeline,
  e a idade também precisa virar métrica. O store carimba; a política decide.

## Critérios de Validação

- `sdui-core`: `ResiliencePrimitivesTest` — faixa e piso do jitter, encolhimento e esgotamento do orçamento,
  lotação e devolução de permissão do bulkhead.
- `sdui-app`: `ComposeResilienceTest` — contador do `503` com motivo, last good dentro e fora do prazo, jitter do
  `Retry-After`, interrupção por orçamento estourado, degradação por lotação e relato de falha de store.
- `sdui-app`: `AdminIdempotencyTest` — `open` concorrente com a mesma chave produzindo um único pedido, devolução da
  chave em falha, invalidação do last good por `approve` e por `rollback`, exclusividade/validade/teto da reserva.
- ArchUnit: as regras existentes continuam valendo; `core` e `orchestrator` seguem sem Spring, Jackson e servlet.
