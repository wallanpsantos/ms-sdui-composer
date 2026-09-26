# AGENTS.md — Memória Operacional do ms-sdui-composer

## Modo operacional vigente

O bootstrap Gradle, o escopo completo do MVP (`H00`–`H18`) e o ciclo de revisão técnica multidimensional
(`code-review-and-quality`) estão **concluídos com Quality Gate APROVADO (PASS)**.

As 7 correções de qualidade e concorrência foram implementadas e verificadas no código produtivo e de testes
(Singleflight timeout sem cancel compartilhado, blindagem de SemVer contra overflow, eliminação da dupla
serialização no hot path, ordenação estável em Filter, pré-cálculo de guards, limpeza de chaves e `.use` em streams).

**Foco a partir de agora:** Manutenção, evolução de features pós-MVP, observabilidade operacional e suporte à
homologação com clientes móveis.

**Ciclo de 2026-09-23 (Seção 23):** surface `catalog` e contratos novos (ADR-020), adapters MongoDB/Redis opt-in
(ADR-021) e integridade da governança com limites de entrada (ADR-022) implementados no código e fontes de teste.
ADR-020, ADR-021 e ADR-022 estão `ACEITO` (Seção 11 de `docs/arquitetura-de-referencia.md`). Seguem pendentes a
homologação móvel (020), o ensaio operacional de persistência (021) e a validação em build (022), acompanhados em
`docs/tasks/todo.md`. O PASS histórico acima não cobre este ciclo.

Regras deste modo:

- **Governança de Git (Operador Humano):** Não executar nenhum comando `git add`, `git commit` ou `git push`, nem
  diretamente nem através de subagentes. Todas as implementações, refatorações, documentações e alterações de código
  são feitas diretamente no workspace, deixando os arquivos prontos e modificados para o operador Humano inspecionar via
  `git diff` / `git status`, revisar tecnicamente com calma e realizar os seus próprios commits e pushes manualmente
  conforme sua preferência de governança e controle. No Claude Code, `.claude/settings.json` nega esses comandos.
- **Mensagem de commit:** somente quando o operador pedir, no formato de `.agents/rules/git-commit-standards.md`
  (fonte única, importada ao fim desta seção; procedimento na skill `sdui-commit-message`). Proibida qualquer
  auto-atribuição, co-autoria ou menção à IA.
- Qualquer alteração pontual deve manter estritamente a conformidade com as regras de pureza do `sdui-core`, o
  isolamento de camadas do ArchUnit e a ausência de warnings (`allWarningsAsErrors = true`).
- Papel padrão: `sdui-implementer`. Os demais papéis só entram quando o operador pedir explicitamente.
- **Execução de Gradle (política única):** não interromper a escrita para esperar compilação ou resultado de testes.
  Gradle roda uma única vez, no fim do recorte e nunca entre tarefas: `.\gradlew.bat clean build --warning-mode=fail`.
  Se falhar, corrigir e rodar mais uma vez; persistindo a falha, reportar a saída e parar. `perfHarness` e `loadTest`
  só a pedido do operador (§23.14). Qualquer outra execução só a pedido do operador.

Formato da mensagem de commit (importado):

@.agents/rules/git-commit-standards.md

Os comandos da seção 16 são registro histórico do bootstrap. Não reexecutá-los como rotina de implementação.

## 1. Identidade e Definição do Serviço

O `ms-sdui-composer` é o serviço responsável por compor a árvore de UI de uma surface (a primeira surface é `home`) a
partir de uma spec versionada, contexto do cliente e capabilities declaradas, entregando um envelope REST/JSON pronto
para clientes iOS e Android.

- **Papel Arquitetural:** Presentation + Application Controller + BFF de UI (Fowler).
- **Runtime:** Estritamente stateless no hot path. Não consulta domínios de negócio regulados diretamente e não persiste
  árvores hidratadas de usuário no banco.
- **Endpoints:** `GET /v1/surfaces/home` (MVP) e `GET /v1/surfaces/catalog` (ADR-020), um mapeamento literal por
  surface da allowlist `Surfaces`.

## 2. Stack Tecnológica e Baseline

- **Linguagem:** Kotlin 2.4.20 (`allWarningsAsErrors = true`).
- **Plataforma:** JVM com Java 25 LTS via Gradle toolchain (`jvmToolchain(25)`). Foojay resolver `1.0.0` (latest estável
  verificada no Plugin Portal).
- **Framework:** Spring Boot 4.1.1 (Spring Framework 7.0.9 gerenciado pelo BOM).
- **Build:** Gradle 9.7.1 com Kotlin DSL, version catalog, convention plugins, configuration cache e build cache.
- **JSON:** Jackson 3 (`tools.jackson.core:jackson-databind` 3.1.5, `tools.jackson.module:jackson-module-kotlin` 3.1.5,
  `com.fasterxml.jackson.core:jackson-annotations` 2.21).
