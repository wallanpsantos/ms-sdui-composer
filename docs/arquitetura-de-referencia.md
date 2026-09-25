# Arquitetura Canônica de Referência — ms-sdui-composer

**ms-sdui-composer** = o serviço que, a cada request, compõe a árvore de UI da surface a partir de uma spec versionada,
do contexto do cliente e das capabilities declaradas, devolvendo um envelope REST/JSON pronto e seguro para aplicações
móveis nativas (**iOS** e **Android**).

Este documento constitui a **arquitetura de referência canônica** e a memória técnica consolidada do serviço. O MVP
(`H00`–`H18`) e os ciclos subsequentes de governança, resiliência, superfícies (`home` e `catalog`), persistência opt-in
e limites de entrada encontram-se integralmente descritos neste documento.

---

## Baseline Tecnológica e Padrões de Engenharia

- **Linguagem:** Kotlin 2.4.20 (`allWarningsAsErrors = true`).
- **JVM / Plataforma:** Java 25 LTS via Gradle toolchain (`jvmToolchain(25)`).
- **Framework:** Spring Boot 4.1.1 (Spring Framework 7.0.9 gerenciado pelo BOM oficial).
- **Build:** Gradle 9.7.1 com Kotlin DSL, Version Catalog (`gradle/libs.versions.toml`) e Convention Plugins
  (`build-logic/`).
- **JSON:** Jackson 3 (`tools.jackson.core:jackson-databind` 3.1.5, `tools.jackson.module:jackson-module-kotlin` 3.1.5,
  `com.fasterxml.jackson.core:jackson-annotations` 2.21).
- **Concorrência:** Spring MVC sobre **Virtual Threads Java 25** (`spring.threads.virtual.enabled: true`). Proibido o
  uso de Coroutines (`suspend fun`), WebFlux ou bibliotecas reativas (ADR-012).
- **Persistência & Cache:** Padrão em memória (heap efêmero para instância única). Modo persistente opt-in (ADR-021) com
  MongoDB 8.3+ (em replica set `rs0` como fonte da verdade de governança) e Redis (cache e fallback de árvores
  pré-compostas).
- **Governança Arquitetural:** ArchUnit 1.5.0 garantindo isolamento estrito entre camadas e integridade de dependências.
- **Regra de Dependências Inegociável:** Versões gerenciadas pelo Spring Boot NUNCA são fixadas no `libs.versions.toml`
  nem nos arquivos de build. Proibido o plugin `dependency-management`.

---

## 1. Decisão Arquitetural e Princípios

O serviço é estruturado como um **build Gradle multi-projeto** (Kotlin DSL), com `settings.gradle.kts` na raiz, version
catalog em `gradle/libs.versions.toml` e convention plugins em `build-logic/`. O único artefato executável é o
`sdui-bootstrap`; os demais produzem JARs internos.

Toda a produção é escrita em **Kotlin** (`src/main/kotlin`), e todos os testes também (`src/test/kotlin`). Não há fontes
Java.

A organização segue uma **Clean Architecture pragmática**, com fronteiras em dois níveis:

| Fronteira                                  | Mecanismo                             | Por quê                                                     |
|--------------------------------------------|---------------------------------------|-------------------------------------------------------------|
| Pureza do domínio (`sdui-core`)            | Módulo Gradle + `verifyPureClasspath` | É onde Spring e infraestrutura vazam com mais facilidade    |
| Contrato público (`sdui-contract`)         | Módulo Gradle                         | Única fronteira que outro processo ou cliente pode consumir |
| Executável (`sdui-bootstrap`)              | Módulo Gradle                         | Só um lugar tem `@SpringBootApplication` e wiring final     |
| orchestrator × adapters × api (`sdui-app`) | Pacotes + ArchUnit                    | Isolamento entre casos de uso, adaptadores e borda HTTP     |

### Papel das Camadas Lógicas

- **`core` (`sdui-core`):** Regras puras de domínio, tipos, invariantes, políticas de negociação, seleção, omissão,
  validação de catálogo e skeleton; zero dependências externas (apenas JDK 25 e stdlib Kotlin).
- **`orchestrator` (em `sdui-app`):** Casos de uso de composição e administração, portas de entrada/saída, SPI de
  hidratação e singleflight; Kotlin puro sem anotações Spring.
- **`contract` (`sdui-contract`):** DTOs do contrato REST/JSON, envelope público e catálogo canônico do SDUI; depende
  estritamente do Jackson 3.
- **`adapters` (em `sdui-app`):** Adaptadores de persistência em memória, MongoDB, Redis, log estruturado e métricas
  Micrometer.
- **`api` (em `sdui-app`):** Controllers HTTP/MVC, filtros de entrada (`CorrelationIdFilter`,
  `AdminRequestLimitFilter`), tratamento de exceções e mapeamento para DTOs.
- **`bootstrap` (`sdui-bootstrap`):** Ponto de entrada executável Spring Boot, inicialização de beans, configuração de
  profiles e wiring de produção.

---

## 2. Estrutura de Módulos

