# MEMÓRIA OPERACIONAL E ARQUITETURAL — MS-SDUI-COMPOSER

**Última Atualização:** 2026-09-23  
**Status do Projeto:** MVP H00–H18 concluído (histórico consolidado na Seção 10); ADR-014 a ADR-019 consolidados na Seção 8; ADR-020 a ADR-022 consolidados na Seção 9. Em 2026-09-23: achados de performance tratados com medição, surface `catalog` e contratos novos (ADR-020, `PROPOSTO`), adapters MongoDB/Redis (ADR-021, `PROPOSTO`, não homologados) e integridade da governança e limites de entrada (ADR-022, `PROPOSTO`). O PASS histórico não cobre esta entrega; a evidência dela está em `tasks/todo.md`.

---

## 1. Identidade e Papel Arquitetural

O `ms-sdui-composer` é o Backend-For-Frontend (BFF) Server-Driven UI responsável por compor a árvore de componentes das
surfaces da allowlist (`home` e `catalog`) para clientes iOS e Android a partir de especificações versionadas,
capabilities declaradas e contexto de cliente.

- **Padrão:** Presentation + Application Controller + BFF de UI (Martin Fowler).
- **Princípio:** Estritamente **stateless** no hot path. Não consulta domínios de negócio regulados diretamente e não
  persiste árvores hidratadas de usuário no MongoDB.
- **Endpoints:** `GET /v1/surfaces/home` e `GET /v1/surfaces/catalog`, um mapeamento literal por surface, com
  versionamento HTTP via cabeçalho `API-Version: 1` (`UI-Schema-Version` é versão de envelope de UI, não de API HTTP).

---

## 2. Stack Tecnológica e Baseline

- **Linguagem:** Kotlin 2.4.20 (`allWarningsAsErrors = true`).
- **JVM / Plataforma:** Java 25 LTS via Gradle toolchain (`jvmToolchain(25)`).
- **Framework:** Spring Boot 4.1.1 (Spring Framework 7.0.9 gerenciado pelo BOM).
- **JSON:** Jackson 3 (`tools.jackson.core:jackson-databind` 3.1.5, `tools.jackson.module:jackson-module-kotlin` 3.1.5,
  `com.fasterxml.jackson.core:jackson-annotations` 2.21).
- **Concorrência:** Spring MVC sobre **Virtual Threads Java 25** (`spring.threads.virtual.enabled: true`). Proibido o
  uso de Coroutines (`suspend fun`), WebFlux ou bibliotecas reativas (ADR-012).
- **Bancos e Cache:** padrão em memória. Opcionalmente (ADR-021) MongoDB 8.3+ em replica set como autoridade da
  governança e Redis como cache de spec, árvore e last good. Singleflight e limitador continuam locais ao processo.
- **Testes & Governança:** JUnit Jupiter, AssertJ, MockMvc e ArchUnit 1.5.0.

---

## 3. Topologia de Módulos (ADR-001)

```text
sdui-bootstrap        --> implementation(project(":sdui-app"))
sdui-app              --> api(project(":sdui-core"))
                      --> implementation(project(":sdui-contract"))
sdui-contract         --> Jackson 3 estrito apenas (DTOs de resposta)
sdui-core             --> JDK 25 e Kotlin stdlib apenas (Zero dependências externas)
sdui-integration-test --> testImplementation de todos os módulos + ArchUnit
```

- **`sdui-core`:** Puro. Regras de `Negotiate`, `Select`, `Filter`, `SemVer`, `Capability`, `MvpCatalog`,
  `SpecValidator`, `Guards` e `TokenBucket`.
- **`sdui-contract`:** DTOs públicos de resposta (`ScreenEnvelope`, `ScreenResponse`, `SectionResponse`,
  `ActionResponse`, etc.). Não referencia `sdui-core`.
- **`sdui-app`:** Orquestrador (`compose`, `hydration`, `admin`), adaptadores (`memory`, `mongo`, `redis`, `json`,
  `seed`, `health`, `invalidation`, `observability`) e controladores REST (`SurfaceController`, `AdminController`).
- **`sdui-bootstrap`:** Único módulo executável contendo `@SpringBootApplication`.
- **`sdui-integration-test`:** Suíte ArchUnit validando isolamento de camadas e regras de dependência.

---

## 4. Pipeline de Composição no Hot Path

O fluxo de composição executa 6 fases determinísticas:

```
[Request HTTP]
      │
      ▼
1. NEGOTIATE ── Validação de cabeçalhos obrigatórios, parsing de SemVer ordinal
      │         (com proteção contra overflow) e capabilities.
      ▼
2. SELECT    ── Seleção determinística da Spec publicada por plataforma, faixa
      │         de versão, schema e canal (stable vs. canary).
      ▼
3. FILTER    ── Omissão graciosa de seções incompatíveis com as capabilities
      │         do cliente móvel. Ordenação estável TimSort O(N log N).
      ▼
4. HYDRATE   ── Hidratador sem I/O (pass-through) na thread da requisição; hidratador com
      │         I/O em Virtual Threads com Semaphore, prazo e cancelamento por seção.
      ▼
5. GUARD /   ── VisualGuard (anti-CSS) e PiiGuard (anti-PII/CPF/PAN). Se slots portantes
   FALLBACK     ('header'/'accounts') falharem: Escada de Fallback (Cache -> LastGood -> 503).
      │
      ▼
6. COMPOSE   ── Serialização única JSON direta para ByteArray, montagem de ETag
                e emissão de cabeçalhos de cache (HTTP 200 ou HTTP 304).
```

---

## 5. Histórico de Decisões e Correções de Qualidade

Durante a revisão técnica multidimensional do MVP (`H00` a `H18`), foram sanados 7 apontamentos críticos:

1. **Singleflight Timeout Fix (`InMemoryStores.kt`):** Removido `existing.cancel(true)` do waiter. Um cliente que expira
   em timeout retorna `WaitTimeout()` sem cancelar o future compartilhado do líder.
2. **SemVer Overflow Protection (`SemVer.kt`):** Substituído `.toInt()` por `.toIntOrNull() ?: return null` nos grupos
   de captura regex, evitando `NumberFormatException` (HTTP 500) e gerando `ContextValidation.Invalid` (HTTP 400).