- **Testes e Arquitetura:** JUnit Jupiter e AssertJ na versão do BOM; ArchUnit 1.5.0 (`com.tngtech.archunit:archunit`).
- **Persistência e Cache:** padrão em memória. Opt-in (ADR-021, não homologado): MongoDB 8.3+ em replica set como
  autoridade da governança e Redis como cache; clientes montados pelo próprio serviço, autoconfigurações excluídas.
- **Concorrência:** Spring MVC + Virtual Threads (`spring.threads.virtual.enabled: true`).
- **REGRA INEGOCIÁVEL DE DEPENDÊNCIAS:** Versões gerenciadas pelo Spring Boot NUNCA são fixadas no catálogo ou nos
  arquivos de build. Apenas bibliotecas fora do BOM (ArchUnit, Foojay, plugins Kotlin/Boot) possuem versões explícitas.
  Proibido o plugin `io.spring.dependency-management`.

## 3. Módulos e Grafo de Dependências (ADR-001)

```text
sdui-bootstrap        --> implementation(project(":sdui-app"))
sdui-app              --> api(project(":sdui-core"))
                      --> implementation(project(":sdui-contract"))
sdui-contract         --> Jackson 3 estritamente necessário
sdui-core             --> JDK e stdlib Kotlin apenas
sdui-integration-test --> testImplementation de todos os módulos acima + ArchUnit
```

- Apenas `sdui-bootstrap` é executável e possui `@SpringBootApplication`.
- `sdui-app` reúne `orchestrator`, `adapters` e `api`, isolados por pacotes e garantidos via ArchUnit.

## 4. Convention Plugins (`build-logic/`)

- `sdui.kotlin-base`: JVM toolchain 25, compilador Kotlin, BOM do Boot, JUnit Jupiter/AssertJ e
  `verifyForbiddenDependencies`.
- `sdui.kotlin-library`: Aplica `sdui.kotlin-base` + task `verifyPureClasspath`.
- `sdui.spring-library`: Aplica `sdui.kotlin-base` + plugin Spring Kotlin + `kotlin-reflect`.
- `sdui.spring-app`: Aplica `sdui.spring-library` + plugin `org.springframework.boot`.
- Proibido qualquer uso de `allprojects {}` e `subprojects {}`.

## 5. Tríade SDUI e Regra de Fragment

- **Section:** Bloco autocontido com `id`, `slot`, `type`, `typeVersion`, `props`, `actions` e `analytics`.
- **Screen:** Composição ordenada de sections para uma surface e contexto.
- **Action:** Intenção serializada despachada pelo dispatcher nativo (`navigate`, `open_bottom_sheet`, `track`, `noop`).
- **REGRA DE FRAGMENT (ADR-004):** `Fragment`, `FragmentStore`, `FragmentResolver`, endpoint de fragment e chave Redis
  de fragmento estão FORA do MVP.

## 6. Pipeline de Composição

`Negotiate` (Headers) -> `Select` (Pointer + Targeting) -> `Filter` (Capabilities) -> `Hydrate` (Projeções seguras) ->
`Guard / Fallback` -> `Compose` (Montagem do envelope).

## 7. Eixos de Compatibilidade

1. **Eixo A (Envelope/Protocolo):** `UI-Schema-Version` (versão 3 no MVP) e `API-Version` (versão 1).
2. **Eixo B (Renderer/Capabilities):** `type@typeVersion` suportado pelo cliente móvel.
3. **Eixo C (Faixa de Aplicativo):** `Client-Platform` (`ios`|`android`), `Client-Version` (semver ordinal) e
   `Client-Build`.

- Não existe targeting por form factor (sem DSL de pixels/breakpoints no servidor).

## 8. Proibições Rígidas

- **Sem Aparência/CSS:** Proibido enviar `color`, `background`, `font`, `margin`, `padding`, `gap`, `width`, `height`,
  `radius`, `shadow`, `orientation`, `shimmer`, `dp`, `pt`.
- **Sem Primitivas Genéricas:** Proibido criar `row`, `column`, `container`, `card` genérico.
- **Sem PII ou Segredos:** Proibido trafegar CPF, dados bancários regulados, tokens JWT ou senhas em payloads, cache,
  logs ou métricas.
- **Sem Coroutines ou Reativo:** Proibido `suspend fun`, WebFlux, Reactor ou repositórios reativos (ADR-012).
- **Sem Spring Cloud, gRPC, Protobuf ou MapStruct:** Sem bibliotecas externas desnecessárias sem ADR.

## 9. Concorrência e Resiliência

