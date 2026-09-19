# Prompt único — Inicialização do repositório `ms-sdui-composer`

> **Estado:** bootstrap **concluído**. Este arquivo é o prompt histórico de inicialização. **Não reexecutar.**
>
> O modo operacional vigente está em `AGENTS.md`: implementação direta e completa de todo o código produtivo necessário
> (`H01`–`H18`), sem ciclos repetitivos de Gradle e sem espera de testes.
>
> **Como usar (somente em repositório novo, já executado aqui):** salve este arquivo na raiz. Abra-o na sua IA CLI e
> peça: **“Execute integralmente o arquivo `iniciar-prompt.md`.”**
>
> Este prompt criou o bootstrap do repositório e os papéis especializados em `.agents/agents/`. A memória operacional
> curta vigente é o `AGENTS.md`.

## Papel

Atue como Engenheiro de Software Staff/Principal Kotlin/Spring responsável por criar o bootstrap verificável do
`ms-sdui-composer`.

A implementação usa Kotlin 2.4.20 sobre JVM com Java 25 LTS e Spring Boot 4.1.1.
O projeto é multi-módulo, usa Gradle Kotlin DSL e todo código de produção fica em `src/main/kotlin`;
testes ficam em `src/test/kotlin`.
Não criar fontes Java, exceto quando estritamente exigido por uma integração externa.

O projeto é greenfield. Prepare a fundação Gradle, a documentação operacional, a estrutura de módulos, os papéis
especializados e os testes mínimos para iniciar H00. Não implemente H01–H18 **neste prompt de bootstrap** (já
executado). Depois do bootstrap, o modo vigente em `AGENTS.md` é implementar `H01`–`H18` de forma direta e completa, sem
ciclos de Gradle nem espera de testes.

## Regra de verdade

Antes de alterar qualquer arquivo, localize e leia os documentos disponíveis em `docs/` (incluindo `docs/adr/` e
`docs/artifacts/`).

Precedência:

1. `docs/plano-servico-sdui.md`.
2. ADRs em `docs/adr/` e ADRs explícitos em `docs/pre-arquitetura-ms-sdui-composer.md`.
3. `docs/pre-arquitetura-ms-sdui-composer.md`.
4. `docs/fluxos-integracao-ms-sdui-composer.md`.
5. `docs/historias/H*.md`.
6. `docs/artifacts/*.json`.
7. `docs/resumos-server-driven-ui.md`.
8. `MEMORIA-PROJETO-MS-SDUI-COMPOSER.md`, se existir.

Se um documento existir com nome legado (por exemplo `pre-arquitetura-sdui-home.md` ou
`fluxos-integracao-ms-sdui-home.md`), use-o na mesma posição de precedência e registre a divergência de nome no
relatório final e no `AGENTS.md`. Não renomeie arquivos de origem sem pedido explícito.

Se um documento citado não existir em nenhuma variante, informe o caminho exato, não reconstrua o conteúdo por
inferência e registre a lacuna no relatório final e no `AGENTS.md`. Se houver divergência sem ADR resolvendo-a, pare
antes de codar a parte afetada.

## Identidade do serviço

O `ms-sdui-composer` compõe a árvore de UI de uma surface a partir de uma spec versionada, do contexto do cliente e das
capabilities, devolvendo um envelope REST/JSON pronto para o app.

A primeira surface é `home`:

```text
GET /v1/surfaces/home
```

O Composer é Presentation + Application Controller + BFF de UI e permanece stateless no hot path.

Não criar CMS genérico, Design System, micro-frontend, backend que envia CSS, gateway genérico, serviço de domínio,
GraphQL, gRPC, Protobuf, SDK SDUI de mercado, CQRS, Event Sourcing ou framework de Hexagonal Architecture.

O Spring Boot 4.1 passou a oferecer suporte nativo a gRPC; isso não altera a proibição. Nenhuma dependência de
gRPC/Protobuf entra no projeto sem ADR.

## Tríade SDUI

- **Section:** bloco autocontido com `id`, `type`, `typeVersion`, dados semânticos e actions.
- **Screen:** composição ordenada de sections para uma surface.
- **Action:** intenção serializada encaminhada pelo app a um dispatcher nativo.

`Fragment`, `FragmentStore`, `FragmentResolver`, endpoint de fragment e chave Redis de fragmento estão fora do MVP.

## Stack travada

Versões exatas vivem em `gradle/libs.versions.toml`. Este bloco define pisos e regras.

- Kotlin 2.4.20. Deve coincidir com a versão de Kotlin gerenciada pelo Spring Boot; não divergir. Ao subir o Kotlin,
  subir o Boot junto ou justificar.