3. **Eliminação de Dupla Serialização (`SurfaceController.kt`, antes `HomeController.kt`):** O controller agora entrega o `byte[]` pré-serializado
   diretamente no `ResponseEntity` com `MediaType.APPLICATION_JSON`, reduzindo em 50% o overhead de CPU e GC.
4. **Constantes Pré-calculadas em Guards (`Guards.kt`):** `LOWER_VISUAL_KEYS` e `LOWER_PII_KEYS` cacheados como
   `private val`, eliminando alocações repetidas de `Set` durante o `PropWalk`.
5. **Ordenação O (N log N) em Filter (`Filter.kt`):** Removido `.thenBy { kept.indexOf(it) }`, aproveitando a
   estabilidade natural do TimSort.
6. **Limpeza de API (`RedisKeyspace.kt`):** Removido o parâmetro `channel` não utilizado do método `treePrefix`.
7. **Resource Leak Prevention (`SduiConfiguration.kt`):** Leitura de arquivo de seed protegida por
   `.use { it.readText() }`.
8. **Liberação de Semáforo sob Timeout no Fan-out (`HydrationCoordinator.kt`):** Tarefas com timeout agora sofrem
   `taskThread.interrupt()`, evitando que Virtual Threads órfãs retenham permits do `Semaphore(fanOut)`.
9. **Isolamento de Métricas no Fan-out (`HydrationCoordinator.kt`):** Gravação de métricas com `runCatching` dentro do
   `.handle`, blindando `job.join()` contra `CompletionException`.
10. **Poda e Retenção em Stores (`InMemoryStores.kt`):** Adicionada rotina periódica `prune()` no
    `InMemoryProjectionStore`
    (teto de 10.000 entradas, varredura no máximo a cada minuto) e teto FIFO no `InMemoryAuditLogStore` (2.000 eventos).
11. **Pré-cálculo de Capability e SlotOrder (`Section.kt`, `Skeleton.kt`, `Filter.kt`):** `Section.capability` e
    `Skeleton.slotOrder` / `requiredSlotIds` pré-calculados imutavelmente, eliminando milhares de alocações transitórias
    no hot path.
12. **Lookups O (1) em Enums (`Enums.kt`):** `SlotLayout.parse` e `ActorRole.parse` indexados via mapas estáticos
    pré-calculados.
13. **Guarda de Profundidade de Recursão (`ScreenResponseMapper.kt`, `JsonMaps.kt`):** Limite de 32 níveis em `toNode`
    prevenindo `StackOverflowError` sob payloads aninhados.
14. **Correlation ID e Rastreabilidade (`CorrelationIdFilter.kt` e `ComposeTraceContext.kt`):** Captura de
    `X-Request-Id`
    com sanitização e geração de UUID v4 de fallback, propagado para o SLF4J MDC (`requestId`, `surface`, `platform`,
    `schemaVersion`).
    Não emite cabeçalhos `X-` na resposta da Home em estrita conformidade com o contrato.
15. **Telemetria de Governança no Plano Administrativo (`AdminController.kt`, `ApiExceptionHandler.kt`):** Métricas
    Micrometer (`admin.spec.draft`, `admin.publish.open`, `admin.publish.approved`, `admin.publish.rejected`,
    `admin.rollback`,
    `admin.error`) e logs estruturados em operações de mutação e rollback.
16. **Structured Logging Nativo (`application.yaml`):** Configuração de console estruturado no padrão ECS JSON
    (`logging.structured.format.console: ecs`) incorporando automaticamente todos os campos do MDC.
17. **Especificação de Alertas Prometheus (`regras-de-alerta-prometheus.md`):** Alertas orientados a sintomas (SLO de
    latência, taxa de indisponibilidade 503, esgotamento de bulkhead e gatilho de rollback) vinculados aos runbooks
    operacionais, padronizados em segundos (`compose_duration_seconds_bucket`).
18. **Histogram Buckets no Prometheus (`application.yaml`):** Habilitação de `percentiles-histogram` para timers
    críticos (`compose.duration` e `section.hydrate.ms`), viabilizando o cálculo de percentis P95/P99 no Prometheus.
19. **Propagação de MDC no Fan-out (`MdcPropagatingExecutor`):** Virtual threads assíncronas de hidratação recebem o
    contexto MDC herdado da thread principal via executor decorador em `adapters`, garantindo correlação sem quebrar a
    pureza
    do `sdui-orchestrator`.
20. **Carimbo de Ponto de Entrada (`entryPoint` no MDC):** Requisições e tarefas de background carimbam `entryPoint`
    (`home`, `admin`, `management`, `seed`) no MDC, eliminando diagnósticos por eliminação em logs estruturados
    compartilhados.
21. **Gauges de Recursos USE (`MeterBinder`):** Exposição contínua da saturação de memória do `TokenBucket`
    (`rate_limiter.resident_keys`) e da ocupação do `Bulkhead` (`compose.bulkhead.available_permits`) via Micrometer.

---

## 6. Governança e Regras Inegociáveis

- **Maker-Checker Estrito:** O criador de um draft de spec não pode aprovar o próprio pedido de publicação em canais
  `canary` ou `stable`.
- **Atomicidade e Idempotência:** Rollback atômico por ponteiro (`PointerStore`) com suporte a `Idempotency-Key` e
  auditoria append-only.
- **Isolamento iOS e Android:** Pointers e chaves de cache independentes. Requisições Android nunca selecionam specs ou
  revisões iOS.
- **Proibições Estruturais:**
    - Proibido atributos de CSS/visual no payload (`color`, `padding`, `margin`, `radius`, etc.).
    - Proibido tipos genéricos (`row`, `column`, `container`, `card` genérico).
    - Proibido tráfego ou persistência de PII/segredos (CPF, PAN, senhas, tokens).
    - Proibido `@Transactional` em qualquer classe do projeto (transações programáticas via `TransactionalUnitOfWork`
      com `TransactionTemplate` — ADR-013).
    - Proibido `Fragment`, `FragmentResolver` ou endpoints de fragmento no MVP (ADR-004).