- Virtual Threads para I/O bound.
- Proibido `synchronized` segurando I/O; usar `ReentrantLock` com escopo mínimo se indispensável.
- Timeouts explícitos em todas as chamadas remotas. Fan-out limitado com semáforos globais.
- Omissão graciosa de sections falhas (exceto slots portantes `header` e `accounts`, conforme ADR-009).
- Escada de fallback (ADR-007): 200 OK -> 200 OK com omissão -> 200 Cache -> 200 Last Good -> 503 Retry-After.

## 10. Ordem e Status das Histórias

- **H00:** Concluída. Gates de contrato e fixture verdes (identidade da fixture, catálogo, actions, sem visual,
  round-trip Jackson 3).
- **H01–H13:** Concluídas. Código produtivo e testes completos (Negotiate→Envelope, persistência em memória e MongoDB,
  admin maker-checker, cache/fallback escalonado, canary iOS e métricas Micrometer). Quality Gate APROVADO.
- **H14–H18:** Concluídas no servidor. Isolamento Android integralmente implementado (pointer/cache/select
  independentes,
  matriz de capabilities e canary Android). Fixture `contrato-sdui-home-android-proposto.json` aguarda definição formal
  da equipe Android — conteúdo não inferido a partir do iOS.
- **Pós-H18:** Auditoria multidimensional (`code-review-and-quality`) e auditoria de performance
  (`performance-optimization`) realizadas e consolidadas. 7 correções de qualidade e 11 otimizações de performance,
  concorrência e resiliência aplicadas (Seção 21).

## 11. Lacunas Documentais Registradas

- ADRs canônicos (ADR-001 a ADR-022) estão unificados e narrados na Seção 11 de `docs/arquitetura-de-referencia.md`.
- Presente: `docs/README.md` — catálogo da documentação canônica em 3 arquivos (`arquitetura-de-referencia.md`,
  `guia-depreciacao-e-migracao.md`, `guia-criacao-telas-componentes.md`).
- Presente: `sdui-contract/src/test/resources/fixtures/contrato-sdui-home-definitivo.json`. Fonte de verdade do contrato
  Home iOS (exemplos em `docs/examples/screens/`).
- `documentacao-contrato-sdui-home-v3.docx`: removido de propósito. Não recriar. Semântica de campo vive no JSON
  canônico e nos testes de `sdui-contract`.
- Ausente: `contrato-sdui-home-android-proposto.json` (H14 pendente de fornecimento pela equipe mobile).
- Skill `sdui-backend`: não disponível; marcador em `.agents/skills/sdui-backend/README.md`. A skill ausente não
  bloqueia o que já está especificado nas histórias, nos ADRs, nos guias e no contrato. As skills procedurais do
  projeto (§15) derivam só de documentos canônicos e não substituem o marcador.
- Presente: `.agents/README.md` (índice dos papéis, regras e skills) e os adaptadores do Claude Code em `.claude/`
  (`agents/`, `skills/` e `settings.json`, que nega `git add`, `git commit` e `git push`).
- Presente: catálogo das histórias concluídas do MVP (`H00`–`H18`) consolidado na Seção 12 de
  `docs/arquitetura-de-referencia.md`.
- Presente: ADRs 020 a 022 consolidados na Seção 11 de `docs/arquitetura-de-referencia.md`; `docs/contratos/` com os
  contratos propostos; `docs/examples/screens/` com os quatro exemplos (skeleton, spec, resposta, matriz de
  rastreabilidade); `docs/guia-criacao-telas-componentes.md`.
- Pendente de terceiros: homologação móvel dos contratos novos e ensaio operacional da persistência.

## 12. Decisões Provisórias

- **Pacote Base Canônico:** `br.com.empresa.sdui`, estruturado por camadas (`.contract`, `.core`, `.orchestrator`,
  `.adapters`, `.api`, `.bootstrap`, `.it`).

## 13. Pendências Temporárias

- **`allowEmptyShould(true)` no ArchUnit:** removido das regras de `core`, `orchestrator`, `adapters` e `api` após essas
  camadas receberem classes de produção.

## 14. Regra para ADRs

Novas decisões estruturais exigem ADR registrado na Seção 11 de `docs/arquitetura-de-referencia.md` (não existe
diretório `docs/adr/`): uma linha na matriz de decisões (colunas ADR, Título, Status, Escopo Principal) e uma entrada
`#### ADR-NNN — <Título>` com Status, Contexto, Decisão e Consequências, no molde do ADR-001; quando houver, também
Alternativas descartadas e Verificação. ADR-023 a ADR-026 estão reservados em `docs/tasks/plano-tokens-semanticos.md`.
Procedimento na skill `sdui-adr`.

## 15. Papéis especializados e skills

Os papéis estão em `.agents/agents/` e o índice em `.agents/README.md`. São instruções de desenvolvimento, não
componentes do runtime.