- Java 25 LTS via toolchain (`kotlin { jvmToolchain(25) }`), com o plugin
  `org.gradle.toolchains.foojay-resolver-convention` no `settings.gradle.kts` (versão estável mais recente, registrada
  no relatório).
- Spring Boot 4.1.1 como BOM, importado com `platform(SpringBootPlugin.BOM_COORDINATES)`. Não usar
  `io.spring.dependency-management`.
- Spring Framework, Jackson 3, JUnit Jupiter, AssertJ, Mockito, Testcontainers, driver MongoDB e Lettuce usam **sempre**
  a versão do BOM. Nunca fixar versão dessas bibliotecas.
- Gradle 9.7.1 com Kotlin DSL, version catalog (`gradle/libs.versions.toml`) e convention plugins em `build-logic/`.
  Proibido `allprojects {}` e `subprojects {}`. Configuration cache e build cache habilitados.
- Plugins por papel de módulo (aplicados somente via convention plugins):
    - `sdui.kotlin-base` (aplicado indiretamente por todos): `kotlin("jvm")`, `java-library`, toolchain 25, flags do
      compilador, BOM do Boot como `platform`, JUnit Jupiter/AssertJ e `verifyForbiddenDependencies`.
    - `sdui.kotlin-library` (`sdui-core`, `sdui-contract`): `sdui.kotlin-base` + `verifyPureClasspath`.
    - `sdui.spring-library` (`sdui-app`, `sdui-integration-test`): `sdui.kotlin-base` + `kotlin("plugin.spring")` +
      `kotlin-reflect`.
    - `sdui.spring-app` (`sdui-bootstrap`): `sdui.spring-library` + `org.springframework.boot`.
    - Detalhes e esboços em `docs/pre-arquitetura-ms-sdui-composer.md` §8–§9.
- Compilador Kotlin: `-Xannotation-default-target=param-property` e `allWarningsAsErrors = true`.
- `kotlin-reflect` nos módulos Spring, pois a aplicação depende de reflexão Kotlin.
- JSON: Jackson 3 gerenciado pelo Boot. Onde houver serialização de `data class`, declarar
  `tools.jackson.module:jackson-module-kotlin` (groupId do Jackson 3; nunca `com.fasterxml.jackson.module`).
- Starters: usar os starters modulares do Spring Boot 4. Confirmar o nome exato de cada starter no BOM antes de
  declará-lo; não usar nomes da linha 3.x.
- Testes: JUnit Jupiter e AssertJ na versão do BOM.
- ArchUnit 1.5.0 (fora do BOM, no catálogo), artefato `com.tngtech.archunit:archunit` usado dentro de testes JUnit
  Jupiter comuns. Não usar o engine `archunit-junit5`.
- Mocks: MockK com `springmockk` (`@MockkBean`) quando uma história exigir. Não adicionar no bootstrap. Ao adicionar,
  registrar no catálogo uma versão verificada como compatível com Spring Boot 4.1. Se Mockito for usado em vez de MockK,
  usar `mockito-kotlin` e configurar o Mockito como `-javaagent` na task de teste.
- Testes de integração com Testcontainers (+ `@ServiceConnection`): somente quando uma história exigir. Não adicionar no
  bootstrap.
- MongoDB como fonte de verdade das specs. A versão de servidor é a que o ambiente produtivo roda; o driver segue o BOM.
  Testes futuros usam a mesma imagem de servidor da produção.
- Redis na mesma AZ para cache.
- Virtual Threads para I/O bound, habilitadas com `spring.threads.virtual.enabled: true` no `application.yaml` do
  bootstrap.
- Kafka somente para auditoria assíncrona real; não adicionar no bootstrap.
- Spring Cloud fora por padrão.
- Sem MapStruct e sem `kapt`: mapeamento entre camadas via funções de extensão Kotlin (`fun SectionSpec.toDto() = ...`),
  explícito e testável.

Não usar preview, incubating ou `--enable-preview`.

## Convenções Kotlin

- Usar `data class` para DTOs, envelopes, comandos, resultados e value objects imutáveis.
- Usar `sealed interface` ou `sealed class` para hierarquias fechadas de domínio.
- Usar `enum class` para conjuntos enumerados estáveis.
- Preferir construtor primário e propriedades `val`.
- Não usar `Optional`; representar ausência com `T?` somente quando ela for válida no domínio.
- Não usar `!!`; tratar explicitamente valores ausentes.
- Código de produção em `src/main/kotlin`; testes em `src/test/kotlin`.
- Não criar fontes em Java, salvo necessidade comprovada de integração.
- `kotlin("plugin.spring")` somente nos módulos que têm classes Spring, via convention plugin.
- O pacote base é `br.com.empresa.sdui`, conforme a pré-arquitetura. Pacotes seguem a **camada**, não o módulo:
  `br.com.empresa.sdui.core`, `.contract`, `.orchestrator`, `.adapters`, `.api`, `.bootstrap`. `orchestrator`,
  `adapters` e `api` compartilham o módulo `sdui-app`, separados por pacote. Se a pré-arquitetura não estiver
  disponível, escolha um pacote base, registre-o como decisão provisória no `AGENTS.md` e no relatório.