---

## 7. Metas de Performance e Métricas (H11)

- **SLO Hot Path (Cache Hit):** P99 ≤ 400 ms.
- **SLO End-to-End (App):** P99 ≤ 1200 ms (Rede + Compose + First Paint).
- **Cache Hit Ratio Esperado:** ≥ 90%.
- **Métricas Emitidas:** catálogo completo em `MetricNames` (`sdui-app`, `orchestrator/port/outbound`), entre elas
  `compose.duration`, `compose.hit`, `compose.miss`, `compose.fallback`, `compose.singleflight.wait`,
  `compose.singleflight.recheck_hit`, `payload.bytes`, `mapping.ms`, `serialize.ms`, `section.hydrate.ms{type}`,
  `section.omitted`, `select.no_candidate`, `store.failure{stage}`, `cache.*`. Nenhuma tag leva versão exata de app.
- **Cenário de Carga Versionado:** [
  `sdui-app/src/test/resources/load/compose-hit-p99.yaml`](../sdui-app/src/test/resources/load/compose-hit-p99.yaml),
  executado por `HttpLoadGenerator` (`gradlew :sdui-app:loadTest`); medições em
  [`performance/medicoes-2026-09-23.md`](performance/medicoes-2026-09-23.md).

---

## 8. Consolidação Arquitetural dos ADRs 014 a 019

Com a implementação integral do código e cobertura de testes, as decisões dos ADRs 014 a 019 passam a integrar a memória
viva do serviço:

1. **Política de Resiliência de Integração (ADR-014):**
    - Ausência deliberada de retry sobre stores no servidor (evita sobrecarga cascateada).
    - Delimitação de tempo por requisição com `TimeBudget`, sem interromper composições já em andamento.
    - Bulkhead explícito no plano de leitura (`sdui.read-bulkhead-permits = 32`).
    - `Retry-After` com jitter pseudoaleatório de ±40% dispersando coortes móveis.
    - Idade máxima de `lastgood` cacheado (`sdui.max-fallback-age-seconds = 86400s`), recusando cache vencido em prol de
      HTTP 503 visível.
    - Idempotência administrativa por reserva prévia de chave (`reserve` -> `complete` / `release`).

2. **Escopo de Uso do SDUI e Superfícies Hostis (ADR-015):**
    - **Superfícies Elegíveis:** Home, Hubs de Produtos/Exploração, Vitrines e Banners de Campanhas.
    - **Superfícies Estritamente Proibidas:** Autenticação, Passcode/PIN, Onboarding/KYC regulado, Checkout
      transacional, Chat em tempo real, Mapas e rastreamento GPS.
    - **Blindagem de Segurança:** Rejeição de PII (`pin`, `otp`, `passcode`) e ações de mutação de rede (`callApi`,
      `addToCart`, `completeOnboarding`).

3. **Rejeição de CMS por Nós e Flags por Componente (ADR-016):**
    - Rejeição formal de templates desacoplados com flags por nó ou chamadas diretas a repositórios de negócio.
    - Proteção de cache de árvore e previsibilidade de targeting garantidas por testes de regressão
      (`RejectedProposalContractTest`).

4. **Modelo de Experimentação por Revisão de Spec (ADR-017):**
    - Experimentação configurada no nível do `Pointer` com `ExperimentConfig` e lista de `ExperimentArm` (pesos
      percentuais e specRevisionId).
    - Roteamento dinâmico em runtime reservado para o primeiro caso de uso prático em produção.

5. **Montagem Variável de Surface e Vocabulário de Slots (ADR-018):**
    - Ordem e layout dos slots desacoplados da superfície e versionados no `Skeleton`.
    - Vocabulário de slots fechado por surface (`Surfaces`, ADR-020) com layouts permitidos (`allowedLayouts` por
      slot) validados em `SkeletonValidator`.
    - Disponibilização de skeletons canônicos: `home.default` (sequencial tradicional) e `home.cards_first` (cartões em
      grade de 2 colunas logo abaixo do header).

6. **Eliminação de Variantes Visuais no Catálogo (ADR-019):**
    - Remoção de `variant: "compact"` do `shortcut_shelf@1` e inserção em `MvpCatalog.VISUAL_KEYS`.
    - Garantia de que a renderização visual e densidade de tela pertencem exclusivamente às classes de tamanho nativas
      (`WindowSizeClass` e `SizeClass`).

---

## 9. Ciclo de 2026-09-23 — surfaces, contratos, persistência e performance

O ciclo de evolução de 2026-09-23 introduziu suporte a múltiplas surfaces e novos contratos de componente (ADR-020),
adapters de persistência MongoDB 8.3+ e cache Redis (ADR-021), integridade estrita da governança e limites de entrada
(ADR-022) e otimizações de performance com medições empíricas.

As três decisões arquiteturais deste ciclo (ADR-020, ADR-021 e ADR-022) possuem status `PROPOSTO` — implementadas no
código do servidor e cobertas por testes e fontes de regressão, mas com homologação operacional e aceitação móvel pendentes.
A seguir registra-se o detalhamento arquitetural completo dessas decisões.

---

### ADR-020 — Múltiplas surfaces e contratos de componente

- **Status:** `PROPOSTO` — implementado no servidor em 2026-09-23; os contratos novos (`transaction_summary@1`,
  `catalog_navigation@1`, `product_collection@1`) aguardam homologação das equipes iOS e Android antes de qualquer uso
  em produção.
- **Data:** 2026-09-23
- **Relacionados:** ADR-004, ADR-007, ADR-009, ADR-010, ADR-011, ADR-015, ADR-016, ADR-018, ADR-021.
- **Planejamento:** [`tasks/plan.md`](../tasks/plan.md), tarefas T01 e T04–T07 de [`tasks/todo.md`](../tasks/todo.md).

#### Contexto