Papel padrão deste modo: **implementação** (`.agents/agents/sdui-implementer.md`), importado abaixo para que toda
sessão o carregue junto com este arquivo. Escrever o código sem esperar o fluxo completo de papéis.

@.agents/agents/sdui-implementer.md

Os outros papéis só são carregados quando o operador os pedir nominalmente:

- Arquitetura: `.agents/agents/sdui-architect.md`
- Testes (autoria de fontes de teste): `.agents/agents/sdui-tester.md`
- Guarda de Contrato: `.agents/agents/sdui-contract-guard.md`
- Revisão: `.agents/agents/sdui-reviewer.md`

No Claude Code, esses quatro papéis também existem como subagentes em `.claude/agents/`, adaptadores que só apontam
para o arquivo canônico e restringem ferramentas (revisor e guarda de contrato sem escrita).

Skills (formato Agent Skills, em `.agents/skills/<nome>/SKILL.md`, com adaptadores em `.claude/skills/`):

- `sdui-component`: criar e depreciar componente (`type@typeVersion`).
- `sdui-surface`: surface nova.
- `sdui-adr`: registrar ADR na Seção 11 da arquitetura.
- `sdui-perf`: medir antes de otimizar (§23.14).
- `sdui-commit-message`: mensagem de commit, só a pedido do operador.

`AgentInstructionsIntegrityTest` (`sdui-integration-test`) falha quando uma instrução deste arquivo, do `.agents/` ou
do `.claude/` aponta caminho ou arquivo `.kt` inexistente, quando uma skill não tem frontmatter válido ou quando um
adaptador não aponta para o canônico. Quem renomeia ou move um símbolo citado aqui atualiza este arquivo no mesmo diff.

## 16. Comandos executados no bootstrap (histórico)

Registro único da inicialização. Não repetir como rotina de implementação.

```text
java -version
  OpenJDK 25.0.4.1 Temurin (build 25.0.4.1+1-LTS)

.\gradlew.bat --version
  Gradle 9.7.1 | Launcher JVM 25.0.4.1 | Kotlin do Gradle 2.4.0 (runtime do wrapper, não o Kotlin do projeto)

.\gradlew.bat clean build --warning-mode=fail
  BUILD SUCCESSFUL
```

## 17. Estado de Persistência (limitação operacional vigente)

O modo padrão é em memória (`sdui.persistence.store=memory`, `sdui.persistence.cache=memory`): todos os stores e
caches vivem no heap, e as autoconfigurações de Mongo e Redis continuam excluídas em `SduiApplication`. Existem
adapters MongoDB e Redis **opt-in** (ADR-021), com clientes montados pelo próprio serviço, mas eles **não foram
homologados operacionalmente** (ensaio de restart, perda de Redis, indisponibilidade do Mongo, publicação concorrente
e duas instâncias em base representativa pendente de execução). Detalhes na Seção 8 de
`docs/arquitetura-de-referencia.md`.
Enquanto não forem homologados, as consequências abaixo valem para qualquer decisão de deploy:

- O estado não sobrevive a restart. O que existe após subir é o que o seed reconstrói.
- O estado não é compartilhado entre instâncias: publicar, aprovar ou fazer rollback em um pod não
  muda nada nos demais, e o pointer pode divergir entre réplicas.
- Enquanto isso valer, o serviço só opera corretamente como instância única, ou com o plano de
  administração (`/admin/v1/**`) dirigido a uma instância designada.

Os adapters persistentes vieram com ADR próprio (ADR-021), documentos e índices definidos e testes que só rodam
com infraestrutura real (`SDUI_IT_MONGO_URI`, `SDUI_IT_REDIS_URL`). Esta seção só muda para "persistência
operacional" depois do ensaio registrado; build verde, mocks ou containers saudáveis não bastam.

## 18. Diretrizes de Qualidade e Concorrência Consolidadas (Pós-Review)

Regras inegociáveis resultantes do ciclo de auditoria técnica (`code-review-and-quality` e `performance-optimization`):

1. **Singleflight Concorrente:** Waiters que sofrem timeout local no `ComposeSingleflight` **nunca** executam
   `existing.cancel(true)`. Devem retornar `SingleflightOutcome.WaitTimeout` (`data object`) deixando o líder concluir
   a computação normalmente (implementação em `InMemoryComposeSingleflight`).
2. **Parsing SemVer Seguro:** Todo parsing de números em SemVer (`SemVer.kt`) deve utilizar
   `.toIntOrNull() ?: return null`.
   Proibido lançar `NumberFormatException` que possa vazar como HTTP 500 no `Negotiate`.
