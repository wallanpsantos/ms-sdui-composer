# Plano — evolução do `.agents/`: papéis, regras, skills e guarda contra deriva

Status: **EXECUTADO em 2026-09-25 com as decisões D1 a D4 no padrão recomendado.** `clean build --warning-mode=fail`
verde, com os quatro testes do `AgentInstructionsIntegrityTest` executados. Pendente: as verificações do Claude Code
numa sessão nova (Checkpoint E) e o ajuste local do achado 16. Resultado de uma revisão por leitura do `.agents/`, do
`AGENTS.md` e do código que eles citam, feita em três levantamentos independentes (build, testes, docs e CI; símbolos
citados no AGENTS.md §18–§23; formatos de agentes e skills nas ferramentas). A execução segue o AGENTS.md: sem Gradle
por tarefa, execução única no checkpoint final e nenhum `git add`, `commit` ou `push` pelo agente. Nada muda no
runtime do serviço.

## Resposta curta

- O `.agents/` parou no tempo do MVP. O índice `papel-agents.md` repete os cinco papéis com texto divergente e aponta
  fontes que não existem. Os papéis ainda mandam implementar `H01`–`H18`. O tester prescreve bibliotecas que o projeto
  não tem, e o arquiteto manda criar ADR em `docs/adr/`, removido na consolidação.
- O AGENTS.md, que os papéis tratam como regra inegociável, cita nove nomes, arquivos ou mecanismos que não batem com o
  código. Um agente procura `RedisKeys.kt` e não acha, porque `RedisKeys` mora em `Screen.kt`.
- Nenhum papel cita as regras pós-review (§18–§23). Nenhum papel é executável como subagente, e a governança de Git só
  existe em prosa.
- A proposta tem sete fases:
    1. fonte única e higiene;
    2. AGENTS.md fiel ao código, com uma só política de Gradle;
    3. papéis do modo pós-MVP, com checklists por regra e convenções verificadas no código;
    4. cinco skills procedurais tiradas dos guias canônicos;
    5. adaptadores no Claude Code, com ferramentas restritas e `git add/commit/push` negados por configuração;
    6. um teste que falha quando uma instrução aponta um caminho ou arquivo inexistente;
    7. backlog dos 15 achados de código e documentação que ficaram fora do escopo (um 16º é ajuste local do operador).
- Só a Fase 6 acrescenta código (um teste em `sdui-integration-test`) e exige a execução única de Gradle.

## Decisões pendentes do operador

A execução usa o padrão recomendado de cada decisão, a menos que o operador mude antes.

| Id | Decisão                                                                   | Padrão recomendado                                                                                                                                                                                                                                                   | Alternativa                                               |
|----|---------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------|
| D1 | Política de Gradle (AGENTS.md L76-80 tem três formulações)                | Uma execução no fim do recorte, nunca entre tarefas: `.\gradlew.bat clean build --warning-mode=fail`. Em falha, corrigir e rodar mais uma vez; persistindo, reportar a saída e parar. `perfHarness` e `loadTest` só a pedido. Outra execução só a pedido do operador | Só com pedido do operador (remover a L77 "Após todas…")   |
| D2 | Padrão de commit duplicado (AGENTS.md L27-72 = `git-commit-standards.md`) | Governança fica no AGENTS.md; o formato da mensagem vive só em `.agents/rules/git-commit-standards.md` e o AGENTS.md o importa com `@.agents/rules/git-commit-standards.md` (o Claude Code continua carregando o texto em toda sessão)                               | Manter as duas cópias e corrigir ambas                    |
| D3 | Adaptadores e bloqueio de Git no Claude Code (Fase 5)                     | Sim                                                                                                                                                                                                                                                                  | Pular a Fase 5; os papéis seguem carregados por instrução |
| D4 | Teste de integridade das instruções (Fase 6)                              | Sim                                                                                                                                                                                                                                                                  | Só as buscas da verificação global, feitas à mão          |

## Diagnóstico (verificado em 2026-09-25)

| Tema                       | Hoje                                                                                                                                                         | Evidência                                                                                                                                                                                                         |
|----------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Índice do `.agents/`       | `papel-agents.md` (597 linhas) copia os cinco papéis com texto divergente e cita fontes inexistentes                                                         | `.agents/papel-agents.md:69-70, 536-538` (`plano-servico-sdui.md`, `resumos-server-driven-ui.md`, `artifacts/`); implementer "Java 25, Spring Framework 7.x" (L142-143) × Kotlin 2.4.20 (`sdui-implementer.md:5`) |
| Modo dos papéis            | Mandam escrever `H01`–`H18`                                                                                                                                  | `sdui-implementer.md:7-8, 18`; `sdui-architect.md:9-10, 41`; `papel-agents.md:22-25, 581-583` × AGENTS.md L3-20 (MVP concluído)                                                                                   |
| Persistência nos papéis    | "MongoDB como fonte da verdade das specs e Redis como cache"                                                                                                 | `papel-agents.md:593-594` × AGENTS.md §17 (memória é o padrão; Mongo e Redis opt-in, não homologados)                                                                                                             |
| Regras pós-review          | Nenhum papel cita §18–§23                                                                                                                                    | busca por `§18`–`§23` e `Seção 18`–`23` em `.agents/` sem resultado                                                                                                                                               |
| Ferramentas de teste       | Tester prescreve MockK, springmockk e Testcontainers                                                                                                         | `sdui-tester.md:34-35`; MockK e springmockk fora do `gradle/libs.versions.toml`; Testcontainers declarado (L34-36) sem uso; nenhum `src/test` usa framework de mock                                               |
| Chaves proibidas           | O índice lista 14 itens visuais em prosa; o código tem 32                                                                                                    | `papel-agents.md:389-406` × `MvpCatalog.VISUAL_KEYS` (`sdui-core/.../model/Capability.kt:373-380`)                                                                                                                |
| Contrato e catálogo        | Contract guard não cobre surfaces, catálogo nem contrato de componente novo (ADR-020)                                                                        | `sdui-contract-guard.md:15-24`; AGENTS.md §23.1–23.3                                                                                                                                                              |
| ADR                        | Papel e AGENTS.md mandam criar `docs/adr/ADR-XXX-<slug>.md`; o diretório não existe desde `24f8a13`                                                          | `sdui-architect.md:28`; AGENTS.md L221; ADRs em `docs/arquitetura-de-referencia.md:424-638` (§11)                                                                                                                 |
| Status ADR-020 a 022       | AGENTS.md diz `PROPOSTO` e aponta `tasks/todo.md`; a §11 diz `ACEITO` (`3d43bc2`) e o backlog está em `docs/tasks/todo.md`                                   | AGENTS.md L17-19; `docs/arquitetura-de-referencia.md:449-451`                                                                                                                                                     |
| Política de Gradle         | Três formulações diferentes                                                                                                                                  | AGENTS.md L76-80; `sdui-implementer.md:21, 64`; `plano-tokens-semanticos.md:310-313`                                                                                                                              |
| Padrão de commit           | Descrições truncadas, exemplos com espaço antes do parêntese e texto duplicado                                                                               | `git-commit-standards.md:44-52, 58-66` = AGENTS.md L53-65; histórico real usa `remove(docs):` (`24f8a13`)                                                                                                         |
| AGENTS.md × código         | Nove citações com nome, arquivo ou mecanismo diferente                                                                                                       | tabela do T04                                                                                                                                                                                                     |
| Execução pelas ferramentas | Papéis sem frontmatter; o Claude Code só descobre subagentes em `.claude/agents/` e skills em `.claude/skills/`; o Codex lê `.agents/skills/<nome>/SKILL.md` | Fase 0 › Formatos; `.agents/skills/sdui-backend/` só tem `README.md`                                                                                                                                              |
| Governança de Git          | Só em prosa; não há `.claude/settings.json` versionado                                                                                                       | AGENTS.md L22-26                                                                                                                                                                                                  |
| Buscas                     | Cópias obsoletas de IDE em `*/bin/` (ignoradas pelo git) aparecem em `grep -r` e não existem em `src/`                                                       | ex.: `sdui-app/bin/main/br/com/empresa/sdui/api/http/HomeController.kt`, `sdui-app/bin/main/application.yaml`                                                                                                     |