O composer servia uma única surface, `home`. `Select`, fallback, catálogo e validação de skeleton embutiam regras da
Home financeira: sete types, sete slots, `header` e `accounts` portantes. `CatalogValidator` exigia **exatamente** o
conjunto ativo do MVP, impedindo a entrada de novos componentes e, ao mesmo tempo, aceitava componentes inativos de
nome arbitrário — que viravam tag de métrica (`admin.catalog.upsert{type}`). `SpecValidator` não restringia
`spec.surface`, que também virava tag (`admin.spec.draft{surface}`).

As referências visuais de `docs/images` demandavam quatro composições: duas montagens da Home bancária (Nubank), uma
Home com resumo de transações e um catálogo de moda. As três primeiras cabem na surface `home`; o catálogo não pode
exigir o slot `accounts`.

#### Decisão

1. **Allowlist finita de surfaces:** `Surfaces` (em `sdui-core`) declara, por surface, os slots, os layouts permitidos
   por slot, quais slots são portantes, quais types podem ocupá-la, o primeiro slot obrigatório e o evento de analytics
   do envelope. Configuração ativa: `home` (os sete slots do MVP mais `transactions`, opcional) e `catalog` (`header` e
   `products` portantes; `navigation` e `featured` opcionais). Uma surface fora da lista é recusada pelos validadores
   antes de virar dado, e não possui rota HTTP.
2. **Leitura por mapeamento literal:** `GET /v1/surfaces/home` continua idêntico (caminho, headers, corpo, ETag, Vary).
   `GET /v1/surfaces/catalog` é um segundo mapeamento literal no mesmo controller (`SurfaceController`); não há
   `/{surface}` genérico. A surface entra em toda leitura, chave e tag do pipeline: pointer, specs publicadas, chave de
   árvore, chave de singleflight, last good, `HydrationContext` e métricas. A seleção nunca cruza surfaces.
3. **Catálogo fechado em contratos aprovados:** `ComponentContracts.APPROVED` é o universo finito de `type@typeVersion`:
   os sete legados mais os três novos. `CatalogValidator` recusa qualquer componente fora dele, em qualquer status, e
   exige os sete legados ativos. O mesmo conjunto é o universo que `CapabilityMatrix` aceita no header
   `Component-Capabilities` — o que é publicável é declarável, e nada além disso.
4. **Nenhum type novo concedido automaticamente:** A matriz do servidor continua concedendo apenas os sete legados. Um
   contrato novo só chega a quem o declara explicitamente em `Component-Capabilities`. Regras de compatibilidade:
   - Slot opcional com type não declarado: section omitida com `unsupported_type` (ex.: `transactions` na Home);
   - Slot portante que dependa de type novo: a spec declara a capability em `targeting.requiredCapabilities`; cliente
     sem ela não seleciona a spec e cai na escada de fallback da própria surface (no catálogo, `503 no_compatible_spec`,
     nunca a árvore da Home);
   - A simulação de slot portante em `SpecValidator` soma `requiredCapabilities` às capabilities do servidor, pois
     Select garante que apenas clientes com elas recebem a spec;
   - Na composição, um slot portante esvaziado pelo filtro de capabilities também dispara a escada de fallback
     (`required_slot_empty`), não apenas falha de hidratação.
5. **Contratos novos nascem estritos:** `ComponentPropsValidator` valida props obrigatórias, tipos, limites de itens
   (transações ≤ 5, categorias ≤ 12, vitrine ≤ 12), gatilhos pareados (rótulo + `...ActionId`) e recusa chaves de
   operação comercial (`quantity`, `favorite`, `addToCart`, `cart`, `checkout`, `stock`, `sku`). Os sete legados
   continuam sem validador de props para não quebrar specs já publicadas. Referências a action nas props passam a
   incluir `<papel>ActionId` além de `actionId`.
6. **Limites de entrada administrativa:** Props com mais de 16 níveis de aninhamento são recusadas na publicação —
   abaixo do teto de 32 do mapper da resposta, que portanto nunca trunca conteúdo publicado.
7. **Política de locale:** O conteúdo da entrega é sintético e de locale fixo `pt-BR` (`SurfaceDefinition.contentLocale`).
   O envelope ecoa o locale pedido. A chave de cache não possui locale; localizar props exige incluir essa dimensão na
   chave **antes** de ativar tradução.
8. **Tags de métrica com vocabulário fechado:** A versão exata do app sai de toda tag (permanecendo no MDC do log);
   `schemaVersion` só aparece como valor suportado ou `other`; surface e type só entram depois de validados. Um
   `MeterFilter` com teto de valores por tag nas métricas próprias atua como defesa em profundidade.

#### Alternativas descartadas

- **`/v1/surfaces/{surface}` com validação no handler:** Aceitaria qualquer string no path e deslocaria para o código
  a garantia que o roteamento explícito já fornece.
- **Catálogo aberto a qualquer type não genérico:** Reintroduziria cardinalidade ilimitada de tags e permitiria que
  componentes sem contrato chegassem a produção.
- **Conceder types novos às faixas atuais na matriz:** Entregaria a apps sem renderer componentes desconhecidos que
  quebrariam a renderização nativa.
- **Representar produto com `card_product` ou categoria com `shortcut_shelf`:** Mascararia conceitos distintos usando
  types incompatíveis (proibido pelo ADR-010 e pela matriz de rastreabilidade).
- **Header `template` ou canal por exemplo:** A alternância entre montagens deve ser feita publicando e movendo o
  pointer; canal não é tenant.

#### Consequências

- A Home canônica continua estritamente compatível: fixture, ETag, Vary e contrato v3 inalterados.
- Surface nova nunca recebe fallback nem árvore da Home: last good e cache são chaveados isoladamente por surface.
- Os exemplos em [`examples/screens/`](examples/screens/README.md) são propostas: a fixture Android canônica continua
  ausente e nenhum renderer foi validado.
- Nomes de rotas `app://` e bottom sheets nativos dos exemplos são ilustrativos até homologação formal com os apps.

#### Verificação

Fontes de teste: `SurfaceRulesTest`, `ComponentContractsTest`, `SelectSurfaceAndPropWalkTest` (`sdui-core`);
`ScreenExamplesTest`, `SurfaceIsolationAndSingleflightTest`, `SurfaceWebTest` (`sdui-app`);
`ExampleResponsesContractTest` (`sdui-contract`). Evidências registradas em [`tasks/todo.md`](../tasks/todo.md).

