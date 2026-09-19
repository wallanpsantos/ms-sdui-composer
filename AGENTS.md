# AGENTS.md — Memória Operacional do ms-sdui-composer

## Modo operacional vigente

O bootstrap Gradle, o escopo completo do MVP (`H00`–`H18`) e o ciclo de revisão técnica multidimensional
(`code-review-and-quality`) estão **concluídos com Quality Gate APROVADO (PASS)**.

As 7 correções de qualidade e concorrência foram implementadas e verificadas no código produtivo e de testes
(Singleflight timeout sem cancel compartilhado, blindagem de SemVer contra overflow, eliminação da dupla
serialização no hot path, ordenação estável em Filter, pré-cálculo de guards, limpeza de chaves e `.use` em streams).

**Foco a partir de agora:** Manutenção, evolução de features pós-MVP, observabilidade operacional e suporte à
homologação com clientes móveis.

Regras deste modo:

- Qualquer alteração pontual deve manter estritamente a conformidade com as regras de pureza do `sdui-core`, o isolamento
  de camadas do ArchUnit e a ausência de warnings (`allWarningsAsErrors = true`).
- **Não** executar `gradlew`, `gradlew.bat`, `clean`, `build`, `test`, `check` nem qualquer tarefa Gradle de forma
  repetitiva.
- **Não** interromper a escrita para esperar compilação ou resultado de testes.
- Papel padrão: `sdui-implementer`. Os demais papéis só entram quando o operador pedir explicitamente.
- Gradle, `clean build` ou suíte de testes só correm se o operador humano pedir, e nesse caso **uma única vez, no
  final**, sem repetir o ciclo.

Os comandos da seção 16 são registro histórico do bootstrap. Não reexecutá-los como rotina de implementação.

## 1. Identidade e Definição do Serviço

O `ms-sdui-composer` é o serviço responsável por compor a árvore de UI de uma surface (a primeira surface é `home`) a
partir de uma spec versionada, contexto do cliente e capabilities declaradas, entregando um envelope REST/JSON pronto
para clientes iOS e Android.

- **Papel Arquitetural:** Presentation + Application Controller + BFF de UI (Fowler).
- **Runtime:** Estritamente stateless no hot path. Não consulta domínios de negócio regulados diretamente e não persiste
  árvores hidratadas de usuário no banco.
- **Endpoint MVP:** `GET /v1/surfaces/home`.

## 2. Stack Tecnológica e Baseline

- **Linguagem:** Kotlin 2.4.20 (`allWarningsAsErrors = true`, `-Xannotation-default-target=param-property`).
- **Plataforma:** JVM com Java 25 LTS via Gradle toolchain (`jvmToolchain(25)`). Foojay resolver `1.0.0` (latest estável
  verificada no Plugin Portal).
- **Framework:** Spring Boot 4.1.1 (Spring Framework 7.0.9 gerenciado pelo BOM).
- **Build:** Gradle 9.7.1 com Kotlin DSL, version catalog, convention plugins, configuration cache e build cache.
- **JSON:** Jackson 3 (`tools.jackson.core:jackson-databind` 3.1.5, `tools.jackson.module:jackson-module-kotlin` 3.1.5,
  `com.fasterxml.jackson.core:jackson-annotations` 2.21).
- **Testes e Arquitetura:** JUnit Jupiter e AssertJ na versão do BOM; ArchUnit 1.5.0 (`com.tngtech.archunit:archunit`).
- **Persistência e Cache:** MongoDB 8.3+ (fonte da verdade de specs) e Redis (cache). Starters ainda não entram no
  bootstrap.
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
- **H14–H18:** Concluídas no servidor. Isolamento Android integralmente implementado (pointer/cache/select independentes,
  matriz de capabilities e canary Android). Fixture `contrato-sdui-home-android-proposto.json` aguarda definição formal
  da equipe Android — conteúdo não inferido a partir do iOS.
- **Pós-H18:** Auditoria multidimensional (`code-review-and-quality`) e auditoria de performance
  (`performance-optimization`) realizadas e consolidadas. 7 correções de qualidade e resiliência aplicadas.

## 11. Lacunas Documentais Registradas

- ADRs canônicos (ADR-001 a ADR-013) estão narrados em `docs/02-pre-arquitetura-ms-sdui-composer.md`. Arquivos individuais
  `docs/adr/ADR-XXX-*.md` ainda não foram extraídos; o diretório tem só `README.md`.
