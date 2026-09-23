# Análise de performance do código produtivo — 2026-09-23

Revisão estática do worktree na base `1f22515ac8f3cd48bbb4c0ffe23bd5c6ea865b3f`.
Escopo: os 58 arquivos Kotlin produtivos (24 core, 23 app, 10 contract, 1 bootstrap),
configuração de runtime e seed; os cinco fontes de build-logic também foram inspecionados.
Nenhum código produtivo foi alterado. Gradle, testes e carga não foram executados.
A pasta `.claude/` já estava não rastreada e não foi modificada.

Esta análise identifica mecanismos e riscos demonstráveis no código. Não estabelece latência,
throughput, consumo de heap ou ganho de otimização medidos. O PASS histórico não prova o
desempenho do worktree atual. A skill performance-optimization orienta medir antes de otimizar.

## Achados prioritários

### 1. P1 — Cardinalidade de métricas controlada pelo cliente

Evidência: `ComposeScreenService.kt:102`, `:144`, `:295`; `HomeController.kt:132`;
`MicrometerMetricsRecorder.kt:17`.

`appVersion` completo entra nas tags de contadores, timer de serialização e distribuição de bytes.
O parsing aceita versões arbitrárias válidas, e não há filtro de cardinalidade nos fontes de runtime.
Mesmo requisições recusadas pelo rate limiter registram `compose.rate_limited` com essa versão.
Variar `Client-Version` mantendo o build produz combinações novas: o teto do rate limiter
não é um teto de meters. `schemaVersion` também aceita valores numéricos além do schema 3.
O efeito esperado é crescimento de meters, heap e séries exportadas ao longo da vida do processo.

Há dois caminhos administrativos adicionais: `admin.spec.draft` tagueia `spec.surface`, que
`SpecValidator` não restringe ao vocabulário Home, e `admin.catalog.upsert` tagueia `type`.
`CatalogValidator` restringe o conjunto ACTIVE, mas permite componentes não ACTIVE de nomes
arbitrários não genéricos. Portanto a validação anterior ao incremento não torna essa tag finita.

