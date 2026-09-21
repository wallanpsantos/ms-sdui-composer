# Registro Consolidado de Histórias do MVP (`H00`–`H18`)

Todas as 19 histórias de desenvolvimento do MVP do `ms-sdui-composer` foram **concluídas com sucesso**, implementadas no código de produção e validadas por suíte de testes automatizados com **Quality Gate APROVADO (PASS)**.

Os arquivos individuais de tarefa (`H00-*.md` a `H18-*.md`) foram aposentados após a entrega integral do código produtivo e a homologação dos critérios de aceite.

---

## 📋 Matriz Consolidada de Entregas do MVP

| História | Domínio / Título | Escopo Entregue no Código | Módulo Principal | Testes / Cobertura |
| :---: | :--- | :--- | :--- | :--- |
| **H00** | Contrato e Fixture | DTOs canônicos, Jackson 3 estrito, round-trip JSON, fixture `home.default` | `sdui-contract` | `HomeFixtureRoundTripTest`, `NoVisualAttributesTest` |
| **H01** | Runtime HTTP e Negotiate | 6 cabeçalhos obrigatórios, SemVer ordinal (`major.minor.patch`), `Negotiate.kt` | `sdui-core`, `sdui-app` | `NegotiateTest`, `HomeControllerWebTest` |
| **H02** | Modelo e Índices | Entidades de domínio: `Spec`, `Skeleton`, `Pointer`, `Targeting`, `SlotDefinition` | `sdui-core` | `SpecTest`, `TargetingTest` |
| **H03** | Catálogo e Seed iOS | 7 tipos canônicos em `@1`, skeleton `home.default`, seed in-memory iOS | `sdui-core`, `sdui-app` | `MvpCatalogTest`, `HomeSeedTest` |
| **H04** | Select e Filter | Seleção determinística por faixa/canal e filtro estável de capabilities | `sdui-core` | `SelectTest`, `FilterTest` |
| **H05** | Hydrate e Envelope | Fan-out assíncrono em Virtual Threads com Semaphore, montagem do envelope | `sdui-app` | `HydrationCoordinatorTest`, `ComposeScreenServiceTest` |
| **H06** | HTTP Condicional e Rate Limit | ETag determinístico `W/"..."`, HTTP 304 Not Modified, Token Bucket por cliente | `sdui-core`, `sdui-app` | `TokenBucketRateLimiterTest`, `ConditionalRequestTest` |
| **H07** | Concorrência e Fallback | Singleflight concorrente, lastgood em cache, escada de fallback (ADR-007) | `sdui-app` | `SingleflightTest`, `FallbackLadderTest` |
| **H08** | Validação de Spec e Diff | Validação de integridade, checksum sha256, detecção de mudanças estruturais | `sdui-core` | `SpecValidatorTest`, `DiffEngineTest` |
| **H09** | Maker-Checker e Auditoria | Fluxo de publicação com segregação de funções Maker/Checker, log append-only | `sdui-app` | `PublishServiceTest`, `AuditLogTest` |
| **H10** | Rollback por Pointer | Reversão atômica de ponteiros de surface com suporte a `Idempotency-Key` | `sdui-app` | `PointerRollbackTest`, `IdempotencyStoreTest` |
| **H11** | Observabilidade e SLO | Métricas Micrometer para composição, fan-out, cache, fallback e SLOs de latência | `sdui-app` | `MetricsRecorderTest`, `SloObservabilityTest` |
| **H12** | Validação e Gates | Regras ArchUnit de pureza de camadas, zero dependências proibidas, CI checks | `sdui-integration-test` | `ArchUnitArchitectureTest`, `CleanArchitectureTest` |
| **H13** | Canary iOS e Operação | Roteamento dinâmico de canal Canary baseado na build iOS do cliente | `sdui-app` | `CanaryRoutingTest`, `IosCanaryWebTest` |
| **H14** | Contrato e Isolamento Android | Isolamento estrito de runtime entre iOS e Android (chaves e stores segregados) | `sdui-contract`, `sdui-app` | `PlatformIsolationTest` |
| **H15** | Spec e Targeting Android | Resolução independente de specs, pointers e faixas de versão para Android | `sdui-core`, `sdui-app` | `AndroidTargetingTest` |
| **H16** | Matriz de Compatibilidade Android | Capabilities nativas de Android e suporte a renderers de Jetpack Compose | `sdui-core` | `CapabilityMatrixTest` |
| **H17** | Governança Android | Publicação, aprovação e rollback de ponteiros dedicados para plataforma Android | `sdui-app` | `AndroidGovernanceTest` |
| **H18** | Canary e Promoção Android | Estratégia de Canary com builds Android e promoção atômica para Stable | `sdui-app` | `AndroidCanaryPromotionTest` |

---

## 🏛️ Transição do Backlog para Arquitetura Viva

Com o encerramento do MVP:
1. **Código e Testes como Especificação Viva:** Qualquer comportamento funcional ou restrição de negócio vive em `src/main/kotlin` e é garantido por testes em `src/test/kotlin`.
2. **Decisões Estruturais em ADRs:** Novas evoluções ou mudanças de arquitetura são governadas via ADRs em [`docs/adr/`](../adr/README.md).
3. **Procedimentos de Contingência em Runbooks:** Rollbacks, tolerância a falhas e contratos de retry móvel residem em [`docs/runbooks/`](../runbooks/README.md).