---

### ADR-021 — Persistência MongoDB 8.3+ e Cache Redis

- **Status:** `PROPOSTO` — adapters implementados e cobertos por testes em 2026-09-23; **não homologado**. O ensaio
  operacional (restart, perda de Redis, indisponibilidade do MongoDB, publicação concorrente e duas instâncias) e a
  medição com `explain` em base representativa ainda não foram realizados. Até lá, o modo padrão continua em memória
  (AGENTS.md §17).
- **Data:** 2026-09-23
- **Relacionados:** ADR-006, ADR-007, ADR-013, ADR-014, ADR-020.
- **Planejamento:** [`tasks/plano-persistencia-mongo-redis.md`](../tasks/plano-persistencia-mongo-redis.md) (tarefas P01–P13).

#### Contexto

Todos os stores eram em memória: o estado não sobrevivia a reinicializações, não era compartilhado entre pods e a
unidade de trabalho apenas oferecia exclusão mútua sem rollback real. Embora os starters de MongoDB e Redis estivessem
no classpath, suas autoconfigurações eram explicitamente excluídas e nenhum adapter existia.

#### Decisão

##### Modos e autoridade

| Propriedade              | Valores             | Padrão   | Efeito                                                                                                       |
|--------------------------|---------------------|----------|--------------------------------------------------------------------------------------------------------------|
| `sdui.persistence.store` | `memory` \| `mongo` | `memory` | Specs, skeletons, catálogo, pointers, pedidos, diffs, auditoria, idempotência, outbox e unidade de trabalho. |
| `sdui.persistence.cache` | `memory` \| `redis` | `memory` | Cache de spec, cache de árvore e last good.                                                                  |

Valores fora da allowlist interrompem a inicialização (binding de enum). URI ausente com modo persistente configurado
falha o boot. Nenhum modo persistente realiza fallback silencioso para memória. `ProjectionStore`, singleflight e
limitador de taxa continuam estritamente locais ao processo.

| Porta                                               | Autoridade | Observação                                                                                     |
|-----------------------------------------------------|------------|------------------------------------------------------------------------------------------------|
| `SpecStore`, `SkeletonStore`                        | MongoDB    | `PUBLISHED` é imutável: gravação condicionada a `status != PUBLISHED`.                         |
| `CatalogStore`                                      | MongoDB    | Documento único; validação de integridade antes da gravação; última gravação vence.            |
| `PointerStore`                                      | MongoDB    | `compareAndSet` pela versão gravada no documento; conflito resulta em HTTP 409.                |
| `PublishRequestStore`                               | MongoDB    | Transição atômica por `findOneAndReplace` condicionado ao status.                              |
| `DiffStore`, `AuditLogStore`                        | MongoDB    | Auditoria append-only; leitura paginada pelos registros mais recentes.                         |
| `IdempotencyStore`                                  | MongoDB    | Reserva via `insertOne` no `_id`; TTL remove apenas reservas abandonadas ou fora da janela.    |
| `CacheInvalidationOutbox`                           | MongoDB    | Gravado na mesma transação que move o pointer de publicação.                                   |
| `SpecCache`, `HydratedScreenCache`, `LastGoodStore` | Redis      | Nunca fontes de verdade; perda do Redis acarreta latência adicional e perda de degrau fallback. |

##### Documentos e índices

Cada documento armazena o objeto de domínio serializado em `json` (formato canônico de `DomainJson`, desacoplado do
mapper HTTP) e, como campos de primeiro nível, apenas atributos filtrados ou ordenados por consultas, acompanhados de
`_v` (versão do esquema). Chaves arbitrárias de props nunca se tornam nomes de campo no MongoDB.

Índices criados idempotentemente no boot (`MongoSchema.ensureIndexes`):
- `specs`: único em `specRevisionId`; composto em `(surface, platform, status)`; composto em `(specId, revision)`;
- `skeletons`: composto em `(skeletonId, revision desc)`;
- `audit_events`: índice em `tsMillis desc`;
- `idempotency`: índice TTL em `expiresAt`;
- `cache_invalidations`: índice em `createdAtMillis`.

Teto de documento serializado: `sdui.persistence.mongo.max-document-bytes` (1 MiB por padrão; limite do MongoDB é 16 MiB).
Documentos excedentes são rejeitados com HTTP 400.

##### Transação e topologia

`MongoTransactionalUnitOfWork` abre sessão cliente com `readConcern: snapshot` e `writeConcern: majority`, vinculando a
sessão à thread corrente; os adaptadores utilizam-na transparentemente. Efeito de negócio, registro de auditoria, fecho
da reserva de idempotência e inserção no outbox commitam atomicamente.

**Exigência Topológica:** Requer obrigatoriamente replica set ou cluster shardeado; standalone não suporta transações
multi-documento. O health indicator `sduiStore` reporta `DOWN` com `transactions: unsupported: standalone`. Implementações
compatíveis alternativas (como DocumentDB) permanecem fora do suporte até validação homologada.

Proibido o uso de `ClientSession.withTransaction` (que pode repetir loops transacionais por até 120 s). Falhas transitórias
resultam em `StoreConflict` (HTTP 409); o operador reenvia com a mesma `Idempotency-Key`. Se o commit ocorreu mas a
resposta foi perdida, o registro fechado de idempotência garante replay seguro.

##### Prazos, pool e resiliência

- **MongoDB:** Prazo por operação do driver (CSOT, `operation-timeout-ms`), `server-selection-timeout-ms`,
  `connect-timeout-ms`, pool com `max-pool-size` e `max-wait-time-ms`; `retryWrites=false` e `retryReads=false`.
- **Redis (Lettuce):** `command-timeout-ms` reduzido, `connect-timeout-ms` e recusa imediata com conexão indisponível
  (`REJECT_COMMANDS`), impedindo enfileiramento infinito.
- **Zero Retry no Servidor:** Nenhum retry de dependência no caminho síncrono da requisição (ADR-014).

##### Redis: formato, tetos e chaves