```text
ms-sdui-composer/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── README.md
├── gradlew
├── gradlew.bat
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
│
├── build-logic/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       ├── sdui.kotlin-base.gradle.kts
│       ├── sdui.kotlin-library.gradle.kts
│       ├── sdui.spring-library.gradle.kts
│       └── sdui.spring-app.gradle.kts
│
├── sdui-contract/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/br/com/empresa/sdui/contract/
│       └── test/kotlin/br/com/empresa/sdui/contract/
│
├── sdui-core/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/br/com/empresa/sdui/core/
│       └── test/kotlin/br/com/empresa/sdui/core/
│
├── sdui-app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/br/com/empresa/sdui/
│       │   ├── orchestrator/
│       │   ├── adapters/
│       │   │   ├── memory/
│       │   │   ├── mongo/
│       │   │   ├── redis/
│       │   │   ├── json/
│       │   │   ├── seed/
│       │   │   └── observability/
│       │   └── api/
│       └── test/kotlin/br/com/empresa/sdui/
│
├── sdui-bootstrap/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/br/com/empresa/sdui/bootstrap/
│       ├── main/resources/
│       │   └── application.yaml
│       └── test/kotlin/br/com/empresa/sdui/bootstrap/
│
└── sdui-integration-test/
    ├── build.gradle.kts
    └── src/test/kotlin/br/com/empresa/sdui/it/
```

---

## 3. Grafo de Dependências e Convention Plugins

```text
                    +----------------------+
                    |    sdui-bootstrap    |
                    | Spring Boot runnable |
                    +----------+-----------+
                               |
                    +----------v-----------------------------+
                    |               sdui-app                 |
                    |  api ─────────► orchestrator ◄── adapters
                    |   │                  │                 |
                    +---│------------------│-----------------+
                        │                  │
                +--------v-------+  +-------v--------+
                | sdui-contract  |  |   sdui-core    |
                | DTOs públicos  |  | domínio puro   |
                +----------------+  +----------------+
```

### Declaração de Dependências no Gradle

| Módulo                  | Dependências de Projeto                                                   | Observação                                           |
|-------------------------|---------------------------------------------------------------------------|------------------------------------------------------|
| `sdui-core`             | nenhuma                                                                   | stdlib Kotlin apenas; `verifyPureClasspath` ativo    |
| `sdui-contract`         | nenhuma                                                                   | Jackson 3 apenas; `verifyPureClasspath` ativo        |
| `sdui-app`              | `api(project(":sdui-core"))`, `implementation(project(":sdui-contract"))` | Portas expõem tipos do core; implementa contrato DTO |
| `sdui-bootstrap`        | `implementation(project(":sdui-app"))`                                    | Recebe core transitivamente; monta os beans Spring   |
| `sdui-integration-test` | `testImplementation` de todos os módulos + ArchUnit                       | Não possui código de produção (`main`)               |

---

## 4. Modelo de Clean Architecture e Domínio

### 4.1 `sdui-core`: Domínio Puro

- **`context/`:** Tipos imutáveis do cliente: `ClientPlatform`, `ClientVersion`, `ClientBuild`, `OsVersion`,
  `UiSchemaVersion`, `Channel`, `Capability`. Parsing e validação de SemVer ordinal com proteção estrita contra overflow
  (`.toIntOrNull() ?: return null`).
- **`model/`:** Entidades centrais: `Surfaces`, `SurfaceDefinition`, `SlotRule`, `Skeleton`, `Slot`, `Spec`,
  `SpecRevisionId`, `Section`, `Action`, `Pointer`, `Targeting`, `HydratedScreen`.
- **`policy/`:** Políticas de decisão: `SpecSelectionPolicy`, `CapabilityCompatibilityPolicy`, `OmissionPolicy`,
  `FallbackPolicy`.
- **`catalog/`:** Catálogo fechado de componentes homologados (`MvpCatalog`, `ComponentContracts`), regras de slots
  permitidos (`allowedLayouts`) e chaves restritas (`LOWER_VISUAL_KEYS`, `LOWER_PII_KEYS`).

### 4.2 `sdui-orchestrator`: Casos de Uso e Portas

- **`port/inbound/`:** Contratos de entrada: `ComposeScreenUseCase`, `PublishSpecUseCase`, `RollbackPointerUseCase`,
  `AuditQueryUseCase`.
- **`port/outbound/`:** Contratos de persistência e coordenação: `SpecStore`, `PointerStore`, `HydratedScreenCache`,
  `LastGoodScreenStore`, `ProjectionStore`, `ComposeSingleflight`, `AuditLogStore`, `IdempotencyStore`,
  `TransactionalUnitOfWork`.
- **`compose/`:** Pipeline de composição: `ComposeScreenService`, `ContextValidator`, `Select`, `Filter`,
  `HydrationCoordinator`, `FallbackCoordinator`.
- **`hydration/`:** SPI de hidratação: `SectionHydrator`, `HydrationContext`, `HydrationResult`. Hidratadores declaram
  `performsIo`; se `false`, rodam na thread da requisição sem overhead de fan-out.

### 4.3 `sdui-adapters`: Infraestrutura

- **`memory/`:** Implementações concorrentes em memória: `InMemoryGovernance` (CAS de pointer, store de rascunhos,
  outbox de invalidação), `InMemoryHydratedScreenCache` (teto estrito via compare-and-set com contador `occupiedSlots`),
  `InMemoryLastGoodScreenStore`, `TokenBucketRateLimiter` (limitação por chave de coorte).
- **`mongo/`:** Adaptadores duráveis MongoDB 8.3+ (opt-in ADR-021) com transações de replica set, clientes criados pelo
  serviço e autoconfigurações Spring excluídas.
- **`redis/`:** Adaptadores de cache Redis (opt-in ADR-021) com chaves sem PII, índices Lua e invalidação via lápide
  versionada.
