# Tarefas — specs das quatro composições

Plano: [plan.md](plan.md). Executado em 2026-09-23 na branch `feature/melhorias`. Cada item marcado aponta a
evidência (arquivo, teste ou medição). "Escrito" significa fonte de teste presente; o resultado da execução única da
suíte está na seção [Verificação final](#verificação-final).

## T01 — Matriz de contrato e ADR de múltiplas surfaces

- [x] Mapear todos os blocos das três imagens para as quatro specs, classificando os que ficam nativos.
  Evidência: matriz em [
  `docs/examples/screens/README.md`](../docs/examples/screens/README.md#matriz-de-rastreabilidade-t01)
  (reutilizável / contrato novo / nativo / não mapeado).
- [x] Definir tipos, props, limites, capabilities e destinos; registrar propostas de ADR sem considerá-las aprovadas.
  Evidência: [ADR-020](../docs/memoria-operacional-e-arquitetural.md#adr-020--múltiplas-surfaces-e-contratos-de-componente)
  (`PROPOSTO`),
  [`transaction-summary-v1.md`](../docs/contratos/transaction-summary-v1.md),
  [`componentes-comercio-v1.md`](../docs/contratos/componentes-comercio-v1.md), índice em `docs/adr/README.md`.
- [x] Distinguir referências Android de contrato mobile formal. Evidência: avisos em todos os READMEs de exemplo;
  fixture Android canônica continua ausente e não foi inferida.

## T02 — Primeira Home financeira de demonstração

- [x] Criar skeleton/spec/response de `banking.shortcuts_first` com dados sintéticos.
  Evidência: `docs/examples/screens/banking.shortcuts_first/{skeleton,spec,response}.json`.
- [x] Documentar publicação via maker-checker e headers de consulta. Evidência: README do exemplo e roteiro da pasta.
- [x] Preservar fixture canônica; exemplo Android permanece proposta. Evidência: fixture intocada;
  `HomeComposeContractWebTest` continua comparando com ela.
- [x] Teste de publicação/composição: `ScreenExamplesTest` (fluxo administrativo real + comparação com `response.json`).

## T03 — Home com cartões antes dos atalhos

- [x] Criar skeleton/spec/response de `banking.cards_first`, aproveitando o skeleton `home.cards_first` do seed.
- [x] Demonstrar alteração de ordem e grid sem campos visuais. Evidência: tabela comparativa no README do exemplo.
- [x] Documentar troca de revisão por pointer, sem roteamento experimental novo. Evidência: README e roteiro de
  rollback.
- [x] Teste comparativo: `ScreenExamplesTest` compara ordem de slots, layout de `shortcuts`, types exigidos e rollback.

## Checkpoint A — Composições financeiras

- [x] Exemplos coerentes com o catálogo e governança existentes; diferenças visuais ficam nativas.
- [x] Fontes de teste escritas; nenhuma execução Gradle presumida durante a escrita.

## T04 — Regras de domínio por surface

- [x] Introduzir definição finita de surfaces/slots/types preservando regras atuais da Home.
  Evidência: `sdui-core/.../model/Surface.kt` (`Surfaces`, `SlotRule`, `SurfaceDefinition`).
- [x] Permitir regras comerciais sem slot financeiro obrigatório; recusar surface desconhecida.
  Evidência: `SkeletonValidator`, `SpecValidator`.
- [x] Parametrizar Select e validações sem relaxar guardas visuais/PII. Evidência: `Select.kt` com `surface`.
- [x] Testes: `SurfaceRulesTest`, `SelectSurfaceAndPropWalkTest`, `SkeletonValidatorTest` (Home legada preservada).

## T05 — Propagar surface no pipeline

- [x] Levar surface do pedido até seleção, hidratação, cache, singleflight e fallback.
  Evidência: `ComposeRequest.surface`, `ComposeScreenService`, `FallbackCoordinator`.
- [x] Garantir isolamento e atualização de campos do requisitante em hit e waiter.
  Evidência: `forRequester` no waiter; `SurfaceIsolationAndSingleflightTest`; medição M4.
- [x] Preservar orçamento, métricas de vocabulário finito e fallback por surface.
  Evidência: `MetricTags`, `MetricNames`; medição M1.
- [x] Casos de ausência de candidato, last good cruzado, cache hit e singleflight concorrente escritos
  (`SurfaceIsolationAndSingleflightTest`, `ComposeResilienceTest`, `ScreenExamplesTest`).

## T06 — Expor leitura e governança da nova surface

- [x] Expor catálogo por surface sem ambiguidade com GET Home existente. Evidência: `SurfaceController`
  (mapeamentos literais `/v1/surfaces/home` e `/v1/surfaces/catalog`).
- [x] Preservar negociação, ETag, Vary e contrato legado; parâmetros de entrada finitos.
- [x] Validar surface ao publicar/rollback e manter mapeamento correto de analytics.
  Evidência: `RollbackService`, `AdminController`, `ScreenResponseMapper` (evento por surface).
- [x] Testes HTTP: `SurfaceWebTest` (catálogo 200/304, rota inexistente 404, 503 sem Home, rollback 404, paginação).

## T07 — Habilitar novos contratos com capabilities explícitas

- [x] Catálogo aprovado para comércio e transações, sem types arbitrários. Evidência: `ComponentContracts`,
  `CatalogValidator`, `ComponentPropsValidator`.
- [x] `CapabilityMatrix` e validação de catálogo alinhadas, sem conceder types novos a apps antigos.
- [x] Omissão/fallback por slot e versão definidos; os sete types legados preservados.
- [x] Testes: `ComponentContractsTest` (cliente antigo, suporte parcial, suporte completo, alinhamento publicação ×
  negociação).

## Checkpoint B — Fundação de novas telas

- [x] ADRs/contratos revisáveis, Home preservada e surface comercial isolada.
- [x] Testes negativos escritos para novos tipos e campos proibidos.

## T08 — Catálogo de moda

- [x] Criar skeleton/spec/response de `fashion.catalog` com vitrine limitada e dados fictícios.
- [x] Mapear entrada para busca/filtro e navegação; detalhe/carrinho/checkout continuam nativos.
- [x] Não reutilizar `card_product` para mercadoria nem incluir cores/tamanhos de apresentação.
- [x] Teste de contrato/composição: `ScreenExamplesTest`, `ExampleResponsesContractTest`.

## T09 — Home bancária com resumo de transações

- [x] Criar skeleton/spec/response de `banking.transactions` com `transaction_summary@1` (proposto).
- [x] Separar semanticamente conta e cartão; resumo de transações só com dados sintéticos.
- [x] Demonstrar comportamento sem capability (`response-without-capability.json`) e ações para extrato/filtro nativos.

## T10 — Configuração de demonstração e reprodução

- [x] Carga/publicação explícita dos quatro exemplos sem sobrescrever o seed por padrão.
  Evidência: `DemoScreensLoader`, `sdui.demo-enabled=false` por padrão, recursos em `sdui-app/src/main/resources/demo`.
- [x] Sequência de atores, ids, revisões e respostas para GET/rollback documentada no README da pasta.
- [x] Modo in-memory e advertência factual sobre restart e instância única mantidos.
- [x] Teste de fluxo: `ScreenExamplesTest` (carga idempotente e ordem documentada), `SurfaceWebTest` (demo ligado).

## T11 — Tutorial e sincronização documental

- [x] Guia de nova tela e novo componente: [
  `docs/guia-criacao-telas-componentes.md`](../docs/guia-criacao-telas-componentes.md).
- [x] Corrigir status e capacidade de ordenação em `docs/images/README.md` (versão 3.1).
- [x] Indexar exemplos e planos, distinguindo proposto, implementado e homologado (`docs/README.md`, `README.md`).

## Achados de performance (docs/analise-performance-2026-09-23.md)

- [x] Baseline medida antes de qualquer otimização (harness in-process sobre `01b3375`).
- [x] Achados 1–8 e menores tratados um a um, com medição depois e registro de rejeitados:
  [`docs/performance/medicoes-2026-09-23.md`](../docs/performance/medicoes-2026-09-23.md).
- [ ] Baseline HTTP do cenário `compose-hit-p99` em ambiente **dedicado**: pendente (a medição feita nesta entrega é
  em notebook de desenvolvimento; ver documento de medições).

## Checkpoint final

- [x] Quatro composições documentadas e cobertas por fontes de testes; nenhuma tela foi omitida sem classificação.
- [x] Verificar guards, isolamento ArchUnit e warnings na execução única final (ver abaixo).
- [x] Registrar testes executados ou não executados e limitações de homologação mobile (ver abaixo).
- [x] Persistência permanece entrega separada ([plano](plano-persistencia-mongo-redis.md)), sem declarar Redis/Mongo
  operacionais por existir `compose.yaml`.

## Verificação final

Execução única, em 2026-09-23: `gradlew clean build --warning-mode=fail --continue` (Gradle 9.7.1, JDK 25.0.4.1),
1 min 26 s. **Resultado: `BUILD FAILED` (exit 1) por um teste**; todas as demais tarefas concluíram, inclusive
`verifyForbiddenDependencies`, `verifyPureClasspath`, `bootJar` e a suíte ArchUnit. Nenhum warning de compilação
(`allWarningsAsErrors`) nem de depreciação do Gradle (`--warning-mode=fail`).

| Módulo                             | Testes | Falhas | Ignorados |
|------------------------------------|-------:|-------:|----------:|
| `sdui-core`                        |     63 |      0 |         0 |
| `sdui-contract`                    |     26 |      0 |         0 |
| `sdui-app`                         |    112 |  **1** |        12 |
| `sdui-bootstrap`                   |      1 |      0 |         0 |
| `sdui-integration-test` (ArchUnit) |     14 |      0 |         0 |

- **Falha:** `InMemoryStoresBehaviorTest > cache de arvore mantem o teto sob escritores concorrentes` —
  `maxResident` = 1.093 num teto de 1.000 (folga aceita no teste: 8). Causa: o teto era decidido e medido com
  `ConcurrentHashMap.size()`, que sob inserção e poda concorrentes é estimativa; a checagem seguida de `put`
  também não era atômica. **Corrigido depois da execução:** vaga reservada por compare-and-set num contador antes
  da inserção e devolvida após a remoção (`InMemoryHydratedScreenCache.occupiedSlots`), teste com asserção estrita (≤
  1.000, sem folga) e consistência em repouso, mais um caso de regravação no teto. Registro em
  [`medicoes-2026-09-23.md`](../docs/performance/medicoes-2026-09-23.md) (achado 6, nota 4).
- **Verificação da correção sem Gradle** (a regra do AGENTS.md é uma execução só): todas as fontes de produção e
  de teste dos cinco módulos recompiladas com o `kotlinc` 2.4.20 do cache do Gradle e `-Werror`, sem erro nem
  warning; `InMemoryStoresBehaviorTest` + `InMemoryHydratedScreenCacheTest` executados 50 vezes pelo JUnit
  Platform Launcher (550 execuções, 0 falhas); harness `m6` com vagas máx = 10.000 nas três rodadas. **A suíte
  Gradle completa não foi reexecutada com a correção** — fica para a próxima execução autorizada.
- **Ignorados (12):** `MongoPersistenceIT` (7), `RedisCachesIT` (4) e `DurableModeBootIT` (1) exigem
  `SDUI_IT_MONGO_URI`/`SDUI_IT_REDIS_URL`; não havia Docker nem banco nesta máquina. Os adapters persistentes **não
  foram exercitados contra infraestrutura real**.
- **Carga HTTP:** executada com o `bootJar` desta execução em notebook de desenvolvimento (não dedicado):
  12,9–13,4 mil req/s, p99 no cliente 10,6–11,5 ms, p99 do hit no servidor ≤ 2,4 ms; detalhes, 429 do limitador
  por coorte e limitações em [`medicoes-2026-09-23.md`](../docs/performance/medicoes-2026-09-23.md#carga-http).

### Revisão `code-review-and-quality` (depois da execução Gradle)

Cinco eixos sobre o working tree. Corrigido na hora:

- **Segredo em log (segurança):** `RedisCacheConfiguration` usava `URI.create(url)`; uma URL malformada falhava a
  subida com a URL inteira, senha inclusa, na mensagem. Agora recusa sem ecoar o valor e sem causa encadeada
  (`RedisUrlSecretTest`).
- **Nit:** `platform` e `schemaVersion` entram no MDC truncados, como `appVersion` (valores ainda não validados).

Validação das correções, fora do Gradle (regra de execução única): produção e testes recompilados com `kotlinc`
2.4.20, `-Werror` e o preset `spring` do all-open (como o plugin do build); a suíte inteira de `sdui-app`
executada pelo JUnit Platform Launcher com as bibliotecas de runtime do `bootJar`: **102 testes, 0 falhas** (os 12
ITs sem infraestrutura abortam por premissa, como no Gradle); `ArchitectureTest`, `VisualKeysAlignmentTest` e
`SduiApplicationTest` com o classpath de produção: **15 testes, 0 falhas**.

Deferido com justificativa (entra no ensaio P13, porque o modo Mongo não está homologado):

- **Falha do Mongo no plano administrativo vira 500:** timeout de operação, resultado de commit desconhecido e
  chave duplicada no `MongoSpecStore.save` sobem como `MongoException`/`IllegalStateException` e caem no handler
  genérico (`INTERNAL_ERROR`, sem `Retry-After`, contado como erro inesperado). Para o operador, o certo é 503
  com `Retry-After` (reenvio com a mesma `Idempotency-Key`) e 409 no conflito de chave. Proposta: traduzir no
  adapter para exceções de porta (`StoreUnavailable`, `StoreConflict`) e mapear na API.
- **Leitura de pointer no MongoDB a cada requisição:** aceita no ADR-021 (§19.10), mas a carga HTTP mostra a escala:
  cerca de 13 mil leituras/s por pod. Além disso, com o Mongo fora, todo pedido vai para o last good mesmo com a
  árvore no Redis. Uma alternativa é um cache local de pointer com TTL de até 1 s, o que exige ADR (troca por
  defasagem) e medição no P13.
- **Consider:** `RedisHydratedScreenCache.put` faz SET, SADD e EXPIRE em três idas e voltas não atômicas; pipeline
  ou Lua economizaria cerca de 2 RTT por miss.
- **FYI:** `FallbackReason.REDIS_UNAVAILABLE` também é emitido para falha do MongoDB. O nome está no contrato do
  envelope, então trocá-lo é mudança de contrato. O limitador por coorte (`plataforma:build`, 10 mil/s por pod)
  recusou 24% da carga de um build único na rodada 1.
- **Código sem chamador em produção (remoção a decidir):** `IdempotencyStore.find`, `SpecStore.list(platform,
  channel)` sem paginação e `AuditLogStore.list()`; hoje só os testes os usam.

### Pendências

1. Reexecutar a suíte Gradle completa uma vez, quando autorizado, para confirmar no build as correções feitas
   depois da execução única (teto do cache, URL do Redis, MDC).
2. Rodar `MongoPersistenceIT`, `RedisCachesIT` e `DurableModeBootIT` com infraestrutura real (`docker compose up -d` +
   variáveis `SDUI_IT_*`) e executar o ensaio P13 do
   [runbook](../docs/runbooks/persistencia-mongodb-redis.md). Até lá o AGENTS.md §17 (instância única) vale.
3. Baseline HTTP do `compose-hit-p99` em ambiente dedicado, com gerador em outra máquina.
4. Homologação com os apps: `transaction_summary@1`, `catalog_navigation@1` e `product_collection@1` são
   propostas (ADR-020 `PROPOSTO`); fixture Android canônica continua ausente.

## Correções da auditoria de bugs e segurança — 2026-09-23

- [x] Implementar R01–R12 da [análise](../docs/analise-bugs-seguranca-2026-09-23.md).
- [x] Escrever regressões de entrada, schema, IDs, skeleton exato, revisão humana, CAS, rollback,
  dono de idempotência, overflow e índice Redis; adaptar os testes/harness às assinaturas.
- [x] 
  Registrar [ADR-022](../docs/memoria-operacional-e-arquitetural.md#adr-022--integridade-da-governança-e-limites-de-entrada),
  incluindo reabertura de pedidos antigos e drenagem de escritores na atualização.
- [x] Revisar estaticamente o diff, dependências entre camadas e chamadores das portas alteradas.
- [ ] Compilar e executar a suíte uma única vez ao final, quando o operador autorizar.
- [ ] Executar regressões Mongo/Redis com infraestrutura real e o ensaio operacional.

Neste ciclo **não foram executados Gradle, compilador, testes ou carga**. Os resultados anteriores
acima não validam estas alterações. O modo persistente continua não homologado e o modo em memória
continua restrito a instância única. A sugestão histórica de SET/SADD/EXPIRE foi substituída pelo
índice v2 com Lua, por correção de cardinalidade e atomicidade, sem alegação de ganho medido.