3. **Serialização de Passo Único no Hot Path:** No 200, o `SurfaceController` deve retornar o `ByteArray`
   pré-serializado diretamente (`ResponseEntity<ByteArray>`) com `MediaType.APPLICATION_JSON`. Nunca repassar
   instâncias de objeto de resposta para o Spring re-serializar. As respostas de erro (400, 429, 503) devolvem
   `ApiErrorResponse` serializado pelo Spring; a regra vale para o caminho de sucesso.
4. **Constantes Pré-calculadas em Validações:** Em classes de guardas (`Guards.kt`), sets de chaves restritas
   (`LOWER_VISUAL_KEYS`, `LOWER_PII_KEYS`) devem ser `private val` pré-calculados, evitando alocações no loop recursivo.
   Os conjuntos de origem são `MvpCatalog.VISUAL_KEYS` e `MvpCatalog.PII_KEYS` (`sdui-core`, `model/Capability.kt`).
5. **Estabilidade de Ordenação em Filter:** A ordenação de seções em `Filter.kt` deve utilizar `sortBy` (estável, sobre
   a lista mutável) pela ordem de slots do skeleton. Não introduzir comparadores secundários com busca linear O (N)
   (`indexOf`), aproveitando a estabilidade do TimSort.
6. **Limpeza de Chaves Redis:** Assinaturas de métodos geradores de chaves (`object RedisKeys`, em `sdui-core`,
   `model/Screen.kt`) devem conter apenas parâmetros efetivamente interpolados na chave, e garantir
   `!RedisKeys.containsUserId(key)`.
7. **Fechamento de Recursos:** Qualquer leitura de stream de arquivo ou classpath (`ClassPathResource`) deve ser
   envolvida
   por `.use { }` para garantir encerramento do recurso e evitar vazamentos de file descriptors.

## 19. Diretrizes do Segundo Ciclo de Revisão

Regras inegociáveis resultantes da revisão multidimensional de 2026-09-20 (`code-review-and-quality`):

1. **Capabilities do Header São Filtradas:** `CapabilityMatrix.effective` só soma ao conjunto do servidor
   as capabilities que pertencem ao universo conhecido (`byPlatformVersion` + `ComponentContracts.APPROVED`). Uma
   capability arbitrária nunca casaria com uma section, mas entraria no `capsHash` e criaria uma chave de
   cache nova por requisição. `Capability.parseList` deduplica e aplica `MAX_HEADER_CAPABILITIES`.
2. **Todo Cache e Mapa Alimentado por Entrada do Cliente Tem Teto:** `InMemoryHydratedScreenCache`
   (`treeCacheMaxEntries`) e `TokenBucketRateLimiter` (`rateLimitMaxKeys`) podam entradas. Proibido
   introduzir mapa residente cuja chave derive de header sem limite de tamanho.
3. **Sem Lock Global no Hot Path:** o rate limiter usa `ConcurrentHashMap.compute`, que dá exclusão
   mútua por chave. Proibido reintroduzir um `ReentrantLock` único cobrindo todas as identidades.
4. **Nome de Métrica é Constante:** dimensão vai em tag, nunca interpolada no nome do meter.
5. **Cache Fora da Transação:** `specCache` e `treeCache` só são escritos depois do commit. Escrita de
   cache dentro de `tx.execute` publicaria estado que um rollback ainda pode desfazer.
6. **`Idempotency-Key` é Honrada ou Não é Aceita:** `open`, `approve`, `reject` e `rollback` consultam e
   gravam `IdempotencyStore`. Proibido parâmetro de idempotência ignorado na assinatura.
7. **Sem Valor de Integridade Inventado:** o `skeletonHash` do envelope é o `checksum` do spec. O formato
   `sha256:<hex>` é exigido por `SpecValidator` na governança; não existe literal de reserva no hot path.
8. **Falha de Dependência de Dados é Tratada em Um Ponto:** `ComposeScreenService.composeFresh` envolve
   todas as leituras de store num único `catch` que reporta `REDIS_UNAVAILABLE`. `DEPENDENCY_TIMEOUT`
   fica reservado ao singleflight.
9. **Test Double Não Entra em Produção:** o fallback sem `MeterRegistry` é `NoOpMetricsRecorder`.
   `RecordingMetrics` vive em `src/test`.
10. **Chave de Cache Coerente com a Seleção:** a árvore é chaveada por `specRevisionId`, e a seleção
    roda antes da consulta ao cache. O `Targeting` discrimina por versão completa do app e por versão
    de SO — dimensões que não cabem na chave sem explodir a cardinalidade. Proibido voltar a montar a
    chave a partir do contexto do cliente: isso reintroduz a entrega de uma árvore que o targeting
    teria recusado. O custo aceito é uma leitura de pointer e de specs publicados por requisição,
    inclusive em acerto de cache.
