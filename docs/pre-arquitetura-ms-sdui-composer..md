# Pré-arquitetura modular — ms-sdui-composer

**ms-sdui-composer** = o serviço que, a cada request, compõe a árvore de UI da surface a partir de uma spec versionada,
do contexto do cliente e das capabilities, devolvendo um envelope pronto e seguro para o app.

## Gradle, Clean Architecture e estratégia de testes

**Baseline:** Java 25 LTS, Spring Framework 7.0.9+, Spring Boot 4.1.1, Gradle 9.7.1 - Kotlin e multi-módulo.

**Objetivo:** estruturar o **ms-sdui-composer** para suportar composição de surfaces por múltiplos times, evolução de
telas, fragments estáticos, hidratação dinâmica por squads e testes com níveis claros de isolamento (tendo a `home` como
primeira surface).


> **Atualização tecnológica — 16/09/2026.** Esta revisão eleva o baseline para Java 25 LTS, Spring Boot 4.1.1 e Spring
> Framework 7.0.9+. Gradle 9.7.1 é o release estável adotado. O parent do Spring Boot passa a gerir as versões do
> ecossistema; não fixar manualmente versões de starters, Testcontainers, JUnit, Mockito, AssertJ ou drivers já
> gerenciados. ArchUnit foi corrigido para 1.5.0.
>
> O projeto permanece sem Kafka e sem Spring Cloud por padrão. Kafka exige fluxo assíncrono real com contrato,
> consumidores, idempotência, retry, DLQ, retenção e owner operacional. Spring Cloud só entra com caso concreto de
> Config
> Client, Discovery, client-side LoadBalancer ou Circuit Breaker. MongoDB permanece porque esta pré-arquitetura o define
> como store de specs; RestClient/WireMock continuam condicionais à existência de hidratação HTTP real.

---

## 1. Decisão arquitetural

A proposta de modularização faz sentido, mas precisa de alguns ajustes para não criar acoplamento entre contrato, core,
orquestrador e adaptadores.

O serviço será um **Gradle multi-módulo** (Kotlin DSL), com `build.gradle.kts` raiz e subprojetos declarados em `settings.gradle.kts`. O único artefato executável será o módulo `sdui-bootstrap`; os demais produzirão JARs internos.

A organização seguirá uma **Clean Architecture pragmática**:

- `sdui-core` contém regras puras de domínio e não depende de Spring, MongoDB, Redis ou HTTP.
- `sdui-orchestrator` contém casos de uso e coordena o pipeline de composição.
- `sdui-contract` contém DTOs do contrato REST/JSON e catálogo público do SDUI.
- `sdui-adapters` contém integrações externas, persistência, cache e clients HTTP.
- `sdui-api` contém a entrada HTTP/MVC e a conversão entre HTTP e casos de uso.
- `sdui-bootstrap` monta a aplicação, configura propriedades, segurança, observabilidade e beans.

A proposta não deve introduzir Hexagonal Architecture como um framework adicional. O uso de portas/interfaces será
limitado aos contratos que o caso de uso realmente precisa: stores, cache, singleflight e hydrators.

Também não devem ser introduzidos CQRS, Event Sourcing, command bus ou uma camada `common` genérica. O projeto é um
composer/BFF de UI stateless, não um sistema de domínio transacional.

O plano do projeto define que o MS entrega uma árvore de UI hidratada, não acessa contratos, apólices ou domínios de
negócio diretamente, usa MongoDB como fonte de specs e Redis para árvore, spec, projeções, `lastgood` e singleflight.
Ele também proíbe N+1 por widget e exige que uma section degradada possa ser omitida sem derrubar a Home. [file:8]

> **Decisões fechadas:** as escolhas que divergem do `plano-servico-sdui.md` ou que não estavam explícitas nele estão
> registradas como ADRs no **§16**. Toda divergência em relação ao plano precisa de um ADR antes de virar código.

---

## 2. Estrutura de módulos ajustada

```text
ms-sdui-composer/
├── pom.xml
├── README.md
├── .mvn/wrapper/
├── mvnw
├── mvnw.cmd
│
├── sdui-contract/
│   ├── pom.xml
│   └── src/
│       ├── main/java/br/com/empresa/sdui/contract/
│       └── test/java/br/com/empresa/sdui/contract/
│
├── sdui-core/
│   ├── pom.xml
│   └── src/
│       ├── main/java/br/com/empresa/sdui/core/
│       └── test/java/br/com/empresa/sdui/core/
│
├── sdui-orchestrator/
│   ├── pom.xml
│   └── src/
│       ├── main/java/br/com/empresa/sdui/orchestrator/
│       └── test/java/br/com/empresa/sdui/orchestrator/
│
├── sdui-adapters/
│   ├── pom.xml
│   └── src/
│       ├── main/java/br/com/empresa/sdui/adapters/
│       │   ├── mongo/
│       │   ├── redis/
│       │   └── http/
│       └── test/java/br/com/empresa/sdui/adapters/
│
├── sdui-api/
│   ├── pom.xml
│   └── src/
│       ├── main/java/br/com/empresa/sdui/api/
│       └── test/java/br/com/empresa/sdui/api/
│
├── sdui-bootstrap/
│   ├── pom.xml
│   └── src/
│       ├── main/java/br/com/empresa/sdui/bootstrap/
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── application-local.yml
│       │   └── application-test.yml
│       └── test/java/br/com/empresa/sdui/bootstrap/
│
└── sdui-integration-test/
    ├── pom.xml
    └── src/test/java/br/com/empresa/sdui/it/
```

### Papel dos módulos

| Módulo                  | Responsabilidade                                           | Spring em produção? | Executável? |
|-------------------------|------------------------------------------------------------|--------------------:|------------:|
| `sdui-contract`         | Envelopes, responses, catálogo e DTOs REST/JSON            |                 Não |         Não |
| `sdui-core`             | Regras puras do motor SDUI                                 |                 Não |         Não |
| `sdui-orchestrator`     | Casos de uso e pipeline de composição                      |                 Não |         Não |
| `sdui-adapters`         | MongoDB, Redis, clients HTTP e implementações de hydrators |                 Sim |         Não |
| `sdui-api`              | Controllers MVC, headers, validação e exception mapping    |                 Sim |         Não |
| `sdui-bootstrap`        | Main, wiring, properties, segurança e observabilidade      |                 Sim |         Sim |
| `sdui-integration-test` | Testes HTTP/infra com MongoDB e Redis reais                |     Apenas em teste |         Não |

### Observação sobre adapters

Manter `sdui-adapters` como um módulo único no início é aceitável porque o projeto ainda está definindo suas
integrações. Porém, ele deve ser internamente dividido por pacote e regras de dependência:

```text
sdui-adapters
├── mongo
├── redis
└── http
```

Se o volume crescer, separar em `sdui-adapter-mongo`, `sdui-adapter-redis` e `sdui-adapter-http` é uma evolução
mecânica. Não criar três módulos apenas por simetria antes de haver necessidade operacional ou ownership distinto.

O mesmo vale para `sdui-contract`: ele é uma fronteira pública do processo, não um lugar para acumular qualquer DTO
interno.

---

## 3. Grafo de dependências

A estrutura proposta pelo usuário precisa ser corrigida para evitar que adapters dependam do `contract` diretamente como
dependência de implementação, e para deixar o bootstrap como composição final.

```text
                         +----------------------+
                         |    sdui-bootstrap    |
                         | Spring Boot runnable |
                         +----------+-----------+
                                    |
                +-------------------+-------------------+
                |                                       |
        +-------v--------+                      +-------v--------+
        |    sdui-api    |                      |  sdui-adapters |
        | MVC / REST     |                      | Mongo/Redis/HTTP|
        +-------+--------+                      +-------+--------+
                |                                       |
                |                                       |
        +-------v---------------------------------------v--------+
        |                 sdui-orchestrator                    |
        |             application use cases                    |
        +-------------------------+----------------------------+
                                  |
                         +--------v---------+
                         |     sdui-core     |
                         | domain rules pure |
                         +-------------------+

sdui-contract é usado por sdui-api e, se necessário, por bootstrap/adapters apenas
para conversões explicitamente definidas; o core não depende dele.

sdui-integration-test depende do bootstrap em escopo de teste.
```

### Dependências Maven permitidas

| Módulo                  | Pode depender de                                                                    |
|-------------------------|-------------------------------------------------------------------------------------|
| `sdui-core`             | JDK, bibliotecas puras pequenas e JUnit/AssertJ/Mockito em teste                    |
| `sdui-orchestrator`     | `sdui-core`; JDK; bibliotecas puras pequenas; testes                                |
| `sdui-contract`         | JDK e Jackson somente para contrato/serialização, se necessário                     |
| `sdui-adapters`         | `sdui-orchestrator`, `sdui-core`, Spring Data, Redis/Lettuce, Spring HTTP client    |
| `sdui-api`              | `sdui-orchestrator`, `sdui-core`, `sdui-contract`, Spring MVC/Validation            |
| `sdui-bootstrap`        | `sdui-api`, `sdui-adapters`, `sdui-orchestrator`, `sdui-contract`, starters de Boot |
| `sdui-integration-test` | `sdui-bootstrap` e dependências de teste                                            |

### Regra importante sobre o `contract`

O `sdui-core` não deve depender de `sdui-contract`. O core representa regras e modelos internos; o contrato REST
representa a forma pública do payload.

Essa separação evita que o domínio fique preso a nomes JSON, annotations de Jackson, envelopes HTTP e decisões de
compatibilidade externa.

---

## 4. Modelo de Clean Architecture

### 4.1 `sdui-core`: Domain