## Fase 0 — Fatos verificados e formatos permitidos

Toda fase lê esta seção antes de começar. O que não está aqui nem na fonte citada não entra nos arquivos.

### Formatos de ferramenta

| Ferramenta  | Formato confirmado                                                                                                                                                                                                                                                                     | Fonte                                                                                |
|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| Claude Code | Subagente de projeto em `.claude/agents/<nome>.md`; frontmatter obrigatório `name` e `description`; `tools` é allowlist (`Read, Grep, Glob, Bash, Edit, Write`); `model` omitido herda o da sessão. A `description` guia a delegação automática                                        | https://code.claude.com/docs/en/sub-agents                                           |
| Claude Code | Skill em `.claude/skills/<nome>/SKILL.md`, com `name`, `description` e, opcional, `disable-model-invocation: true` (só o usuário invoca). Não lê `.agents/skills/`                                                                                                                     | https://code.claude.com/docs/en/skills                                               |
| Claude Code | Lê `AGENTS.md` quando não há `CLAUDE.md`; aceita import `@caminho` (relativo ao arquivo; ignorado dentro de bloco ou trecho de código)                                                                                                                                                 | https://code.claude.com/docs/en/memory                                               |
| Claude Code | `permissions.deny` com `Bash(git commit *)`; o `*` final com espaço também casa o comando sem argumentos; `:*` é equivalente; `PowerShell(...)` tem a mesma forma. Deny de qualquer escopo vence allow e vale para cada subcomando de comando composto. **Não** casa `git -C . commit` | https://code.claude.com/docs/en/permissions (seções de wildcard, compound e limites) |
| Codex       | Skills de repositório em `.agents/skills/<nome>/SKILL.md`, do diretório corrente até a raiz; `name` e `description` obrigatórios; opcionais `scripts/`, `references/`, `assets/` e `agents/openai.yaml` (`allow_implicit_invocation`)                                                  | https://learn.chatgpt.com/docs/build-skills                                          |
| Nenhuma     | Não foi confirmada ferramenta que leia `.agents/agents/*.md` nativamente. Os papéis continuam prompts carregados por instrução                                                                                                                                                         | —                                                                                    |

### Fontes de verdade no código

Os papéis e as skills apontam para estes símbolos em vez de repetir listas em prosa. `CORE` =
`sdui-core/src/main/kotlin/br/com/empresa/sdui/core`; `APP` = `sdui-app/src/main/kotlin/br/com/empresa/sdui`.

| Tema                     | Símbolo                                                                                            | Local                                                                                                                         |
|--------------------------|----------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| Chaves visuais (32)      | `MvpCatalog.VISUAL_KEYS`                                                                           | `CORE/model/Capability.kt:373-380`                                                                                            |
| Espelho no contrato      | `NoVisualAttributesTest.FORBIDDEN_VISUAL_KEYS`, travado por `VisualKeysAlignmentTest`              | `sdui-contract/src/test/.../NoVisualAttributesTest.kt:13-20`; `sdui-integration-test/src/test/.../VisualKeysAlignmentTest.kt` |
| Chaves de PII (14)       | `MvpCatalog.PII_KEYS`                                                                              | `CORE/model/Capability.kt:396-399`                                                                                            |
| Guardas                  | `VisualGuard`, `PiiGuard`                                                                          | `CORE/validate/Guards.kt:32-36, 90-94`                                                                                        |
| Surfaces                 | `Surfaces.HOME`, `Surfaces.CATALOG`, `ALL`, `find`                                                 | `CORE/model/Surface.kt:255-410`                                                                                               |
| Contratos aprovados (10) | `ComponentContracts.APPROVED`                                                                      | `CORE/model/Surface.kt:497`                                                                                                   |
| Props por componente     | `ComponentPropsValidator`                                                                          | `CORE/validate/ComponentPropsValidator.kt:115-120`                                                                            |
| Catálogo                 | `CatalogValidator`                                                                                 | `CORE/validate/CatalogValidator.kt:58-80`                                                                                     |
| Chaves Redis             | `object RedisKeys`                                                                                 | `CORE/model/Screen.kt:329-467`                                                                                                |
| Nomes de métrica (37)    | `MetricNames`, lista `ALL`                                                                         | `APP/orchestrator/port/outbound/MetricNames.kt:27-585`                                                                        |
| Camadas (15 regras)      | `ArchitectureTest`                                                                                 | `sdui-integration-test/src/test/kotlin/br/com/empresa/sdui/it/ArchitectureTest.kt`                                            |
| Dependências proibidas   | `verifyForbiddenDependencies`, `verifyPureClasspath`                                               | `build-logic/src/main/kotlin/sdui.kotlin-base.gradle.kts:28-39`; `sdui.kotlin-library.gradle.kts:5-10`                        |
| Endpoints                | `SurfaceController` (`/v1/surfaces/home`, `/v1/surfaces/catalog`), `AdminController` (`/admin/v1`) | `APP/api/http/SurfaceController.kt:88, 106`; `APP/api/admin/AdminController.kt:80-81`                                         |
| Erro → HTTP              | `ApiExceptionHandler`                                                                              | `APP/api/http/ApiExceptionHandler.kt:65-265`                                                                                  |

### Convenções verificadas no código de produção

- **Erros:** tipos selados no hot path (`ComposeResult` em `APP/orchestrator/port/inbound/UseCases.kt:132-301`,
  `BulkheadOutcome`, `SingleflightOutcome`, `HydrationResult`, `IdempotencyReservation`); exceções no admin e nos
  stores (`AdminErrors.kt`, `StoreConflict` e `StoreRejected` em `Stores.kt:33, 48`), mapeadas pelo
  `ApiExceptionHandler`. Sem `kotlin.Result` e sem value class.
