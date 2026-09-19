# AGENTS.md — Memória Operacional do ms-sdui-composer

## 1. Identidade e Definição do Serviço
O `ms-sdui-composer` é o serviço responsável por compor a árvore de UI de uma surface (a primeira surface é `home`) a partir de uma spec versionada, contexto do cliente e capabilities declaradas, entregando um envelope REST/JSON pronto para clientes iOS e Android.
- **Papel Arquitetural:** Presentation + Application Controller + BFF de UI (Fowler).
- **Runtime:** Estritamente stateless no hot path. Não consulta domínios de negócio regulados diretamente e não persiste árvores hidratadas de usuário no banco.
- **Endpoint MVP:** `GET /v1/surfaces/home`.

## 2. Stack Tecnológica e Baseline
- **Linguagem:** Kotlin 2.3.21 (`allWarningsAsErrors = true`, `-Xannotation-default-target=param-property`).
- **Plataforma:** JVM com Java 25 LTS via Gradle toolchain (`jvmToolchain(25)`). Foojay resolver `1.0.0` (latest estável verificada no Plugin Portal).
- **Framework:** Spring Boot 4.1.1 (Spring Framework 7.0.9 gerenciado pelo BOM).
- **Build:** Gradle 9.7.1 com Kotlin DSL, version catalog, convention plugins, configuration cache e build cache.
- **JSON:** Jackson 3 (`tools.jackson.core:jackson-databind` 3.1.5, `tools.jackson.module:jackson-module-kotlin` 3.1.5, `com.fasterxml.jackson.core:jackson-annotations` 2.21).
- **Testes e Arquitetura:** JUnit Jupiter e AssertJ na versão do BOM; ArchUnit 1.5.0 (`com.tngtech.archunit:archunit`).
- **Persistência e Cache:** MongoDB 8.3+ (fonte da verdade de specs) e Redis (cache). Starters ainda não entram no bootstrap.
- **Concorrência:** Spring MVC + Virtual Threads (`spring.threads.virtual.enabled: true`).
- **REGRA INEGOCIÁVEL DE DEPENDÊNCIAS:** Versões gerenciadas pelo Spring Boot NUNCA são fixadas no catálogo ou nos arquivos de build. Apenas bibliotecas fora do BOM (ArchUnit, Foojay, plugins Kotlin/Boot) possuem versões explícitas. Proibido o plugin `io.spring.dependency-management`.

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
- `sdui.kotlin-base`: JVM toolchain 25, compilador Kotlin, BOM do Boot, JUnit Jupiter/AssertJ e `verifyForbiddenDependencies`.
- `sdui.kotlin-library`: Aplica `sdui.kotlin-base` + task `verifyPureClasspath`.
- `sdui.spring-library`: Aplica `sdui.kotlin-base` + plugin Spring Kotlin + `kotlin-reflect`.
- `sdui.spring-app`: Aplica `sdui.spring-library` + plugin `org.springframework.boot`.
- Proibido qualquer uso de `allprojects {}` e `subprojects {}`.

## 5. Tríade SDUI e Regra de Fragment
- **Section:** Bloco autocontido com `id`, `slot`, `type`, `typeVersion`, `props`, `actions` e `analytics`.
- **Screen:** Composição ordenada de sections para uma surface e contexto.
- **Action:** Intenção serializada despachada pelo dispatcher nativo (`navigate`, `open_bottom_sheet`, `track`, `noop`).
- **REGRA DE FRAGMENT (ADR-004):** `Fragment`, `FragmentStore`, `FragmentResolver`, endpoint de fragment e chave Redis de fragmento estão FORA do MVP.

## 6. Pipeline de Composição
`Negotiate` (Headers) -> `Select` (Pointer + Targeting) -> `Filter` (Capabilities) -> `Hydrate` (Projeções seguras) -> `Guard / Fallback` -> `Compose` (Montagem do envelope).

## 7. Eixos de Compatibilidade
1. **Eixo A (Envelope/Protocolo):** `UI-Schema-Version` (versão 3 no MVP) e `API-Version` (versão 1).
2. **Eixo B (Renderer/Capabilities):** `type@typeVersion` suportado pelo cliente móvel.
3. **Eixo C (Faixa de Aplicativo):** `Client-Platform` (`ios`|`android`), `Client-Version` (semver ordinal) e `Client-Build`.
- Não existe targeting por form factor (sem DSL de pixels/breakpoints no servidor).