```text
br.com.empresa.sdui.core
├── context/
│   ├── ClientContext.java
│   ├── ClientPlatform.java
│   ├── ClientVersion.java
│   ├── ClientBuild.java
│   ├── OsVersion.java
│   ├── UiSchemaVersion.java
│   ├── Channel.java
│   └── ComponentCapability.java
├── model/
│   ├── Screen.java
│   ├── Fragment.java
│   ├── Section.java
│   ├── Action.java
│   ├── Skeleton.java
│   ├── Slot.java
│   ├── Spec.java
│   ├── SpecRevisionId.java
│   ├── Pointer.java
│   └── HydratedScreen.java
├── policy/
│   ├── CapabilityCompatibilityPolicy.java
│   ├── SpecSelectionPolicy.java
│   ├── FragmentResolutionPolicy.java
│   ├── OmissionPolicy.java
│   └── FallbackPolicy.java
├── result/
│   ├── SelectionResult.java
│   ├── OmittedFragment.java
│   ├── OmittedSection.java
│   └── FallbackReason.java
└── exception/
    ├── InvalidClientContextException.java
    ├── InvalidSpecException.java
    └── UnsupportedSchemaException.java
```

O core deve ser Java puro, sem Spring, Mongo, Redis, HTTP ou Jakarta. Pode usar records, enums, sealed classes e
`java.time`.

### 4.2 `sdui-orchestrator`: Application

```text
br.com.empresa.sdui.orchestrator
├── compose/
│   ├── ComposeScreenService.java
│   ├── ComposeRequest.java
│   ├── ComposeResult.java
│   ├── ContextValidator.java
│   ├── SpecResolver.java
│   ├── FragmentResolver.java
│   ├── CapabilityFilter.java
│   ├── HydrationCoordinator.java
│   ├── FallbackResolver.java
│   └── ResponseAssembler.java
├── port/
│   ├── in/
│   │   ├── ComposeScreenUseCase.java
│   │   ├── PublishSpecUseCase.java
│   │   └── RollbackPointerUseCase.java
│   └── out/
│       ├── SpecStore.java
│       ├── FragmentStore.java
│       ├── PointerStore.java
│       ├── HydratedScreenCache.java
│       ├── LastGoodScreenStore.java
│       ├── ProjectionStore.java
│       ├── ComposeSingleflight.java
│       ├── AuditLogStore.java
│       ├── IdempotencyStore.java
│       └── TransactionalUnitOfWork.java
├── hydration/
│   ├── SectionHydrator.java
│   ├── HydrationContext.java
│   ├── HydrationResult.java
│   └── HydratorRegistry.java
└── support/
    ├── ClockProvider.java
    └── MetricsRecorder.java
```

#### SPI `SectionHydrator`

A SPI para hydrators de squads faz sentido, desde que seja definida pelo orquestrador e não pelo adapter HTTP:

```java
public interface SectionHydrator {

    boolean supports(ComponentType type, int typeVersion);

    HydrationResult hydrate(HydrationContext context, Section section);
}
```

Regras importantes:

- O orchestrator não conhece cada squad.
- Cada hydrator deve declarar os tipos que suporta.
- A hidratação deve ser bounded, com timeout e tratamento terminal.
- Falha de uma hidratação pode omitir a section, conforme contrato.
- Não permitir que cada squad faça seu próprio acesso irrestrito ao Redis ou Mongo.
- O hydrator não deve inserir cor, tipografia, padding, tamanho, raio ou tokens de aparência no payload.
- O registro de hydrators deve ser determinístico quando houver mais de um candidato para o mesmo `type@version`.

Uma decisão que precisa ser fechada: se hydrators forem módulos externos no futuro, a SPI deve ser um artefato pequeno e
estável, por exemplo `sdui-orchestrator-spi`, em vez de forçar squads consumidoras a depender de todo o módulo de casos
de uso.

### 4.3 `sdui-contract`: contrato

```text
br.com.empresa.sdui.contract
├── screen/
│   ├── ScreenResponse.java
│   ├── FragmentResponse.java
│   ├── ScreenEnvelope.java
│   ├── FragmentEnvelope.java
│   ├── SkeletonResponse.java
│   ├── SectionResponse.java
│   └── OmittedItemResponse.java
├── component/
│   ├── ComponentData.java
│   ├── ComponentResponse.java
│   ├── Action.java
│   └── ActionPayload.java
├── client/
│   └── ClientResponse.java
├── admin/
│   ├── PublishSpecRequest.java
│   └── RollbackRequest.java
└── error/
    └── ApiErrorResponse.java
```

#### Sobre `@JsonTypeInfo`

Contratos polimórficos podem fazer sentido para `ComponentData`, mas não devem ser adicionados por padrão.

A recomendação é preferir um envelope estável e semântico:

```json
{
  "id": "sec_accounts",
  "type": "account_card",
  "typeVersion": 1,
  "data": {
    "title": "Conta",
    "rows": []
  },
  "actions": []
}
```

`@JsonTypeInfo` é adequado somente se houver necessidade real de desserializar subclasses conhecidas no servidor. Para
um servidor que principalmente **produz** JSON, um `type` explícito e um `JsonNode`/mapa validado pode ser mais simples
e menos acoplado.

Evitar usar o valor de `type` como nome de classe Java. O contrato deve ser semanticamente versionado
(`account_card@1`), e não uma hierarquia polimórfica exposta.

O contrato deve respeitar a tríade do projeto:

- **Sections:** blocos autocontidos com `id`, `type`, `typeVersion` e data.
- **Screens:** composição ordenada de sections/fragments em uma surface.
- **Actions:** intenções serializadas encaminhadas por dispatcher central do cliente.

### 4.4 `sdui-adapters`: Infrastructure

```text
br.com.empresa.sdui.adapters
├── mongo/
│   ├── document/
│   ├── repository/
│   ├── mapper/
│   ├── store/
│   └── configuration/
├── redis/
│   ├── key/
│   ├── codec/
│   ├── store/
│   ├── script/
│   └── configuration/
└── http/
    ├── client/
    ├── dto/
    ├── mapper/
    ├── hydrator/
    └── configuration/
```

#### Mongo

Responsável por:

- Specs publicadas e imutáveis.
- Catálogo de componentes.
- Skeletons.
- Fragments estáticos persistidos.
- Pointers.
- Publish requests.
- Diffs.
- Audit log append-only.
- Idempotency records.

`@Document` e repositories Spring Data ficam apenas neste módulo. O orchestrator recebe objetos do core por meio das
interfaces de `port.out`.

#### Redis

Responsável por:

- Spec materializada.
- Screen/fragment hidratado.
- Projeções de section.
- `lastgood`.
- Singleflight distribuído.
- Rate limit distribuído, se essa implementação for escolhida.

TTL estático e dinâmico devem ser separados. Exemplos:

```text
sdui:fragment:static:{fragmentId}:{revision}:{platform}
  TTL maior, alterado somente após publish/invalidate

sdui:tree:{surface}:{platform}:{schema}:{appMajorMinor}:{capsHash}:{channel}
  TTL curto, por exemplo 30–90 s

sdui:lastgood:{surface}:{platform}:{channel}
  TTL longo, conforme política de fallback
```

Não colocar `userId`, PII, mídia binária ou credenciais em chaves/valores de cache compartilhado.

#### HTTP clients

Usar clients HTTP somente se existir hidratação dinâmica real. Não adicionar WireMock, RestClient ou `@HttpExchange`
apenas por antecipação.

Quando houver client HTTP, `RestClient` + `@HttpExchange` é adequado para este serviço síncrono. Spring Framework
oferece `RestClient` como API síncrona e `HttpServiceProxyFactory` para criar proxies a partir de interfaces anotadas
com `@HttpExchange`. [web:52]

O client deve possuir:

- Connect timeout.
- Read/response timeout.
- Tratamento de status e corpo de erro.
- Propagação de correlation/trace context conforme padrão da empresa.
- Limite de resposta.
- Retry somente para erros comprovadamente transitórios e idempotentes.
- Circuit breaker somente se telemetria justificar.
- Tratamento terminal de toda execução concorrente.

Exemplo de SPI de client fora do core:

```java
public interface CoverageHydrationClient {
    CoverageProjection fetch(CoverageHydrationRequest request);
}
```

O `SectionHydrator` usa a interface; a implementação concreta usa `RestClient` ou `@HttpExchange`. Assim, o teste do
orchestrator usa Mockito e o teste do adapter usa WireMock.

### 4.5 `sdui-api`: Interface Adapters

```text
br.com.empresa.sdui.api
├── compose/
│   ├── ScreenController.java
│   ├── FragmentController.java
│   ├── RequestHeaders.java
│   └── ResponseMapper.java
├── admin/
│   ├── SpecAdminController.java
│   ├── PublishAdminController.java
│   └── PointerAdminController.java
├── validation/
│   ├── ClientHeadersValidator.java
│   └── ApiVersionValidator.java
├── advice/
│   └── ApiExceptionHandler.java
├── interceptor/
│   ├── CorrelationIdInterceptor.java
│   └── RateLimitInterceptor.java
├── context/
│   └── ComposeTraceContext.java
└── security/
    └── AdminAuthorization.java
```

O controller só deve:

1. Ler headers.
2. Validar formato HTTP e encaminhar para a aplicação.
3. Chamar o caso de uso.
4. Mapear resultado para response DTO.
5. Aplicar `ETag` e decidir 200/304.

Ele não acessa banco/cache, não executa hidratação e não implementa seleção de spec.

### 4.6 `sdui-bootstrap`

```text
br.com.empresa.sdui.bootstrap
├── SduiApplication.java
├── configuration/
│   ├── ApplicationWiringConfiguration.java
│   ├── AdapterConfiguration.java
│   ├── HydratorConfiguration.java
│   ├── WebConfiguration.java
│   ├── SecurityConfiguration.java
│   └── ObservabilityConfiguration.java
└── properties/
    ├── ComposeProperties.java
    ├── CacheProperties.java
    ├── FragmentProperties.java
    ├── HydrationProperties.java
    └── RateLimitProperties.java
```