- Portas ficam em `orchestrator.port.inbound` e `orchestrator.port.outbound` (`in` é palavra reservada em Kotlin).

## Regras inegociáveis

Não enviar no JSON de UI: `color`, `background`, `font`, `typography`, `margin`, `padding`, `gap`, `width`, `height`,
`radius`, `rounded`, `cornerRadius`, `shadow`, `orientation`, `circle`, `rectangle`, `shimmer`, `ripple`, `haptic`,
`dp`, `pt`, `itemWidth`, `itemHeight`, `breakpoint` ou `formFactor`.

Não criar `row`, `column`, `container`, `stack`, `card` genérico ou DSL de pixels. Usar types semânticos versionados.

Headers próprios não usam `X-`. Headers previstos:

```text
UI-Schema-Version
Client-Platform
Client-Version
Client-Build
Accept-Language
API-Version
OS-Version
Component-Capabilities
```

O compose da Home não retorna 404 por ausência de targeting. A política de fallback deve seguir os artefatos
disponíveis; não inventar regra ausente da skill.

Não colocar PII, dado regulado, valor financeiro individual, credencial, token de autenticação ou domínio bruto em
payload, cache, log ou métrica.

## Concorrência e rede

- Não usar `Executors.newFixedThreadPool` para I/O de banco ou rede.
- Não usar `synchronized` envolvendo I/O. Desde o Java 24 (JEP 491) `synchronized` não prende mais a virtual thread ao
  carrier, mas segurar lock durante I/O continua gerando contenção e latência. Quando exclusão mútua for necessária,
  preferir `ReentrantLock` com escopo mínimo.
- Não deixar `CompletableFuture` sem `.handle()` ou `.exceptionally()`.
- Não usar `StructuredTaskScope` (preview no Java 25).
- Não usar coroutines, `suspend fun` nem repositórios reativos (ADR-012 da pré-arquitetura): o modelo é Spring MVC +
  virtual threads + drivers bloqueantes.
- Não usar `ScopedValue`. Ele é final no Java 25; a exclusão é decisão arquitetural (sem consumidor concreto no MVP) e
  só pode ser revista via ADR.
- Todo I/O externo deve ter timeout explícito.
- Fan-out deve ter limite de concorrência compartilhado por instância/dependência (ex.: `Semaphore` de vida longa, não
  um por request) e orçamento total de tempo; virtual threads não limitam carga por si só.
- Timeout em `Future.get(timeout)` exige `future.cancel(true)`; sem isso a tarefa continua rodando depois da resposta.
- Falha tolerável de section deve omitir a section.
- Não fazer N+1.
- Não adicionar Resilience4j no bootstrap.

## Estrutura obrigatória

Crie exatamente esta estrutura inicial:

```text
ms-sdui-composer/
├── .gitignore
├── AGENTS.md
├── README.md
├── iniciar-prompt.md
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── build-logic/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       ├── VerifyDependencies.kt
│       ├── sdui.kotlin-base.gradle.kts
│       ├── sdui.kotlin-library.gradle.kts
│       ├── sdui.spring-library.gradle.kts
│       └── sdui.spring-app.gradle.kts
├── docs/
│   ├── plano-servico-sdui.md                      (somente se existir)
│   ├── pre-arquitetura-ms-sdui-composer.md        (somente se existir)
│   ├── fluxos-integracao-ms-sdui-composer.md      (somente se existir)
│   ├── resumos-server-driven-ui.md                (somente se existir)
│   ├── historias/
│   │   └── H00...H18                              (somente as que existirem)
│   ├── adr/
│   │   └── README.md
│   └── artifacts/
│       └── contrato-sdui-home-android-proposto.json   (somente se existir)
├── .agents/
│   ├── agents/
│   │   ├── sdui-architect.md
│   │   ├── sdui-implementer.md
│   │   ├── sdui-tester.md
│   │   ├── sdui-contract-guard.md
│   │   └── sdui-reviewer.md
│   └── skills/
│       └── sdui-backend/
│           └── README.md
├── sdui-contract/
├── sdui-core/
├── sdui-app/
├── sdui-bootstrap/
└── sdui-integration-test/
```