11. **Campos do Requisitante São Reidratados no Acerto de Cache:** `client`, `locale` e `generatedAt`
    vêm sempre da requisição corrente (`withRequester`), nunca da árvore cacheada. Esses campos
    existem para auditar a composição; servidos do cache, reportariam o dispositivo que compôs
    primeiro.
12. **Um `application.yaml` Só:** apenas `sdui-bootstrap` tem `application.yaml`. O Spring Boot resolve
    `classpath:/application.yaml` para um único recurso; um segundo arquivo em módulo biblioteca faz a
    configuração vencedora depender da ordem do classpath.

## 20. Política de Resiliência de Integração (ADR-014)

Regras inegociáveis do ciclo de resiliência de integração, consolidadas na Seção 6 e Seção 11 (ADR-014) de
`docs/arquitetura-de-referencia.md`.

1. **Sem Retry de Dependência no Servidor:** a escada de fallback (ADR-007) é a política de degradação.
   Acrescentar retry sobre store multiplicaria a carga exatamente quando a dependência está fraca.
   Retry é do cliente móvel, sobre um `GET` idempotente, com orçamento e jitter declarados.
2. **Orçamento Limita Espera, Nunca Trabalho:** toda requisição abre um `TimeBudget`, e só as
   esperas — permissão de bulkhead e espera pelo líder do singleflight — recebem `budget.stage(teto)`.
   Orçamento estourado emite `compose.deadline.exceeded` e o pipeline segue. Proibido abortar
   composição já iniciada por prazo: num pod recém-subido isso vira `503` por JVM fria, com o
   `lastGood` ainda vazio. A espera do waiter é sempre menor que o orçamento total.
3. **Todo Desfecho Degradado Emite Métrica:** `503`, recusa de bulkhead, prazo estourado, falha de
   store, escrita de cache perdida e last good vencido têm contador próprio. Proibido `catch` mudo no
   pipeline de composição — um cache que lê e recusa gravar precisa ser distinguível de operação normal.
4. **`Retry-After` Sempre com Jitter:** a chave do limitador é `plataforma:build`, uma coorte. Valor
   constante devolve a coorte inteira no mesmo segundo e repete o pico que causou a recusa.
5. **Fallback Tem Prazo de Validade:** acima de `max-fallback-age-seconds` o last good é recusado em
   favor do `503`. Indisponibilidade visível é preferível a incorreção silenciosa.
6. **Publicação e Rollback Invalidam o Last Good:** junto com o cache de árvore e sempre depois do
   commit. Sem isso o fallback reintroduz a revisão que o operador acabou de retirar.
7. **Idempotência é Reserva, Não Gravação no Fim:** `reserve` toma a chave antes do efeito, de forma atômica (em memória
   sob o lock de escrita da governança; no Mongo por `insertOne` com chave única), e o resultado da
   reserva é conferido; `complete` fecha no mesmo commit do efeito; `release` devolve a chave
   quando a operação falha sem efeito. `find`-depois-`put` deixa dois retries concorrentes executarem.
8. **Bulkhead Explícito no Plano de Leitura:** Virtual Threads removeram o pool limitado que fazia
   shedding. O teto de leituras simultâneas é declarado e separado do plano administrativo.
9. **Prazo por Chamada é do Adapter:** o orçamento não interrompe chamada síncrona em andamento.
   Quando Mongo e Redis forem cabeados, o timeout de socket do driver é obrigatório — e não um
   parâmetro `Duration` decorativo na assinatura das portas.

## 21. Diretrizes de Performance, Concorrência e Resiliência (Pós-Auditoria de Performance)

Regras inegociáveis consolidadas no ciclo de auditoria de concorrência e otimização de performance:

1. **Liberação Ativa de Semáforo sob Timeout no Fan-out:** Em `HydrationCoordinator`, tarefas assíncronas
   despachadas em Virtual Threads devem rastrear a thread em execução (`taskThread`). Quando `orTimeout`
   expira, `taskThread.interrupt()` deve ser acionado para cancelar bloqueios de I/O e assegurar que o
   permit do semáforo de fan-out seja liberado no `finally`, impedindo o esgotamento cascateado do bulkhead.
2. **Isolamento de Observabilidade no Pipeline Assíncrono:** Registros de métricas dentro de handlers
   assíncronos (como `.handle` em `CompletableFuture`) devem ser protegidos com `runCatching { }`. Falhas do
   Micrometer ou registradores de métricas nunca podem vazar como `CompletionException` não tratada no `join()`,
   garantindo que a resposta siga a escada de fallback (ADR-007) sem gerar HTTP 500 indevido.