- **`observability/`:** `CorrelationIdFilter`, `MdcPropagatingExecutor`, registradores Micrometer e gauges USE.

### 4.4 `sdui-api`: Borda HTTP

- **`SurfaceController`:** Mapeamentos explícitos literais `GET /v1/surfaces/home` e `GET /v1/surfaces/catalog`.
  Serialização única em `byte[]` diretamente no `ResponseEntity` com `MediaType.APPLICATION_JSON`, gerando ETag
  determinístico e cabeçalho `Vary`.
- **`AdminController`:** Plano administrativo Maker-Checker (`/admin/v1/**`): criação de rascunhos, abertura e aprovação
  de publicações, rollback atômico e consulta de auditoria.
- **Filtros:** `CorrelationIdFilter` (MDC `requestId`) e `AdminRequestLimitFilter` (teto de 1MB de body e 8 mutações
  administrativas simultâneas).

---

## 5. Pipeline de Composição e Surfaces

O `ms-sdui-composer` suporta uma **allowlist finita de surfaces** (`Surfaces.HOME = "home"` e
`Surfaces.CATALOG = "catalog"`, conforme ADR-020). Requisições para superfícies desconhecidas são rejeitadas
imediatamente antes de alocar recursos.

```
[Request HTTP GET /v1/surfaces/{surface}]
                  │
                  ▼
         1. NEGOTIATE ── Validação dos 6 cabeçalhos obrigatórios de negociação.
                  │      Parsing de SemVer ordinal blindado contra overflow.
                  ▼
         2. SELECT    ── Resolução determinística da Spec publicada pelo Pointer,
                  │      canal (stable/canary/internal), plataforma e versão de app.
                  ▼
         3. FILTER    ── Omissão graciosa de seções incompatíveis com capabilities.
                  │      Ordenação estável TimSort O(N log N) preservando os slots.
                  ▼
         4. HYDRATE   ── Hidratação pass-through na thread ou fan-out em Virtual
                  │      Threads com Semaphore, prazo individual e cancelamento ativo.
                  ▼
         5. GUARD /   ── VisualGuard (anti-CSS) e PiiGuard (anti-PII/segredos).
            FALLBACK  ── Se slot portante falhar: Escada de Fallback (Cache -> LastGood -> 503).
                  │
                  ▼
         6. COMPOSE   ── Serialização única JSON para ByteArray, montagem de ETag
                         e emissão de resposta (HTTP 200 OK ou HTTP 304 Not Modified).
```

### Eixos de Compatibilidade

1. **Eixo A (Protocolo / Envelope):** `API-Version: 1` e `UI-Schema-Version: 3`.
2. **Eixo B (Renderização / Capabilities):** Par `type@typeVersion` declarado pelo cliente em `Component-Capabilities`.
   Seções sem capacidade correspondente são omitidas graciosamente (exceto se pertencentes a slots portantes).
3. **Eixo C (Faixa de Aplicativo):** `Client-Platform` (`ios` | `android`), `Client-Version` e `Client-Build`.

---

## 6. Concorrência e Resiliência

### 6.1 Virtual Threads e Fan-out de Hidratação

O serviço roda sobre Spring MVC acoplado ao scheduler de **Virtual Threads do Java 25**
(`spring.threads.virtual.enabled: true`):

- **Proibição de Coroutines / Reatividade (ADR-012):** Nenhuma `suspend fun`, nenhum repositório reativo e nenhum
  WebFlux. O modelo síncrono bloqueante sobre Virtual Threads elimina a complexidade de alternância de contextos e
  pontes `runBlocking`.
- **Cancelamento Ativo no Fan-out (`HydrationCoordinator`):** Quando o prazo individual de uma seção expira
  (`orTimeout`), a Virtual Thread responsável (`taskThread`) recebe `interrupt()` imediatamente. Isso cancela chamadas
  de rede/I/O bloqueadas e garante que o semáforo de bulkhead (`Semaphore`) seja liberado no bloco `finally`, evitando
  vazamento cascateado de permits.
- **Isolamento de Observabilidade:** Handlers assíncronos protegem o registro de telemetria com `runCatching { }`.
  Falhas de métricas nunca provocam falha de composição.

### 6.2 Singleflight Concorrente (`ComposeSingleflight`)

Para evitar avalanche no backend (*thundering herd*) durante recomposições de cache miss:

- O primeiro requisitante assume a liderança e inicia a computação da árvore.
- Requisitantes subsequentes para a mesma chave tornam-se *waiters*.
- **Comportamento sob Timeout do Waiter:** Waiters que atingem o tempo limite local de espera **nunca cancelam** o
  future do líder compartilhado (`existing.cancel(true)` é proibido). O waiter recebe `WaitTimeout()` e recorre à escada
  de fallback, permitindo que o líder conclua a composição e popule o cache para a frota.
- **Reidratação de Contexto:** Ao receber o resultado do líder, o waiter tem seus campos contextuais (`client`,
  `locale`, `generatedAt`) reidratados com os seus próprios dados de requisição (`withRequester`).

### 6.3 Orçamento de Tempo (`TimeBudget`, ADR-014)

Toda requisição recebe um orçamento global (`sdui.request-budget-ms = 1000ms`):

- O orçamento limita apenas os **tempos de espera** (espera por permissão no bulkhead e espera pelo líder no
  singleflight).