Cada módulo contém `build.gradle.kts`, `src/main/kotlin/` e `src/test/kotlin/` (criar apenas os diretórios necessários;
não criar classes vazias de enfeite).

Arquivos de `docs/` marcados como "somente se existir" não são criados por este prompt. Se não estiverem disponíveis,
não os invente: crie apenas os diretórios e registre os arquivos ausentes no `AGENTS.md` e no relatório.

## Grafo de dependências

Quatro módulos de produção e um de teste (ADR-001 da pré-arquitetura, `ACEITO`):

```text
sdui-bootstrap        → sdui-app (implementation)
sdui-app              → sdui-core (api), sdui-contract (implementation)
sdui-contract         → JDK, stdlib Kotlin e Jackson estritamente necessário
sdui-core             → JDK e stdlib Kotlin
sdui-integration-test → testImplementation de sdui-bootstrap, sdui-app, sdui-core e sdui-contract
```

- `sdui-app` declara `api(project(":sdui-core"))` porque portas e casos de uso expõem tipos do core nas assinaturas
  públicas.
- Dentro de `sdui-app`, a direção entre camadas é garantida por ArchUnit: `api → orchestrator ← adapters`;
  `orchestrator` sem Spring, sem `contract` e sem as bordas; `api` sem `adapters`; `adapters` sem `api` e sem
  `contract`.

Somente `sdui-bootstrap` é executável e contém `@SpringBootApplication`. Slices de teste em `sdui-app` (`@WebMvcTest`,
`@DataMongoTest`) usam uma classe de teste anotada com `@SpringBootConfiguration` + `@EnableAutoConfiguration`, nunca
`@SpringBootApplication`.

O grafo é garantido primeiro pelo Gradle (declaração de dependências + verificação de classpath) e depois pelo ArchUnit.
O ArchUnit não substitui a verificação de classpath.

## Build Gradle

### `settings.gradle.kts`

- `pluginManagement { includeBuild("build-logic") }`.
- Plugin `org.gradle.toolchains.foojay-resolver-convention`.
- `rootProject.name = "ms-sdui-composer"` e `include` dos cinco projetos (`sdui-contract`, `sdui-core`, `sdui-app`,
  `sdui-bootstrap`, `sdui-integration-test`).
- `dependencyResolutionManagement` com `repositoriesMode` em `FAIL_ON_PROJECT_REPOS` e `mavenCentral()`.

### `build-logic/`

- Build incluído com plugin `kotlin-dsl` e precompiled script plugins.
- `build-logic/settings.gradle.kts` importa o catálogo da raiz:
  `versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }`.
- `build-logic/build.gradle.kts` declara como dependências os artefatos dos plugins (Kotlin Gradle Plugin,
  `kotlin-allopen` para `plugin.spring` e Spring Boot Gradle Plugin), com versões vindas do catálogo.
- Dentro de precompiled script plugins o acessor tipado `libs` não está disponível; usar
  `extensions.getByType<VersionCatalogsExtension>().named("libs")`.

### Verificações de classpath (ligadas à task `check`)

- Ambas usam a task `VerifyDependencies` (em `build-logic/src/main/kotlin/`, esboço na pré-arquitetura §8.5), que
  **ignora componentes de plataforma**: sem isso, o próprio BOM `spring-boot-dependencies` seria acusado como
  dependência Spring em `sdui-core`.
- `verifyPureClasspath`, registrada em `sdui.kotlin-library` (portanto ativa somente em `sdui-core` e `sdui-contract`):
  falha se o `runtimeClasspath` resolvido contiver módulos dos grupos `org.springframework*`, `org.mongodb`,
  `io.lettuce`, `jakarta.servlet`.
- `verifyForbiddenDependencies`, registrada em `sdui.kotlin-base` (portanto em todos os módulos): falha se o
  `runtimeClasspath` contiver `io.grpc`, `com.google.protobuf`, `org.springframework.grpc`, `com.graphql-java`,
  `org.springframework.graphql`, `org.mapstruct` ou `org.apache.kafka` (este último até existir ADR de auditoria
  assíncrona).
- As duas tasks devem ser compatíveis com configuration cache: receber o resultado da resolução como input
  (`incoming.resolutionResult.rootComponent` via `Provider`), sem acessar `project` na execução.

### `gradle.properties`

```properties
org.gradle.configuration-cache=true
org.gradle.caching=true
org.gradle.parallel=true
kotlin.code.style=official
```

### Wrapper

O `gradle-wrapper.jar` é binário e **não** deve ser escrito à mão nem baixado de fonte não oficial. Gerar com um Gradle
instalado localmente:

```text
gradle wrapper --gradle-version 9.7.1 --distribution-type bin --gradle-distribution-sha256-sum acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a
```

O checksum acima é o da distribuição `bin` do Gradle 9.7.1 publicado pelo projeto Gradle; confirme
em https://gradle.org/release-checksums/ antes de usar. `gradle-wrapper.properties` deve conter `distributionSha256Sum`.
Se não houver Gradle instalado, reportar o bloqueio e parar.

### `.gitignore`

Incluir no mínimo: `build/`, `.gradle/`, `.kotlin/`, `.idea/`, `*.iml`, `out/`, `local.properties`, `.env`.

## Escopo do bootstrap

Criar:

1. `settings.gradle.kts`, `build.gradle.kts` raiz (vazio ou apenas `apply false`; sem lógica compartilhada) e
   `build.gradle.kts` de cada módulo aplicando um único convention plugin.
2. `build-logic/` com os quatro convention plugins, a task `VerifyDependencies` e as duas verificações de classpath.
3. `gradle/libs.versions.toml` e `gradle.properties`.
4. Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) com checksum.
5. Diretórios dos módulos.
6. `SduiApplication` em `sdui-bootstrap`.
7. `application.yaml` sem credenciais ou URLs reais, com `spring.threads.virtual.enabled: true`.
8. `README.md`.
9. `.gitignore`.
10. `AGENTS.md` abaixo de 400 linhas.
11. Os cinco arquivos de papel em `.agents/agents/`.
12. `.agents/skills/sdui-backend/README.md` como marcador.
13. `docs/adr/README.md` explicando formato e numeração de ADRs.
14. Teste mínimo de contexto no bootstrap.
15. Testes ArchUnit mínimos em `sdui-integration-test`.

Não criar controller de produção, endpoint, Mongo ativo, Redis ativo, repositories, hydrator, cache produtivo,
governança, rollback, canary, auditoria, seed ou CI/CD, salvo requisito explícito que pertença ao bootstrap. Não
declarar starters de MongoDB ou Redis no bootstrap.

## Papéis especializados

Os arquivos em `.agents/agents/` são instruções operacionais, não agentes executáveis e não componentes do runtime.

Cada papel deve ler `AGENTS.md` antes de agir e reportar as fontes consultadas, arquivos alterados, resultado, riscos e
bloqueios. Depois do bootstrap, o implementer **não** reporta nem executa Gradle no ciclo de implementação.

| Papel         | Arquivo                                 | Permissão padrão                                          |
|---------------|-----------------------------------------|-----------------------------------------------------------|
| Arquitetura   | `.agents/agents/sdui-architect.md`      | documentação/ADRs somente                                 |
| Implementação | `.agents/agents/sdui-implementer.md`    | código e testes do escopo                                 |
| Testes        | `.agents/agents/sdui-tester.md`         | testes e relatório; não corrigir produção silenciosamente |
| Contrato      | `.agents/agents/sdui-contract-guard.md` | auditoria; não alterar código                             |
| Revisão       | `.agents/agents/sdui-reviewer.md`       | auditoria; não alterar código                             |

### Conteúdo dos papéis

Crie cada arquivo com as instruções abaixo.

#### `.agents/agents/sdui-architect.md`

```markdown
# SDUI Architect

## Papel

Atuar como arquiteto técnico do `ms-sdui-composer`.

Produzir decisões implementáveis sem inventar requisitos ou conteúdo de skills ausentes.

## Antes de decidir

Ler `AGENTS.md`, a história, os artefatos relacionados, os ADRs aplicáveis e a documentação de contrato. Se uma decisão
depender de conteúdo ausente, declarar a limitação e bloquear a parte afetada.

## Responsabilidades

- Interpretar a história.
- Identificar invariantes e dependências.
- Definir responsabilidades e interfaces.
- Definir comportamento normal, erro, timeout e fallback.
- Avaliar impacto no contrato e na compatibilidade.
- Definir testes e observabilidade.
- Produzir ADR em `docs/adr/` para decisão estrutural.

## Restrições

- Não implementar produção por padrão.
- Não alterar contrato informalmente.
- Não criar endpoint fora do escopo.
- Não colocar regra de domínio no Composer.
- Não introduzir GraphQL, gRPC, Protobuf ou framework SDUI.
- Não introduzir `ScopedValue`, Kafka ou nova biblioteca fora do BOM sem ADR.
- Não criar targeting por form factor.
- Não adicionar aparência, geometria ou CSS ao payload.
- Não propor abstração genérica sem consumidor concreto.

## Saída

## Objetivo

## Fontes consultadas

## Decisão

## Fluxo proposto

## Interfaces e responsabilidades

## Falhas e fallback

## Impacto no contrato

## Observabilidade

## Plano de testes

## Riscos e incertezas

## Próxima ação
```

