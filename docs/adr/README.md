# Registro Histórico e Catálogo de ADRs (ADR-001 a ADR-019)

Este documento centraliza o catálogo completo de **Registros de Decisões Arquiteturais (ADRs)** do `ms-sdui-composer`.

Todas as decisões arquiteturais foram implementadas no código produtivo, cobertas por testes automatizados e
consolidadas na especificação canônica em [
`docs/memoria-operacional-e-arquitetural.md`](../memoria-operacional-e-arquitetural.md). Os arquivos individuais foram
aposentados pós-implementação para manter o repositório limpo e focado no código vivo.

---

## 📋 Matriz Consolidada de Decisões Arquiteturais

|     ADR     | Título / Decisão                      |    Status    | Escopo da Decisão                                                                                                | Implementação no Código                              |
|:-----------:|:--------------------------------------|:------------:|:-----------------------------------------------------------------------------------------------------------------|:-----------------------------------------------------|
| **ADR-001** | Topologia Multi-Módulo                |   `ACEITO`   | Divisão em 4 módulos de produção (`bootstrap`, `app`, `contract`, `core`) e 1 de teste                           | `settings.gradle.kts`, convention plugins            |
| **ADR-002** | Isolamento de Contexto                |   `ACEITO`   | `ComposeTraceContext` isolado de `core` e `orchestrator`                                                         | `br.com.empresa.sdui.adapters.logging`               |
| **ADR-003** | `@Transactional` em Publish           | `SUPERSEDED` | Substituído por transações programáticas (ADR-013)                                                               | Supersedido pelo ADR-013                             |
| **ADR-004** | Adoção de Screen e Fim de Fragment    |   `ACEITO`   | Exclusão de `Fragment`, `FragmentResolver` e endpoints parciais no MVP                                           | Pipeline `Select` -> `Filter` -> `Compose`           |
| **ADR-005** | Jackson 3 Estrito                     |   `ACEITO`   | Adoção de `tools.jackson.core:jackson-databind` sem Spring Cloud ou reativos                                     | `sdui-contract` (DTOs imutáveis)                     |
| **ADR-006** | Invalidação de Cache Redis            |   `ACEITO`   | Invalidação atômica e seletiva por scan desacoplado / in-memory                                                  | `InMemoryStores.kt` / `RedisKeys.kt`                 |
| **ADR-007** | Escada Determinística de Fallback     |   `ACEITO`   | 200 Regular -> 200 Omissão -> 200 LastGood -> 503 com Retry-After                                                | `ComposeScreenService.kt`                            |
| **ADR-008** | Governança Maker-Checker              |   `ACEITO`   | Segregação estrita: criador não aprova própria spec em canais públicos                                           | `PublishService.kt`, `AdminController.kt`            |
| **ADR-009** | Validação de Slots Portantes          |   `ACEITO`   | Slots `header` e `accounts` obrigatórios no publish e na composição                                              | `SpecValidator.kt`, `SkeletonValidator.kt`           |
| **ADR-010** | Rejeição de Tipos Genéricos e CSS     |   `ACEITO`   | Proibição de `row`, `column`, `card` genérico e atributos visuais                                                | `MvpCatalog.kt`, `VisualGuard.kt`                    |
| **ADR-011** | Conjunto Fechado de Actions           |   `ACEITO`   | Apenas intenções declarativas auditáveis: `navigate`, `open_bottom_sheet`, `track`, `noop`                       | `MvpCatalog.ACTIONS`, `ActionResponse.kt`            |
| **ADR-012** | Concorrência com Virtual Threads      |   `ACEITO`   | Exclusão de coroutines e WebFlux; adoção de Spring MVC sobre Virtual Threads Java 25                             | `application.yaml`, `HydrationCoordinator.kt`        |
| **ADR-013** | Transação Programática de Publish     |   `ACEITO`   | Adoção de `TransactionalUnitOfWork` com `TransactionTemplate` (sem proxies AOP)                                  | `TransactionalUnitOfWork.kt`                         |
| **ADR-014** | Política de Resiliência de Integração |   `ACEITO`   | TimeBudget, bulkhead de leitura, Retry-After com jitter ±40%, idade máxima de last good                          | `TimeBudget.kt`, `ComposeScreenService.kt`           |
| **ADR-015** | Escopo SDUI e Telas Hostis            |   `ACEITO`   | Bloqueio de telas hostis (onboarding, login/pin, checkout); anti-PII (`pin`, `otp`, `passcode`) e anti-`callApi` | `MvpCatalog.PII_KEYS`, `PiiGuard.kt`                 |
| **ADR-016** | Rejeição de CMS por Nós               | `REJEITADO`  | Rejeição formal de propostas com flags por nó e templates desacoplados                                           | `RejectedProposalContractTest.kt`                    |
| **ADR-017** | Experimentação por Revisão de Spec    |  `PROPOSTO`  | Modelagem `ExperimentArm` e `ExperimentConfig` no `Pointer`; tráfego adiado                                      | `Pointer.kt`                                         |
| **ADR-018** | Montagem Variável de Surface          |   `ACEITO`   | Ordem de slots e layouts como dado no `Skeleton` (`allowedLayouts`, seed `home.cards_first`)                     | `Skeleton.kt`, `SkeletonValidator.kt`, `HomeSeed.kt` |
| **ADR-019** | Remoção de `variant` do Catálogo      |   `ACEITO`   | Eliminação de `variant: "compact"` do `shortcut_shelf@1` e inserção em `VISUAL_KEYS`                             | `NoVisualAttributesTest.kt`, fixtures canônicas      |

---

## 🏛️ Consulta e Detalhamento das Decisões

Para a fundamentação técnica aprofundada, consulte os documentos canônicos:

- **ADRs 001 a 013:** Detalhados na seção 16 de [`docs/arquitetura-de-referencia.md`](../arquitetura-de-referencia.md).
- **ADRs 014 a 019:** Consolidados na seção 8 de [
  `docs/memoria-operacional-e-arquitetural.md`](../memoria-operacional-e-arquitetural.md).
- **Contrato de Retry Móvel:** Detalhado em [
  `docs/runbooks/contrato-de-retry-clientes-moveis.md`](../runbooks/contrato-de-retry-clientes-moveis.md).