- **Trabalho em andamento nunca é abortado** por estouro de prazo. Quando o prazo expira durante uma espera, emite-se o
  contador `compose.deadline.exceeded` e a requisição prossegue para a escada de fallback.

### 6.4 Escada Determinística de Fallback (ADR-007)

Se ocorrer falha em um slot portante obrigatório (`header` ou `accounts` na Home), ausência de spec compatível ou
indisponibilidade de stores:

1. **`200 OK` (Composição Regular):** Árvore completa montada com sucesso.
2. **`200 OK` com Omissão Graciosa:** Seções opcionais degradadas são omitidas do envelope.
3. **`200 OK` com `fallback: true` (Last Good):** Serve a última árvore íntegra cacheada para a revisão e plataforma,
   desde que sua idade não ultrapasse `sdui.max-fallback-age-seconds` (24h).
4. **`503 Service Unavailable`:** Caso não haja last good válido, retorna corpo JSON estruturado (`COMPOSE_UNAVAILABLE`)
   acompanhado do cabeçalho `Retry-After`.

- **Jitter Pseudoaleatório:** O valor de `Retry-After` aplica variação de ±40% sobre a base
  (`sdui.retry-after-seconds = 5s`), evitando que as coortes de clientes tentem reconectar simultaneamente.

---

## 7. Governança Administrativa e Segurança

### 7.1 Governança Maker-Checker Estrita (ADR-008)

Toda publicação de especificação nos canais públicos (`canary` e `stable`) obedece à segregação de funções:

- `Actor-Role: MAKER`: Cria rascunhos de spec e abre solicitações de publicação.
- `Actor-Role: CHECKER`: Aprova ou rejeita solicitações.
- **Regra Inegociável:** O `MAKER` que solicitou a publicação de uma revisão está estritamente proibido de aprová-la
  (`maker != checker`). Tentativas resultam em `HTTP 403 Forbidden` (`MAKER_CANNOT_APPROVE_OWN_REQUEST`).
- O canal `internal` permite autoaprovação pelo criador exclusivamente para viabilizar testes rápidos de
  desenvolvimento.

### 7.2 Idempotência, CAS e Limites de Entrada (ADR-022)

- **Idempotência por Reserva:** Requisições administrativas com `Idempotency-Key` utilizam reserva atômica prévia
  (`reserve`). Retentativas com os mesmos parâmetros recebem a resposta original; tentativas de reutilizar a chave com
  payload ou ação diferente resultam em `HTTP 422 Unprocessable Entity`. Reservas ociosas expiram em 300 segundos.
- **Ponteiro por Compare-And-Set (CAS):** A atualização do ponteiro ativo de uma surface utiliza CAS atômico
  (`compareAndSet(versaoLida, novaVersao)`). Concorrência conflitante retorna imediatamente `HTTP 409 Conflict`.
- **Limites de Entrada:** Filtro administrativo (`AdminRequestLimitFilter`) rejeita payloads acima de 1 MB
  (`HTTP 413 Payload Too Large`) antes da desserialização Jackson e limita a concorrência administrativa simultânea a 8
  requisições (`HTTP 503 Service Unavailable`).

### 7.3 Blindagem de Segurança e Proteções Estruturais

1. **Proibição de CSS e Atributos Visuais (`VisualGuard`, ADR-010):** Chaves como `color`, `background`, `font`,
   `padding`, `margin`, `radius`, `width`, `height`, `variant`, `style` são proibidas em qualquer propriedade de
   section. Decisões visuais pertencem estritamente ao Design System nativo nos clientes.
2. **Zero PII no Hot Path (`PiiGuard`, ADR-015):** Proibição absoluta de dados sensíveis e regulados (`cpf`, `token`,
   `password`, `pin`, `otp`, `passcode`, `cvv`) em propriedades de UI, cache, logs e métricas.
3. **Bloqueio de Telas Hostis (ADR-015):** Superfícies transacionais reguladas (autenticação, login, onboarding/KYC e
   checkout de compras) são expressamente proibidas no SDUI e devem ser 100% nativas.
4. **Conjunto Fechado de Actions (ADR-011):** Apenas intenções auditáveis são aceitas: `navigate`, `open_bottom_sheet`,
   `track` e `noop`. Ações que induzam mutações cegas de rede (`callApi`) são rejeitadas.

---

## 8. Persistência e Cache

O `ms-sdui-composer` desacopla completamente os casos de uso da tecnologia de armazenamento através de portas
(`SpecStore`, `PointerStore`, `HydratedScreenCache`, `LastGoodScreenStore`, `AuditLogStore`, `IdempotencyStore`).

### 8.1 Modo In-Memory (Padrão Operacional Vigente)

Configurado via `sdui.persistence.store = memory` e `sdui.persistence.cache = memory`:

- Todos os stores e caches residem no heap da JVM, suportados por estruturas concorrentes thread-safe
  (`ConcurrentHashMap`).
- **Limitação Operacional:** O serviço opera em **instância única**. O estado não sobrevive a reinicializações do
  processo. Escalar instâncias horizontalmente em modo in-memory provoca divergência de ponteiros e inconsistência de
  governança.
- **Teto Rígido de Memória:** O cache de telas (`InMemoryHydratedScreenCache`) utiliza contador atômico
  (`occupiedSlots`) e poda para metade ao atingir o teto de 10.000 entradas, prevenindo exaustão de heap sob carga.

### 8.2 Modo Persistente Opt-in (MongoDB 8.3+ e Redis, ADR-021)