#### `.agents/agents/sdui-implementer.md`

```markdown
# SDUI Implementer

## Papel

Implementar mudanças aprovadas em Kotlin 2.4.20, sobre JVM Java 25 e Spring Boot 4.1.x.

## Pré-condições

Ler `AGENTS.md`, a história, os artefatos relacionados, as decisões arquiteturais e o contrato afetado antes de alterar
arquivos.

## Regras

- Implementar somente o escopo solicitado.
- Escrever produção em `src/main/kotlin` e testes em `src/test/kotlin`; não criar fontes Java.
- Não alterar contrato para facilitar implementação.
- Não inventar comportamento.
- Não expor entidades como DTOs HTTP.
- Mapear entre camadas com funções de extensão Kotlin; não usar MapStruct nem `kapt`.
- Não colocar regra de domínio no Composer.
- Não criar N+1.
- Usar timeout em chamadas externas.
- Tratar terminalmente operações assíncronas.
- Não usar `synchronized` envolvendo I/O; se precisar de exclusão mútua, `ReentrantLock` com escopo mínimo.
- Usar Virtual Threads somente para I/O bound e limitar fan-out explicitamente.
- Não usar coroutines nem `suspend fun` (ADR-012).
- Respeitar as camadas dentro de `sdui-app`: `orchestrator` sem Spring e sem `contract`; `api` sem `adapters`.
- Não adicionar dependências sem justificativa; versões gerenciadas pelo Spring Boot nunca são fixadas.
- Adicionar dependência sempre via `gradle/libs.versions.toml` e convention plugins; nunca `allprojects {}`/
  `subprojects {}`.
- Manter o Composer stateless.
- Não usar `@Transactional` em controller, adapter ou infraestrutura.

## Bloqueios

Parar e reportar se houver contrato ambíguo, decisão arquitetural ausente, mudança de schema não aprovada, skill
necessária ausente ou critérios incompatíveis.

## Saída

- arquivos alterados;
- comportamento implementado;
- decisões reutilizadas;
- testes e comandos executados;
- resultado;
- riscos;
- pendências.
```

#### `.agents/agents/sdui-tester.md`

```markdown
# SDUI Tester

## Papel

Validar comportamento, critérios de aceite, contrato e cenários de falha.

## Antes de testar

Ler `AGENTS.md`, a história, o contrato, os artefatos, a decisão arquitetural, o código alterado e os testes existentes.

## Verificar quando aplicável

- headers, schema, plataforma, app, build, OS e capabilities;
- seleção determinística e filtering;
- hidratação, timeout e fallback;
- última árvore boa, ETag e 304;
- rate limit, cache e singleflight;
- rollback, maker-checker e observabilidade;
- ausência de PII, dado regulado e campos visuais proibidos.

## Regras

- Não testar somente o happy path.
- Não tratar cobertura de linhas como prova suficiente.
- Não corrigir silenciosamente o código sob teste.
- Registrar reprodução mínima de cada falha.
- Não inventar critérios de aceite.
- Usar JUnit Jupiter e AssertJ do BOM; mocks com MockK/springmockk quando necessários.
- Testcontainers somente quando a história exigir integração real, com a mesma imagem de servidor da produção.

## Saída

## Escopo

## Cenários executados

## Comandos

## Resultado

## Falhas encontradas

## Severidade

## Evidências

## Limitações

## Recomendação
```

#### `.agents/agents/sdui-contract-guard.md`

```markdown
# SDUI Contract Guard

## Papel

Verificar se a mudança preserva contrato, compatibilidade e regras de governança.

## Decisão

Usar `PASS`, `PASS_WITH_WARNINGS` ou `BLOCK`.

## Verificar

- envelope e tipos obrigatórios;
- headers e ausência de novos headers próprios com prefixo `X-`;
- `id`, `slot`, `type`, `typeVersion`, `props`, `actions` e analytics;
- actions permitidas e labels de CTAs;
- omissão de sections incompatíveis;
- separação entre schema, typeVersion, app, OS e capabilities;
- comparação semver não lexicográfica;
- ausência de PII, dado regulado, CSS, aparência e geometria.

## Bloqueio

Todo `BLOCK` deve informar regra violada, arquivo/localização, evidência, impacto, correção mínima e teste preventivo
recomendado.

As verificações repetíveis devem ser convertidas em testes, validadores ou gates de CI. Este agente não é a única
proteção contra regressões.
```

#### `.agents/agents/sdui-reviewer.md`