- Presente: `docs/README.md` — índice sequencial e catálogo da documentação em 3 arquivos canônicos (`01`, `02`, `03`).
- Presente: `docs/artifacts/contrato-sdui-home-definitivo.json` (e a cópia de teste em
  `sdui-contract/src/test/resources/fixtures/`). Fonte de verdade do contrato Home iOS.
- Presente: `docs/03-memoria-projeto-ms-sdui-composer.md` — memória operacional e arquitetural consolidada do serviço.
- `documentacao-contrato-sdui-home-v3.docx`: removido de propósito. Não recriar. Semântica de campo vive no JSON
  canônico e nos testes de `sdui-contract`.
- Ausente: `contrato-sdui-home-android-proposto.json` (H14 pendente de fornecimento pela equipe mobile).
- Skill `sdui-backend`: não disponível; marcador em `.agents/skills/sdui-backend/README.md`. A skill ausente não
  bloqueia o que já está especificado nas histórias, no plano, na pré-arquitetura, nos ADRs e no contrato.
- Presente: `docs/historias/README.md` — backlog de implementação do MVP (`H01`–`H18`).

## 12. Decisões Provisórias

- **Pacote Base Canônico:** `br.com.empresa.sdui`, estruturado por camadas (`.contract`, `.core`, `.orchestrator`,
  `.adapters`, `.api`, `.bootstrap`, `.it`).

## 13. Pendências Temporárias

- **`allowEmptyShould(true)` no ArchUnit:** removido das regras de `core`, `orchestrator`, `adapters` e `api` após essas
  camadas receberem classes de produção.

## 14. Regra para ADRs

Novas decisões estruturais exigem ADR em `docs/adr/ADR-XXX-<slug>.md` seguindo o padrão documentado em
`docs/adr/README.md`.

## 15. Papéis especializados

Os papéis estão em `.agents/agents/`. São instruções de desenvolvimento, não componentes do runtime.

Papel padrão deste modo: **implementação** (`.agents/agents/sdui-implementer.md`). Carregar `AGENTS.md` e o implementer
e escrever o código. Não carregar os demais papéis nem esperar o fluxo completo antes de implementar.

Os outros papéis só são carregados quando o operador os pedir nominalmente:

- Arquitetura: `.agents/agents/sdui-architect.md`
- Testes (autoria de fontes de teste, sem execução Gradle no ciclo): `.agents/agents/sdui-tester.md`
- Guarda de Contrato: `.agents/agents/sdui-contract-guard.md`
- Revisão: `.agents/agents/sdui-reviewer.md`

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

## 17. Diretrizes de Qualidade e Concorrência Consolidadas (Pós-Review)

Regras inegociáveis resultantes do ciclo de auditoria técnica (`code-review-and-quality` e `performance-optimization`):

1. **Singleflight Concorrente:** Waiters que sofrem timeout local no `ComposeSingleflight` **nunca** executam
   `existing.cancel(true)`. Devem retornar `WaitTimeout()` deixando o líder concluir a computação normalmente.
2. **Parsing SemVer Seguro:** Todo parsing de números em SemVer (`SemVer.kt`) deve utilizar `.toIntOrNull() ?: return null`.
   Proibido lançar `NumberFormatException` que possa vazar como HTTP 500 no `Negotiate`.
3. **Serialização de Passo Único no Hot Path:** O `HomeController` deve retornar o `byte[]` pré-serializado diretamente
   com `MediaType.APPLICATION_JSON`. Nunca repassar instâncias de objeto de resposta para o Spring re-serializar.
4. **Constantes Pré-calculadas em Validações:** Em classes de guardas (`Guards.kt`), sets de chaves restritas
   (`LOWER_VISUAL_KEYS`, `LOWER_PII_KEYS`) devem ser `private val` pré-calculados, evitando alocações no loop recursivo.
5. **Estabilidade de Ordenação em Filter:** A ordenação de seções em `Filter.kt` deve utilizar `sortedBy` sobre a ordem de
   slots do skeleton. Não introduzir comparadores secundários com busca linear O(N) (`indexOf`), aproveitando a estabilidade
   do TimSort.
6. **Limpeza de Chaves Redis:** Assinaturas de métodos geradores de chaves (`RedisKeyspace.kt`) devem conter apenas
   parâmetros efetivamente interpolados na chave, e garantir `!RedisKeys.containsUserId(key)`.
7. **Fechamento de Recursos:** Qualquer leitura de stream de arquivo ou classpath (`ClassPathResource`) deve ser envolvida
   por `.use { }` para garantir encerramento do recurso e evitar vazamentos de file descriptors.