Configurado via `sdui.persistence.store = mongo` e `sdui.persistence.cache = redis`:

- **MongoDB 8.3+ (Autoridade de Governança):** Executado em replica set (`rs0`) para suporte obrigatório a transações
  multi-documento (`MongoTransactionalUnitOfWork`). Specs e ponteiros são armazenados com schema versionado (`_v`).
- **Redis 8 (Cache e Last Good):** Cache de specs com TTL de 10 minutos, árvores pré-compostas com TTL de 60 segundos e
  last good com TTL de 7 dias. Nenhuma chave contém identificadores de usuário (`!RedisKeys.containsUserId(key)`).
- **Invalidação via Outbox e Lápide Versionada:** Mutações de ponteiro registram eventos de invalidação no outbox do
  MongoDB dentro da mesma transação. O `InvalidationRelay` processa as pendências e emite lápides versionadas no Redis,
  garantindo que nenhum last good com revisão inferior à lápide seja servido.

---

## 9. Observabilidade e Instrumentação

A arquitetura de observabilidade assegura diagnóstico determinístico sem degradação do hot path:

- **Structured Logging (ECS JSON):** O console emite logs estruturados no padrão Elastic Common Schema (ECS),
  viabilizando agregação e busca estruturada em plataformas centrais.
- **Correlation ID e MDC no SLF4J:** O `CorrelationIdFilter` captura `X-Request-Id` ou gera UUID v4, propagando-o no MDC
  como `requestId`. O controller carimba o `entryPoint` (`home`, `catalog`, `admin`, `actuator`, `demo`).
- **Propagação em Virtual Threads (`MdcPropagatingExecutor`):** Virtual threads assíncronas no fan-out de hidratação
  herdam o contexto MDC da requisição original via executor decorador em `adapters`, mantendo a rastreabilidade ponta a
  ponta.
- **Preservação de Cabeçalhos HTTP:** O servidor nunca emite cabeçalhos customizados com prefixo `X-` na resposta de
  composition da Home (RFC 6648).
- **Métricas Micrometer e Histogram Buckets:** Timers críticos (`compose.duration`, `section.hydrate.ms`, `mapping.ms`,
  `serialize.ms`) possuem `percentiles-histogram: true` no `application.yaml`, permitindo monitoramento de P95/P99 sem
  interpolação de tags arbitrárias.
- **USE Gauges:** `rate_limiter.resident_keys` e `compose.bulkhead.available_permits` monitoram a saturação de recursos
  do processo.
- **Defesa de Cardinalidade:** O `CardinalityGuardMeterFilter` bloqueia a criação de métricas que excedam
  `sdui.metrics-max-tag-values = 64` valores distintos por tag.

---

## 10. Estratégia de Testes e Regras ArchUnit

A integridade do sistema é blindada por níveis claros de teste automatizado:

1. **Testes de Contrato (`sdui-contract`):** Validação de serialização/desserialização Jackson 3 estrita, ausência de
   campos visuais e identidade de round-trip JSON sobre fixtures canônicas.
2. **Testes Unitários de Domínio (`sdui-core`):** Validação pura de SemVer ordinal, matriz de capabilities, ordenação
   estável do Filter, validação de skeletons e slots portantes.
3. **Testes de Casos de Uso (`sdui-app`):** Cobertura de Singleflight concorrente, escada de fallback, transações
   programáticas, isolamento de plataformas (iOS vs Android) e rate limiting.
4. **Testes de Arquitetura ArchUnit (`sdui-integration-test`):** Regras arquiteturais executadas a cada build:

- `core` e `contract` não dependem de Spring, bancos, HTTP ou frameworks.
- `orchestrator` não acessa `contract`, adapters nem anotações Spring.
- Proibição absoluta de `@Transactional` em qualquer classe do projeto (transação exclusiva via
  `TransactionalUnitOfWork`).
- Proibição de Coroutines (`kotlinx.coroutines..`, `kotlin.coroutines..`) e Reatividade (WebFlux, Reactor, RxJava).
- Ausência de ciclos entre pacotes (`slices().matching("br.com.empresa.sdui.(**)")`).
- Regras configuradas com `failOnEmptyShould` estrito (proibido `allowEmptyShould(true)`).

---

## 11. Catálogo Consolidado de Decisões Arquiteturais (ADR-001 a ADR-022)

### 📋 Matriz de Decisões