Valores envelopados em JSON versionado (`RedisCacheCodec`, `v = 1`); divergência de versão resulta em miss. Entradas
acima de `max-entry-bytes` são ignoradas (`cache.write.skipped`). Chaves padronizadas via `RedisKeys`, sem jamais conter
PII ou `userId`. TTLs configurados: `tree-ttl-seconds`, `spec-ttl-seconds`, `last-good-ttl-seconds`. O índice de árvores
por surface/plataforma/canal evita comandos `SCAN` bloqueantes durante a invalidação.

##### Invalidação pós-commit e escrita atrasada

- Cache de árvore e cache de spec são indexados por revisão: entradas antigas nunca são servidas a requisições que
  selecionaram outra revisão, sendo a revisão publicada imutável.
- O last good é indexado por surface/plataforma/canal e registra a **versão do pointer** sob a qual a composição ocorreu.
  A invalidação grava uma lápide na versão nova; gravações concorrentes de versão anterior são recusadas atomicamente
  (script Lua no Redis, `compute` em memória), impedindo que computações atrasadas ressuscitem revisões removidas.
- A invalidação é gravada no outbox dentro da transação e processada logo após o commit. Em caso de queda do processo ou
  indisponibilidade momentânea do Redis, o registro pendente é executado pelo relay em background
  (`sdui.persistence.invalidation-relay-interval-ms`). Reaplicações são seguras e idempotentes.

##### Retenção, seed e segurança

- **Retenção:** Dados de governança não sofrem expiração ou poda automática; listagens administrativas são estritamente
  paginadas (`offset`, `limit` ≤ 500).
- **Seed e Restart:** Seed canônico e carga de demonstração são idempotentes (`insertIfAbsent`). Reinicializações não
  revertem pointers ou catálogos ao estado inicial do seed.
- **Segurança Operacional:** Credenciais configuradas via ambiente (`SDUI_PERSISTENCE_MONGO_URI`,
  `SDUI_PERSISTENCE_REDIS_URL`). O plano administrativo depende de isolamento de rede perimetral, operando sem autenticação
  embutida no MVP.

#### Alternativas descartadas

- **Spring Data com entidades mapeadas:** Exigiria violar a pureza do domínio ou duplicar classes; o padrão de documento
  com `json` e campos de consulta mantém o domínio desacoplado.
- **`@Transactional`:** Proibido no projeto (ADR-013); o gerenciamento transacional é programático.
- **Invalidação imediata sem outbox:** Sujeita a perda de invalidação em travamentos pós-commit.
- **Singleflight e limitador distribuídos no Redis:** Desnecessários no dimensionamento atual; cada pod gerencia seu
  bulkhead localmente.

#### Consequências

- A operação multi-instância só é permitida **após** o ensaio de homologação operacional; até sua conclusão, vigora a
  restrição de instância única (AGENTS.md §17).
- No modo `mongo`, a seleção consulta o pointer por `_id` e a revisão apontada por índice único, com cache de spec na
  frente. Listagens de publicadas ocorrem apenas na contingência de o pointer apontar para revisão incompatível.

#### Verificação

Cobertura unitária e de stores em memória: `DomainJsonRoundTripTest`, `InMemoryStoresBehaviorTest`, `CacheInvalidatorTest`,
`AdminIdempotencyTest`. Cobertura de integração com MongoDB (replica set) e Redis reais: `MongoPersistenceIT`,
`RedisCachesIT`, `DurableModeBootIT` (acionados por `SDUI_IT_MONGO_URI` e `SDUI_IT_REDIS_URL`). Roteiro de homologação em
[`runbooks/persistencia-mongodb-redis.md`](runbooks/persistencia-mongodb-redis.md).

---

### ADR-022 — Integridade da Governança e Limites de Entrada

- **Status:** `PROPOSTO` — implementação e fontes de regressão escritas em 2026-09-23; compilação, testes e homologação
  ainda não executados neste ciclo.
- **Data:** 2026-09-23.
- **Relacionados:** ADR-007, ADR-008, ADR-013, ADR-014, ADR-021.
- **Origem:** [revisão estática R01–R12](analise-bugs-seguranca-2026-09-23.md).

#### Contexto

A auditoria de código e segurança identificou vulnerabilidades no plano administrativo e na fronteira HTTP: a aprovação
guardava apenas o identificador de um rascunho mutável, sem garantir a imutabilidade do conteúdo inspecionado pelo checker;
leituras seguidas de gravação permitiam concorrência com sobrescrita; a unidade de trabalho em memória não realizava rollback
em falha; reservas de idempotência vencidas podiam ser indevidamente alteradas por processos tardios; e payloads JSON
administrativos, assim como o índice de árvores no Redis, careciam de limites estritos de crescimento.

#### Decisão

##### Conteúdo revisado e integridade transacional

`PublishRequest.reviewedContentHash` armazena o SHA-256 da serialização determinística da spec e do skeleton exato
(com mapas ordenados). Apenas o status do skeleton é normalizado, pois sua publicação prévia por outro pedido não altera
a semântica do conteúdo.

O processo de aprovação relê e valida as entidades dentro da transação, exigindo spec em status `DRAFT` e hash idêntico
ao revisado, promovendo spec e skeleton por compare-and-set (CAS) do conteúdo observado. Qualquer modificação posterior
à abertura do pedido ou submissão sem hash resulta em HTTP 409, exigindo nova abertura de pedido e revisão humana.

Rascunhos também utilizam CAS: criação exige ausência prévia e edição exige correspondência do estado lido. Colisões
na alocação de revisões geram HTTP 409 sem tentativas cegas de sobrescrita. `specRevisionId` é único globalmente entre
todas as identidades nos dois adaptadores. Revisões de skeleton devem existir com exatidão (`current` não substitui
referências ausentes). Identificadores de revisão utilizam conjunto restrito de caracteres seguros, com limite de até
128 caracteres e proibição de `userId`.

No modo em memória, um coordenador transacional mantém snapshots imutáveis de todos os stores administrativos. Somente
escritores administrativos disputam o lock de escrita, enquanto as leituras do hot path continuam sem bloqueios (lock-free).
O commit publica o snapshot e falhas descartam o estado intermediário da transação (incluindo índices, pedidos, pointer,
auditoria, idempotência e outbox). Outbox cheia rejeita a operação em vez de descartar invalidações pendentes.