Direção: vocabulário finito para tags, versão exata em contexto diagnóstico quando necessária,
e teto explícito de meters/tags como defesa adicional. Micrometer oferece
[maximumAllowableTags](https://docs.micrometer.io/micrometer/reference/concepts/meter-filters.html).
Validar com muitas versões/tipos/surfaces distintos e observar meters residentes, heap e scrape.

### 2. P1 — Poda de idempotência pode expulsar reservas em andamento

Evidência: `InMemoryStores.kt:224`, `:248`, `:380`; `AdminServices.kt:171`, `:202`.

Ao atingir 10.000 entradas, `reserve` chama a mesma poda usada pelos caches. Após remover
expirados, ela ordena por vencimento e remove até metade das entradas, inclusive registros com
`resultRef == null` e resultados ainda dentro do TTL. Um retry da chave expulsa pode reservá-la
novamente enquanto a operação original continua. Em `publish.open`, isso permite dois pedidos
para a mesma chave. A transação global serializa os efeitos, mas não recompõe a reserva removida.

O limite de memória está sendo aplicado a um registro de coordenação como se fosse cache
descartável. Saturação pode causar trabalho duplicado e perda da garantia de idempotência.
Direção: preservar reservas e a janela prometida de deduplicação; recusar novas admissões
quando não houver capacidade segura. Definir o tratamento de reservas vencidas antes de mudar
a política. Verificar com capacidade pequena, operação bloqueada e retry da chave após pressão.

### 3. P2 — Acerto de cache e 304 ainda varrem todas as specs

Evidência: `ComposeScreenService.kt:121`, `:224`; `InMemoryStores.kt:72`; `Select.kt:33`.

Cada requisição admitida chama `listPublished`, que filtra `items.values` sobre todas as specs,
incluindo rascunhos de outras plataformas. `Select` materializa outra lista e procura o pointer;
se necessário, filtra candidatos compatíveis. Com N revisões residentes, o custo de seleção é
O (N), inclusive com árvore em cache ou ETag correspondente. O singleflight vem depois e não
coalesce esse trabalho. O bulkhead limita concorrência na seleção, não seu custo por chamada.

Manter obrigatoriamente seleção antes do cache, conforme AGENTS.md §19.10. Uma melhoria
candidata é indexar publicadas por surface/plataforma no adapter, com atualização coerente em
save. Não substituir a seleção por cache baseado apenas em contexto. Medir com 3, 1.000 e
10.000 revisões, variando a proporção publicada, e conferir alocações e tempo de seleção.

### 4. P2 — Falta segunda consulta ao cache dentro do líder do singleflight

Evidência: `ComposeScreenService.kt:179`, `:192`, `:250`; `InMemoryStores.kt:424`.

Interleaving possível: A lê cache vazio e pausa; B compõe, grava cache e remove a entrada
inflight; A retoma, torna-se líder e hidrata novamente sem consultar o cache preenchido por B.
Isso não viola a exclusão simultânea do singleflight, mas permite recomposições sequenciais
redundantes numa rajada. Direção: consultar novamente a árvore ao assumir liderança,
preservando seleção e campos do requisitante. Verificar com barreiras e contagem de hidratações.

Há também uma restrição de correção para qualquer otimização nessa área: o waiter retorna
`outcome.value` diretamente (`ComposeScreenService.kt:204`), sem `withRequester`, enquanto
o hit faz essa atualização. Dois clientes da mesma chave podem receber os campos do líder.
Esse caso precisa ser coberto antes de ampliar reutilização de resultados.

### 5. P2 — Fan-out assíncrono para trabalho que atualmente não faz I/O

Evidência: `SduiConfiguration.kt:232`; `PassThroughHydrator.kt:18`;
`HydrationCoordinator.kt:60`, `:93`, `:112`; `MdcPropagatingExecutor.kt:15`.

O único hydrator cabeado devolve `section.props`. Mesmo assim cada seção cria tarefa virtual,
future, timeout e cópias de MDC, disputa o semáforo global de 8 permits e depois é copiada.
A fixture atual tem 8 seções: uma composição completa dispara 8 tarefas para repassar mapas.
O custo existe por construção; sua relevância para P99 ainda precisa ser medida.

Direção candidata: caminho síncrono explícito para pass-through, preservando semântica e
telemetria e mantendo fan-out limitado para adapters de I/O futuros. A documentação do
[JDK 25](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Thread.html)
situa virtual threads como ferramenta para concorrência com espera, não aceleração de CPU.
Medir misses, alocações e tarefas criadas. Não aumentar o semáforo sem essa evidência.

### 6. P2 — Poda síncrona tem custo de ordenação e teto apenas aproximado

Evidência: `InMemoryStores.kt:278`, `:363`, `:380`.

No limite, o escritor percorre o mapa, ordena entradas vivas por expiração (O (N log N)) e
descarta aproximadamente metade. Isso pode concentrar latência em uma escrita e provocar
novos misses ao remover árvores ainda válidas. O AtomicBoolean evita podas simultâneas,
mas outros escritores continuam inserindo quando a poda já está em andamento: `maxEntries`
não é um limite estrito sob concorrência. Contar entradas tampouco limita bytes de props.

Medir o cruzamento de 10.000 entradas com escritores concorrentes, tempo de poda, tamanho
máximo residente e hit ratio após poda antes de escolher política mais complexa.
Idempotência deve ser tratada separadamente, conforme achado 2.

`InMemoryProjectionStore` não tem poda periódica: só poda em put quando chega ao teto,
ou remove a chave expirada consultada. Expirados abaixo do teto podem permanecer indefinidamente.
Isso diverge do texto de AGENTS.md §21.3, mas o store não tem consumidor de hidratação atual;
o risco operacional desse trecho é futuro, não gargalo demonstrado hoje.

### 7. P2 — Histórico administrativo sem retenção e listagens sem paginação

Evidência: `InMemoryStores.kt:49`, `:87`, `:139`, `:163`, `:329`;
`AdminController.kt:105`, `:134`; `AdminServices.kt:77`.

Specs, skeletons, pedidos de publicação, diffs e specCache crescem sem política de retenção.
GET specs e revisions retornam listas integrais com props; custo de cópia e serialização
cresce com o histórico e o tamanho dos documentos. Isso disputa heap/CPU com Home na mesma JVM.
Não confundir com audit log (limitado a 2.000) ou last good (chaves por surface/plataforma/canal).

Direção: medir heap após ciclos de publicação e tamanho/latência das listagens; definir
paginação e retenção de acordo com governança. Não apagar revisões necessárias para seleção,
rollback e auditoria como simples otimização. Persistência continua sendo feature com ADR.

### 8. P2 — O teste de cenário de carga não mede o SLO

Evidência: `ComposeHitLoadScenarioTest.kt:10`; `load/compose-hit-p99.yaml`;
`HomeController.kt:128`; `application.yaml`.

O teste verifica strings no YAML. Não gera requisições, mede percentis ou verifica hit ratio.
O YAML especifica duração de 60 s, hit ratio de 90% e P99 de 400 ms, mas não taxa de chegada,
concorrência, aquecimento ou forma de gerar os misses. Não foi localizada baseline executável
com resultados de performance entre os arquivos versionados inspecionados.

O timer `serialize.ms` começa depois de `mapper.toResponse`, deixando a construção de DTOs
e JsonNodes fora dele. `compose.duration` inclui esse custo, mas termina antes da escrita HTTP;
não mede rede e renderização móvel. As durações são truncadas para milissegundos. O cenário
ainda cita `section.top_bar.ms`, enquanto o runtime emite `section.hydrate.ms` com tag `type`.

Direção: usar carga HTTP real em ambiente dedicado e registrar a baseline; alinhar os nomes
do cenário ao runtime e separar mapping de serialização no perfil. Os histogramas existentes
ajudam a acompanhar o SLO, mas sua configuração não prova que ele foi cumprido.

## Observações de menor prioridade

- `PropWalk.referencesForeignSection` evita novas comparações depois de `found`, mas os
  walkers continuam percorrendo os descendentes. Não há interrupção real da travessia como
  afirma AGENTS.md §21.8. Atua no plano administrativo; não é otimização prioritária da Home.
- `InMemoryAuditLogStore` usa lista com `removeAt(0)`, não anel: desloca até 1.999 referências
  sob lock por append no limite. O custo é limitado e administrativo; medir antes de trocar.
- `ScreenResponseMapper` reconstrói DTOs e árvore JSON também em hits. Isso é diferente de
  dupla serialização: a resposta de sucesso é corretamente entregue como bytes uma única vez.
  Reutilizar partes estáveis só faz sentido com perfil de alocação e sem congelar campos do cliente.
- `CapsHash`, parsing, cópias de tags e `CapabilityMatrix.effective` alocam por requisição.
  O catálogo atual tem sete capabilities; não há evidência para priorizar micro-otimizações aqui.
- `JsonMaps.toValue` é recursivo sem teto próprio, mas só converte o seed local atual.
  O teto 32 existe em `ScreenResponseMapper.toNode`; não atribuir essa proteção aos guards
  recursivos nem ao seed. Validar limites de entrada administrativos antes de aceitar props maiores.

## Proteções confirmadas na leitura

Seleção anterior ao cache; chave com revisão/capabilities/plataforma/canal; atualização de
requisitante em hits; saída de sucesso como bytes; capabilities conhecidas e parsing limitado;
SemVer com toIntOrNull; ordenação estável no Filter; propriedades de domínio pré-calculadas;
timeout de waiter sem cancelar líder; interrupção da thread de hidratação em erro; permit em
finally; proteção das métricas no handler assíncrono; TTL de fallback; invalidação de árvore e
last good após publicação/rollback; MDC propagado; stream do seed fechado com use.
Interrupção é cooperativa: não prova cancelamento de qualquer adapter de I/O futuro.

Não há consultas Mongo/Redis cabeadas para diagnosticar N+1, índices ou pools neste runtime.
Os DTOs de contract não executam I/O nem mantêm caches. Bootstrap e seed fazem trabalho de
inicialização, e build-logic não integra o caminho de requisição. Não foram encontrados achados
de performance adicionais nesses grupos pela leitura estática.

## Plano de medição para uma etapa posterior

1. Registrar commit, JVM, CPU/memória, limites do processo, dados, concorrência e taxa de chegada.
   Separar aquecimento de medição; repetir condições idênticas para estimar variância.
2. Medir HTTP hit, miss, 304 e fallback separadamente, incluindo erros/429/503 e payload.
   Usar baseline de Home P99 hit ≤ 400 ms e hit ratio ≥ 90%; o SLO móvel de 1.200 ms exige cliente.
3. Variar revisões residentes, tags distintas, ocupação dos caches e publicação concorrente;
   capturar CPU, alocações, GC, heap retido, meters e tarefas virtuais.
4. Exercitar com barreiras os interleavings de singleflight e saturação da idempotência.
5. Só então experimentar uma mudança por vez e comparar sob a mesma carga. Manter apenas
   ganhos acima da variância com correção preservada; registrar também experimentos rejeitados.

Esta etapa posterior não foi executada nem é requisito para concluir a análise estática pedida.
Nenhum resultado numérico de performance é reivindicado neste relatório.

## Inventário de cobertura

Todos os fontes abaixo foram inspecionados. Os hashes identificam o conteúdo ao concluir a análise.

| Arquivo                                                                                                                                                                                                 | SHA-256                                                            |
|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| [build-logic/src/main/kotlin/sdui.kotlin-base.gradle.kts](../build-logic/src/main/kotlin/sdui.kotlin-base.gradle.kts)                                                                                   | `630d20821d4fd0174683882c5230efb2964093ee0d735b6e16991299038c677d` |
| [build-logic/src/main/kotlin/sdui.kotlin-library.gradle.kts](../build-logic/src/main/kotlin/sdui.kotlin-library.gradle.kts)                                                                             | `2ed8765bd15c05e8e4c6c59ee86e670c3c82180bed0df1967c0d627990b9571d` |
| [build-logic/src/main/kotlin/sdui.spring-app.gradle.kts](../build-logic/src/main/kotlin/sdui.spring-app.gradle.kts)                                                                                     | `9d8d08f745ae14f43ae6f61d8207b70595b34e863aa6943b0d6474ca9b3557b3` |
| [build-logic/src/main/kotlin/sdui.spring-library.gradle.kts](../build-logic/src/main/kotlin/sdui.spring-library.gradle.kts)                                                                             | `33ae5f396197c97db64d8a670566ca53bd77f921cae52740f6d4dbcd624a02b3` |
| [build-logic/src/main/kotlin/VerifyDependencies.kt](../build-logic/src/main/kotlin/VerifyDependencies.kt)                                                                                               | `be7fac85d673d22e2e3cd656813f3ccf8a8a50713ac644554ec02495717523f5` |
| [sdui-app/src/main/resources/seed/contrato-sdui-home-definitivo.json](../sdui-app/src/main/resources/seed/contrato-sdui-home-definitivo.json)                                                           | `fad9750c231bccc81cc76f22d97d0f4084fce1e8db46ffce56716ce9b63054ad` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/configuration/SduiConfiguration.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/configuration/SduiConfiguration.kt)                 | `397a37e08a4cf432f146c710870779141cca6f17050b6fb49c599d94fd3aca09` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/memory/InMemoryStores.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/memory/InMemoryStores.kt)                                     | `15609f0c56afe20734209e5e3d54c0caf8b9dac137edc6057b6e7cfa01a44b70` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/observability/MdcPropagatingExecutor.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/observability/MdcPropagatingExecutor.kt)       | `ddeaecd5265a7183c6a7ac737bc8d328b13d34b188a7622bf1a5b02b76c70459` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/observability/MicrometerMetricsRecorder.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/observability/MicrometerMetricsRecorder.kt) | `f99b8b51456b4126572925187245c243eeb3c2324ed919dadcd0506dacd670c1` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/observability/NoOpMetricsRecorder.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/observability/NoOpMetricsRecorder.kt)             | `111746efd5e4014b092d5c246e1ca6e7cd4fdf420aee51a204b0e3a8ccb6d3b8` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/seed/HomeSeed.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/seed/HomeSeed.kt)                                                     | `73e942e11399dd92daf1134c37d8ae3fd4a3499dbb5c005bde5028fc6a9ad5af` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/seed/JsonMaps.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/seed/JsonMaps.kt)                                                     | `7e0cc025cc5cbee4ed945696c78760eba2dda195e7ebe9f8f47d8831ef5ba83d` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/api/admin/AdminController.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/api/admin/AdminController.kt)                                               | `b058badce1520e20720aec6be6bcffab3dede27ba62f7d25ba855bef740c135d` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/api/configuration/ApiBeans.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/api/configuration/ApiBeans.kt)                                             | `6990fa158e1c382a717f67410fa518bdfc38f7de4c52d04918233428c71e3aa5` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/api/http/ApiExceptionHandler.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/api/http/ApiExceptionHandler.kt)                                         | `4dc9315d005fd6af5ac50456171be6dfccae369066188875038952288edddd08` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/api/http/ApiVersionConfig.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/api/http/ApiVersionConfig.kt)                                               | `abe6069ae633a6e03a932f89b5d262bb916def15b46c3c47725d94764779deda` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/api/http/CorrelationIdFilter.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/api/http/CorrelationIdFilter.kt)                                         | `8aed80b348326acccd7959cbfc35108c4273f9ca8bff3f115ad4c1dceebe1764` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/api/http/SurfaceController.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/api/http/SurfaceController.kt)                                             | `422a62ae3572fd9df0ffb0b96e7a07a10c9d7be6d12cf337fc55f9d8950dd9cf` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/api/mapping/ScreenResponseMapper.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/api/mapping/ScreenResponseMapper.kt)                                 | `c3958de71bc2e32c6b86bd307a015331db8f6d489c344fa16214a28426d871d7` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/api/trace/ComposeTraceContext.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/api/trace/ComposeTraceContext.kt)                                       | `9bdba4b6137928ed77a12999458e694a3f074e1457adc97aaedc2beb48a913de` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/admin/AdminServices.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/admin/AdminServices.kt)                                 | `753e70829c8eb063cce740466135509eb6c25071535e69f4afa771b47540aa07` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/compose/ComposeModels.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/compose/ComposeModels.kt)                             | `b1d62516c0bbb07688f7e9f1dba6a4480ad508ea60d8391c676ea3ec57b9cd58` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/compose/ComposeScreenService.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/compose/ComposeScreenService.kt)               | `e130e961bc66d10f3c8555fd90998df4d49c1d5327310315ef869a4958cb0663` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/compose/FallbackCoordinator.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/compose/FallbackCoordinator.kt)                 | `47fbfe49a8fdd1be3d59bc8ce62ae8e156a95cb8c4c5d62eb5705cafdd2e52d1` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/hydration/HydrationCoordinator.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/hydration/HydrationCoordinator.kt)           | `17781db8697dd213401cd340037c2e909a5052a96f0ab2682c439d0ccdbb2748` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/hydration/PassThroughHydrator.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/hydration/PassThroughHydrator.kt)             | `d6cbc1230b462d7e9771bdb8964fa9ef6b885420ff660d0b36c2713713f9d108` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/port/inbound/UseCases.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/port/inbound/UseCases.kt)                             | `f1fde45596bc10cc9bba342a06a391a23eec034f9bea779d35942d2ad55449e4` |
| [sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/port/outbound/Stores.kt](../sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/port/outbound/Stores.kt)                               | `e964c518a8b5e61730a08e7b5230501ad0580710eef3990e467652e9a617877d` |
| [sdui-bootstrap/src/main/resources/application.yaml](../sdui-bootstrap/src/main/resources/application.yaml)                                                                                             | `0856220bc5fd37ea1ca7d4cf3ff19d303b6bcb80ff2c80fa910aeffe71306540` |
| [sdui-bootstrap/src/main/kotlin/br/com/empresa/sdui/bootstrap/SduiApplication.kt](../sdui-bootstrap/src/main/kotlin/br/com/empresa/sdui/bootstrap/SduiApplication.kt)                                   | `45e179fba5eeeb8dad82f77a5605b6d832cc92010ab01c177a82e8f8dcf3d544` |
| [sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/analytics/AnalyticsResponse.kt](../sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/analytics/AnalyticsResponse.kt)               | `07a63de4dad90a208aa975658a59e1647c16791c0bb121ca3e1ca1043614f7b2` |
| [sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/client/ClientResponse.kt](../sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/client/ClientResponse.kt)                           | `1fe1ca480f3a3edb8ad26131055d4d5d969ea55d3700d5770bd96f77d163c8fa` |
| [sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/component/ActionResponse.kt](../sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/component/ActionResponse.kt)                     | `ddf7d00d9f9f71a071eebeb3c664d5c6ff712aad34f6783584770bf1dfc6699b` |
| [sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/error/ApiErrorResponse.kt](../sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/error/ApiErrorResponse.kt)                         | `9012fc01fe196930e26fb55fabdd2c84fe0777517060e594f4ca5eb15dbc34a0` |
| [sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/screen/OmittedItemResponse.kt](../sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/screen/OmittedItemResponse.kt)                 | `a195a245632f1c3bfdfe4509ef0d7888c969d58665bbdf4994554f1f6c7d6931` |
| [sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/screen/ScreenEnvelope.kt](../sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/screen/ScreenEnvelope.kt)                           | `802eb269cec11345d35984c36270cb47cb4f344595ae03342d8a842e753a071a` |
| [sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/screen/ScreenResponse.kt](../sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/screen/ScreenResponse.kt)                           | `c095f3d8020a912d39493a0f1ef96a09849f177bd01877052705ea44cc06481f` |
| [sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/screen/SectionResponse.kt](../sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/screen/SectionResponse.kt)                         | `af32832fbc57c37e3aaa1a2e2a13a002c5f80bc36e0f406cacd72126b5858f8f` |
| [sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/screen/SkeletonResponse.kt](../sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/screen/SkeletonResponse.kt)                       | `c3f142e02d18ac55e38e47c227379049b9bee6a6bd76124578e52b27b6a36ec9` |
| [sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/targeting/TargetingResponse.kt](../sdui-contract/src/main/kotlin/br/com/empresa/sdui/contract/targeting/TargetingResponse.kt)               | `8223623a2ee13c2917e3088ffdb2cae119811921156df7786d5bba67b8705412` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/cache/CapsHash.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/cache/CapsHash.kt)                                                         | `167a7836ca6a2120e3be70a803b28bfc5e89f039f379e28d98cd0fc1b413ef6e` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/compat/CapabilityMatrix.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/compat/CapabilityMatrix.kt)                                       | `cc026600408aea7a48fc014243d17ca6bd1fd7d1dff0a9740b1cf9beaa64b433` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/diff/SpecDiffFactory.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/diff/SpecDiffFactory.kt)                                             | `fb3f7409aea3dcc936be500922dbb25ce23c3ff6cb0b5e027c511999318065c7` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/filter/Filter.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/filter/Filter.kt)                                                           | `047a84d091c805efeff79319deaf0e142827a2152cbde6c82902c4ee0181183b` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/limit/Bulkhead.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/limit/Bulkhead.kt)                                                         | `aa0391f81138f4c83b20e5adaa4bb81d7cc65e91e9d3d8c0f5370f83b6983a99` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/limit/RetryAfter.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/limit/RetryAfter.kt)                                                     | `3345ad9d788fb51df634f1064712c0ff05651d012ded25f5b2a634604de7024f` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/limit/TimeBudget.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/limit/TimeBudget.kt)                                                     | `8ac265510d5b734b063923ea315f6f122b99ec44e3716645c872028562d6c8ca` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/limit/TokenBucket.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/limit/TokenBucket.kt)                                                   | `75c9c49431ad644fc522e04ddf91f55589bf6d9263ed78d79e96d3b896cb92b6` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/Capability.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/Capability.kt)                                                     | `aa8a172c71dc0b47f85dfc7bba2d219806f82060d0aa62892af130f40a1d40ca` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/Catalog.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/Catalog.kt)                                                           | `4e744df1a85d8d2846e7a516a63299cf85760f13aa58e6681462501ae6680dc4` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/ClientContext.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/ClientContext.kt)                                               | `ab5f8ac176a5aab21623b61c264d27983d1a3e0b6eb68209afcc94646c396698` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/Enums.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/Enums.kt)                                                               | `cb5cdcad5c87a9eba0b6ea48df576828d5817d6bb68b787404317d5970def119` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/Screen.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/Screen.kt)                                                             | `8fadc0476d45b0b9700fe7971e3fad0b7f1e9365d954fcbc29c71230153e5355` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/Section.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/Section.kt)                                                           | `6364de9e11b89fc6a30ce722a92407f02c3df008cf63d9b4ba03514c46a93b08` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/SemVer.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/SemVer.kt)                                                             | `d531e412ec819f698a12c1d963254df2855779cd23b0a485551fecc25a5c87f4` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/Skeleton.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/Skeleton.kt)                                                         | `8d9f64d49cac6334c20e90358a77e71a17728ba7ea6535159beff94b43523206` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/Spec.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/Spec.kt)                                                                 | `17099f7c5a6426cb49a80e7903e1a0ec38ff6207842f48e80b61a8b883fdf00a` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/negotiate/Negotiate.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/negotiate/Negotiate.kt)                                               | `5365b0b71ff9223040e8f066d3e0d809f62d9d7a14e6c9d3b9362781250f176f` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/select/Select.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/select/Select.kt)                                                           | `172b3f7f358897a65d6a90b10eb6f7084e3b67c8973e882f0956069d6d9b2e34` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/validate/CatalogValidator.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/validate/CatalogValidator.kt)                                   | `15fd56758a4c9f1a645a47968ad112ce7db23bf7567c73a9c96110876ecd3855` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/validate/Guards.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/validate/Guards.kt)                                                       | `15cc028829afbdf84a0b26ac6b14d40cf6670a701633dec5b123db2dae71e658` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/validate/PropWalk.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/validate/PropWalk.kt)                                                   | `247b2e97b005684438e7ace6d2edf0f8a7c22b02c554fc90e0623f4f38ab7bfe` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/validate/SkeletonValidator.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/validate/SkeletonValidator.kt)                                 | `b1f2e18ff5bfa7e1c050e587b9f3c17ff8a589971971736b6eb2ca57a9982d0e` |
| [sdui-core/src/main/kotlin/br/com/empresa/sdui/core/validate/SpecValidator.kt](../sdui-core/src/main/kotlin/br/com/empresa/sdui/core/validate/SpecValidator.kt)                                         | `17a299c10e401f6665e600ce155a3f095da89117905d4998430191a1a5ec84d0` |