|     ADR     | Título                                         |    Status    | Escopo Principal                                                                              |
|:-----------:|:-----------------------------------------------|:------------:|:----------------------------------------------------------------------------------------------|
| **ADR-001** | Topologia Multi-Módulo                         |   `ACEITO`   | Quatro módulos de produção (`bootstrap`, `app`, `contract`, `core`) e um de integração.       |
| **ADR-002** | Contexto de Trace                              |   `ACEITO`   | `ComposeTraceContext` isolado de `core` e `orchestrator`, sincronizado no MDC.                |
| **ADR-003** | `@Transactional` em Publish                    | `SUPERSEDED` | Supersedido pelo ADR-013 (transações programáticas via porta).                                |
| **ADR-004** | Adoção de Screen e Fim de Fragment             |   `ACEITO`   | Eliminação de `Fragment`, `FragmentResolver` e endpoints parciais no MVP.                     |
| **ADR-005** | Jackson 3 Estrito                              |   `ACEITO`   | Adoção de Jackson 3 (`tools.jackson.core:jackson-databind`) sem módulos reativos.             |
| **ADR-006** | Invalidação de Cache Redis                     |   `ACEITO`   | Invalidação seletiva desacoplada e atômica por pointer.                                       |
| **ADR-007** | Escada Determinística de Fallback              |   `ACEITO`   | 200 Regular -> 200 Omissão -> 200 LastGood -> 503 com Retry-After jitter ±40%.                |
| **ADR-008** | Governança Maker-Checker                       |   `ACEITO`   | Segregação estrita: criador de spec não aprova publicação em canais públicos.                 |
| **ADR-009** | Validação de Slots Portantes                   |   `ACEITO`   | Slots `header` e `accounts` obrigatórios na composição e validação da Home.                   |
| **ADR-010** | Rejeição de Tipos Genéricos e CSS              |   `ACEITO`   | Proibição de `row`, `column`, `card` genérico e atributos visuais (`VisualGuard`).            |
| **ADR-011** | Conjunto Fechado de Actions                    |   `ACEITO`   | Apenas quatro intenções permitidas: `navigate`, `open_bottom_sheet`, `track`, `noop`.         |
| **ADR-012** | Concorrência com Virtual Threads               |   `ACEITO`   | Exclusão de coroutines e WebFlux; adoção de Spring MVC sobre Virtual Threads Java 25.         |
| **ADR-013** | Transação Programática de Publish              |   `ACEITO`   | Adoção de `TransactionalUnitOfWork` com `TransactionTemplate` (sem proxies AOP).              |
| **ADR-014** | Política de Resiliência de Integração          |   `ACEITO`   | TimeBudget, bulkhead de leitura, Retry-After com jitter, zero retry no servidor.              |
| **ADR-015** | Escopo SDUI e Telas Hostis                     |   `ACEITO`   | Bloqueio de telas hostis (onboarding, login/pin, checkout); blindagem anti-PII.               |
| **ADR-016** | Rejeição de CMS por Nós                        | `REJEITADO`  | Rejeição de flags por componente e templates desacoplados de nós.                             |
| **ADR-017** | Experimentação por Revisão de Spec             |   `ACEITO`   | Modelagem de braços experimentais no pointer; tráfego dinâmico adiado.                        |
| **ADR-018** | Montagem Variável de Surface                   |   `ACEITO`   | Layouts homologados (`allowedLayouts`) e ordem de slots declarada no `Skeleton`.              |
| **ADR-019** | Remoção de `variant` do Catálogo               |   `ACEITO`   | Eliminação do atributo `variant` e inclusão em chaves restritas de apresentação.              |
| **ADR-020** | Múltiplas Surfaces e Contratos de Componente   |   `ACEITO`   | Allowlist `home`/`catalog`, catálogo fechado em contratos aprovados com capabilities.         |
| **ADR-021** | Persistência MongoDB 8.3+ e Cache Redis        |   `ACEITO`   | MongoDB como autoridade transacional e Redis como cache; modo em memória segue padrão.        |
| **ADR-022** | Integridade da Governança e Limites de Entrada |   `ACEITO`   | CAS de ponteiro, reserva de idempotência, filtro de tamanho de body (1MB) e concorrência (8). |

---

### Detalhamento dos Registros de Decisão Arquitetural

#### ADR-001 — Topologia Multi-Módulo

- **Status:** `ACEITO`
- **Contexto:** Necessidade de garantir que o domínio permaneça isolado de frameworks e que o contrato público seja
  consumível de forma limpa.
- **Decisão:** Divisão em 4 módulos de produção (`sdui-bootstrap`, `sdui-app`, `sdui-contract`, `sdui-core`) e 1 de
  teste (`sdui-integration-test`).
- **Consequências:** Fronteiras físicas no Gradle garantem que `sdui-core` nunca veja Spring ou Jackson; dependências
  proibidas são barradas na compilação.

#### ADR-002 — Contexto de Trace

- **Status:** `ACEITO`
- **Contexto:** Rastreabilidade de requisições através das camadas sem poluir assinaturas de métodos de domínio.
- **Decisão:** Criação do `ComposeTraceContext` acoplado ao MDC do SLF4J, encapsulado em `adapters.logging`, mantendo
  `core` e `orchestrator` puros.
- **Consequências:** Logs estruturados carregam `requestId`, `surface` e `platform` automaticamente em todas as camadas.

#### ADR-003 — `@Transactional` em Publish

- **Status:** `SUPERSEDED` pelo ADR-013
- **Contexto:** Inicialmente previa o uso de `@Transactional` em classes adapter de persistência.
- **Decisão:** Supersedido pelo ADR-013 em favor de transações programáticas puras via porta.

#### ADR-004 — Adoção de Screen e Fim de Fragment

- **Status:** `ACEITO`
- **Contexto:** Propostas iniciais contemplavam endpoints de fragmentos de tela que aumentavam a complexidade de rede e
  geravam N+1 no cliente móvel.
- **Decisão:** Eliminar completamente `Fragment`, `FragmentStore` e endpoints parciais. O serviço entrega exclusivamente
  árvores de tela (`Screen`) compostas e autocontidas.
- **Consequências:** Envelope único e atômico por requisição; menor latência acumulada para clientes móveis.

#### ADR-005 — Jackson 3 Estrito

- **Status:** `ACEITO`
- **Contexto:** Necessidade de serialização de alta performance com a versão mais recente do ecossistema Jackson.
- **Decisão:** Utilizar Jackson 3 (`tools.jackson.core:jackson-databind`) no módulo `sdui-contract`.
- **Consequências:** Tipos imutáveis com deserialização estrita e validação estática de schema.