É o único módulo que contém `@SpringBootApplication` e faz o wiring final.

---

## 5. Fragmentos estáticos e telas dinâmicas

> **Status: proposta, fora do MVP (ADR-004).** "Fragment" não existe no vocabulário do `plano-servico-sdui.md` §2 e
> nenhuma fonte do projeto o define. Esta seção fica como registro da ideia; `Fragment.java`, `FragmentStore`,
> `FragmentResolver`, `FragmentController`, a chave `sdui:fragment:static:{...}` e o endpoint
> `GET /v1/fragments/{fragmentId}` **não entram no primeiro PR**. Reabrir só quando existir uma segunda surface reusando
> o
> mesmo bloco.

A ideia de fragmentos estáticos e dinâmicos faz sentido, mas a separação precisa ser feita por **semântica e política de
cache**, não por classes que bypassam o pipeline.

### 5.1 Fragmento estático

Exemplos:

- Header padrão.
- Footer/chrome de uma surface.
- Skeleton estável.
- Bloco de navegação comum.

Características:

- Pode ser publicado com revisão.
- Pode ser materializado antecipadamente.
- Possui TTL de cache maior ou invalidação dirigida por revision.
- Não possui dados específicos de usuário.
- Ainda passa por validação de schema/capabilities antes de ser anexado à Screen.

### 5.2 Fragmento dinâmico

Exemplos:

- Cards cuja `data` depende de uma projeção externa.
- Ofertas vindas de API de squad.
- Conteúdo contextual que precisa de hidratação.

Características:

- Hidratação por `SectionHydrator`.
- Timeout individual e orçamento total do compose.
- Falha pode resultar em omissão de section.
- Dados precisam ser classificados para LGPD antes de entrar em cache.
- Não executar chamadas independentes ilimitadas para cada widget.

### 5.3 Resolução de fragmentos

O core pode definir uma política de resolução:

```text
ScreenSpec
  -> declara slots obrigatórios/opcionais
  -> declara fragment IDs e sections
  -> resolve fragmento estático publicado
  -> aplica overlay/plataforma
  -> filtra capabilities
  -> hidrata somente sections dinâmicas permitidas
  -> omite incompatíveis/falhas toleráveis
```

A herança não deve virar herança arbitrária de JSON. Prefira composição explícita:

```json
{
  "surface": "home",
  "fragments": [
    {
      "id": "home.header.default",
      "required": true
    },
    {
      "id": "home.footer.default",
      "required": false
    }
  ],
  "placements": []
}
```

Se for necessário override, definir uma operação fechada e validada no publish. Não fazer merge recursivo genérico de
qualquer JSON no request quente.

---

## 6. Estratégia de testes

A estratégia proposta pelo usuário faz sentido e deve ser incorporada, com uma correção: **não usar Testcontainers em
testes unitários**. Testcontainers pertence aos testes de adapter e integração.

### 6.1 Matriz de testes

| Camada                    | Ferramentas                               | Escopo                                          |                Infra real? |
|---------------------------|-------------------------------------------|-------------------------------------------------|---------------------------:|
| Unitário core/application | JUnit 5 + AssertJ + Mockito               | Regras, casos de uso e falhas                   |                        Não |
| Adapter Mongo/Redis       | JUnit 5 + Testcontainers                  | Mapeamento, TTL, índices, comandos e integração |                        Sim |
| Client HTTP               | JUnit 5 + WireMock                        | Status, timeout, payload, retry e erro remoto   | Simulado por servidor HTTP |
| API MVC                   | `@WebMvcTest` + `MockMvc`/`MockMvcTester` | Controller, headers, status, JSON e advice      |                        Não |
| Arquitetura               | ArchUnit                                  | Dependências e package rules                    |                        Não |
| Integração/E2E leve       | `@SpringBootTest` + Testcontainers        | Fluxo HTTP com Mongo + Redis                    |                        Sim |
| Carga/performance         | Ferramenta de carga da plataforma         | P99, payload, cache, singleflight               |          Ambiente dedicado |

`@WebMvcTest` limita o contexto aos componentes MVC e auto-configura MockMvc; no Boot 4, o módulo de testes MVC é
`spring-boot-starter-webmvc-test`. `MockMvcTester`, baseado em AssertJ, também pode ser usado quando estiver disponível
no classpath. [web:56][web:62]

### 6.2 Unitários: `sdui-core` e `sdui-orchestrator`

Dependências:

```xml

<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
<groupId>org.assertj</groupId>
<artifactId>assertj-core</artifactId>
<scope>test</scope>
</dependency>
<dependency>
<groupId>org.mockito</groupId>
<artifactId>mockito-junit-jupiter</artifactId>
<scope>test</scope>
</dependency>
```

JUnit 5 é o framework de execução; AssertJ melhora as asserções; Mockito é reservado para dependências externas do caso
de uso. Não mockar records, value objects ou políticas puras.

#### Testes do `sdui-core`

- `ClientContext` válido e inválido.
- Comparação semver inclusiva de `min`/`max`.
- `schemaVersion` compatível/incompatível.
- União da matriz de capabilities com delta do header.
- Omissão de `type@version` desconhecido.
- Resolução de fragmentos obrigatórios/opcionais.
- Proibição de fragmento incompatível com slot.
- Ações permitidas e payloads fechados.
- Rejeição de campos de aparência no catálogo.

#### Testes do `sdui-orchestrator`

- Cache hit não acessa `SpecStore` nem hydrator.
- Cache miss consulta pointer e spec na ordem esperada.
- Apenas um líder de singleflight executa a composição.
- Falha de hydrator omite section quando permitido.
- Falha de hydrator em section obrigatória aciona fallback.
- Falta de spec compatível usa `lastgood`.
- Fallback contém `fallback=true` e motivo correto.
- Árvore válida grava cache e lastgood.
- Capabilities incompatíveis não causam 4xx.
- Nenhuma cadeia assíncrona fica sem tratamento terminal.

Exemplo de teste de caso de uso:

```java

@ExtendWith(MockitoExtension.class)
class ComposeScreenServiceTest {

    @Mock
    SpecStore specStore;
    @Mock
    FragmentStore fragmentStore;
    @Mock
    HydratedScreenCache cache;
    @Mock
    LastGoodScreenStore lastGood;
    @Mock
    ComposeSingleflight singleflight;

    @Test
    void deveUsarLastGoodQuandoNaoHouverSpecCompativel() {
        // Arrange: cache miss, nenhuma spec aplicável, lastgood disponível.
        // Act: executa caso de uso.
        // Assert: fallback=true e motivo NoCompatibleSpec.
    }
}
```

### 6.3 Adapter Mongo

Usar Testcontainers para validar o comportamento contra Mongo real:

- Mapeamento `Document <-> domain`.
- Índices de specs, pointers e audit log.
- Query de candidatas por plataforma/status.
- Semver ordinal + confirmação em Java.
- Imutabilidade de spec PUBLISHED.
- Transação de approve/rollback em replica set.
- Compare-and-set de pointer.

Não usar mock de `MongoTemplate` para validar query, índice ou comportamento transacional.

Dependências típicas no módulo de adapter:

```xml

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
<dependency>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-starter-test</artifactId>
<scope>test</scope>
</dependency>
<dependency>
<groupId>org.testcontainers</groupId>
<artifactId>mongodb</artifactId>
<scope>test</scope>
</dependency>
```

### 6.4 Adapter Redis

Usar Testcontainers Redis para validar:

- Codec do JSON armazenado.
- TTL de árvore, fragmento estático e lastgood.
- Chaves normalizadas.
- Hash de capabilities ordenadas.
- `SET NX` e lease do singleflight.
- Liberação segura do lease.
- Invalidação seletiva por surface/platform/channel.
- Comportamento quando Redis está indisponível.
- Rate limit atômico, se implementado neste adapter.

Não validar TTL ou script Lua com mock de `RedisTemplate`.

### 6.5 API: `@WebMvcTest` + MockMvc

Para controllers, preferir `@WebMvcTest`:

```java

@WebMvcTest(controllers = ScreenController.class)
class ScreenControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ComposeScreenUseCase composeScreenUseCase;

    @Test
    void deveExigirHeadersDeNegociacao() throws Exception {
        mockMvc.perform(get("/v1/surfaces/home"))
                .andExpect(status().isBadRequest());
    }
}
```

O uso de `@MockitoBean` deve seguir a API de testes disponível no Spring Boot 4.1.1. Se a versão efetivamente adotada do
starter expuser outra annotation de substituição, seguir a API daquela versão; não misturar APIs de Boot 3 e Boot 4.

Testar no slice MVC:

- Headers obrigatórios e opcionais.
- `API-Version` separado de `UI-Schema-Version`.
- `Client-Platform`, `Client-Version`, `Client-Build`, `OS-Version` e capabilities.
- Resposta 200.
- Resposta 304 quando `If-None-Match` casar.
- JSON do envelope.
- Erros de validação.
- `ApiExceptionHandler`.
- Rotas administrativas e autorização, com configuração de segurança apropriada.

`REST Assured` pode ser adicionado depois para testes de contrato HTTP contra servidor real. Não é necessário para
começar se `MockMvc` cobre o comportamento do controller.

### 6.6 WireMock: somente se houver client HTTP

WireMock deve entrar apenas quando existir um adapter HTTP real de hidratação. Ele deve testar:

- Resposta 2xx e mapeamento.
- 4xx/5xx.
- Timeout.
- Conexão recusada.
- Payload inválido.
- Retry somente em condições permitidas.
- Circuit breaker, se existir.
- Propagação de headers técnicos permitidos.
- Limite de resposta e comportamento de fallback.

Se o serviço não tiver client HTTP no MVP, não adicionar WireMock ao projeto. Mongo e Redis não precisam dele.

### 6.7 Integração/E2E leve

Usar `@SpringBootTest` com Testcontainers Mongo + Redis para validar o caminho completo:

```java

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ComposeHomeIntegrationTest {

    @Test
    void deveComporHomeComMongoEประRedisReais() {
        // Request HTTP real contra porta aleatória.
        // Mongo e Redis reais em containers.
        // Assert de status, envelope, ETag, cache e fallback.
    }
}
```

O trecho acima é apenas esqueleto conceitual; o identificador do método deve ser escrito em Java válido
(`deveComporHomeComMongoERedisReais`).

Cobertura mínima:

- Primeiro request: miss + compose + gravação de cache.
- Segundo request: hit de árvore.
- `If-None-Match`: 304.
- Redis indisponível: lastgood, conforme política.
- Spec incompatível: fallback 200, não 404.
- Section desconhecida: omitted.
- Publish: revisão, pointer e auditoria.
- Rollback: pointer anterior sem alteração da revisão publicada.

`@SpringBootTest` por padrão não precisa abrir uma porta real; para E2E leve com HTTP real, usar `RANDOM_PORT`. Para
testes sem servidor real, `@SpringBootTest` + `@AutoConfigureMockMvc` é suficiente. [web:56]

---

## 7. ArchUnit e regras arquiteturais

Adicionar ArchUnit é uma decisão correta. As regras devem proteger a direção das dependências e os limites do projeto,
não impor nomenclatura sem valor.

ArchUnit possui integração para testes JUnit e é obtido pelo Maven Central. [web:63][web:64]

### 7.1 Dependência

No módulo `sdui-integration-test` ou em um módulo dedicado de arquitetura:

```xml

<dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit</artifactId>
    <version>1.5.0</version>
    <scope>test</scope>
</dependency>
<dependency>
<groupId>com.tngtech.archunit</groupId>
<artifactId>archunit-junit5</artifactId>
<version>1.5.0</version>
<scope>test</scope>
</dependency>
```

A versão deve ser validada no repositório corporativo/Maven Central antes de fixar; não declarar uma versão sem
verificar disponibilidade no ambiente de build.

### 7.2 Regras mínimas

```java

@AnalyzeClasses(packages = "br.com.empresa.sdui")
class ArchitectureTest {

    @ArchTest
    static final ArchRule core_must_not_depend_on_frameworks =
            noClasses()
                    .that().resideInAnyPackage("..sdui.core..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "org.mongodb..",
                            "com.mongodb..",
                            "io.lettuce..",
                            "jakarta.servlet.."
                    );

    @ArchTest
    static final ArchRule controllers_must_not_access_adapters =
            noClasses()
                    .that().resideInAnyPackage("..sdui.api..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..sdui.adapters..mongo..", "..sdui.adapters..redis..");

    @ArchTest
    static final ArchRule domain_must_not_depend_on_contract =
            noClasses()
                    .that().resideInAnyPackage("..sdui.core..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..sdui.contract..");

    @ArchTest
    static final ArchRule only_bootstrap_starts_spring_boot =
            classes()
                    .that().areAnnotatedWith(SpringBootApplication.class)
                    .should().resideInAnyPackage("..sdui.bootstrap..");

    @ArchTest
    static final ArchRule controllers_should_reside_in_api =
            classes()
                    .that().areAnnotatedWith(RestController.class)
                    .should().resideInAnyPackage("..sdui.api..");
}
```

### 7.3 Regras adicionais recomendadas

- Classes `@Document` só em `..adapters.mongo.document..`.
- `MongoTemplate` e `RedisTemplate` só em `..adapters..`.
- `@Transactional` não deve aparecer em controllers, filters ou adapters, **com uma única exceção nominal**:
  `..adapters.mongo.tx.MongoTransactionalUnitOfWork`, que implementa a porta `TransactionalUnitOfWork` do
  publish/rollback (ver ADR-003 no §16). A exceção é por nome de classe, não por pacote.
- Nenhuma classe de `..sdui.core..` ou `..sdui.orchestrator..` pode depender de `org.springframework.transaction..`.
- Nenhuma classe fora de `..adapters.mongo.tx..` pode implementar `TransactionalUnitOfWork`.
- `@SpringBootApplication` somente em bootstrap.
- Classes de `core` não devem usar `ResponseEntity`, `HttpHeaders` ou tipos servlet.
- `SectionHydrator` deve residir em application/orchestrator ou em um SPI definido para isso.
- DTOs públicos devem residir em `contract`; documentos de banco em adapters.
- Classes terminadas em `Controller` só em `api`.
- Implementações de gateways devem residir em adapters.
- Nenhuma dependência entre módulos de adapter.

### 7.4 ArchUnit e módulos Maven

ArchUnit verifica dependências entre classes no bytecode carregado, mas não substitui a separação Maven. Usar os dois
níveis:

- Maven impede que uma dependência seja necessária/compilada indevidamente.
- ArchUnit impede violações de package e acoplamentos indiretos dentro do grafo permitido.

Não tentar resolver todos os limites apenas com packages se a dependência Maven puder ser removida.

---

## 8. POM raiz

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/>
    </parent>

    <groupId>br.com.empresa.sdui</groupId>
    <artifactId>ms-sdui-composer-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <name>ms-sdui-composer-parent</name>

    <modules>
        <module>sdui-contract</module>
        <module>sdui-core</module>
        <module>sdui-orchestrator</module>
        <module>sdui-adapters</module>
        <module>sdui-api</module>
        <module>sdui-bootstrap</module>
        <module>sdui-integration-test</module>
    </modules>

    <properties>
        <java.version>25</java.version>
        <maven.compiler.release>25</maven.compiler.release>
        <archunit.version>1.5.0</archunit.version>
    </properties>

    <!--
        Resilience4j removido do MVP - ver ADR-006 no §16.
        Motivo curto: (1) nenhum módulo declarava artefato resilience4j, o BOM
        era peso morto; (2) o `resilience4j-bom:2.4.0` não gerencia o artefato
        `resilience4j-spring-boot4`, então a versão teria de ser explícita de
        qualquer forma; (3) sem client HTTP de hidratação no MVP não há
        consumidor de circuit breaker. Timeout e bounded fan-out saem de
        `Semaphore` + `Future.get(timeout)`; timeout de Mongo/Redis sai da
        configuração do driver; rate limit do compose é distribuído no Redis
        e não seria atendido pelo RateLimiter in-process do Resilience4j.

        Gatilho de reintrodução: primeiro `SectionHydrator` com client HTTP
        real. Nesse momento, adicionar com versão explícita (não via BOM)
        e somar `spring-boot-starter-aspectj` - no Boot 4 o antigo
        `spring-boot-starter-aop` foi renomeado.
    -->

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-enforcer-plugin</artifactId>
                    <executions>
                        <execution>
                            <id>enforce-java-and-maven</id>
                            <goals>
                                <goal>enforce</goal>
                            </goals>
                            <configuration>
                                <rules>
                                    <requireJavaVersion>
                                        <version>[25,26)</version>
                                    </requireJavaVersion>
                                    <requireMavenVersion>
                                        <version>[3.9,)</version>
                                    </requireMavenVersion>
                                </rules>
                            </configuration>
                        </execution>
                    </executions>
                </plugin>

                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                        <release>${maven.compiler.release}</release>
                    </configuration>
                </plugin>

                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <configuration>
                        <useModulePath>false</useModulePath>
                    </configuration>
                </plugin>

                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-failsafe-plugin</artifactId>
                    <configuration>
                        <includes>
                            <include>**/*IT.java</include>
                            <include>**/*IntegrationTest.java</include>
                        </includes>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

O Spring Boot 4.1.1 gerencia Spring Framework 7+ e as versões dos starters. Não declarar manualmente artefatos
individuais do Spring Framework.

---

## 9. POMs dos módulos

### 9.1 `sdui-core/pom.xml`

```xml

<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>br.com.empresa.sdui</groupId>
        <artifactId>ms-sdui-composer-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>sdui-core</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 9.2 `sdui-contract/pom.xml`

```xml

<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>br.com.empresa.sdui</groupId>
        <artifactId>ms-sdui-composer-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>sdui-contract</artifactId>

    <dependencies>
        <!--
            Jackson 3 (ADR-005). No Boot 4 o Jackson 3 é a biblioteca padrão e
            o groupId/pacote mudou de `com.fasterxml.jackson` para
            `tools.jackson`. Exceção: `jackson-annotations` mantém o groupId
            antigo. Declarar `com.fasterxml.jackson.core:jackson-databind`
            aqui colocaria dois databinds no classpath e faria os testes de
            serialização validarem um mapper que não é o do runtime.
        -->
        <dependency>
            <groupId>tools.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 9.3 `sdui-orchestrator/pom.xml`

```xml

<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>br.com.empresa.sdui</groupId>
        <artifactId>ms-sdui-composer-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>sdui-orchestrator</artifactId>

    <dependencies>
        <dependency>
            <groupId>br.com.empresa.sdui</groupId>
            <artifactId>sdui-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 9.4 `sdui-adapters/pom.xml`

```xml

<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>br.com.empresa.sdui</groupId>
        <artifactId>ms-sdui-composer-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>sdui-adapters</artifactId>

    <dependencies>
        <dependency>
            <groupId>br.com.empresa.sdui</groupId>
            <artifactId>sdui-orchestrator</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>br.com.empresa.sdui</groupId>
            <artifactId>sdui-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-mongodb</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-restclient</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mongodb</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.redis</groupId>
            <artifactId>testcontainers-redis</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

**Ajuste:** `spring-boot-starter-restclient` e WireMock só devem permanecer se houver hidratação HTTP. Se não houver
client HTTP no MVP, remover ambos.

### 9.5 `sdui-api/pom.xml`

```xml

<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>br.com.empresa.sdui</groupId>
        <artifactId>ms-sdui-composer-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>sdui-api</artifactId>

    <dependencies>
        <dependency>
            <groupId>br.com.empresa.sdui</groupId>
            <artifactId>sdui-orchestrator</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>br.com.empresa.sdui</groupId>
            <artifactId>sdui-contract</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 9.6 `sdui-bootstrap/pom.xml`

```xml