3. **Poda e Retenção em Stores em Memória:**
    - `InMemoryProjectionStore` varre projeções vencidas no máximo uma vez por `sweepIntervalMs` (padrão 60 s),
      disparado por leitura ou escrita, e poda para metade no teto `maxEntries = 10_000`.
   - `InMemoryAuditLogStore` é uma lista FIFO com teto de retenção (`maxEvents = 2_000`). A medição de ~250 ns por
     append (2026-09-23) perdeu o registro de origem e o `append` copia a lista a cada chamada sob o lock global;
     remedir com `:sdui-app:perfHarness -Pscenarios=audit` antes de decidir sobre anel (§23.14,
     `docs/tasks/todo.md`).
4. **Propriedades Imutáveis Pré-calculadas no Domínio:**
    - `Section` pré-calcula `val capability: Capability = Capability(type, typeVersion)` no construtor.
      Proibido instanciar novos objetos `Capability` a cada section no hot path do `Filter.filter`.
    - `Skeleton` pré-calcula `val slotOrder: Map<String, Int>` e `val requiredSlotIds: Set<String>`, eliminando
      recriação de mapas e listas intermediárias por requisição.
5. **Lookups O (1) em Enums de Protocolo:**
    - `SlotLayout.parse` e `ActorRole.parse` devem utilizar mapas indexados estáticos pré-computados (`BY_WIRE` e
      `BY_NAME`), eliminando alocações repetidas de strings e varreduras lineares $O (N)$.
6. **Constantes Estáticas e Limite de Recursão:**
    - `ScreenResponseMapper` mantém `BRASIL_OFFSET = ZoneOffset.of("-03:00")` e teto de profundidade em `toNode()`
      (acima de `MAX_RECURSION_DEPTH = 32` o nó vira `"[truncated]"`); `JsonMaps.toValue` tem o mesmo teto
      (`MAX_DEPTH = 32`), mas recusa com `require`. Ambos blindam a JVM contra `StackOverflowError` sob payloads
      profundos.
7. **Limites Rígidos em Entradas:**
    - Expressões regulares de `BUILD` e `SCHEMA` em `Negotiate.kt` devem delimitar `^\d{1,10}$`.
    - `Capability.parseList` aplica `.take(MAX_HEADER_CAPABILITIES * 2)` logo na sequência para blindar o
      processamento contra cabeçalhos com milhares de itens duplicados.
8. **Interrupção Antecipada em Validações:**
    - `PropWalk.referencesForeignSection` deve interromper a busca imediatamente ao detectar a primeira violação
      (curto-circuito de `any` em `anyString` e `anyKey`), poupando travessias profundas desnecessárias em specs
      inválidos.

## 22. Diretrizes de Observabilidade e Instrumentação

Regras consolidadas no ciclo de observabilidade e instrumentação:

1. **Correlation ID e MDC no SLF4J:** Requisições capturam `X-Request-Id` (ou geram UUID v4) via `CorrelationIdFilter`,
   alimentando a chave `requestId` no MDC do SLF4J com limpeza estrita no `finally`.
2. **Preservação do Contrato de Headers:** O servidor **nunca** emite cabeçalhos customizados com prefixo `X-` na
   resposta de `GET /v1/surfaces/home` (RFC 6648 e conformidade com `HomeComposeContractWebTest`).
3. **Sincronização de Contexto de Diagnóstico:** `ComposeTraceContext` sincroniza os campos técnicos (`surface`,
   `platform`,
   `schemaVersion`) diretamente no MDC do SLF4J no `open()`, limpando-os no `close()`.
4. **Telemetria no Plano Administrativo:** Mutações no `/admin/v1/**` (criação de rascunho, abertura de publicação,
   aprovação,
   rejeição e rollback) emitem métricas Micrometer (`admin.*`) e logs estruturados contendo ator e motivo, sem PII.
5. **Structured Logging (ECS JSON):** O console emite logs estruturados no padrão Elastic Common Schema (ECS),
   garantindo indexação imediata de campos de MDC e rastreamento ponta a ponta em plataformas de telemetria.
6. **Propagação de MDC no Fan-out (`MdcPropagatingExecutor`):** Virtual threads assíncronas de hidratação recebem o
   contexto MDC herdado da thread principal através de executor decorador em `adapters`, garantindo continuidade de
   rastreabilidade sem violar a pureza do pacote `orchestrator` do `sdui-app`.
7. **Ponto de Entrada Carimbado (`entryPoint` no MDC):** Requisições e processos carimbam `entryPoint` (`home`,
   `catalog`, `surface`, `admin`, `actuator`, `http`, `seed`, `demo`, `invalidation_relay`) no MDC, eliminando
   diagnósticos por eliminação em sinks de logs compartilhados.
8. **Histogram Buckets no Prometheus:** Timers críticos (`compose.duration`, `section.hydrate.ms`, `mapping.ms` e
   `serialize.ms`) possuem
   `percentiles-histogram: true` configurado no `application.yaml`, viabilizando alertas e painéis de P95/P99
   sobre `compose_duration_seconds_bucket`.