#### ADR-006 — Invalidação de Cache Redis

- **Status:** `ACEITO`
- **Contexto:** Publicações e rollbacks administrativos exigem purga determinística de árvores obsoletas em cache.
- **Decisão:** Invalidação seletiva baseada na revisão de spec apontada pelo pointer, desacoplada do hot path.
- **Consequências:** Elimina varreduras `KEYS *` bloqueantes e garante atualização rápida das coortes.

#### ADR-007 — Escada Determinística de Fallback

- **Status:** `ACEITO`
- **Contexto:** Falhas em dependências downstream ou slots essenciais não podem resultar em travamento do cliente ou
  HTTP 500.
- **Decisão:** Escada de degradação: 200 Regular -> 200 Omissão -> 200 LastGood (`fallback: true`) -> 503 com
  `Retry-After`.
- **Consequências:** A Home nunca retorna 404 por falta de targeting; clientes recebem resposta previsível e respeitam o
  jitter do retry.

#### ADR-008 — Governança Maker-Checker

- **Status:** `ACEITO`
- **Contexto:** Alterações na árvore de UI impactam a experiência de milhões de usuários e exigem dupla custódia.
- **Decisão:** Maker não pode aprovar a própria publicação em `canary` e `stable`. Rejeição incondicional com HTTP 403.
- **Consequências:** Blindagem contra publicação acidental de especificações com defeito.

#### ADR-009 — Validação de Slots Portantes

- **Status:** `ACEITO`
- **Contexto:** Determinadas áreas da tela são vitais para a navegação e integridade financeira do usuário.
- **Decisão:** Slots `header` e `accounts` são classificados como portantes (`required: true`) na Home. Se falharem na
  hidratação, a seção não pode ser omitida silenciosamente, acionando a escada de fallback.
- **Consequências:** Usuário nunca visualiza uma Home corrompida sem saldo ou dados essenciais.

#### ADR-010 — Rejeição de Tipos Genéricos e CSS

- **Status:** `ACEITO`
- **Contexto:** Tentativas de enviar estilos, cores e layout livre geram retrabalho nos apps e quebram a acessibilidade
  nativa.
- **Decisão:** O servidor é expressamente proibido de enviar chaves visuais (`color`, `padding`, `radius`, `variant`,
  etc.) ou primitivas genéricas (`row`, `column`, `card`).
- **Consequências:** O cliente nativo mantém o controle total sobre o Design System e temas de acessibilidade.

#### ADR-011 — Conjunto Fechado de Actions

- **Status:** `ACEITO`
- **Contexto:** Actions arbitrárias transportam lógica de negócio não versionada para o cliente.
- **Decisão:** Fechar o catálogo de ações em 4 tipos declarativos: `navigate`, `open_bottom_sheet`, `track` e `noop`.
- **Consequências:** Bloqueio de comandos de mutação arbitrária (`callApi`).

#### ADR-012 — Concorrência com Virtual Threads

- **Status:** `ACEITO`
- **Contexto:** Modelos reativos (WebFlux/Reactor) aumentam a complexidade de stack traces, depuração e manutenção.
- **Decisão:** Adotar Spring MVC síncrono sobre Virtual Threads do Java 25 LTS. Proibido coroutines (`suspend fun`).
- **Consequências:** Código imperativo legível, debugging nativo no JDK e alta taxa de transferência com I/O sem
  bloqueio de carrier threads.

#### ADR-013 — Transação Programática de Publish

- **Status:** `ACEITO` (supersede ADR-003)
- **Contexto:** `@Transactional` exige proxies CGLIB/Spring AOP e impede checagem estrita de ArchUnit contra
  dependências de framework em portas.
- **Decisão:** Implementar a porta `TransactionalUnitOfWork` através de `TransactionTemplate` programático.
- **Consequências:** Elimina proxies, classes abertas e permite proibir `@Transactional` em 100% do projeto via
  ArchUnit.

#### ADR-014 — Política de Resiliência de Integração

- **Status:** `ACEITO`
- **Contexto:** Chamadas lentas no fan-out podem saturar o servidor e causar degradação geral.
- **Decisão:** Implementação de `TimeBudget`, bulkhead de leitura dedicado, jitter de ±40% no `Retry-After`, teto de
  idade de last good (24h) e proibição absoluta de retries síncronos no servidor sobre dependências degradadas.
- **Consequências:** Proteção contra esgotamento de threads e prevenção do efeito de manada (*thundering herd*).

#### ADR-015 — Escopo SDUI e Telas Hostis

- **Status:** `ACEITO`
- **Contexto:** Risco de segurança ao tentar implementar fluxos de alta sensibilidade regulatória via SDUI.
- **Decisão:** Proibir formalmente telas de login, digitação de senhas/PIN, biometria facial, onboarding regulado e
  checkout transacional. Adotar `PiiGuard` estrito no hot path.
- **Consequências:** Dados sensíveis nunca circulam pelo motor de composição SDUI.

#### ADR-016 — Rejeição de CMS por Nós

- **Status:** `REJEITADO`
- **Contexto:** Proposta de permitir que editores de conteúdo configurassem nós individuais com flags e regras de
  renderização soltas.
- **Decisão:** Rejeitada formalmente. A composição deve permanecer estruturada em skeletons rígidos e specs auditáveis.
- **Consequências:** Integridade determinística mantida; impossibilidade de quebras acidentais de layout pelo CMS.

