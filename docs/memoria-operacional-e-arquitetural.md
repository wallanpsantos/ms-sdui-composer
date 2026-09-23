# MEMÓRIA OPERACIONAL E ARQUITETURAL — MS-SDUI-COMPOSER

**Última Atualização:** 2026-09-23  
**Status do Projeto:** MVP H00–H18 concluído; ADR-014 a ADR-019 consolidados na Seção 8. Em 2026-09-23: achados de
performance tratados com medição, surface `catalog` e contratos novos (ADR-020, `PROPOSTO`) e adapters MongoDB/Redis
(ADR-021, `PROPOSTO`, não homologados) — Seção 9. O PASS histórico não cobre esta entrega; a evidência dela está em
`tasks/todo.md`.

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

1. **Múltiplas surfaces (ADR-020, `PROPOSTO`):** allowlist `home`/`catalog` em `Surfaces`; mapeamento HTTP literal
   por surface; surface propagada a pointer, seleção, cache, singleflight, last good e métricas. Catálogo fechado em
   `ComponentContracts.APPROVED`; `transaction_summary@1`, `catalog_navigation@1` e `product_collection@1` só chegam
   a quem declara a capability. Exemplos e tutorial em `docs/examples/screens` e
   `docs/guia-criacao-telas-componentes.md`.
2. **Persistência (ADR-021, `PROPOSTO`, não homologada):** `sdui.persistence.store=memory|mongo` e
   `sdui.persistence.cache=memory|redis`. Mongo transacional (replica set) para governança e idempotência; Redis para
   caches; outbox de invalidação e lápide do last good pela versão do pointer. Padrão continua em memória e instância
   única até o roteiro de `runbooks/persistencia-mongodb-redis.md` ser executado.
3. **Idempotência:** reserva com fingerprint dos parâmetros (reuso com outro alvo = 422); registro vivo nunca sai
   por pressão; no teto em memória, admissão recusada com 503.
4. **Performance medida:** seleção pelo pointer com índice de publicadas (~100× com 10 mil revisões), hidratação
   pass-through síncrona (~8× no miss), teto estrito no cache de árvore, cardinalidade de métricas fechada, listagens
   paginadas. Números e experimentos rejeitados em `performance/medicoes-2026-09-23.md`.