## Status de tratamento (adendo de 2026-09-23)

Adendo posterior à análise; o texto acima foi mantido como registrado. Medições, antes e depois,
em [`performance/medicoes-2026-09-23.md`](performance/medicoes-2026-09-23.md).

| # | Achado | Situação |
|---|--------|----------|
| 1 | Cardinalidade de métricas | Corrigido e medido (1.008 → 9 meters); guarda `MeterFilter` com teto por tag |
| 2 | Poda de idempotência | Corrigido e medido: registro vivo nunca sai por pressão; admissão recusada no teto (503) |
| 3 | Seleção O(N) em hit e 304 | Otimizado e medido (~100× com 10 mil revisões); seleção continua antes do cache |
| 4 | Segunda consulta ao cache no líder; waiter com campos do líder | Corrigidos e medidos (2 → 1 composição; waiter com os próprios campos) |
| 5 | Fan-out para pass-through | Otimizado e medido (~8× no miss, 0 tarefas virtuais) |
| 6 | Poda síncrona e teto aproximado | Corrigido e medido (5,7 mi → 10.315 residentes); projeções com varredura periódica |
| 7 | Histórico sem paginação | Paginação em specs, revisões e auditoria; retenção de governança definida no ADR-021 (sem poda automática) |
| 8 | Cenário de carga | Cenário alinhado ao runtime e conferido por teste; gerador HTTP versionado; baseline HTTP em medicoes |
| — | Menores | `PropWalk` com parada real, teto em `JsonMaps`, timers em nanossegundos; memoização do mapper e anel da auditoria rejeitados por medição |