##### Dono da reserva de idempotência

O método `reserve` retorna um token criptograficamente seguro e aleatório. O método `complete` exige token, operação e
fingerprint idênticos, estado `IN_FLIGHT` e prazo de vigência válido, participando do mesmo commit da mutação de negócio.
O método `release` apenas remove a reserva se o token informado for o dono legítimo. Trabalhadores atrasados não conseguem
fechar nem remover reservas assumidas por requisições sucessoras. No MongoDB, o documento registra `owner` e `_v: 2` com
atualizações atômicas condicionais.

##### Limites antes da desserialização

O filtro `AdminRequestLimitFilter` inspeciona os cabeçalhos de ator antes da leitura do corpo da requisição, limita o
consumo de bytes a `sdui.admin-max-body-bytes` (1 MiB padrão) e controla a concorrência via semáforo
(`sdui.admin-max-concurrent-requests`, padrão 8). Corpos excedentes retornam HTTP 413 (inclusive em transferências
chunked); saturação do semáforo resulta em HTTP 503 com `Retry-After` aleatorizado.

O mapper HTTP restringe a leitura a 50.000 tokens e profundidade máxima de 64 níveis antes de materializar nós de props,
utilizando `StreamReadConstraints` do Jackson. O limite de domínio para aninhamento de props permanece em 32. Payloads que
ultrapassam esses limites são rejeitados na camada de transporte.

##### Schema, fallback e índice Redis v2

A negociação HTTP aceita exclusivamente o schema canônico da allowlist (`3`); overflows, formatos legados ou aliases
(como `03`) retornam HTTP 400. O contexto de cliente não mascara erros de parsing com valores default. O fallback de
last good valida estritamente a compatibilidade de schema, surface, plataforma e canal. A validação de targeting utiliza
limites e transições reais da matriz de capabilities, sem extrapolações artificiais.

O índice Redis adota a estrutura `sdui:treeidx:v2:<surface>:<platform>:<channel>`, modelada como ZSET com score baseado
no timestamp de expiração de cada árvore. Scripts Lua realizam atomicamente a poda de membros expirados, conferem o teto
de capacidade (`sdui.tree-cache-max-entries` por escopo) e persistem a entrada de cache. Atingido o teto, a gravação de
novas entradas é descartada com emissão de `cache.write.skipped`, enquanto renovações de chaves existentes são preservadas.
A invalidação total de escopo é executada diretamente no Redis via script Lua sem transferir coleções volumosas à JVM.

#### Alternativas descartadas

- **Conferir apenas status e identidade:** Permitiria a publicação de conteúdo arbitrariamente alterado após a revisão.
- **Reordenar escritas sem transação:** Reduz janelas de inconsistência, mas não fornece garantia de rollback em falhas.
- **Excluir reserva de idempotência apenas pela chave:** Permitiria que workers lentos apagassem reservas renovadas de
  sucessores.
- **TTL global em SET de chaves de árvore:** Não realizaria a poda de membros obsoletos sob fluxo contínuo de novas gravações.
- **Validação de JSON após o binding:** Não preveniria exaustão de memória ou ataques de profundidade excessiva durante
  a fase de parsing.

#### Consequências e atualização operacional

1. **Procedimento de Atualização:** Antes de aplicar a atualização, suspender mutações administrativas, aguardar a
   conclusão de operações em voo e encerrar todas as instâncias com versões antigas. Não executar pods antigos e novos
   simultaneamente, pois binários legados não validam hash nem token de posse. O downgrade restaura falhas de integridade
   e não é suportado.
2. **Pedidos de Publicação Pendentes:** Pedidos em status `OPEN` criados sem `reviewedContentHash` devem ser cancelados e
   reabertos sob nova `Idempotency-Key` para nova revisão checker. Pedidos já concluídos permanecem legíveis.
3. **Transição de Caches no Redis:** O índice legado do tipo SET deixa de receber gravações e expira naturalmente pelo
   TTL preexistente. Não é necessário realizar flush global no Redis.
4. **Governança de Skeletons:** Registros que porventura apontem para revisões de skeleton inexistentes devem ser
   regularizados via governança; o runtime não realiza substituição silenciosa por versões default.
5. **Ajuste de Clientes Administrativos:** Ferramentas e scripts de esteira devem considerar os novos limites de tamanho
   de payload (HTTP 413), validação estrita de headers (HTTP 400) e limites de concorrência (HTTP 503).

#### Verificação

Fontes de teste escritas: `AdminRequestLimitFilterTest`, `HttpJsonLimitsTest`, `SemVerAndNegotiateTest`, `SpecValidatorTest`,
`AdminIdempotencyTest`, `ComposeResilienceTest`, `DefaultFallbackCoordinatorTest`, `InMemoryStoresBehaviorTest`,
`MongoPersistenceIT` e `RedisCachesIT`.

Neste ciclo foram realizadas revisões estáticas profundas de código, dependências e assinaturas. Em conformidade com o
AGENTS.md, **nenhuma compilação ou execução de suíte de testes foi disparada nesta etapa**. Testes de durabilidade real
dependem da execução assistida do roteiro em [`runbooks/persistencia-mongodb-redis.md`](runbooks/persistencia-mongodb-redis.md).

---

### Otimizações e Medições de Performance (Ciclo 2026-09-23)

O ciclo contemplou a resolução e medição dos achados identificados na análise estática de performance:

1. **Seleção Otimizada de Specs:** Adoção de índice de revisões publicadas no pointer reduzindo o custo de seleção em até
   ~100× em cenários com 10.000 revisões registradas.
2. **Hidratação Síncrona Pass-through:** Eliminação de despacho assíncrono para seções sem I/O, acelerando composições
   em cache miss em cerca de ~8×.
3. **Poda e Tetos de Memória:** Teto rígido em caches de árvore e mapas residentes para blindagem contra vazamento de
   memória.