9. **Gauges de Recursos USE:** `rate_limiter.resident_keys` e `compose.bulkhead.available_permits` são expostos
   como gauges instantâneos no Micrometer via `MeterBinder`, monitorando saturação e utilização de recursos internos.
10. **Proteção contra Cardinalidade em Métricas:** Nenhuma métrica administrativa ou de hot path interpola parâmetros
    arbitrários de path em tags (ex: `admin.skeleton.upsert` não tagueia `skeletonId`, confinado ao log estruturado).

## 23. Diretrizes do Ciclo de 2026-09-23 (Surfaces, Contratos, Persistência e Integridade de Governança)

Regras resultantes da entrega de surfaces adicionais (`catalog`, ADR-020), persistência durável opt-in (ADR-021) e
integridade de governança com limites de entrada (ADR-022):

1. **Surface é Allowlist:** toda surface vem de `Surfaces` (core) e tem mapeamento HTTP literal em
   `SurfaceController`. Proibido `/v1/surfaces/{surface}` genérico, surface como texto livre em chave, tag ou
   documento, e fallback/árvore de uma surface servidos a outra.
2. **Catálogo Fechado em Contratos Aprovados:** `CatalogValidator` só aceita `ComponentContracts.APPROVED` (qualquer
   status) e exige os sete types da Home ativos. Componente novo exige contrato em `docs/contratos`, ADR e regras em
   `ComponentPropsValidator` antes de entrar no catálogo.
3. **Type Novo Só por Capability Declarada:** a matriz do servidor não concede contrato novo a nenhuma faixa de app.
   Slot portante que dependa de type novo declara a capability em `targeting.requiredCapabilities`.
4. **Seleção Pelo Pointer Primeiro:** a revisão apontada é resolvida pelo cache de spec/`findByRevisionId` antes de
   listar publicadas; o resultado tem de ser idêntico ao de `Select.select` sobre a lista inteira. A seleção continua
   antes do cache (§19.10).
5. **Singleflight Relê o Cache e Reidrata o Waiter:** o líder consulta o cache de novo ao assumir; o waiter recebe
   `withRequester` com o próprio contexto.
6. **Hidratador Declara I/O:** `SectionHydrator.performsIo = false` roda na thread da requisição; só hidratador com
   I/O vai para o fan-out em virtual threads. Telemetria e omissão iguais nos dois caminhos.
7. **Tags Sem Versão de App:** versão exata do app vai para o MDC (`appVersion`), nunca para tag; `schemaVersion`
   só como valor suportado ou `other`. Nomes em `MetricNames`; o `CardinalityGuardMeterFilter` nega meter novo acima
   de `sdui.metrics-max-tag-values` por tag. Todo nome de métrica mantém o mesmo conjunto de chaves de tag.
8. **Idempotência Sem Expulsão e Com Fingerprint:** registro vivo nunca sai por pressão; no teto em memória a
   admissão é recusada (503). Reuso da chave com outra operação ou outros parâmetros é 422. Reserva em voo vence em
   `idempotency-reservation-timeout-seconds`.
9. **Pointer por Compare-and-Set:** publicação e rollback movem o pointer com `compareAndSet(versaoLida, novo)`;
   conflito é 409 e nunca é repetido pelo servidor. `save` sem versão é só para seed e preparação de teste.
10. **Invalidação por Outbox e Lápide:** a invalidação é registrada no outbox dentro da transação e aplicada depois
    do commit; o last good só aceita árvore de versão de pointer ≥ à lápide. Relay reaplica pendências.
11. **Cache com Teto Estrito:** a vaga é reservada por compare-and-set num contador antes da inserção e devolvida
    depois da remoção; no teto, uma thread poda e as demais descartam a escrita. Proibido decidir o teto por
    `ConcurrentHashMap.size()` (estimativa sob escrita concorrente) e proibido inserir por cima de uma poda.
12. **Persistência Explícita:** `sdui.persistence.store`/`cache` inválido ou URI ausente falha a subida; modo
    persistente nunca cai para memória. Mongo sem `withTransaction` e com `retryWrites/retryReads=false`; Redis com
    `REJECT_COMMANDS` e prazo curto. Formato de documento e de cache versionado (`_v`, `v`).
13. **Listagens Administrativas Paginadas:** `offset`/`limit` (padrão 100, teto 500); valor fora da faixa é 400.
14. **Medir Antes de Otimizar:** mudança de performance entra com medição antes/depois no mesmo ambiente
    (`gradlew :sdui-app:perfHarness`, `:sdui-app:loadTest`); ganho dentro do ruído é rejeitado e registrado como tal.