#### ADR-017 — Experimentação por Revisão de Spec

- **Status:** `ACEITO`
- **Contexto:** Necessidade futura de testes A/B entre composições distintas de telas.
- **Decisão:** Modelar entidades de experimentação (`ExperimentArm`, `ExperimentConfig`) no ponteiro, associando braços
  a revisões completas de spec, sem intercalar nós dinâmicos em tempo real.
- **Consequências:** Estrutura pronta no domínio sem introduzir complexidade prematura de roteamento estatístico.

#### ADR-018 — Montagem Variável de Surface

- **Status:** `ACEITO`
- **Contexto:** Diferentes segmentos de clientes ou momentos do ciclo de vida exigem ordenações estruturais distintas na
  mesma surface.
- **Decisão:** Skeletons versionados definem slots, ordem e layouts permitidos (`allowedLayouts`). Seed canônico inclui
  `home.default` e `home.cards_first`.
- **Consequências:** Flexibilidade de apresentação sem vazamento de CSS.

#### ADR-019 — Remoção de `variant` do Catálogo

- **Status:** `ACEITO`
- **Contexto:** A propriedade `variant: "compact"` do `shortcut_shelf@1` feria o princípio de ausência de atributos
  visuais.
- **Decisão:** Remover `variant` das props do componente e incluí-la no conjunto restrito `VISUAL_KEYS`. Variações
  visuais devem ser tratadas pelo Design System nativo (`SizeClass`).
- **Consequências:** Conformidade estrita com o princípio de zero CSS no payload.

#### ADR-020 — Múltiplas Surfaces e Contratos de Componente

- **Status:** `ACEITO`
- **Contexto:** Demanda por composições em superfícies não financeiras (ex.: vitrine de catálogo de moda).
- **Decisão:** Allowlist finita de surfaces (`home` e `catalog`), mapeamentos HTTP literais e catálogo aprovado para
  componentes comerciais (`product_collection@1`, `catalog_navigation@1`) e transacionais (`transaction_summary@1`).
  Componentes novos dependem de capability declarada pelo cliente.
- **Consequências:** Extensibilidade controlada sem comprometer a Home financeira legada.

#### ADR-021 — Persistência MongoDB 8.3+ e Cache Redis

- **Status:** `ACEITO`
- **Contexto:** Necessidade de persistência durável e compartilhamento de estado entre pods em produção.
- **Decisão:** Adaptadores opt-in com MongoDB 8.3+ (replica set `rs0` para transações atômicas) e Redis (cache volátil
  com política `volatile-lru`). Invalidação por outbox transacional e lápides versionadas.
- **Consequências:** Operação padrão permanece em memória e em instância única até a homologação operacional com
  infraestrutura dedicada.

#### ADR-022 — Integridade da Governança e Limites de Entrada

- **Status:** `ACEITO`
- **Contexto:** Riscos de concorrência em atualizações de rascunhos, conflitos de ponteiro e ataques de negação de
  serviço por payloads desmedidos.
- **Decisão:** Atualização de ponteiros via CAS com `HTTP 409 Conflict`, idempotência administrativa com expiração e
  verificação de fingerprint, teto estrito de payload (1 MB) via `AdminRequestLimitFilter` e limite de concorrência
  administrativa (máximo 8 requisições simultâneas).
- **Consequências:** Governança administrativa blindada contra colisões concorrentes e sobrecarga de CPU/memória.

---

## 12. Histórico Consolidado de Histórias do MVP (H00–H18)

Todas as 19 histórias de desenvolvimento do MVP do `ms-sdui-composer` foram **concluídas com sucesso**, implementadas no
código de produção e validadas por suíte de testes com **Quality Gate APROVADO (PASS)**.

### Matriz Consolidada de Entregas do MVP

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

---

## Conclusão

A arquitetura do **ms-sdui-composer** estabelece uma fronteira de engenharia pragmática e determinística:

1. **Domínio Puro e Isolado:** O motor de UI é imutável e livre de frameworks no `sdui-core`.
2. **Resiliência Extrema:** Virtual Threads no JDK 25 proporcionam alta densidade de concorrência com cancelamento ativo
   e degradação graciosa em cascata (ADR-007 e ADR-014).
3. **Governança Inegociável:** Segregação de funções Maker-Checker, CAS atômico de ponteiros e blindagem de segurança
   (anti-CSS, anti-PII).
4. **Prontidão Evolutiva:** Capacidade comprovada de operar múltiplas surfaces (`home` e `catalog`) e transitar de
   armazenamento em memória para persistência distribuída (MongoDB 8.3+ e Redis) sem alterar os casos de uso.

---

## Referências

- [Spring Boot 4.1 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot — Testing Spring Boot Applications](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
- [Spring Boot — Kotlin Support](https://docs.spring.io/spring-boot/reference/features/kotlin.html)
- [Spring Framework 7.0 — REST Clients](https://docs.spring.io/spring-framework/reference/7.0/integration/rest-clients.html)
- [Gradle — Sharing build logic with convention plugins](https://docs.gradle.org/current/userguide/sharing_build_logic_between_subprojects.html)
- [Gradle — Version catalogs](https://docs.gradle.org/current/userguide/version_catalogs.html)
- [ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html)
- [JUnit User Guide](https://docs.junit.org/current/user-guide/)
- [MockK](https://mockk.io/)