- **Invariantes:** `require` e `check` (`Capability.kt:54-55`, `ComposeScreenService.kt:229`), `error(..)`.
- **Log:** SLF4J em `api` e `adapters`; `System.Logger` no `orchestrator` (`FallbackCoordinator.kt:344-350`), porque a
  regra 2 do `ArchitectureTest` só permite JDK, stdlib Kotlin e `core` ali.
- **Tempo:** bean `Clock` (`SduiConfiguration.kt:113-114`) injetado nos serviços; lambdas `clockMs` e `nanoTime` em
  estruturas de baixo nível (`TokenBucket`, `TimeBudget`).
- **Configuração:** `SduiProperties`, prefixo `sdui` (`SduiProperties.kt:47-95`). Exceção existente: `@Value` em
  `AdminRequestLimitFilter.kt:54-55`.
- **Métricas:** porta `MetricsRecorder` (`Stores.kt:801-814`), nome sempre de `MetricNames`, sem prefixo `sdui.`.
- **Mapeamento:** `ScreenResponseMapper.toResponse` (método de classe); extensões privadas nos adapters
  (`Document.toSpec()` em `MongoGovernanceStores.kt:310`). O admin recebe tipos de domínio no corpo
  (`@RequestBody spec: Spec`, `AdminController.kt:264`); documento de persistência nunca chega à `api` (regras 4 e 15
  do `ArchitectureTest`).

### Convenções verificadas nos testes

- JUnit Jupiter e AssertJ do BOM: `assertThat(..).`as`("..")`. Nomes em crase, em pt-BR sem acento (250 de 251
  `@Test`).
- Fixture em `companion object` com `@JvmStatic @BeforeAll`; `CanonicalHomeFixture` carrega o contrato canônico.
- Teste web: `@SpringBootTest(classes = [SduiAppTestConfiguration::class])`, `@AutoConfigureMockMvc`, `@Autowired` no
  construtor e DSL `mockMvc.get` (`sdui-app/src/test/.../api/HomeComposeContractWebTest.kt:17-34`). Headers prontos em
  `CanonicalHeaders`.
- Dublês manuais, como `RecordingMetrics` (`sdui-app/src/test/.../adapters/memory/RecordingMetrics.kt`). Nenhum
  Mockito, MockK ou `@MockitoBean`.
- Integração real só com infraestrutura: `@EnabledIfEnvironmentVariable(named = "SDUI_IT_MONGO_URI", matches = ".+")`
  (`MongoPersistenceIT.kt:61`), `SDUI_IT_REDIS_URL` (`RedisCachesIT.kt:41`) e ambos (`DurableModeBootIT.kt:31-32`).
- Sem `@DisplayName`, `@Nested`, `@ParameterizedTest`, `@Tag` ou `@Disabled`.
- Lugar de cada teste: `sdui-core` (puro, sem Spring), `sdui-contract` (fixture e Jackson), `sdui-app` (web,
  orchestrator, adapters, `perf`, `load`), `sdui-integration-test` (arquitetura e alinhamentos).

### Anti-padrões globais

- Inventar conteúdo da skill `sdui-backend` (AGENTS.md §11 L200-202). O marcador fica como está.
- Citar MockK, springmockk, Mockito ou Testcontainers como padrão do projeto.
- Criar `docs/adr/` ou apontar para ele.
- Copiar texto de regra do AGENTS.md para papéis e skills. Cita-se a seção (`§19.10`).
- Repetir em prosa listas que têm fonte no código (chaves visuais, PII, surfaces, contratos, métricas).
- Dar `Edit` ou `Write` a revisor e contract guard.
- Buscar código em `*/bin/`. Só `*/src/` é fonte.
- Executar `git add`, `git commit` ou `git push`, ou rodar Gradle entre tarefas.
- Mudar o sentido de uma regra ao corrigir o texto. Divergência de comportamento vai para o backlog (T20).

## Regra de verificação (vale para todas as tarefas)

- As fases 1 a 5 e 7 são documentação e configuração: verificação por busca e por leitura humana.
- A Fase 6 escreve um teste. Ele só roda no checkpoint final, uma vez, conforme a D1.
- Nenhuma tarefa é declarada concluída com base em teste não executado; o que não rodou fica registrado como tal.
- Cada fase cabe numa sessão nova: carregar o AGENTS.md, a Fase 0 e a fase corrente. Ao terminar, marcar `[x]`, parar
  no checkpoint e deixar o diff para o operador. A skill `sdui-commit-message` (T15) só gera a mensagem.

## Tarefas

### Fase 1 — Fonte única e higiene do `.agents/`

**Leia antes:** `.agents/papel-agents.md`, `.agents/agents/*.md`, `.agents/rules/git-commit-standards.md`, AGENTS.md
(Modo operacional L3-82, §11, §14, §15).

#### T01 — Índice `.agents/README.md` no lugar de `papel-agents.md`

- [x] **Descrição:** criar `.agents/README.md` com:
    - finalidade: instruções de desenvolvimento, não componentes do runtime (`papel-agents.md:596-597`);
    - árvore do diretório;
    - tabela Papel | Quando entra | Escreve arquivos? | Saída (de `papel-agents.md:36-42`);
    - fluxo padrão: só o implementer (`papel-agents.md:547-579`);
    - onde ficam regras e skills e como cada ferramenta os carrega (Fase 0 › Formatos);
    - regra de manutenção: as regras vivem no AGENTS.md, os papéis citam a seção e este índice não copia papel.

  Apagar `.agents/papel-agents.md` pelo sistema de arquivos. Hoje nenhum arquivo cita esse nome.
- **Aceite:**
    - Índice com até 80 linhas e nenhum trecho de papel copiado.
    - `grep -rnE "plano-servico-sdui|resumos-server-driven-ui|artifacts/|papel-agents" .agents AGENTS.md` sem resultado.
- **Verificação:** busca e leitura humana.
- **Dependências:** nenhuma.
- **Arquivos:** `.agents/README.md` (novo), `.agents/papel-agents.md` (removido).
- **Escopo:** S (documentação).

#### T02 — Padrão de commit íntegro e com fonte única (D2)