```markdown
# SDUI Reviewer

## Papel

Fazer a revisão técnica final antes da integração.

## Avaliar

- escopo e critérios de aceite;
- responsabilidades arquiteturais e grafo de módulos;
- statelessness;
- tratamento de erros, timeout e fallback;
- concorrência: limites de fan-out, contenção de locks, locks segurados durante I/O e pinning residual (código
  nativo/JNI);
- N+1;
- contrato, segurança e ausência de PII;
- métricas, logs e impacto no SLO;
- dependências: nada fixado que o BOM gerencia, nada proibido no classpath;
- legibilidade, testes e reversibilidade.

## Não fazer

- Não reescrever a solução sem necessidade.
- Não bloquear por preferência pessoal.
- Não introduzir tecnologia sem requisito.
- Não reabrir decisões sem evidência de problema.

## Saída

## Decisão

`PASS` | `PASS_WITH_WARNINGS` | `REQUEST_CHANGES`

## Evidências

## Bloqueadores

## Riscos relevantes

## Melhorias não bloqueantes

## Próximo passo
```

### Seleção e carregamento

Quando uma tarefa solicitar um papel, carregar nesta ordem:

1. `AGENTS.md`.
2. O arquivo correspondente em `.agents/agents/`.
3. A história e os artefatos relacionados.
4. ADRs aplicáveis.
5. O contrato ou fixture afetado.

Mapeamento:

```text
sdui-architect      → .agents/agents/sdui-architect.md
sdui-implementer    → .agents/agents/sdui-implementer.md
sdui-tester         → .agents/agents/sdui-tester.md
sdui-contract-guard → .agents/agents/sdui-contract-guard.md
sdui-reviewer       → .agents/agents/sdui-reviewer.md
```

Se o arquivo não existir, informar o caminho ausente, não reconstruir o papel por inferência e registrar o bloqueio.

### Fluxos

Estes fluxos valiam para o bootstrap. **Depois do bootstrap, o fluxo padrão é só `sdui-implementer`.** Os demais papéis
só entram se o operador os pedir. Não encadear papéis nem Gradle entre histórias.

Alteração de implementação (legado do bootstrap):

```text
sdui-architect → sdui-implementer → sdui-tester → sdui-contract-guard → sdui-reviewer
```

Alteração sem impacto de contrato (legado):

```text
sdui-implementer → sdui-tester → sdui-reviewer
```

Alteração de contrato (legado):

```text
sdui-architect → sdui-contract-guard → sdui-implementer → sdui-tester → sdui-contract-guard → sdui-reviewer
```

H00 (concluída):

```text
sdui-contract-guard → sdui-tester → sdui-reviewer
```

Não executar todos os papéis automaticamente em toda tarefa. Usar somente os papéis necessários. No modo vigente, o
necessário é o implementer.

## Skill ausente

Crie `.agents/skills/sdui-backend/README.md` com este conteúdo:

```markdown
# sdui-backend

A skill canônica `sdui-backend` não está disponível neste repositório.

Este arquivo é apenas um marcador.

Não inventar conteúdo, referências, comandos ou regras da skill.

Enquanto a skill não estiver disponível, consultar as fontes do projeto.

Se uma decisão depender especificamente de conteúdo ausente da skill,
marcar a tarefa como bloqueada e solicitar uma decisão explícita.
```

## AGENTS.md obrigatório