4. **Fechamento de Cardinalidade em Métricas:** Restrição rigorosa de tags no Micrometer para evitar explosão de séries
   temporais no Prometheus.
5. **Paginação Administrativa:** Listagens de specs, pedidos e auditoria com parâmetros `offset` e `limit` limitados a 500.

Relatório detalhado de telemetria, medições antes/depois e experimentos descartados em
[`performance/medicoes-2026-09-23.md`](performance/medicoes-2026-09-23.md).

---

## 10. Registro Consolidado de Histórias do MVP (H00–H18)

Todas as 19 histórias de desenvolvimento do MVP do `ms-sdui-composer` foram **concluídas com sucesso**, implementadas no
código de produção e validadas por suíte de testes automatizados com **Quality Gate APROVADO (PASS)**.

Os arquivos individuais de tarefa (`H00-*.md` a `H18-*.md`) foram aposentados após a entrega integral do código
produtivo e a homologação dos critérios de aceite.

### 📋 Matriz Consolidada de Entregas do MVP

| História | Domínio / Título                  | Escopo Entregue no Código                                                          | Módulo Principal            | Testes / Cobertura                                     |
|:--------:|:----------------------------------|:-----------------------------------------------------------------------------------|:----------------------------|:-------------------------------------------------------|
| **H00**  | Contrato e Fixture                | DTOs canônicos, Jackson 3 estrito, round-trip JSON, fixture `home.default`         | `sdui-contract`             | `HomeFixtureRoundTripTest`, `NoVisualAttributesTest`   |
| **H01**  | Runtime HTTP e Negotiate          | 6 cabeçalhos obrigatórios, SemVer ordinal (`major.minor.patch`), `Negotiate.kt`    | `sdui-core`, `sdui-app`     | `NegotiateTest`, `HomeControllerWebTest`               |
| **H02**  | Modelo e Índices                  | Entidades de domínio: `Spec`, `Skeleton`, `Pointer`, `Targeting`, `SlotDefinition` | `sdui-core`                 | `SpecTest`, `TargetingTest`                            |
| **H03**  | Catálogo e Seed iOS               | 7 tipos canônicos em `@1`, skeleton `home.default`, seed in-memory iOS             | `sdui-core`, `sdui-app`     | `MvpCatalogTest`, `HomeSeedTest`                       |
| **H04**  | Select e Filter                   | Seleção determinística por faixa/canal e filtro estável de capabilities            | `sdui-core`                 | `SelectTest`, `FilterTest`                             |
| **H05**  | Hydrate e Envelope                | Fan-out assíncrono em Virtual Threads com Semaphore, montagem do envelope          | `sdui-app`                  | `HydrationCoordinatorTest`, `ComposeScreenServiceTest` |
| **H06**  | HTTP Condicional e Rate Limit     | ETag determinístico `W/"..."`, HTTP 304 Not Modified, Token Bucket por cliente     | `sdui-core`, `sdui-app`     | `TokenBucketRateLimiterTest`, `ConditionalRequestTest` |
| **H07**  | Concorrência e Fallback           | Singleflight concorrente, lastgood em cache, escada de fallback (ADR-007)          | `sdui-app`                  | `SingleflightTest`, `FallbackLadderTest`               |
| **H08**  | Validação de Spec e Diff          | Validação de integridade, checksum sha256, detecção de mudanças estruturais        | `sdui-core`                 | `SpecValidatorTest`, `DiffEngineTest`                  |
| **H09**  | Maker-Checker e Auditoria         | Fluxo de publicação com segregação de funções Maker/Checker, log append-only       | `sdui-app`                  | `PublishServiceTest`, `AuditLogTest`                   |
| **H10**  | Rollback por Pointer              | Reversão atômica de ponteiros de surface com suporte a `Idempotency-Key`           | `sdui-app`                  | `PointerRollbackTest`, `IdempotencyStoreTest`          |
| **H11**  | Observabilidade e SLO             | Métricas Micrometer para composição, fan-out, cache, fallback e SLOs de latência   | `sdui-app`                  | `MetricsRecorderTest`, `SloObservabilityTest`          |
| **H12**  | Validação e Gates                 | Regras ArchUnit de pureza de camadas, zero dependências proibidas, CI checks       | `sdui-integration-test`     | `ArchUnitArchitectureTest`, `CleanArchitectureTest`    |
| **H13**  | Canary iOS e Operação             | Roteamento dinâmico de canal Canary baseado na build iOS do cliente                | `sdui-app`                  | `CanaryRoutingTest`, `IosCanaryWebTest`                |
| **H14**  | Contrato e Isolamento Android     | Isolamento estrito de runtime entre iOS e Android (chaves e stores segregados)     | `sdui-contract`, `sdui-app` | `PlatformIsolationTest`                                |
| **H15**  | Spec e Targeting Android          | Resolução independente de specs, pointers e faixas de versão para Android          | `sdui-core`, `sdui-app`     | `AndroidTargetingTest`                                 |
| **H16**  | Matriz de Compatibilidade Android | Capabilities nativas de Android e suporte a renderers de Jetpack Compose           | `sdui-core`                 | `CapabilityMatrixTest`                                 |
| **H17**  | Governança Android                | Publicação, aprovação e rollback de ponteiros dedicados para plataforma Android    | `sdui-app`                  | `AndroidGovernanceTest`                                |
| **H18**  | Canary e Promoção Android         | Estratégia de Canary com builds Android e promoção atômica para Stable             | `sdui-app`                  | `AndroidCanaryPromotionTest`                           |

### 🏛️ Transição do Backlog para Arquitetura Viva

Com o encerramento do MVP:

1. **Código e Testes como Especificação Viva:** Qualquer comportamento funcional ou restrição de negócio vive em
   `src/main/kotlin` e é garantido por testes em `src/test/kotlin`.
2. **Decisões Estruturais em ADRs:** Novas evoluções ou mudanças de arquitetura são governadas via ADRs em [
   `docs/adr/`](adr/README.md).
3. **Procedimentos de Contingência em Runbooks:** Rollbacks, tolerância a falhas e contratos de retry móvel residem em [
   `docs/runbooks/`](runbooks/README.md).