- [x] **Descrição:**
    - Em `.agents/rules/git-commit-standards.md`, completar as descrições truncadas: L44 "empacotament" →
      "empacotamento", L46 "sem m" → "sem mudar comportamento", L50 "arquivos de conf" → "arquivos de configuração",
      L52 "funcionali" → "funcionalidades".
    - Reescrever os exemplos (L56-72) no formato do cabeçalho, `tipo(escopo):` sem espaço antes do parêntese, como no
      histórico real (`🗑️ remove(docs): …`, `📚 docs: …`). Pelo menos um exemplo com corpo em tópicos.
    - Trocar a §1 do arquivo (governança, L3-8) por uma linha que aponta para o AGENTS.md › Modo operacional. A
      governança fica só no AGENTS.md, que toda ferramenta lê.
    - D2 padrão: no AGENTS.md, manter L22-26 (governança) e trocar L27-72 por um tópico curto ("mensagem de commit só a
      pedido do operador; formato abaixo") seguido, em linha própria e fora de bloco de código, de
      `@.agents/rules/git-commit-standards.md`.
    - D2 alternativa: aplicar as mesmas correções em AGENTS.md L53-72 e manter as duas cópias.
- **Aceite:**
    - `grep -rnE "empacotament$|sem m$|arquivos de conf$|funcionali$|(feat|fix|test|refactor) \(" AGENTS.md .agents`
      sem resultado.
    - O formato da mensagem é definido num só arquivo (D2 padrão).
    - Numa sessão nova do Claude Code, `/memory` lista `git-commit-standards.md` como importado.
- **Dependências:** nenhuma.
- **Arquivos:** `.agents/rules/git-commit-standards.md`, `AGENTS.md`.
- **Escopo:** S.

#### T03 — Referências quebradas e status no AGENTS.md e nos papéis

- [x] **Descrição:**
    - AGENTS.md L17-19: ADR-020, 021 e 022 passam a `ACEITO`, como na §11 (`3d43bc2`). Seguem pendentes a homologação
      móvel (020), o ensaio operacional de persistência (021) e a validação em build (022), acompanhados em
      `docs/tasks/todo.md`, e não em `tasks/todo.md`.
    - AGENTS.md §14 (L219-222): ADR nova entra na §11 de `docs/arquitetura-de-referencia.md`. Isso significa uma linha
      na matriz (L426-451, colunas ADR | Título | Status | Escopo Principal) e uma entrada
      `#### ADR-NNN — <Título>` com Status, Contexto, Decisão e Consequências, como o ADR-001 (L457-465).
    - `.agents/agents/sdui-architect.md:28`: o mesmo ajuste.
    - `.aiignore`: acrescentar `bin/`, para que as cópias obsoletas de IDE não entrem no contexto do assistente
      JetBrains.
- **Aceite:**
    - `grep -rnE "docs/adr|[^/]tasks/todo\.md" AGENTS.md .agents` sem resultado.
    - O status no AGENTS.md coincide com a matriz da §11.
- **Dependências:** nenhuma.
- **Arquivos:** `AGENTS.md`, `.agents/agents/sdui-architect.md`, `.aiignore`.
- **Escopo:** S.

#### Checkpoint A — fonte única

- [x] T01 a T03 concluídos e buscas sem resultado.
- [ ] O operador revisou o diff da fase.

### Fase 2 — AGENTS.md fiel ao código

**Leia antes:** AGENTS.md L3-82 e §18–§23 (L273-474); Fase 0 › Fontes de verdade.

#### T04 — Nomes e localizações em §18–§23, sem mudar regra

- [x] **Descrição:** corrigir cada citação da tabela. Só muda nome, local ou mecanismo; o sentido da regra fica.

| AGENTS.md | Texto atual                                  | Código (verificado)                                                                                                                                                                                                   | Correção                                                                                     |
|-----------|----------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| L278      | `WaitTimeout()`                              | `SingleflightOutcome.WaitTimeout` é `data object` (`APP/orchestrator/port/outbound/Stores.kt:751`); implementação `InMemoryComposeSingleflight` (`APP/adapters/memory/InMemoryStores.kt:555-602`)                     | `SingleflightOutcome.WaitTimeout`                                                            |
| L282      | `byte[]` pré-serializado                     | `ResponseEntity<ByteArray>` no 200 (`SurfaceController.kt:226-245`); 400, 429 e 503 devolvem `ApiErrorResponse` serializado pelo Spring (162-191)                                                                     | `ByteArray` e "vale para o 200"                                                              |
| L285-286  | `Guards.kt`                                  | Correto (`Guards.kt:36, 94`); os conjuntos de origem são `MvpCatalog.VISUAL_KEYS` e `PII_KEYS` (`Capability.kt:373-380, 396-399`)                                                                                     | acrescentar a origem                                                                         |
| L287      | `sortedBy`                                   | `sortBy` estável, em lista mutável (`CORE/filter/Filter.kt:131`)                                                                                                                                                      | `sortBy`                                                                                     |
| L292      | `RedisKeys.kt`                               | `object RedisKeys` em `CORE/model/Screen.kt:329-467`                                                                                                                                                                  | `RedisKeys` (`sdui-core`, `model/Screen.kt`)                                                 |
| L360      | "retorno do `putIfAbsent` é conferido"       | memória: reserva sob o lock de escrita (`InMemoryGovernance.kt:849-871`); Mongo: `insertOne` com chave duplicada (`MongoCoordinationStores.kt:37-192`)                                                                | "a reserva é atômica e o resultado é conferido"                                              |
| L384      | "~250 ns por append (medido em 2026-09-23)"  | o documento da medição foi removido (`584366a`); `append` copia a lista a cada chamada sob o lock global (`InMemoryGovernance.kt:752-753`)                                                                            | marcar a medição como a refazer (`:sdui-app:perfHarness -Pscenarios=audit`) e ver o achado 6 |
| L394      | `JsonMaps` mantém `BRASIL_OFFSET` e `toNode` | `JsonMaps` só tem `toValue` com `require(depth <= MAX_DEPTH)`, `MAX_DEPTH = 32`, que lança (`APP/adapters/seed/JsonMaps.kt:21, 37`); `ScreenResponseMapper` tem `BRASIL_OFFSET` (246) e trunca acima de 32 (203, 249) | separar os dois comportamentos                                                               |
| L401      | `found = true`                               | curto-circuito de `any` em `anyString` e `anyKey` (`CORE/validate/PropWalk.kt:132-137, 157-164`)                                                                                                                      | "interrompe na primeira violação (curto-circuito de `any`)"                                  |
| L422      | `sdui-orchestrator`                          | pacote `orchestrator` do `sdui-app` (`settings.gradle.kts:15-21`; regra 2 do `ArchitectureTest`)                                                                                                                      | "pacote `orchestrator`"                                                                      |

- **Aceite:**
    - `grep -nE 'RedisKeys\.kt|putIfAbsent|found = true|sdui-orchestrator|WaitTimeout\(\)|sortedBy' AGENTS.md` sem
      resultado.
    - Todo arquivo `.kt` citado em §18–§23 existe em algum `*/src/`.
    - Nenhuma regra mudou de sentido. As divergências de comportamento estão no T20, não no texto.
- **Dependências:** nenhuma.
- **Arquivos:** `AGENTS.md`.
- **Escopo:** M (documentação).

#### T05 — Uma só política de execução de Gradle (D1)

- [x] **Descrição:** trocar AGENTS.md L76, L77 e L79-80 por um parágrafo único com o texto da D1 (padrão ou
  alternativa) e manter L73-75 (pureza, ArchUnit, `allWarningsAsErrors`) e L78 (papel padrão). Os papéis passam a citar
  "AGENTS.md › Modo operacional" em
  vez de repetir a política.
- **Aceite:** um único parágrafo trata de Gradle no AGENTS.md, e ele é compatível com
  `plano-tokens-semanticos.md:310-313`.
- **Dependências:** decisão D1.
- **Arquivos:** `AGENTS.md`.
- **Escopo:** S.

#### Checkpoint B — AGENTS.md verdadeiro

- [x] T04 e T05 concluídos e buscas sem resultado.
- [ ] O operador conferiu que nenhuma regra mudou de sentido.

### Fase 3 — Papéis do modo pós-MVP

**Leia antes:** AGENTS.md (já corrigido nas fases 1 e 2), `.agents/README.md`, Fase 0 inteira e o papel a reescrever.

Estrutura comum dos cinco papéis: frontmatter `name` e `description`; Papel; Quando entra; Leia antes; Checklist (itens
com o id da regra, como `§19.10`); Não fazer; Saída. Cada papel com até 110 linhas. Nenhuma regra copiada.

#### T06 — `sdui-implementer.md`

- [x] **Descrição:**
    - Contexto: MVP concluído; o trabalho é manutenção, evolução pós-MVP, observabilidade e homologação (AGENTS.md
      L3-20). Sai "H01–H18 numa passada" (L7-8, L18).
    - A proibição absoluta de Gradle (L21, L64) vira referência à política única (T05).
    - Leia antes: seções do AGENTS.md tocadas pelo recorte; `docs/arquitetura-de-referencia.md` (§5 pipeline, §6
      concorrência, §7 governança, §8 persistência, §11 ADRs); o guia e a skill da Fase 4 quando o recorte for
      componente, surface, ADR ou performance.
    - "Convenções verificadas": copiar a Fase 0 › Convenções de produção.
    - Trocar "Não expor entidades como DTOs HTTP" por "documento de persistência (Mongo `Document`, codec Redis) nunca
      chega à `api`", que é o que o `ArchitectureTest` garante.
    - Regras novas contra deriva: quem renomeia ou move um símbolo citado no AGENTS.md atualiza o AGENTS.md no mesmo
      diff; buscar só em `*/src/`.
    - Checklist de entrega com os ids mais tocados: §18.2, §19.2, §19.4, §19.5, §20.3, §23.1, §23.7, §23.11, §23.14.
    - Saída: arquivos, comportamento, decisões reutilizadas (ADR ou §), testes escritos e se rodaram, riscos,
      pendências.
- **Aceite:** nenhuma instrução contradiz o AGENTS.md; nenhuma biblioteca inexistente citada.
- **Dependências:** T05.
- **Arquivos:** `.agents/agents/sdui-implementer.md`.
- **Escopo:** M.

#### T07 — `sdui-tester.md`

- [x] **Descrição:**
    - Tirar MockK, springmockk e Testcontainers (L34-35) e pôr a Fase 0 › Convenções dos testes, inclusive o lugar de
      cada teste por módulo.
    - Catálogo de cenários por regra, cada um com o teste existente que serve de modelo quando houver:
        - §18.1: waiter com timeout não cancela o líder (`SingleflightAndCanaryTest.kt:64-82`);
        - §18.2: SemVer com overflow devolve `null`;
        - §19.1: capability desconhecida não altera `capsHash`;
        - §19.2 e §23.11: teto de cache e de limitador;
        - §19.11: acerto de cache reidrata `client`, `locale` e `generatedAt`;
        - §20.5: last good vencido vira 503;
        - §23.1: surface fora da allowlist;
        - §23.7: meter negado acima de `sdui.metrics-max-tag-values`;
        - §23.8: mesma chave com outro parâmetro dá 422, teto dá 503, reserva vence;
        - §23.9: conflito de CAS dá 409;
        - §23.10: lápide recusa versão antiga;
        - §23.13: paginação fora da faixa dá 400.
    - Execução conforme a política única (T05).
- **Aceite:** `grep -rniE "mockk|springmockk|testcontainers" .agents` sem resultado; cada cenário cita o id da regra.
- **Dependências:** T05.
- **Arquivos:** `.agents/agents/sdui-tester.md`.
- **Escopo:** M.

#### T08 — `sdui-contract-guard.md`

- [x] **Descrição:**
    - Apontar as fontes de verdade (Fase 0) em vez de listas: chaves visuais, PII, surfaces, contratos aprovados, props
      e catálogo.
    - Acrescentar ADR-020 e §23.1–23.3: surface só por allowlist com mapeamento literal
      (`SurfaceController.kt:88, 106`).
      Componente novo exige contrato em `docs/contratos/` (modelo `transaction-summary-v1.md`: Conceito, Props,
      Actions, Proibições, Erros e omissão, Exemplo), ADR, regra em `ComponentPropsValidator` e entrada em
      `ComponentContracts.APPROVED`. Type novo só chega por capability declarada.
    - Fixtures a conferir: `sdui-contract/src/test/resources/fixtures/` (definitivo, cards-first e hostil) e
      `docs/examples/screens/`. Nenhum header `X-` na resposta (`HomeComposeContractWebTest.kt:71-79`).
    - Todo `BLOCK` mantém os seis campos, e o teste preventivo nomeia o módulo e o arquivo onde entra.
- **Aceite:** nenhuma lista de chaves em prosa; ADR-020 coberto.
- **Dependências:** nenhuma.
- **Arquivos:** `.agents/agents/sdui-contract-guard.md`.
- **Escopo:** S.

#### T09 — `sdui-reviewer.md`

- [x] **Descrição:**
    - Cinco eixos: correção, legibilidade, arquitetura, segurança e desempenho. Cada achado traz `arquivo:linha`,
      severidade (bloqueador, importante, sugestão) e a regra violada.
    - Arquitetura: as 15 regras do `ArchitectureTest` já seguram as camadas. O revisor procura o que o ArchUnit não
      pega: regra de domínio no Composer, abstração sem consumidor, teto decidido por `size()`, catch sem métrica.
    - Segurança: §8 (PII em payload, log e métrica), §19.2 (mapa sem teto), ADR-022 (limites de entrada). O plano
      administrativo confia em headers autodeclarados (`Actor-Id`, `Actor-Role`, `AdminController.kt:635-639`, sem
      Spring Security; ADR-026 proposto): mudança no admin não pode ampliar essa confiança.
    - Desempenho: afirmação de ganho exige medição antes e depois (§23.14, skill `sdui-perf`).
    - Deriva: diff que renomeia símbolo citado no AGENTS.md sem atualizá-lo é achado importante.
    - Mantém: revisão sem Gradle.
- **Aceite:** checklist por id de regra; nenhuma regra copiada.
- **Dependências:** nenhuma.
- **Arquivos:** `.agents/agents/sdui-reviewer.md`.
- **Escopo:** S.

#### T10 — `sdui-architect.md`

- [x] **Descrição:**
    - ADR na §11 (T03). Numeração: próximo livre depois de ADR-026, porque ADR-023 a 026 estão reservados em
      `plano-tokens-semanticos.md:105-278`.
    - Campos da §11 (Status, Contexto, Decisão, Consequências) e, quando houver, Alternativas descartadas e
      Verificação, como pede o T00 do plano de tokens (L325).
    - Sai o enquadramento "H00–H18 especificadas" (L9-10, L41). Fontes: arquitetura, guias, contratos e os planos em
      `docs/tasks/`.
    - Mantém as restrições (L32-41).
- **Aceite:** nenhuma menção a `docs/adr/`; numeração explícita.
- **Dependências:** T03.
- **Arquivos:** `.agents/agents/sdui-architect.md`.
- **Escopo:** S.

#### Checkpoint C — papéis atualizados

- [x] T06 a T10 concluídos.
- [x] `grep -rn "H01" .agents/agents` só aparece como histórico explícito.

### Fase 4 — Skills procedurais (formato Agent Skills)

**Leia antes:** Fase 0 › Formatos (Codex). Cada skill é `.agents/skills/<nome>/SKILL.md` com frontmatter `name` (igual
ao diretório) e `description` (quando usar e quando não usar). O corpo lista passos e aponta a seção de origem. Nada
fora da fonte citada. O marcador `sdui-backend/README.md` fica intacto.

#### T11 — `sdui-component` (criar e depreciar componente)

- [x] **Descrição:** dois procedimentos.
    - **Criar**, de `docs/guia-criacao-telas-componentes.md` §5 (L66-84) e AGENTS.md §23.2–23.3:
        1. contrato em `docs/contratos/`, no modelo de `transaction-summary-v1.md`;
        2. ADR (skill `sdui-adr`);
        3. `ComponentContracts.APPROVED`, `Surfaces` (types e slot) e `ComponentPropsValidator`, com testes negativos;
        4. catálogo `PUT /admin/v1/catalog/components/{type}/{v}` com `ACTIVE`;
        5. capability: `requiredCapabilities` quando o slot for portante;
        6. homologação móvel.
    - **Depreciar**, do guia §9 (L122-127), de `docs/guia-depreciacao-e-migracao.md` §3.1–3.2 (L56-94) e do checklist
      §9 (L234-247).
- **Aceite:** cada passo cita a seção de origem; nenhuma regra nova.
- **Dependências:** T08, T13.
- **Arquivos:** `.agents/skills/sdui-component/SKILL.md`.
- **Escopo:** S.

#### T12 — `sdui-surface` (surface nova)

- [x] **Descrição:** do guia §6 (L86-91), do AGENTS.md §23.1 e do ADR-020 (`docs/arquitetura-de-referencia.md:612-620`):
    - `SurfaceDefinition` em `Surfaces` (`Surface.kt:255-410`);
    - mapeamento literal em `SurfaceController`, nunca `/{surface}`;
    - conferir `entryPoint` (`CorrelationIdFilter.kt:76-81`) e os valores de tag (§23.7);
    - skeleton e spec pelo maker-checker (guia §3, L34-57).
- **Aceite:** passos com origem; proibição do mapeamento genérico explícita.
- **Dependências:** nenhuma.
- **Arquivos:** `.agents/skills/sdui-surface/SKILL.md`.
- **Escopo:** S.

#### T13 — `sdui-adr`

- [x] **Descrição:** da §11 (matriz L426-451, entrada modelo L457-465), do AGENTS.md §14 (após o T03) e do T10:
    - número livre;
    - linha na matriz;
    - entrada com os campos;
    - status `PROPOSTO` até a verificação ou homologação;
    - seção nova no AGENTS.md quando o ADR criar regra inegociável.
- **Aceite:** formato idêntico ao da §11.
- **Dependências:** T03, T10.
- **Arquivos:** `.agents/skills/sdui-adr/SKILL.md`.
- **Escopo:** S.

#### T14 — `sdui-perf` (medir antes de otimizar)

- [x] **Descrição:** do AGENTS.md §23.14, de `sdui-app/build.gradle.kts:28-49`, de `PerfHarness.kt:85-101`, de
  `HttpLoadGenerator.kt:29-90` e de `sdui-app/src/test/resources/load/compose-hit-p99.yaml`:
    - cenários do harness: `m1` a `m8` e `audit`, escolhidos com `-Pscenarios=`;
    - carga com `-PbaseUrl`, ajustada pelas propriedades `load.*`;
    - antes e depois no mesmo ambiente, registrando a linha de ambiente do harness, a mediana e o mínimo e máximo;
    - ganho dentro do ruído é recusado e registrado como tal.

  As duas tarefas são execuções Gradle e só rodam a pedido (T05). O registro vai para a seção "Medições" do documento
  da tarefa em `docs/tasks/` (pergunta 3).
- **Aceite:** comandos exatamente como em `build.gradle.kts`; nenhum cenário inventado.
- **Dependências:** T05.
- **Arquivos:** `.agents/skills/sdui-perf/SKILL.md`.
- **Escopo:** S.

#### T15 — `sdui-commit-message`

- [x] **Descrição:** procedimento sobre a regra do T02:
    1. `git status` e `git diff HEAD` (staged e não staged); `git diff --cached` se o operador pedir só o staged;
    2. escolher tipo e emoji;
    3. cabeçalho com até 72 caracteres;
    4. até 10 tópicos;
    5. devolver só a mensagem.

  Nunca `git add`, `commit` ou `push`; nunca co-autoria. A `description` diz "somente quando o operador pedir mensagem
  de commit". O `agents/openai.yaml` com `allow_implicit_invocation: false` só entra se o formato do arquivo estiver
  confirmado na fonte do Codex (Fase 0). Sem confirmação, fica de fora.
- **Aceite:** nenhuma regra além da do arquivo de regras.
- **Dependências:** T02.
- **Arquivos:** `.agents/skills/sdui-commit-message/SKILL.md`.
- **Escopo:** S.

#### Checkpoint D — skills

- [x] T11 a T15 com frontmatter válido (`name` igual ao diretório, `description` não vazia).
- [x] `sdui-backend/README.md` inalterado.

### Fase 5 — Adaptadores no Claude Code e governança por configuração (D3)

**Leia antes:** Fase 0 › Formatos (Claude Code). Adaptador não repete conteúdo: frontmatter, mais uma instrução de
leitura do arquivo canônico.

#### T16 — Subagentes em `.claude/agents/`

- [x] **Descrição:** quatro adaptadores: `sdui-architect`, `sdui-tester`, `sdui-contract-guard` e `sdui-reviewer`. O
  implementer não ganha adaptador, porque é o papel da sessão principal (T18). Ferramentas por papel:
    - revisor e contract guard: `Read, Grep, Glob, Bash` (Bash só para leitura do git);
    - tester e arquiteto: `Read, Grep, Glob, Edit, Write`.

  Modelo:

  ```markdown
  ---
  name: sdui-reviewer
  description: Revisão técnica por leitura do ms-sdui-composer. Usar somente quando o operador pedir nominalmente o sdui-reviewer; nunca delegar por iniciativa própria.
  tools: Read, Grep, Glob, Bash
  ---

  Antes de qualquer ação, leia `.agents/agents/sdui-reviewer.md` e siga-o. Ele e o `AGENTS.md` prevalecem sobre este
  arquivo.

  Bash só para leitura do git (`git status`, `git diff`, `git log`, `git show`). Nunca Gradle, nunca escrita em
  arquivo, nunca `git add`, `git commit` ou `git push`.
  ```

- **Aceite:**
    - `/agents` lista os quatro.
    - Um pedido comum de implementação não dispara delegação.
- **Dependências:** Fase 3.
- **Arquivos:** `.claude/agents/sdui-architect.md`, `sdui-tester.md`, `sdui-contract-guard.md`, `sdui-reviewer.md`.
- **Escopo:** S.

#### T17 — Skills em `.claude/skills/`

- [x] **Descrição:** um adaptador por skill da Fase 4, com o mesmo `name` e a mesma `description` e o corpo "Leia
  `.agents/skills/<nome>/SKILL.md` e siga-o; ele prevalece sobre este arquivo". `sdui-commit-message` leva também
  `disable-model-invocation: true`.
- **Aceite:** `/sdui-` completa as cinco skills.
- **Dependências:** Fase 4.
- **Arquivos:** `.claude/skills/<nome>/SKILL.md` (cinco).
- **Escopo:** S.

#### T18 — Negar `git add/commit/push` e carregar o implementer

- [x] **Descrição:** criar `.claude/settings.json` versionado:

  ```json
  {
    "permissions": {
      "deny": [
        "Bash(git add *)",
        "Bash(git commit *)",
        "Bash(git push *)",
        "PowerShell(git add *)",
        "PowerShell(git commit *)",
        "PowerShell(git push *)"
      ]
    }
  }
  ```

  No AGENTS.md §15, acrescentar `@.agents/agents/sdui-implementer.md` em linha própria. Hoje o papel padrão depende de o
  agente lembrar de carregá-lo (L228-229).
- **Aceite, numa sessão nova do Claude Code:**
    - `/memory` mostra o implementer importado.
    - Pedir `git commit --dry-run` e `git add --dry-run .` resulta em recusa.
    - `git worktree add` continua permitido: o padrão `git add *` não casa com ele.
- **Limite conhecido:** o deny não casa `git -C <dir> commit`. A regra em prosa continua valendo.
- **Dependências:** T06.
- **Arquivos:** `.claude/settings.json` (novo), `AGENTS.md`.
- **Escopo:** S.

#### Checkpoint E — Claude Code

- [ ] T16 a T18 verificados numa sessão nova.
- [ ] O operador corrigiu, fora do git, a regra local malformada (achado 16).

### Fase 6 — Guarda automática contra deriva (D4)

**Leia antes:** `sdui-integration-test/src/test/kotlin/br/com/empresa/sdui/it/VisualKeysAlignmentTest.kt:28-45`
(resolução da raiz subindo diretórios) e a Fase 0 › Convenções dos testes.

#### T19 — `AgentInstructionsIntegrityTest`

- [x] **Descrição:** teste novo em `sdui-integration-test`, sem dependência nova e sem mexer em `build.gradle.kts`.
    - A raiz é o primeiro diretório acima de `Path.of("")` que contém `AGENTS.md` e `settings.gradle.kts`, copiando o
      laço do `VisualKeysAlignmentTest`.
    - Varre `AGENTS.md`, `.agents/**/*.md`, `.claude/agents/*.md` e `.claude/skills/**/SKILL.md`.
    - Quatro testes, com nomes em crase:
        1. `caminhos citados nas instrucoes existem`: tokens entre crases que começam por `docs/`, `.agents/`,
           `.claude/`, `.github/`, `build-logic/`, `gradle/` ou `sdui-(core|contract|app|bootstrap|integration-test)/`,
           sem `*`, `<`, `>`, `{`, `}`, `$`, `XXX` ou espaço, e sem o sufixo `:linha`, existem como arquivo ou
           diretório. Links Markdown relativos dos arquivos de `.agents/` também, sem a âncora. Exceções vão para um
           `INTENCIONALMENTE_AUSENTES`, que começa vazio.
        2. `arquivos kotlin citados existem em src`: tokens `^[A-Z][A-Za-z0-9]*\.kt$` existem com esse nome em
           `sdui-*/src/` ou `build-logic/src/`. Nunca em `bin/`, `build/` ou `.claude/worktrees/`.
        3. `skills tem frontmatter valido`: cada `.agents/skills/<d>/` tem `SKILL.md` com `name: <d>` e `description`
           não vazia, exceto `sdui-backend` (marcador, AGENTS.md §11).
        4. `adaptadores apontam para o canonico`: cada `.claude/agents/<n>.md` tem `name: <n>` e cita
           `.agents/agents/<n>.md`, e cada `.claude/skills/<n>/SKILL.md` cita `.agents/skills/<n>/SKILL.md`. Sem
           `.claude/` (D3 recusada), o teste é pulado com `Assumptions.assumeTrue`.
    - Mensagem de falha com o arquivo de origem e o token. AssertJ com `.as(..)`. Nada que gere warning
      (`allWarningsAsErrors`).
- **Testes:** o próprio teste, executado no checkpoint final. Checagem negativa opcional na mesma execução: citar
  `docs/adr/` num `.md` temporário, ver a falha e reverter.
- **Dependências:** T03, T04 (antes deles o teste falha com as referências atuais), Fases 4 e 5.
- **Arquivos:** `sdui-integration-test/src/test/kotlin/br/com/empresa/sdui/it/AgentInstructionsIntegrityTest.kt`.
- **Escopo:** M.

### Fase 7 — Registro

#### T20 — Backlog, índices e memória operacional

- [x] **Descrição:**
    - `docs/tasks/todo.md`: grupo novo "### 6. Achados da revisão de instruções (2026-09-25)" com os achados 1 a 15
      da tabela abaixo, no formato `- [ ] **Título:** descrição` e com a evidência.
    - `docs/tasks/README.md`: este plano passa de `PROPOSTO` a `ATIVO` no início da execução.
    - AGENTS.md §11 (L190-207) e §15 (L224-236): registrar `.agents/README.md`, as skills, os adaptadores e o
      `settings.json`.
- **Aceite:** cada achado com evidência `arquivo:linha` e destino; AGENTS.md sem afirmar estado que o repositório não
  tem.
- **Dependências:** fases anteriores concluídas.
- **Arquivos:** `docs/tasks/todo.md`, `docs/tasks/README.md`, `AGENTS.md`.
- **Escopo:** S (documentação).

#### Checkpoint final

- [x] Buscas globais sem resultado:

  ```text
  grep -rnE "plano-servico-sdui|resumos-server-driven-ui|papel-agents" .agents AGENTS.md
  grep -rnE "docs/adr|[^/]tasks/todo\.md" .agents AGENTS.md
  grep -rniE "mockk|springmockk|testcontainers" .agents
  grep -nE 'RedisKeys\.kt|putIfAbsent|found = true|sdui-orchestrator|WaitTimeout\(\)|sortedBy' AGENTS.md
  grep -rnE "empacotament$|sem m$|arquivos de conf$|funcionali$" AGENTS.md .agents
  ```

- [x] Com a D4 aceita, execução única conforme a D1: `.\gradlew.bat clean build --warning-mode=fail`.
- [ ] Verificações do Claude Code do T16 ao T18 feitas numa sessão nova.
- [ ] `git status` só mostra os arquivos listados nas tarefas. O operador revisa e commita.

## Achados laterais (fora do escopo; vão para o backlog no T20)

| #  | Achado                                                                                                         | Evidência                                                                                                                                                                                                                                                           | Destino sugerido                                                  |
|----|----------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| 1  | Cinco chaves visuais nunca casam nos testes de contrato: `contains(key.lowercase())` contra entradas camelCase | `NoVisualAttributesTest.kt:41`, `RejectedProposalContractTest.kt:37`; `cornerRadius`, `itemWidth`, `itemHeight`, `formFactor`, `componentType` (L16-19)                                                                                                             | T01 do `plano-tokens-semanticos.md`, que mexe nos mesmos arquivos |
| 2  | Checksum aceita qualquer comprimento                                                                           | `CORE/validate/SpecValidator.kt:53` (`^sha256:[0-9a-f]+$`) × KDoc L51 (64 dígitos)                                                                                                                                                                                  | código e teste                                                    |
| 3  | Chave Redis montada fora de `RedisKeys` e sem `containsUserId`                                                 | `APP/adapters/redis/RedisCaches.kt:281-282` (`sdui:treeidx:v2:`)                                                                                                                                                                                                    | código (§18.6)                                                    |
| 4  | Catch sem métrica                                                                                              | `RedisCaches.kt:552-553` (`decodeOrNull`); `APP/orchestrator/admin/AdminServices.kt:908-910` (`SpecCache.warm`)                                                                                                                                                     | código (§20.3)                                                    |
| 5  | Teto decidido por `size()` fora do cache                                                                       | `CORE/limit/TokenBucket.kt:118, 123`; `InMemoryStores.kt:475` (o KDoc diz que é intencional)                                                                                                                                                                        | decidir o alcance do §23.11 (pergunta 4)                          |
| 6  | Custo do append de auditoria sem medição válida                                                                | `InMemoryGovernance.kt:752-753` copia a lista a cada append sob o lock global; medição removida em `584366a`                                                                                                                                                        | medir com `perfHarness -Pscenarios=audit` (§23.14)                |
| 7  | Possível PII em log do admin                                                                                   | `AdminController.kt:467-473, 535-543` (`currentActor.id`, `reason` livre); KDoc de `Actor.id` cita e-mail (`CORE/model/ClientContext.kt:18`)                                                                                                                        | pergunta 2                                                        |
| 8  | Referências ao documento de medição removido                                                                   | `sdui-app/build.gradle.kts:33`; `PerfHarness.kt:68, 72`; `HttpLoadGenerator.kt:27`                                                                                                                                                                                  | documentação                                                      |
| 9  | KDoc divergente do código                                                                                      | `ComposeModels.kt:124` (`withJitter`, o nome é `jittered`); `AdminController.kt:124, 494, 558` e `ApiExceptionHandler.kt:254` (prefixo `sdui.` que não existe); `SduiConfiguration.kt:447` (`/v1/surfaces/{surface}`); `InMemoryStores.kt:38` (`RedisKeys.treeKey`) | documentação                                                      |
| 10 | A §12 da arquitetura cita classes de teste que não existem                                                     | `docs/arquitetura-de-referencia.md:651-669` (ex.: `NegotiateTest`, `SelectTest`, `HomeControllerWebTest`)                                                                                                                                                           | documentação                                                      |
| 11 | Links quebrados fora do escopo dos agentes                                                                     | `README.md:719` (`docs/adr/README.md`), `README.md:725-727` (`docs/runbooks/`), `plano-tokens-semanticos.md:331-332` (`docs/adr/…`)                                                                                                                                 | documentação; o do plano de tokens, no T00 dele                   |
| 12 | CI sem `--warning-mode=fail`, ao contrário do comando local documentado                                        | `.github/workflows/gradle.yml:48-52`; `release.yml:41`                                                                                                                                                                                                              | CI                                                                |
| 13 | IT mandam subir o perfil `infra`, que está comentado                                                           | KDoc de `MongoPersistenceIT.kt:53-59` × `compose.yaml:56, 74`                                                                                                                                                                                                       | infraestrutura (ensaio P13 do `todo.md`)                          |
| 14 | Aliases do catálogo sem uso                                                                                    | `gradle/libs.versions.toml:31, 34-36` (`restclient` e três de Testcontainers)                                                                                                                                                                                       | build                                                             |
| 15 | Valor inválido de `sdui.persistence.*` sem teste                                                               | falha implícita no binding do enum (`SduiProperties.kt:118-165`)                                                                                                                                                                                                    | testes (§23.12)                                                   |
| 16 | Regra local malformada, fora do git                                                                            | `.claude/settings.local.json`: `"Bash(git worktree *])"`                                                                                                                                                                                                            | operador, localmente                                              |

## Riscos e mitigações

| Risco                                                                  | Impacto | Mitigação                                                                                                 |
|------------------------------------------------------------------------|---------|-----------------------------------------------------------------------------------------------------------|
| Import `@` do AGENTS.md não carregar numa versão antiga do Claude Code | Médio   | Conferir com `/memory` (T02, T18); se falhar, voltar ao texto inline                                      |
| Deny por prefixo contornado (`git -C . commit`)                        | Médio   | A regra em prosa continua; a doc oficial avisa que deny não é fronteira de segurança                      |
| Sessões em segundo plano tentam commitar ao terminar e são bloqueadas  | Baixo   | É o comportamento que o AGENTS.md exige; o diff fica para o operador                                      |
| Adaptador não ler o arquivo canônico                                   | Médio   | Instrução imperativa na primeira linha; T19 confere que o adaptador aponta um arquivo existente           |
| Teste de integridade acusar texto histórico                            | Baixo   | Varre só instruções (AGENTS.md, `.agents/`, `.claude/`); exceções em `INTENCIONALMENTE_AUSENTES`          |
| Skill virar cópia do guia e divergir                                   | Médio   | Skill lista passos e aponta a seção; T19 confere os caminhos                                              |
| Correção de texto do AGENTS.md mudar uma regra sem querer              | Alto    | T04 só troca nome e local; o operador revisa no Checkpoint B; divergência de comportamento vai ao backlog |
| Descrição de subagente disparar delegação proativa                     | Baixo   | Texto "somente quando o operador pedir nominalmente" e teste no T16                                       |

## Perguntas em aberto

1. Que ferramentas além do Claude Code usam o `.agents/`? O `.aiignore` sugere JetBrains AI; o Codex lê
   `.agents/skills/`. A resposta decide se os adaptadores e o `agents/openai.yaml` valem o custo.
2. O `Actor-Id` pode conter e-mail? O KDoc de `ClientContext.kt:18` sugere que sim. Se puder, os logs do admin ferem
   §8 e §22.4 (achado 7).
3. Onde registrar medições de performance, já que `docs/performance/` saiu em `584366a`? Padrão proposto: seção
   "Medições" no documento da tarefa em `docs/tasks/`.
4. O §23.11 vale só para caches ou também para o limitador e o `InMemoryProjectionStore` (achado 5)?
5. O pedido original trazia dois blocos colados que não chegaram a esta revisão. Se eram referências, incorporá-las na
   revisão deste plano.