## 8. Proibições Rígidas
- **Sem Aparência/CSS:** Proibido enviar `color`, `background`, `font`, `margin`, `padding`, `gap`, `width`, `height`, `radius`, `shadow`, `orientation`, `shimmer`, `dp`, `pt`.
- **Sem Primitivas Genéricas:** Proibido criar `row`, `column`, `container`, `card` genérico.
- **Sem PII ou Segredos:** Proibido trafegar CPF, dados bancários regulados, tokens JWT ou senhas em payloads, cache, logs ou métricas.
- **Sem Coroutines ou Reativo:** Proibido `suspend fun`, WebFlux, Reactor ou repositórios reativos (ADR-012).
- **Sem Spring Cloud, gRPC, Protobuf ou MapStruct:** Sem bibliotecas externas desnecessárias sem ADR.

## 9. Concorrência e Resiliência
- Virtual Threads para I/O bound.
- Proibido `synchronized` segurando I/O; usar `ReentrantLock` com escopo mínimo se indispensável.
- Timeouts explícitos em todas as chamadas remotas. Fan-out limitado com semáforos globais.
- Omissão graciosa de sections falhas (exceto slots portantes `header` e `accounts`, conforme ADR-009).
- Escada de fallback (ADR-007): 200 OK -> 200 OK com omissão -> 200 Cache -> 200 Last Good -> 503 Retry-After.

## 10. Ordem e Status das Histórias
- **H00:** Gates testáveis verdes (identidade da fixture, catálogo, actions, sem visual, round-trip Jackson 3).
- **H01–H18:** Bloqueadas até a conclusão das etapas precedentes. Não antecipar implementação.

## 11. Lacunas Documentais Registradas
- `docs/fluxos-integracao-ms-sdui-composer.md`: o arquivo real contém `\u200b` (Zero Width Space) no nome. Mantido intacto conforme regra de verdade.
- ADRs canônicos (ADR-001 a ADR-013) estão narrados em `docs/pre-arquitetura-ms-sdui-composer.md`. Arquivos individuais `docs/adr/ADR-XXX-*.md` ainda não foram extraídos; o diretório tem só `README.md`.
- Presente: `docs/artifacts/contrato-sdui-home-definitivo.json` (e a cópia de teste em `sdui-contract/src/test/resources/fixtures/`).
- Ausentes: `contrato-sdui-home-android-proposto.json` (H14) e `MEMORIA-PROJETO-MS-SDUI-COMPOSER.md`.
- Skill `sdui-backend`: não disponível; marcador em `.agents/skills/sdui-backend/README.md`.

## 12. Decisões Provisórias
- **Pacote Base Canônico:** `br.com.empresa.sdui`, estruturado por camadas (`.contract`, `.core`, `.orchestrator`, `.adapters`, `.api`, `.bootstrap`, `.it`).

## 13. Pendências Temporárias
- **`allowEmptyShould(true)` no ArchUnit:** permanece nas regras de `core`, `orchestrator`, `adapters` e `api` enquanto essas camadas não tiverem classes de produção. Remover gradualmente de H01 a H04. A regra de `contract` já verifica classes reais.

## 14. Regra para ADRs
Novas decisões estruturais exigem ADR em `docs/adr/ADR-XXX-<slug>.md` seguindo o padrão documentado em `docs/adr/README.md`.

## 15. Papéis especializados

Os papéis estão em `.agents/agents/`.

Antes de executar uma tarefa especializada, carregar `AGENTS.md` e o arquivo do papel correspondente. Os papéis são instruções de desenvolvimento, não componentes do runtime.

Mapeamento:
- Arquitetura: `.agents/agents/sdui-architect.md`
- Implementação: `.agents/agents/sdui-implementer.md`
- Testes: `.agents/agents/sdui-tester.md`
- Guarda de Contrato: `.agents/agents/sdui-contract-guard.md`
- Revisão: `.agents/agents/sdui-reviewer.md`

## 16. Comandos executados no bootstrap
```text
java -version
  OpenJDK 25.0.4.1 Temurin (build 25.0.4.1+1-LTS)

.\gradlew.bat --version
  Gradle 9.7.1 | Launcher JVM 25.0.4.1 | Kotlin do Gradle 2.4.0 (runtime do wrapper, não o Kotlin do projeto)

.\gradlew.bat clean build --warning-mode=fail
  BUILD SUCCESSFUL
```