Criar `AGENTS.md` como memória operacional curta, contendo: definição do serviço, stack (com a regra "versões
gerenciadas pelo Boot nunca são fixadas"), módulos e grafo, convention plugins, tríade SDUI, pipeline, eixos de
compatibilidade, proibições, regras de concorrência, ordem H00–H18, status inicial das histórias, lacunas (incluindo
documentos ausentes ou com nome legado), decisões provisórias (ex.: pacote base), pendências temporárias (ex.:
`allowEmptyShould`), comandos executados, regra sobre `Fragment`, regra de ADR e esta localização dos papéis:

```markdown
## Papéis especializados

Os papéis estão em `.agents/agents/`.

Antes de executar uma tarefa especializada, carregar `AGENTS.md` e o arquivo do papel correspondente. Os papéis são
instruções de desenvolvimento, não componentes do runtime.
```

## Testes do bootstrap

Criar somente:

1. Teste de contexto Spring Boot em `sdui-bootstrap` (`@SpringBootTest`), que sobe sem Mongo nem Redis.
2. Testes ArchUnit em `sdui-integration-test`, usando `ClassFileImporter` sobre `br.com.empresa.sdui` (sem classes de
   teste) e o artefato `archunit` dentro de testes JUnit Jupiter comuns:
    - `..sdui.core..` não depende de `org.springframework..`, `org.mongodb..`, `com.mongodb..`, `io.lettuce..`,
      `jakarta.servlet..`, `tools.jackson..`, `com.fasterxml.jackson..` nem de outras camadas;
    - `..sdui.orchestrator..` não depende de `org.springframework..`, `jakarta.servlet..`, Jackson, `..sdui.contract..`,
      `..sdui.adapters..` nem `..sdui.api..`;
    - `..sdui.api..` não depende de `..sdui.adapters..`;
    - `..sdui.adapters..` não depende de `..sdui.api..` nem de `..sdui.contract..`;
    - `..sdui.contract..` não depende de `..sdui.core..` nem de `org.springframework..`;
    - somente `..sdui.bootstrap..` contém classes anotadas com `@SpringBootApplication`;
    - nenhuma classe de produção depende de `kotlinx.coroutines..`.

Com `orchestrator`, `adapters` e `api` no mesmo módulo, essas regras são a única proteção entre as três camadas; não são
opcionais.

Enquanto os módulos estiverem vazios, as regras ArchUnit falhariam por não verificarem nenhuma classe. Use
`.allowEmptyShould(true)` apenas nessas regras, com comentário apontando a história que removerá a exceção, e registre a
pendência no `AGENTS.md`.

A proteção primária de pureza de `sdui-core` e `sdui-contract` é a task `verifyPureClasspath`; o ArchUnit é a segunda
camada.

Não incluir Testcontainers, WireMock, Mongo, Redis ou mocks até uma história exigir.

## Comandos obrigatórios (somente no bootstrap; já executados)

Estes comandos valem para a inicialização do repositório. **Não** reexecutá-los como rotina depois do bootstrap. No modo
vigente, a implementação escreve código e não espera Gradle.

Executar e reportar no shell disponível **apenas ao criar o repositório**.

Windows PowerShell:

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat clean build --warning-mode=fail
```

Linux/macOS (e CI):

```bash
java -version
./gradlew --version
./gradlew clean build --warning-mode=fail
```

`build` já executa `test` e `check` (incluindo `verifyPureClasspath` e `verifyForbiddenDependencies`); não rodar os
testes duas vezes. `--warning-mode=fail` torna deprecações do Gradle visíveis desde o início.

Se algum comando falhar, informar a falha exata e não declarar o projeto validado.

## Critério de pronto

Somente considerar concluído quando:

- os cinco projetos (`sdui-contract`, `sdui-core`, `sdui-app`, `sdui-bootstrap`, `sdui-integration-test`) existirem e
  estiverem no `settings.gradle.kts`;
- cada módulo aplicar exatamente um convention plugin e não houver `allprojects {}`/`subprojects {}`;
- nenhuma versão gerenciada pelo Spring Boot estiver fixada no catálogo ou nos builds;
- o wrapper apontar para Gradle 9.7.1 com `distributionSha256Sum`;
- a aplicação compilar com toolchain Java 25;
- o contexto iniciar sem Mongo/Redis externos;
- `verifyPureClasspath`, `verifyForbiddenDependencies` e ArchUnit estiverem verdes;
- os cinco arquivos em `.agents/agents/` existirem;
- o marcador da skill existir sem conteúdo inventado;
- `AGENTS.md` apontar para os papéis e registrar lacunas e pendências temporárias;
- `clean build --warning-mode=fail` tiver sido executado com sucesso **neste bootstrap**;
- nenhuma funcionalidade H01–H18 tiver sido antecipada **neste bootstrap**. Depois dele, `H01`–`H18` passam a ser o
  código produtivo a escrever de uma vez.

## Relatório final

Responder com:

### Build

Versões detectadas (Java, Gradle, Kotlin, Spring Boot e versões efetivas resolvidas pelo BOM), comandos executados e
resultado real.

### Arquivos criados

Agrupar por documentação, papéis, build (`build-logic/`, catálogo, wrapper), raiz e módulos.

### Estrutura final

Mostrar a árvore sem `build/`, `.gradle/` e `.kotlin/`.

### Testes

Listar testes e verificações de classpath, resultado e proteção fornecida.

### Decisões aplicadas

Listar somente decisões documentadas, expressamente definidas neste prompt ou registradas como provisórias no
`AGENTS.md`.

### Lacunas e bloqueios

Listar arquivos (incluindo nomes legados encontrados), skills, ADRs ou decisões ausentes.

### Próximo passo

Bootstrap e H00 concluídos. A partir daqui:

```text
Implementar de forma direta e completa o código produtivo de H01–H18,
sem ciclos repetitivos de Gradle e sem espera de testes.
```