<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>br.com.empresa.sdui</groupId>
        <artifactId>ms-sdui-composer-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>sdui-bootstrap</artifactId>

    <dependencies>
        <dependency>
            <groupId>br.com.empresa.sdui</groupId>
            <artifactId>sdui-api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>br.com.empresa.sdui</groupId>
            <artifactId>sdui-adapters</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <mainClass>br.com.empresa.sdui.bootstrap.SduiApplication</mainClass>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### 9.7 `sdui-integration-test/pom.xml`

```xml

<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>br.com.empresa.sdui</groupId>
        <artifactId>ms-sdui-composer-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>sdui-integration-test</artifactId>

    <dependencies>
        <dependency>
            <groupId>br.com.empresa.sdui</groupId>
            <artifactId>sdui-bootstrap</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mongodb</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.redis</groupId>
            <artifactId>testcontainers-redis</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.tngtech.archunit</groupId>
            <artifactId>archunit-junit5</artifactId>
            <version>${archunit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 10. YAML e configuração

### `sdui-bootstrap/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: ms-sdui-composer

  threads:
    virtual:
      enabled: true

  mvc:
    apiversion:
      use:
        header: API-Version
      default: "1"
      required: true
      supported: "1"

  data:
    mongodb:
      uri: ${MONGODB_URI}

    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT:6379}
      timeout: ${REDIS_TIMEOUT:50ms}

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics

  endpoint:
    health:
      probes:
        enabled: true

  metrics:
    tags:
      application: ${spring.application.name}

sdui:
  compose:
    deadline: ${SDUI_COMPOSE_DEADLINE:400ms}
    tree-ttl: ${SDUI_TREE_TTL:60s}
    last-good-ttl: ${SDUI_LAST_GOOD_TTL:15m}
    singleflight-lease: ${SDUI_SINGLEFLIGHT_LEASE:3s}
    singleflight-wait-budget: ${SDUI_SINGLEFLIGHT_WAIT_BUDGET:250ms}
    max-sections: ${SDUI_MAX_SECTIONS:20}
    max-payload-bytes: ${SDUI_MAX_PAYLOAD_BYTES:131072}

  fragments:
    static-ttl: ${SDUI_STATIC_FRAGMENT_TTL:15m}
    dynamic-ttl: ${SDUI_DYNAMIC_FRAGMENT_TTL:30s}

  hydration:
    enabled: ${SDUI_HYDRATION_ENABLED:false}
    max-concurrency: ${SDUI_HYDRATION_MAX_CONCURRENCY:8}
    section-timeout: ${SDUI_SECTION_TIMEOUT:150ms}

  rate-limit:
    enabled: ${SDUI_RATE_LIMIT_ENABLED:true}
    capacity: ${SDUI_RATE_LIMIT_CAPACITY:60}
    refill-tokens: ${SDUI_RATE_LIMIT_REFILL_TOKENS:60}
    refill-period: ${SDUI_RATE_LIMIT_REFILL_PERIOD:1m}
```

As properties ficam somente em `.yml`. O binding deve usar `@ConfigurationProperties` tipado, não `@Value` espalhado.

```java

