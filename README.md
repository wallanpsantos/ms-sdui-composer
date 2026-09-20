# ms-sdui-composer — Server-Driven UI BFF

Serviço orquestrador e compositor **Server-Driven UI (SDUI)** para aplicações móveis nativas (**iOS** e **Android**).
O `ms-sdui-composer` atua como **Presentation + Application Controller + BFF de UI** (Martin Fowler), compondo
árvores de componentes hidratadas, determinísticas e compatíveis a partir de especificações versionadas, capabilities
homologadas e contexto dinâmico do cliente móvel.

---

## 📋 Sumário

- [Visão Geral e Arquitetura](#-visão-geral-e-arquitetura)
- [Stack Tecnológica e Baseline](#-stack-tecnológica-e-baseline)
- [Estrutura de Módulos](#-estrutura-de-módulos)
- [Como Subir a Aplicação Localmente](#-como-subir-a-aplicação-localmente)
  - [Pré-requisitos](#pré-requisitos)
  - [Subindo via Gradle Wrapper](#subindo-via-gradle-wrapper)
  - [Subindo via JAR Executável](#subindo-via-jar-executável)
  - [Configurações e Variáveis de Ambiente](#configurações-e-variáveis-de-ambiente)
- [Como Realizar Chamadas (Exemplos Práticos)](#-como-realizar-chamadas-exemplos-práticos)
  - [1. Endpoint Principal da Home (Hot Path)](#1-endpoint-principal-da-home-hot-path)
  - [2. Chamada Condicional com ETag (HTTP 304)](#2-chamada-condicional-com-etag-http-304)
  - [3. Validação Estrita de Headers (HTTP 400)](#3-validação-estrita-de-headers-http-400)
  - [4. Escada de Fallback e Resiliência (HTTP 503)](#4-escada-de-fallback-e-resiliência-http-503)
- [Governança Administrativa (Maker-Checker)](#-governança-administrativa-maker-checker)
  - [Ciclo de Publicação de Especificações](#ciclo-de-publicação-de-especificações)
  - [Rollback Atômico com Idempotência](#rollback-atômico-com-idempotência)
  - [Consulta de Auditoria](#consulta-de-auditoria)
- [Validação, Testes e Qualidade](#-validação-testes-e-qualidade)
- [Documentação Canônica Sequencial](#-documentação-canônica-sequencial)

---

## 🏛️ Visão Geral e Arquitetura

O serviço opera de forma estritamente **stateless no hot path**: não consulta domínios de negócio regulados
diretamente, não retém sessões de usuário e não persiste árvores hidratadas no MongoDB.

### Pipeline de Composição no Hot Path

```
[Request HTTP GET /v1/surfaces/home]
                  │
                  ▼
         1. NEGOTIATE ── Validação obrigatória de 6 cabeçalhos contratuais.
                  │      Parsing de SemVer ordinal protegido contra overflow.
                  ▼
         2. SELECT    ── Seleção determinística da Spec publicada por plataforma,
                  │      canal (stable/canary/internal) e faixa de versão.
                  ▼
         3. FILTER    ── Omissão graciosa de seções incompatíveis com capabilities.
                  │      Ordenação estável TimSort O(N log N).
                  ▼
         4. HYDRATE   ── Fan-out assíncrono em Virtual Threads delimitado por Semaphore.
                  │      Cancelamento ativo e timeout individual de seção.
                  ▼
         5. GUARD     ── Proteção estrutural: VisualGuard (anti-CSS) e PiiGuard (anti-PII).
                  │      Se slot portante falhar: Escada de Fallback (Cache -> LastGood -> 503).
                  ▼
         6. COMPOSE   ── Serialização direta em ByteArray, geração de ETag e resposta HTTP.
```

---

## 🚀 Stack Tecnológica e Baseline

- **Linguagem:** Kotlin 2.4.20 (`allWarningsAsErrors = true`)
- **JVM / Plataforma:** Java 25 LTS via Gradle Toolchain (`jvmToolchain(25)`)
- **Framework:** Spring Boot 4.1.1 (Spring Framework 7.0.x gerenciado pelo BOM oficial)
- **JSON:** Jackson 3 (`tools.jackson.core:jackson-databind` 3.1.5 + `tools.jackson.module:jackson-module-kotlin`)
- **Concorrência:** Spring MVC sobre **Virtual Threads Java 25** (`spring.threads.virtual.enabled: true`)
- **Persistência & Cache:** MongoDB 8.3+ (fonte da verdade de specs) e Redis (cache e fallback de árvores)
- **Governança Arquitetural:** ArchUnit 1.5.0 garantindo isolamento estrito entre camadas

---

## 📦 Estrutura de Módulos

```text
sdui-bootstrap        --> implementation(project(":sdui-app"))
sdui-app              --> api(project(":sdui-core"))
                      --> implementation(project(":sdui-contract"))
sdui-contract         --> Jackson 3 estrito apenas (DTOs de resposta)
sdui-core             --> JDK 25 e Kotlin stdlib apenas (Zero dependências externas)
sdui-integration-test --> testImplementation de todos os módulos + ArchUnit
```

- **`sdui-core`:** Puro. Regras de domínio, SemVer ordinal, validação de catálogo e skeleton, guards e token bucket.
- **`sdui-contract`:** DTOs de contrato público e envelopes JSON serializáveis com Jackson 3.
- **`sdui-app`:** Orquestrador de composição, hydration coordinator, adaptadores de persistência e controllers HTTP.
- **`sdui-bootstrap`:** Módulo executável Spring Boot com inicialização, beans e profiles.
- **`sdui-integration-test`:** Regras de arquitetura ArchUnit e testes end-to-end de isolamento.

---

## 💻 Como Subir a Aplicação Localmente

### Pré-requisitos

- **Java JDK 25 LTS** instalado e configurado no `PATH` (`JAVA_HOME`).
- Opcional: Docker / Rancher Desktop (para instâncias locais de MongoDB e Redis caso deseje persistência real).

### Subindo via Gradle Wrapper

A aplicação sobe por padrão em **modo memória com seed canônica ativada** (`sdui.seed-ios=true`), pronta para
atender chamadas imediatamente na porta `8080`.

**PowerShell (Windows):**
```powershell
.\gradlew.bat :sdui-bootstrap:bootRun
```

**Bash / Zsh (Linux / macOS):**
```bash
./gradlew :sdui-bootstrap:bootRun
```

### Subindo via JAR Executável

Para compilar e gerar o pacote de produção:

```powershell
# Compilação e empacotamento
.\gradlew.bat :sdui-bootstrap:bootJar

# Execução do JAR independente
java -jar .\sdui-bootstrap\build\libs\sdui-bootstrap.jar
```

Aguarde o log de inicialização do Spring Boot:
```text
Started SduiApplication in 0.852 seconds (process running for 1.15)
```

Para validar a integridade da aplicação:
```powershell
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

### Configurações e Variáveis de Ambiente

As propriedades podem ser customizadas via `application.yml` ou variáveis de ambiente com o prefixo `SDUI_`:

| Propriedade | Padrão | Descrição |
|---|:---:|---|
| `server.port` | `8080` | Porta HTTP da aplicação |
| `spring.threads.virtual.enabled` | `true` | Habilita concorrência com Virtual Threads |
| `sdui.seed-ios` | `true` | Carrega o catálogo MVP e a fixture canônica da Home iOS no startup |
| `sdui.tree-ttl-seconds` | `60` | TTL do cache de tela pré-composta no Redis |
| `sdui.hydration-timeout-ms` | `80` | Timeout individual de hidratação remota de section |
| `sdui.hydration-fanout` | `8` | Limite de seções hidratadas concorrentemente por requisição |
| `sdui.rate-limit-capacity` | `10000` | Capacidade do Token Bucket por cliente |
| `sdui.canary-ios-builds` | `[]` | Lista de builds de iOS autorizadas para canal Canary |
| `sdui.canary-android-builds` | `[]` | Lista de builds de Android autorizadas para canal Canary |

---

## 📡 Como Realizar Chamadas (Exemplos Práticos)

O BFF Server-Driven UI exige **6 cabeçalhos de negociação obrigatórios** para garantir que a composição entregue a
árvore correta para a plataforma e versão do aplicativo.

### 1. Endpoint Principal da Home (Hot Path)

#### Cabeçalhos Obrigatórios

| Cabeçalho | Exemplo | Descrição |
|---|---|---|
| `API-Version` | `1` | Versão da API REST HTTP |
| `UI-Schema-Version` | `3` | Versão da estrutura de envelope SDUI (v3 no MVP) |
| `Client-Platform` | `ios` | Plataforma nativa do cliente (`ios` ou `android`) |
| `Client-Version` | `8.10.0` | Versão SemVer com 3 partes numéricas (`major.minor.patch`) |
| `Client-Build` | `1234` | Número da compilação do aplicativo (apenas dígitos) |
| `Accept-Language` | `pt-BR` | Idioma primário do cliente |

#### Cabeçalhos Opcionais

- `OS-Version`: Versão do sistema operacional (ex.: `17.5.1`).
- `Component-Capabilities`: Lista de componentes suportados pelo cliente (ex.: `top_bar@1,shortcut_shelf@1,account_card@1`).
- `SDUI-Channel`: Canal solicitado (`stable`, `canary` ou `internal`). Padrão: `stable`.
- `If-None-Match`: ETag da última tela recebida para validação de cache.

---

#### Exemplo de Chamada com cURL

```bash
curl -X GET http://localhost:8080/v1/surfaces/home \
  -H "API-Version: 1" \
  -H "UI-Schema-Version: 3" \
  -H "Client-Platform: ios" \
  -H "Client-Version: 8.10.0" \
  -H "Client-Build: 1234" \
  -H "Accept-Language: pt-BR" \
  -H "OS-Version: 17.5.1" \
  -H "SDUI-Channel: stable" \
  -i
```

#### Exemplo de Chamada com PowerShell

```powershell
$headers = @{
    "API-Version"         = "1"
    "UI-Schema-Version"   = "3"
    "Client-Platform"     = "ios"
    "Client-Version"      = "8.10.0"
    "Client-Build"        = "1234"
    "Accept-Language"     = "pt-BR"
    "OS-Version"          = "17.5.1"
    "SDUI-Channel"        = "stable"
}

$response = Invoke-RestMethod -Uri "http://localhost:8080/v1/surfaces/home" -Headers $headers -Method Get
$response | ConvertTo-Json -Depth 5
```

---

#### Exemplo de Resposta: HTTP 200 OK

```json
{
  "envelope": {
    "surface": "home",
    "platform": "ios",
    "schemaVersion": "3",
    "specRevisionId": "rev_01K8HOMEMAIN",
    "skeletonId": "home.default",
    "skeletonHash": "sha256:7c2b0e1a9d4f6a8c3e5b1d0f2a4c6e8b",
    "etag": "W/\"rev_01K8HOMEMAIN-ios-3\"",
    "generatedAt": "2026-09-19T15:30:00.000-03:00",
    "locale": "pt-BR",
    "channel": "stable",
    "fallback": false,
    "fallbackReason": "none",
    "omitted": [],
    "client": {
      "platform": "ios",
      "appVersion": "8.10.0",
      "build": "1234",
      "osVersion": "17.5.1",
      "schemaVersionRequested": "3"
    },
    "targeting": {
      "platform": "ios",
      "appVersionMin": "8.10.0",
      "appVersionMax": "8.19.99",
      "osVersionMin": "16.0",
      "schemaVersion": "3",
      "band": "current"
    },
    "analytics": {
      "event": "sdui_home_composed",
      "surface": "home",
      "platform": "ios",
      "experience": "home_ios_current",
      "schemaVersion": "3",
      "specRevisionId": "rev_01K8HOMEMAIN",
      "sectionCount": 7,
      "fallback": false
    }
  },
  "skeleton": {
    "id": "home.default",
    "layout": "single_column_vertical",
    "slots": [
      { "id": "header", "layout": "fixed", "title": null },
      { "id": "shortcuts", "layout": "shelf", "title": null },
      { "id": "accounts", "layout": "list", "title": "Conta" },
      { "id": "cards", "layout": "list", "title": "Cartão de crédito" },
      { "id": "offers", "layout": "list", "title": "Crédito" },
      { "id": "coverage", "layout": "list", "title": "Seguros" },
      { "id": "foryou", "layout": "pager", "title": "Para você" }
    ]
  },
  "sections": [
    {
      "id": "sec_header_v1",
      "slot": "header",
      "type": "top_bar",
      "typeVersion": 1,
      "layout": null,
      "props": { "greeting": "Olá, Cliente" },
      "actions": [],
      "analytics": {
        "event": "sdui_section_shown",
        "component": "top_bar",
        "componentVersion": 1,
        "slot": "header",
        "sectionId": "sec_header_v1",
        "specRevisionId": "rev_01K8HOMEMAIN"
      }
    }
  ]
}
```

---

### 2. Chamada Condicional com ETag (HTTP 304)

Quando o cliente já possui a tela em cache, envia o cabeçalho `If-None-Match`:

```bash
curl -X GET http://localhost:8080/v1/surfaces/home \
  -H "API-Version: 1" \
  -H "UI-Schema-Version: 3" \
  -H "Client-Platform: ios" \
  -H "Client-Version: 8.10.0" \
  -H "Client-Build: 1234" \
  -H "Accept-Language: pt-BR" \
  -H "If-None-Match: W/\"rev_01K8HOMEMAIN-ios-3\"" \
  -i
```

**Resposta HTTP 304 Not Modified:**
```http
HTTP/1.1 304 Not Modified
ETag: W/"rev_01K8HOMEMAIN-ios-3"
Cache-Control: private, max-age=60
Vary: API-Version, UI-Schema-Version, Client-Platform, Client-Version, Client-Build, Component-Capabilities
```

---

### 3. Validação Estrita de Headers (HTTP 400)

Se qualquer cabeçalho obrigatório faltar ou for inválido:

```bash
curl -X GET http://localhost:8080/v1/surfaces/home \
  -H "API-Version: 1" \
  -H "Client-Platform: ios" \
  -i
```

**Resposta HTTP 400 Bad Request:**
```json
{
  "code": "INVALID_HEADERS",
  "message": "headers de negociacao invalidos",
  "details": [
    "UI-Schema-Version:required",
    "Client-Version:required",
    "Client-Build:required",
    "Accept-Language:required"
  ]
}
```

---

### 4. Escada de Fallback e Resiliência (HTTP 503)

Se um cliente descontinuado requisitar uma Home e não houver nenhuma spec compatível nem `lastgood` cacheado:

**Resposta HTTP 503 Service Unavailable:**
```http
HTTP/1.1 503 Service Unavailable
Retry-After: 5
Content-Type: application/json

{
  "code": "COMPOSE_UNAVAILABLE",
  "message": "home indisponivel",
  "details": ["no_compatible_spec"]
}
```

---

## 🛡️ Governança Administrativa (Maker-Checker)

Toda alteração de catálogo, skeleton ou especificação passa por governança estrita **Maker-Checker**:
- `Actor-Id`: Identificador do usuário administrativo.
- `Actor-Role`: Papel do usuário (`MAKER`, `CHECKER` ou `AUDITOR`).
- **Regra:** O criador de um draft de spec (`MAKER`) não pode aprovar a publicação para si mesmo nos canais `canary` e `stable`.

### Ciclo de Publicação de Especificações

#### 1. Criar Rascunho de Spec (Maker)

```bash
curl -X POST http://localhost:8080/admin/v1/specs \
  -H "Content-Type: application/json" \
  -H "Actor-Id: joao.maker" \
  -H "Actor-Role: MAKER" \
  -d '{
    "specId": "spec_home_ios_v2",
    "revision": 1,
    "specRevisionId": "spec_home_ios_v2#1",
    "surface": "home",
    "platform": "ios",
    "channel": "stable",
    "skeletonId": "home.default",
    "skeletonRevision": 1,
    "targeting": {
      "platform": "ios",
      "appVersion": { "min": { "major": 8, "minor": 20, "patch": 0 }, "max": null },
      "schemaVersion": { "min": { "major": 3, "minor": 0, "patch": 0 }, "max": null },
      "requiredCapabilities": [{ "type": "top_bar", "typeVersion": 1 }],
      "priority": 100,
      "band": "next"
    },
    "sections": [
      {
        "id": "sec_header_v1",
        "slot": "header",
        "type": "top_bar",
        "typeVersion": 1,
        "props": { "greeting": "Olá" },
        "actions": []
      },
      {
        "id": "sec_acc_v1",
        "slot": "accounts",
        "type": "account_card",
        "typeVersion": 1,
        "props": { "title": "Conta Principal" },
        "actions": []
      }
    ],
    "checksum": "sha256:7c2b0e1a9d4f6a8c3e5b1d0f2a4c6e8b",
    "experience": "home_v2"
  }'
```

#### 2. Abrir Solicitação de Publicação (Maker)

```bash
curl -X POST http://localhost:8080/admin/v1/publish-requests \
  -H "Content-Type: application/json" \
  -H "Actor-Id: joao.maker" \
  -H "Actor-Role: MAKER" \
  -H "Idempotency-Key: idemp_pub_001" \
  -d '{
    "specId": "spec_home_ios_v2",
    "revision": 1,
    "channel": "stable"
  }'
```

#### 3. Aprovar Publicação (Checker)

```bash
curl -X POST http://localhost:8080/admin/v1/publish-requests/pr_12345/approve \
  -H "Actor-Id: maria.checker" \
  -H "Actor-Role: CHECKER" \
  -H "Idempotency-Key: idemp_app_001"
```

---

### Rollback Atômico com Idempotência

Para reverter instantaneamente o ponteiro de uma surface para a revisão estável anterior:

```bash
curl -X POST http://localhost:8080/admin/v1/pointers/home/ios/stable:rollback \
  -H "Content-Type: application/json" \
  -H "Actor-Id: maria.checker" \
  -H "Actor-Role: CHECKER" \
  -H "Idempotency-Key: idemp_rollback_001" \
  -d '{ "reason": "Incidente em producao - retorno para revisao estavel anterior" }'
```

---

### Consulta de Auditoria

Consulta append-only de todos os eventos de governança:

```bash
curl -X GET http://localhost:8080/admin/v1/audit \
  -H "Actor-Id: carlos.auditor" \
  -H "Actor-Role: AUDITOR"
```

---

## 🧪 Validação, Testes e Qualidade

O projeto adota política de **tolerância zero para warnings** (`allWarningsAsErrors = true`).

### Rodar Toda a Suíte de Testes

```powershell
.\gradlew.bat test --warning-mode=fail
```

### Rodar Testes de Arquitetura (ArchUnit)

Garante conformidade com o Clean Architecture, pureza do `sdui-core` e ausência de `@Transactional`:

```powershell
.\gradlew.bat :sdui-integration-test:test --warning-mode=fail
```

### Validação Completa (Quality Gate)

```powershell
.\gradlew.bat clean check --warning-mode=fail
```

---

## 📚 Documentação Canônica Sequencial

A documentação detalhada do projeto está versionada na pasta [`docs/`](docs/README.md) em ordem sequencial:

1. [`01-iniciar-prompt.md`](docs/01-iniciar-prompt.md) — Prompt canônico, precedência e regras de desenvolvimento.
2. [`02-pre-arquitetura-ms-sdui-composer.md`](docs/02-pre-arquitetura-ms-sdui-composer.md) — Clean Architecture e catálogo formal de ADRs 001 a 013.
3. [`03-memoria-projeto-ms-sdui-composer.md`](docs/03-memoria-projeto-ms-sdui-composer.md) — Memória operacional viva, regras inegociáveis e metas de SLO.
4. [`04-guia-depreciacao-e-migracao-sdui.md`](docs/04-guia-depreciacao-e-migracao-sdui.md) — Padrão Strangler para componentes, sunset de faixas de app e Expand/Contract no MongoDB.