@ConfigurationProperties(prefix = "sdui.hydration")
public record HydrationProperties(
        boolean enabled,
        int maxConcurrency,
        Duration sectionTimeout
) {
}
```

---

## 11. Regras para hidratação paralela

A proposta menciona hidratação paralela. Ela faz sentido somente com escopo e orçamento claros.

### Regras obrigatórias

- O número máximo de hydrators concorrentes deve ser limitado.
- O limite deve proteger a dependência, não apenas a CPU do processo.
- Cada section tem timeout próprio, menor que o deadline total.
- O compose espera apenas o escopo que iniciou; não deixar tarefas órfãs.
- Falhas devem ser convertidas em resultado terminal: section omitida, fallback ou erro de compose conforme contrato.
- Não usar `CompletableFuture` sem `handle`/`exceptionally`.
- Não usar `Executors.newFixedThreadPool` para I/O de hydrators.
- Virtual Threads não eliminam a necessidade de limitar concorrência na API externa.
- `synchronized` não deve envolver chamadas de rede.

Em Java 25, usar `Executors.newVirtualThreadPerTaskExecutor()` para tarefas I/O-bound, com `Semaphore` limitando o
fan-out. Não usar Structured Concurrency enquanto exigir preview. O executor deve estar em try-with-resources ou em um
componente com ciclo de vida explícito.

O limite definido em `sdui.hydration.max-concurrency` deve ser validado contra o SLA e rate limit das APIs das squads.

---

## 12. Controller e contrato HTTP

### Endpoint de tela

```text
GET /v1/surfaces/{surface}
```

### Endpoint de fragmento (fora do MVP — ADR-004)

```text
GET /v1/fragments/{fragmentId}
```

A existência do endpoint de fragmento separado precisa ser justificada pelo consumidor. Se o app sempre recebe o
fragmento dentro da Screen e o fragmento não é reutilizado independentemente, manter apenas o endpoint de Screen reduz
superfície operacional.

Uma opção pragmática para MVP:

- Expor `GET /v1/surfaces/home`.
- Persistir fragments internamente.
- Permitir rotas públicas de fragmento somente quando houver consumidor real ou necessidade de cache/entrega
  independente.

### Headers

O contrato usa headers sem prefixo `X-`:

```text
API-Version: 1
UI-Schema-Version: 3
Client-Platform: ios
Client-Version: 8.14.2
Client-Build: 81420
Accept-Language: pt-BR
OS-Version: 18.1
Component-Capabilities: top_bar@1,account_card@1
```

Não substituir esses nomes por `X-Platform`, `X-App-Version` ou `X-Capabilities`, pois isso contradiz a decisão do
projeto.

### Respostas

- `200`: árvore completa ou fallback `lastgood`.
- `304`: `If-None-Match` coincide com ETag.
- `400`: header ausente, inválido ou incompatível com o contrato HTTP.
- `401/403`: autenticação/autorização conforme segurança corporativa.
- `429`: rate limit.
- `500`: somente erro não tratado fora da escada de fallback; timeout de section não deve produzir 500 por padrão.
- `503` + `Retry-After`: **último degrau da escada de fallback**. Ocorre quando não há árvore compatível e também não
  existe `lastgood` para `surface + platform + channel` (boot frio, Redis vazio, primeira subida). Corpo estável e
  previsível, sem stacktrace. Nunca usar `500` nesse caso: o app precisa do sinal de retry.

A Home não retorna 404 quando não há spec compatível; usa última árvore boa ou uma resposta de fallback definida pelo
contrato. [file:8]

Escada completa, do melhor para o pior caso:

```text
200 árvore composta (cache hit ou miss composto com sucesso)
200 árvore composta com sections omitidas  -> envelope.omitted preenchido
200 lastgood                               -> fallback=true + fallbackReason
503 + Retry-After                          -> sem árvore e sem lastgood
```

O app mobile cobre o degrau seguinte com o skeleton local mínimo do binário. Isso é contrato com o time mobile, não
código deste MS.

---

## 13. Métricas e observabilidade

Métricas mínimas:

```text
sdui.compose.hit
sdui.compose.miss
sdui.compose.fallback
sdui.compose.singleflight.wait
sdui.compose.duration
sdui.section.duration
sdui.section.omitted
sdui.fragment.cache.hit
sdui.fragment.cache.miss
sdui.payload.bytes
sdui.serialize.duration
sdui.hydration.timeout
sdui.hydration.error
```

Tags permitidas e de baixa cardinalidade:

```text
surface
platform
schemaVersion
appMajorMinor
channel
outcome
cache
fragmentKind=static|dynamic
componentType
fallbackReason
```

Evitar `userId`, request ID, capability bruta e `specRevisionId` sem controle de cardinalidade.

Traces devem mostrar:

```text
compose
├── cache.tree
├── resolve.pointer
├── select.spec
├── resolve.fragments
├── hydrate.sections
│   ├── section.top_bar
│   └── section.account_card
├── assemble.envelope
└── serialize.response
```

---

## 14. Ajustes finais à proposta do usuário

### O que faz sentido aprovar

- Modularização em `contract`, `core`, `orchestrator`, `adapters`, `api` e `bootstrap` — **com a ressalva do ADR-001**:
  com um único engenheiro, sete módulos Maven provavelmente não pagam o custo; a recomendação é colapsar para três.
- Fragmentos estáticos e dinâmicos com políticas de cache distintas — **adiado pelo ADR-004**, mantido aqui como
  proposta.
- SPI `SectionHydrator` para hidratação por squad.
- JUnit 5 + AssertJ + Mockito para unitários.
- Testcontainers para Mongo/Redis.
- `@WebMvcTest` + MockMvc para controller.
- ArchUnit para regras de dependência.
- `@SpringBootTest` com Testcontainers para integração/E2E leve.
- WireMock apenas quando existir client HTTP de hidratação.

### O que ajustar

1. **Não chamar o `sdui-core` de simples validação de contexto.** Ele deve conter as invariantes SDUI completas,
   inclusive compatibilidade, omissão, targeting e resolução limitada de fragments.
2. **O `sdui-orchestrator` precisa ser o dono dos casos de uso**, não apenas um pipeline técnico.
3. **`sdui-adapters` não deve depender diretamente de DTOs HTTP do contract**. Preferir domínio/application e mappers
   explícitos.
4. **`@JsonTypeInfo` não deve ser obrigatório.** Usar somente se polimorfismo de desserialização for realmente
   necessário.
5. **Não expor fragments publicamente automaticamente.** Começar com Screen e adicionar endpoint independente quando
   houver consumidor.
6. **Hydrators devem ser bounded.** SPI não pode permitir fan-out ilimitado por squad.
7. **Não adicionar RestClient/WireMock sem client HTTP real.**
8. **ArchUnit deve proteger regras reais**, e não apenas verificar nomes de packages.
9. **Testcontainers não pertence a unitários.** Deve ser usado em adapters e integração.
10. **Corrigir headers da proposta:** usar os nomes sem `X-` definidos no projeto.
11. **Fixar o starter MVC de teste do Boot 4.1.1:** `spring-boot-starter-webmvc-test`. [web:62]
12. **Separar API version de UI schema version:** `API-Version` é obrigatório e independente de
    `UI-Schema-Version`. [file:8]
13. **Jackson 3, não Jackson 2.** `tools.jackson.core:jackson-databind` +
    `com.fasterxml.jackson.core:jackson-annotations`. Ver ADR-005.
14. **Resilience4j sai do POM raiz.** Sem consumidor no MVP e o BOM 2.4.0 não gerencia o artefato de Boot 4. Ver
    ADR-006.
15. **`503` + `Retry-After` entra na tabela de respostas** como último degrau da escada de fallback. Ver ADR-007.
16. **`Fragment` não entra no MVP.** `Screen` é formalizado no vocabulário; fragments ficam como proposta até haver
    segunda surface. Ver ADR-004.
17. **Transação de publish via porta `TransactionalUnitOfWork`**, com exceção ArchUnit nominal. Ver ADR-003.
18. **Omissão pura confirmada como política de section incompatível**, com slot portante marcado no skeleton persistido.
    `downgradeTo` por type fica fora do MVP com gatilho de reentrada nomeado. Ver ADR-009.
19. **`SectionComponentType` não entra no contrato.** Variação de renderização escolhida pelo servidor é aparência;
    alternativas por caso no ADR. Ver ADR-010.
20. **Conjunto de actions permanece fechado em quatro**, sem extensão por feature. Ver ADR-011.

---

## 15. Ordem de implementação

1. Criar parent Maven e Maven Wrapper.
2. Criar `sdui-core`, `sdui-orchestrator`, `sdui-contract`, `sdui-adapters`, `sdui-api`, `sdui-bootstrap` e
   `sdui-integration-test`.
3. Implementar primeiro o core com testes unitários e sem qualquer Spring.
4. Implementar casos de uso e ports do orchestrator.
5. Implementar o contrato v1 e testes de serialização.
6. Implementar resolução de Screen sem hidratação remota (sem fragments — ADR-004).
7. Implementar cache Redis para Screen/lastgood/singleflight.
8. Implementar Mongo para spec, pointer, skeleton e auditoria.
9. Implementar API MVC e `@WebMvcTest`.
10. Implementar ArchUnit e fazer o build falhar quando houver violação.
11. Implementar hydrators somente para squads/clientes efetivamente definidos.
12. Adicionar client HTTP e WireMock apenas nesse momento.
13. Criar integração completa com `@SpringBootTest` + Mongo/Redis Testcontainers.
14. Fechar segurança, rate limit, maker-checker e idempotência.
15. Executar carga e validar P99, contenção de singleflight, tamanho de payload e comportamento de fallback.

---

## 16. Decisões registradas (ADR)

Formato curto: contexto, decisão, consequência. Um ADR só muda por outro ADR que o supersede. O projeto tem **um único
engenheiro de backend**; isso não dispensa o registro, ao contrário: o ADR é o que impede a decisão de ser reaberta por
esquecimento daqui a três meses.

Legenda de status: `ACEITO` (fechado, vale para o código) e `PENDENTE` (recomendação registrada, aguardando decisão).

---

### ADR-001 — Modularização Maven vs. §13.2 do plano

**Status:** `PENDENTE` — é a única decisão deste documento que continua aberta.

**Contexto.** O `plano-servico-sdui.md` §13.2 se intitula "Pacotes (simples, sem hexágono)" e propõe um layout achatado
de pacotes (`api`, `compose`, `catalog`, `skeleton`, `spec`, `diff`, `cache`, `targeting`, `audit`, `support`) num
artefato único. A premissa 12 do §0 põe "Hexágono neste MS" em fora de escopo.

Esta pré-arquitetura entrega sete módulos Maven com `port/in/`, `port/out/` e módulo `adapters`. O §1 rejeita o hexágono
no texto e o §4 o implementa na estrutura. É uma reversão de decisão do plano, não uma consistência.

**Análise.** O benefício real de sete módulos é fronteira compilada e ownership separado por squad. Com um engenheiro, o
ownership não existe e a fronteira pode ser obtida por ArchUnit sobre pacotes, que já está previsto no §7. O custo é
sete POMs, build mais lento, e toda mudança de assinatura atravessando módulos.

**Recomendação.** Colapsar para **três módulos**:

```text
sdui-contract   -> DTOs REST/JSON públicos (fronteira de processo, versionada à parte)
sdui-app        -> core + orchestrator + adapters + api, separados por pacote e protegidos por ArchUnit
sdui-bootstrap  -> main, wiring, YAML, observabilidade (único executável)
```

`sdui-contract` continua separado porque é a única fronteira que outro processo pode consumir. `sdui-bootstrap` continua
separado para manter a regra "só um módulo tem `@SpringBootApplication`". Todo o resto vira pacote com regra ArchUnit,
que é exatamente o que o §13.2 do plano pede.

Se a resposta for manter os sete módulos, o §13.2 do plano precisa ser reescrito no mesmo commit.

**Consequência se aceito.** Reescrever os §2, §3, §9 e §15 deste documento. As regras ArchUnit do §7.2 passam de
`..sdui.adapters..` para os pacotes equivalentes dentro de `sdui-app`, sem perda de cobertura.

---

### ADR-002 — Contexto de trace: `ComposeTraceContext` com `ThreadLocal`

**Status:** `ACEITO`

**Contexto.** O plano §11 fecha `ThreadLocal` para `surface`, `platform` e `specRevisionId`, com `remove()` garantido no
`finally` da borda da request. Em Java 25, `ScopedValue` já é permanente (JEP 506), mas não é requisito deste MS:
contexto funcional continua explícito em `ComposeRequest`, e o contexto de observabilidade permanece encapsulado na
borda HTTP. Esta pré-arquitetura não mencionava `ThreadLocal` em lugar nenhum.

**Decisão.** Criar `br.com.empresa.sdui.api.context.ComposeTraceContext`:

- Escopo **exclusivo de observabilidade**: MDC de log, tag de métrica e atributo de span. Nunca dado funcional.
- Dado funcional continua viajando por parâmetro explícito em `ComposeRequest`. Se uma classe precisa de `platform` para
  decidir algo, ela recebe `platform`; não lê do `ThreadLocal`.
- Aberto e fechado no `CorrelationIdInterceptor`, com `remove()` em `finally`. A virtual thread é descartada por
  request, mas o `finally` protege contra reuso do carrier e contra troca futura para pool.
- Vive em `sdui-api` porque é infraestrutura HTTP. `sdui-core` e `sdui-orchestrator` continuam sem conhecê-lo.

**Consequência.** Uma regra ArchUnit adicional: nenhuma classe de `..core..` ou `..orchestrator..` pode referenciar
`ComposeTraceContext`. Se alguém precisar dele lá, o dado deveria estar na assinatura.

**Nota sobre a referência.** A validação inicial citava "§11/§13.1" do plano. O §13.1 é apenas a lista de runtime e só
referencia o §11 para o veto ao `ScopedValue`. A decisão de `ThreadLocal` vive **só no §11**.

---

### ADR-003 — Transação de publish: porta `TransactionalUnitOfWork`

**Status:** `ACEITO`

**Contexto.** O plano §7.5 é explícito: `@Transactional` não vai em controller, o serviço de publish é o único que abre
transação, e compose não abre transação. A tabela de módulos do §2 diz que `sdui-orchestrator` não tem Spring em
produção. `@Transactional` é `spring-tx`. As duas regras colidem: o caso de uso de publish mora no orchestrator.

**Opções.** (a) `spring-tx` no orchestrator, quebrando "sem Spring em produção". (b) Porta pura no orchestrator,
implementação anotada em adapters, exigindo exceção na regra ArchUnit que proíbe `@Transactional` em adapters.

**Decisão.** Opção (b).

```java
// sdui-orchestrator :: port.out
public interface TransactionalUnitOfWork {
    <T> T execute(Supplier<T> work);
}
```

Implementação única em `..adapters.mongo.tx.MongoTransactionalUnitOfWork`, anotada com `@Transactional`.
`PublishSpecUseCase` e `RollbackPointerUseCase` recebem a porta e envolvem a coordenação de `pointers` +
`publish_requests` + `audit_log` + `idempotency`.

**Justificativa da escolha.** Manter o orchestrator livre de Spring vale mais do que a pureza da regra ArchUnit. Uma
exceção **com nome próprio de classe** é auditável e aparece no grep; `spring-tx` no orchestrator abre um precedente que
ninguém fecha depois — a próxima anotação entra sem discussão.

**Consequência.** A regra do §7.3 foi reescrita: a exceção é por nome de classe, não por pacote, e duas regras novas
fecham o contorno (nada de `org.springframework.transaction` em core/orchestrator; nada implementa a porta fora de
`..adapters.mongo.tx..`).

**Pendência operacional.** O plano §7.5 avisa que em DocumentDB é preciso validar suporte a transação multi-documento;
sem ele, o caminho é compare-and-set em `pointers.version` + outbox para o audit. A porta absorve as duas implementações
sem mudar o caso de uso, que é o principal ganho desse desenho.

---

### ADR-004 — Vocabulário: `Screen` formalizado, `Fragment` adiado

**Status:** `ACEITO`

**Contexto.** O §2 do plano fecha treze termos: Surface, Skeleton, Slot, Component type, Component instance, Spec,
Overlay, Árvore hidratada, Schema version, Capability, Targeting, Pointer, Channel. Nem "Fragment" nem "Screen" estão
lá. Esta pré-arquitetura usa `Screen`, `HydratedScreen`, `ScreenController`, `ScreenSpec`, `Fragment`, `FragmentStore`,
`FragmentResolver` e um endpoint `GET /v1/fragments/{fragmentId}`, enquanto a chave Redis do mesmo objeto é `sdui:tree:`
e o endpoint público é `/v1/surfaces/{surface}`. Três nomes para a mesma coisa.

**Decisão sobre `Screen`.** Formalizar. `Screen` é conceito canônico de SDUI (a tríade Sections / Screens / Actions) e o
código já está inteiro nele. Adicionar ao §2 do plano: **Screen = árvore hidratada de uma surface, o objeto que o
compose devolve**. `Árvore hidratada` vira sinônimo textual; em código, cache e métrica usa-se `Screen`/`tree` de forma
consistente.

**Decisão sobre `Fragment`.** **Não entra no MVP.** Nenhuma fonte do projeto define "Fragment"; o próprio §14 item 5 já
dizia para não expor fragments publicamente. Ficam fora do primeiro PR: `Fragment.java`, `FragmentStore`,
`FragmentResolver`, `FragmentController` e `GET /v1/fragments/{fragmentId}`. O §5 deste documento passa a ser leitura de
proposta, não especificação.

**Hipótese a validar antes de reabrir.** "Fragmento estático" parece ser sinônimo operacional de *conjunto de placements
publicado e reutilizável, sem hidratação dinâmica*. Se for isso, é uma feature de composição de spec e cabe dentro do
`SpecResolver`, sem entidade nova, sem store, sem endpoint. Só vale formalizar quando existir uma surface real além da
Home reusando o mesmo bloco.

**Consequência.** A chave Redis `sdui:fragment:static:{...}` do §4.4 fica sem consumidor no MVP. Remover do primeiro PR.

---

### ADR-005 — Jackson 3 no `sdui-contract`

**Status:** `ACEITO`

**Contexto.** O `sdui-contract/pom.xml` declarava `com.fasterxml.jackson.core:jackson-databind` sem versão, sob o parent
`spring-boot-starter-parent:4.1.1`. No Boot 4, Jackson 3 é a biblioteca preferida e padrão, e o suporte a Jackson 2 está
depreciado, existindo apenas para facilitar migração. O Jackson 3 mudou groupId e pacote de `com.fasterxml.jackson` para
`tools.jackson`, com exceção de `jackson-annotations`, que mantém o groupId antigo.

**Problema concreto.** O artefato resolveria (o gerenciamento de Jackson 2 continua no BOM), mas o `sdui-contract`
compilaria contra o `ObjectMapper` do Jackson 2 enquanto o `sdui-api` serializaria a resposta com o `JsonMapper` do
Jackson 3 autoconfigurado pelo Boot. Dois databinds no classpath e os testes de serialização do §6.2 validando um mapper
que não é o do runtime.

**Decisão.** `tools.jackson.core:jackson-databind` + `com.fasterxml.jackson.core:jackson-annotations`, ambos sem versão
(gerenciados pelo parent). O MVP nasce em Jackson 3. Não usar `spring.jackson2` nem `spring-boot-jackson2`: são
ferramenta de migração, não escolha de greenfield.

**Consequência.** O envelope usa `JsonMapper` imutável e ISO-8601 por padrão. Os testes de contrato do `sdui-contract`
devem usar o mesmo `JsonMapper` que o Boot autoconfigura, não um mapper construído à mão no teste — senão o teste passa
e a resposta HTTP sai diferente. Reforça o item 4 do §14: `@JsonTypeInfo` só se o polimorfismo de desserialização for
realmente necessário.

---

### ADR-006 — Resilience4j fora do MVP

**Status:** `ACEITO`

**Contexto.** O POM raiz importava `resilience4j-bom:2.4.0`. Dois problemas: **nenhum módulo declarava qualquer artefato
resilience4j**, então o BOM era peso morto; e o suporte a Spring Boot 4 entrou na 2.4.0 mas o artefato
`resilience4j-spring-boot4` ficou de fora do BOM, com o fix mergeado sem release subsequente que o propagasse — projetos
que usam o BOM enfrentam falha de resolução e precisam sobrescrever a versão explicitamente.

**Análise do que o MVP realmente precisa.**

| Necessidade                          | Solução no MVP                          | Precisa de Resilience4j?              |
|--------------------------------------|-----------------------------------------|---------------------------------------|
| Timeout por section                  | `Future.get(timeout)` em virtual thread | Não                                   |
| Bounded fan-out de hydrator          | `Semaphore` (§11 deste documento)       | Não                                   |
| Timeout de Mongo/Redis               | Configuração do driver                  | Não                                   |
| Rate limit do compose                | Token bucket distribuído no Redis       | Não (o RateLimiter dele é in-process) |
| Circuit breaker por dependência HTTP | —                                       | Sim, **quando existir client HTTP**   |

**Decisão.** Remover o BOM e o `resilience4j.version` do POM raiz. Nenhuma dependência de resiliência entra antes de
existir consumidor.

**Gatilho de reintrodução.** O primeiro `SectionHydrator` com client HTTP real. Nesse momento: declarar
`resilience4j-spring-boot4` **com versão explícita, não via BOM**; verificar antes se já saiu release que corrigiu o
BOM; e somar `spring-boot-starter-aspectj` — no Boot 4 o `spring-boot-starter-aop` foi renomeado para
`spring-boot-starter-aspectj`, e nenhum dos dois estava declarado.

**Alternativa a avaliar no gatilho.** O Boot 4 traz `@Retryable` e `@ConcurrencyLimit` nativos. Só vale puxar
Resilience4j se o ganho for circuit breaker com métricas por dependência; retry e limite de concorrência já estão
cobertos.

**Consequência.** O §13.1 do plano lista Resilience4j como stack de runtime. Este ADR não contradiz a intenção, adia a
introdução até haver consumidor — mas o §13.1 deve ganhar a nota "a partir do primeiro hydrator HTTP".

---

### ADR-007 — `503` + `Retry-After` como último degrau da escada

**Status:** `ACEITO`

**Contexto.** O plano §11 fecha: se Mongo/Redis da spec falhar, usa `lastgood`; se `lastgood` não existir (boot frio),
`503` com corpo estável e `retry-after`. A tabela de respostas do §12 desta pré-arquitetura listava 200, 304, 400,
401/403, 429 e 500, sem 503 e sem `Retry-After`.

**Risco que isso criava.** Com aquela tabela como fonte, um boot frio sem `lastgood` viraria `500` — exatamente o que o
próprio §12 diz que não deve acontecer — e o app mobile perderia o sinal de retry.

**Decisão.** Escada completa fixada no §12, do melhor para o pior caso: `200` composto, `200` com `envelope.omitted`,
`200` `lastgood` com `fallback=true` + `fallbackReason`, e `503` + `Retry-After`. O `500` fica reservado a erro não
tratado fora da escada. `404` nunca, para `home`.

**Consequência.** Teste obrigatório: Redis vazio e sem `lastgood` retorna `503` com `Retry-After` e corpo estável, não
`500` nem stacktrace. Entra na matriz do §6 e nos critérios de H07/H11. O degrau seguinte é o skeleton local do binário,
que é contrato com mobile e não código deste MS.

---

### ADR-008 — Maker-checker com um único engenheiro

**Status:** `PENDENTE` — decisão de processo, não de código. Registrado porque bloqueia a H09.

**Contexto.** O plano §4.3 e a H09 exigem que o maker não aprove o próprio publish, e que rollback de `stable` passe por
checker. Com **um engenheiro de backend**, não existe um segundo ator técnico para aprovar.

**O que não muda.** A regra "maker ≠ checker" **não deve ser desligada no código**. É requisito de persona regulada, e
desligá-la para caber no time de hoje significa reescrevê-la quando o time crescer, provavelmente sob pressão de
incidente.

**Opções para o checker, em ordem de preferência.**

1. **Checker é uma pessoa de produto ou de negócio**, não de backend. Aprovar uma revisão de Home é decisão de produto:o
   diff N-1 → N mostra copy, ordem de slots e ações. Não exige ler Java. É a opção que preserva a regra e melhora a
   governança.
2. **Channel `internal` com atalho de publish** para o ciclo de desenvolvimento, mantendo maker-checker obrigatório em
   `stable` e `canary`. O plano §12 já prevê exatamente isso: "channel `internal` pode ter atalho de publish para dev,
   nunca para `stable`".
3. Conta de serviço aprovando automaticamente. **Descartada**: transforma a trilha de auditoria em ficção e é o tipo de
   coisa que compliance encontra depois.

**Decisão recomendada.** Opção 2 para o dia a dia de desenvolvimento, mais opção 1 assim que houver um par de produto
nomeado. A H09 continua implementando a regra completa; o que muda é quem é o checker, não se existe checker.

**Consequência.** O `audit_log` precisa registrar papel (`maker`/`checker`/`auditor`) mesmo quando maker e checker forem
a mesma pessoa em `internal`, com o channel explícito no registro. Assim a auditoria mostra "aprovado em internal sem
segundo ator" em vez de esconder o fato.

---

### ADR-009 — Section incompatível: omissão pura, com slot portante declarado

**Status:** `ACEITO`

**Contexto.** A política vigente (plano §5.4, fluxos §3, H04, H07) é: section cujo
`type@typeVersion` não pertence às capabilities efetivas é removida da árvore e
registrada em `envelope.omitted`, sem `4xx`. Simples, testável e já coberta por
H12.

O artigo de Joud descreve a alternativa do Shopify: o fallback é campo de
primeira classe no envelope de cada section, e o ganho é estrutural, não de UX —
com o fallback no envelope, torna-se impossível publicar um tipo novo sem
caminho de degradação, porque o schema não permite. Se o fallback mora no
cliente, alguém esquece de cadastrar o novo tipo e o usuário recebe espaço em
branco.

O Ghost chega ao mesmo lugar por outro caminho: cada section trafega dentro de
um `SectionContainer` que carrega, além do modelo de dados, o **status** daquela
section e os dados de logging. Ou seja, a condição da section é campo de
primeira classe do envelope, não algo que o cliente infere da ausência. Somado
ao fallback por section do Shopify, são duas implementações independentes que
trataram degradação como estrutura em vez de omissão.

Isso não inverte a decisão recomendada — omissão pura continua sendo a política
certa para section opcional, e `envelope.omitted` já responde "por que você não
recebeu isto". Mas reforça o ponto do slot portante: as duas referências
consideram inaceitável que a única informação sobre uma section ausente seja o
silêncio.

**O risco que a política atual cria.** Omissão pura é a resposta certa para uma
section opcional: `offers` some e a Home continua coerente. Não é a resposta
certa para um slot portante. Se `account_card@2` for publicado para uma faixa
onde parte da frota só declara `@1`, esses binários recebem uma Home sem o bloco
principal — tecnicamente `200`, com `omitted` preenchido, dashboard verde, e uma
tela que não cumpre a função da superfície. O modo de falha é silencioso por
construção: o MS fez exatamente o que foi mandado fazer.

**Opções.**

1. **Omissão pura, como hoje.** Custo zero. Mantém o buraco do slot portante.
2. **Omissão pura + slot portante declarado.** O documento de skeleton
   persistido no Mongo ganha `required: true|false` por slot, ao lado de
   `allowedTypes` e do máximo de instâncias. A validação de publish recusa
   spec em que um slot `required` possa ficar vazio para qualquer combinação de
   `platform × faixa de appVersion × capabilities` alcançada pelo targeting
   daquela revisão. O campo é de validação, não vai para o payload de fio.
3. **`downgradeTo` por type no catálogo.** Cada `type@N` declara o alvo de
   degradação (`credit_offer@2 → credit_offer@1`) e o compose substitui em vez
   de omitir. Resolve o caso geral e introduz um grafo de degradação que precisa
   ser validado, versionado, testado por aresta e mantido vivo — a complexidade
   que matou o HubFramework do Spotify, adotada antes de existir o problema que
   ela resolve. Hoje o catálogo tem sete types, todos `@1`: o grafo teria zero
   arestas.

**Decisão.** Opção 2. Fecha o modo de falha silencioso com um campo
booleano e uma regra de validação, sem criar entidade nova, sem tocar no
contrato de fio e sem mudar a escada de fallback. `header` e `accounts` são
`required: true`; os demais slots (`shortcuts`, `cards`, `offers`, `coverage`,
`foryou`), `false` — a lista de slots portantes nasce fixada para a Home MVP.

A opção 3 fica **fora do MVP**, com gatilho de reentrada nomeado, no mesmo
formato do ADR-004: reabrir quando o primeiro type chegar a `@2` **e** a frota
estiver dividida o bastante para que a faixa `@1` não possa ser simplesmente
coberta por uma spec própria. Enquanto uma spec por faixa resolver, spec por
faixa é mais barata que grafo de degradação.

**Consequência.**

- H03 ganha `required` no skeleton persistido e um critério de aceite para ele.
- H08 ganha a regra de recusa na validação de rascunho.
- H12 ganha teste: spec que deixa slot `required` vazio para uma faixa dentro do
  próprio targeting é recusada no publish, não em runtime, além de teste que
  prova ausência de `required` no payload de fio.
- `envelope.omitted` continua como está. O contrato de fio não muda.

---

### ADR-010 — `SectionComponentType` não entra no contrato

**Status:** `PROPOSTO` — fecha uma porta que o artigo do Ghost abre.

**Contexto.** O Ghost separa *qual dado* de *como renderizar* em dois campos: o
modelo de dados da section e um `SectionComponentType` que escolhe a renderização
entre várias possíveis para o mesmo modelo. O exemplo do artigo é um título
renderizado com ou sem o tratamento visual da linha Plus. O ganho declarado é
reúso de schema: um modelo, várias caras.

Este MS tem hoje dois eixos para a mesma pergunta: `type` (qual renderer) e
`typeVersion` (qual contrato daquele renderer). Não existe um terceiro campo
escolhendo variação de renderização.

**Por que a tentação é real.** No dia em que produto pedir "o mesmo
`account_card`, mas com o tratamento de cliente premium", existem três saídas: um
type novo (`account_card_premium`, e o catálogo começa a crescer por aparência),
um campo em `props` (e aí é token visual no JSON, o antipadrão que o projeto
nomeia), ou um `componentType` como o do Ghost. A terceira parece a mais limpa e
é a que o artigo endossa.

**Por que ela não entra.** No exemplo do próprio artigo, o que o
`SectionComponentType` seleciona é logo e estilo de título — decisão de aparência
tomada no servidor. É a linha que o projeto fecha em §2 e que a H12 já faz o
build reprovar. Adotá-lo seria mover a violação de `props` para um campo de
primeira classe, com o agravante de ficar mais difícil de detectar: o linter
procura `color`, `gap`, `radius`; não procura um enum opaco cujo significado mora
no binário.

Há também uma assimetria de contexto que o artigo não discute porque não se
aplica a ele. O Ghost roda em web, iOS e Android com um framework próprio em cada
plataforma e uma equipe de plataforma dedicada mantendo o mapa
`componentType → renderer`. Aqui, esse mapa seria conhecimento tácito espalhado
entre dois binários, sem dono nomeado.

**Decisão.** Não adotar. As três situações que motivariam `componentType` têm
resposta própria:

1. **Semântica diferente, dado parecido** — é type diferente. `card_product` e
   `credit_offer` já ilustram isso no catálogo MVP: dados parecidos, conceitos de
   produto distintos, types separados. É a regra do Lyft, que o artigo do Joud
   defende: nomear pelo conceito de produto, não pela forma.
2. **Mesma semântica, ênfase diferente** — é estado semântico em `props`
   (`PREMIUM`, `REQUIRES_LOGIN`, `LOW_STOCK`), e o binário decide o que fazer com
   ele. O servidor diz o que a coisa **é**; nunca como ela aparece.
3. **Mesma semântica, mesmo estado, look diferente** — é Design System. O
   servidor não participa.

**Consequência.**

- H08 recusa, na validação, qualquer campo de section que selecione variação de
  renderização, sob qualquer nome (`componentType`, `variant`, `style`,
  `appearance`, `presentation`).
- A regra de três da H03 (D6.1) continua sendo a defesa contra o outro extremo:
  se três sections diferentes acabarem precisando do mesmo estado semântico novo,
  aí sim generaliza.
- Se o time decidir o contrário no futuro, o gatilho de reabertura precisa ser um
  caso concreto em que as três saídas acima falharam — não a leitura do artigo.

---

### ADR-011 — Conjunto de actions fechado, sem extensão por feature

**Status:** `PROPOSTO` — registra divergência deliberada do Ghost.

**Contexto.** O Ghost permite que features adicionem tipos de `IAction` próprios,
roteados para handlers de escopo da feature, e o artigo apresenta isso como
liberdade: a feature põe quanta lógica de negócio precisar no seu handler. Este
MS fecha o conjunto em `navigate`, `open_bottom_sheet`, `track` e `noop`, e a
H12 reprova o build se aparecer um quinto.

**Decisão.** Manter fechado.

O que o Ghost chama de liberdade é, no nosso desenho, a migração da regra de
negócio de volta para o binário — uma por feature, sem registro central e sem
versionamento. Cada action nova é um contrato implícito entre um time de produto
e dois binários, que ninguém consegue auditar e cuja remoção exige saber quais
versões de app ainda a implementam. É o oposto do motivo pelo qual este MS
existe.

A assimetria de contexto vale de novo: o Ghost tem framework próprio por
plataforma e equipe de plataforma mantendo o roteamento. Aqui, action nova é
combinação verbal entre backend e mobile.

**Consequência.** Action nova é mudança de contrato: entra pelo mesmo processo de
`typeVersion` e matriz de capabilities, com faixa de app declarada, e não por
acordo entre dois times. O custo alto é intencional — é o que impede o conjunto
de virar dezoito.

---

## Conclusão

A proposta de adicionar testes por camada e de preparar módulos para fragmentos estáticos, telas dinâmicas e múltiplas
squads **faz sentido**, desde que o desenho mantenha limites simples e ownership claro.

A pré-arquitetura recomendada fica assim:

```text
sdui-contract       -> contrato REST/JSON público
sdui-core           -> regras SDUI puras
sdui-orchestrator   -> casos de uso + ports + SPI de hydrators
sdui-adapters       -> Mongo + Redis + HTTP clients
sdui-api            -> Controllers MVC e adaptação HTTP
sdui-bootstrap      -> composição Spring Boot e YAML
sdui-integration-test -> integração real e ArchUnit
```

A decisão mais importante é não transformar a modularização em uma plataforma genérica antes da necessidade. Começar com
os módulos acima, interfaces pequenas, composição explícita e testes que protegem regras reais. A evolução para adapters
separados por tecnologia ou para um SPI distribuído entre squads deve acontecer quando ownership, deploy ou ciclo de
vida justificarem a divisão.

---

## Referências

- [Spring Boot — Testing Spring Boot Applications](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
- [Spring Framework — REST Clients](https://docs.spring.io/spring-framework/reference/7.1/integration/rest-clients.html)
- [Spring Boot Starter WebMVC Test 4.1.1](https://central.sonatype.com/artifact/org.springframework.boot/spring-boot-starter-webmvc-test/4.1.1)
- [ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html)
- [JUnit 5 User Guide](https://docs.junit.org/5.10.2/user-guide/index.html)
- Plano de construção — ms-sdui-composer: `plano-servico-sdui.md`
