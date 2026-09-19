# Plano de construção — ms-sdui-composer

**ms-sdui-composer** = o serviço que, a cada request, compõe a árvore de UI da surface a partir de uma spec versionada,
do contexto do cliente e das capabilities, devolvendo um envelope pronto e seguro para o app.

Documento de engenharia para implementar o **ms-sdui-composer**, desenhado para atender múltiplos times e superfícies de
produto (tendo a `home` como primeira surface). Destinado a implementação posterior, não é código.

> **Baseline atualizada em 16/09/2026.** Java 25 LTS, Spring Framework 7.0.9+, Spring Boot 4.1.1, Gradle 9.7.1 - Kotlin+, MongoDB
> 8.3+ e Redis. Kafka permanece opcional e somente para auditoria/evento assíncrono real. Spring Cloud não faz parte da
> baseline e só entra com módulo concreto e requisito comprovado. Testcontainers, Springdoc, Micrometer, OpenTelemetry,
> ArchUnit e demais bibliotecas devem usar as versões gerenciadas pelo Spring Boot ou a versão mais recente verificada no
> repositório corporativo/Maven Central.

Papel: Staff/Principal Kotlin/Spring.
Stack fechada pelo projeto: Kotlin 2.3.21, JVM Java 25 LTS,
Spring Framework 7.0.9+, Spring Boot 4.1.1, Gradle 9.7.1 com Kotlin DSL,
MongoDB 8.3+ (ou DocumentDB compatível), Redis na mesma AZ e Kafka 4.2+
opcional para auditoria assíncrona.

Fontes permanentes: `resumos-server-driven-ui.md`, `instrucoes-projeto.md`, skill `skills/sdui-backend/`, PDFs em
`Books/`. Não copiar livro.

---

## 0. Premissas explícitas

1. O **ms-sdui-composer** compõe a árvore de UI da surface solicitada (tendo a Home como primeira surface). Não consulta
   contrato, dado de cliente, apólice, sinistro ou qualquer domínio de negócio. Valor na surface, se existir, chega já
   formatado de um BFF/agregador externo — e ainda assim como `BigDecimal` no compose interno, nunca no payload cru de
   domínio.
2. Composer = Presentation + Application Controller + BFF de UI (Fowler). Payload = DTO de view (Transform View), não
   entidade.
3. Cliente = registry + renderer nativo. Servidor decide **o quê** (estrutura, conteúdo, ordem); cliente decide **como**
   (tamanho, cor, raio, animação, tipografia, dark mode, acessibilidade, Dynamic Type, densidade, convenção
   iOS/Android/Web). Campo de aparência no JSON (`width`, `height`, `rounded`, `orientation`, `circle`, `rectangle`,
   `shimmer`) é o antipadrão “backend que manda CSS” (Joud).
4. Componentes são semânticos do Design System (`top_bar`, `shortcut_shelf`, `account_card`, `card_product`,
   `credit_offer`, `coverage_card`, `decision_card`), não DSL de pixel. Catálogo oficial da Home iOS:
   `contrato-sdui-home-definitivo.json`.
5. Flag decide se a superfície existe; SDUI decide como monta. Sem explosão `schema × componente × flag × plataforma`.
6. Contrato do app: REST + JSON próprio. Sem GraphQL, gRPC, Protobuf ou framework SDUI de terceiros.
7. Headers de negociação **sem** prefixo `X-` (RFC 6648 / BCP 178).
8. SLO: P99 (rede + compose + first paint) < 1200 ms. Meta interna: compose P99 ≤ 400 ms no hit de cache.
9. Composer **stateless**. Estado vive em Mongo (fonte da verdade de spec) e Redis (cache).
10. Persona regulada (Empresa Regulada / Banco): maker-checker no publish, auditoria append-only, LGPD (nada de dado
    regulado no payload), rastreio de quem publicou o quê.
11. iOS e Android **não** compartilham o mesmo documento final quando o contrato de campos diverge. Compartilham
    catálogo, skeleton e regras. O documento persistido pode ser específico da plataforma.
12. Fora de escopo: checkout, câmera, mapa, animação pesada, WebView como SDUI, CQRS/Event Sourcing/Hexágono neste MS.

Se alguma premissa acima mudar, o plano de dados e o pipeline de compose mudam com ela.

---

## 1. Objetivo do serviço

Entregar, por request autenticada de app, uma **árvore de UI hidratada e renderizável** da Home, compatível com a versão
do binário, do schema e das capabilities do cliente, com:

- seleção de spec por faixa de versão (mínima, intermediária, máxima) em iOS e Android;
- skeleton estável + componentes reutilizáveis plugados nos slots;
- documentos distintos por plataforma quando o JSON divergir;
- cache que protege o P99;
- rollback em um apontador (sem reescrever histórico);
- diff rastreável entre revisão N-1 e N;
- publish com maker-checker.

Não é um CMS genérico. Não é um Design System. É o orquestrador e compositor de surfaces (**ms-sdui-composer**),
estruturado para atender múltiplos times e superfícies de produto a partir de um motor comum.

### 1.1 O que é o Composer e por que este nome

No contexto de Server-Driven UI (especialmente nos padrões Joud W. Awad + Airbnb Ghost + skill `sdui-backend`), o
**Composer** descreve exatamente o que o serviço **faz**, não a tecnologia nem o protocolo. É o motor que transforma:

```text
(spec imutável + contexto do cliente + capabilities + channel) → Envelope JSON pronto para o app
```

Ele **não** é um gateway/proxy genérico, nem um registry de templates, nem um CMS, nem um serviço de domínio de negócio.
Ele é o **Presentation + Application Controller + BFF de UI** (Fowler).

A operação central é **compor**:

1. **Selecionar** a spec correta (pointer + targeting).
2. **Filtrar** sections que o cliente não consegue renderizar (capabilities).
3. **Hidratar** dados (fan-out controlado de projeções allowlist).
4. **Aplicar** guardas e fallback (omissão pura de section degradada ou fallback para última árvore boa).
5. **Montar** o envelope final (árvore de sections + slots + actions + metadados de rastreabilidade).

### 1.2 Responsabilidades do `ms-sdui-composer`

| Responsabilidade     | O que faz                                                                                                                                    | O que **não** faz                                               |
|----------------------|----------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------|
| **Negotiate**        | Lê headers (`UI-Schema-Version`, `Client-Platform`, `Client-Version`, `Client-Build`, `Component-Capabilities`…) e decide a faixa compatível | Não valida regra de negócio de domínio                          |
| **Select**           | Resolve pointer + targeting → escolhe a `specRevisionId` correta (stable/canary/internal)                                                    | Não cria nem edita specs                                        |
| **Filter**           | Omite sections cujo `type@version` o cliente não declara nas capabilities efetivas                                                           | Não rejeita a tela inteira por falta de uma section opcional    |
| **Hydrate**          | Chama projeções allowlist (timeout + circuit breaker). Section que falha é omitida                                                           | Não consulta domínio regulado nem faz N+1                       |
| **Guard / Fallback** | Se tudo sumir → devolve skeleton + header mínimo (ou última árvore boa / `503 Retry-After`)                                                  | Não inventa UI “bonita” no backend                              |
| **Envelope**         | Monta o JSON final + ETag + `Cache-Control` + `Vary` + `fallback`                                                                            | Não envia cor, margem, padding, fonte, radius, geometria ou CSS |
| **Observabilidade**  | Métricas de hit/miss, fallback, section omitida, payload size, schemaVersion, etc.                                                           | Não loga PII, valores financeiros ou dados regulados            |

Tudo isso é **stateless** e no caminho crítico quente (SLO interno: compose P99 ≤ 400 ms no hit de cache).

### 1.3 Governança multi-times: motor central vs. autonomia das equipes

O `ms-sdui-composer` é o motor central de execução para múltiplas superfícies de produto. Para evitar o antipadrão do
Spotify HubFramework (abstração genérica demais cedo) e o antipadrão de "todo mundo joga regra de negócio no BFF", a
separação de responsabilidades é rígida:

| Peça                                       | Quem                          | Responsabilidade                                                            |
|--------------------------------------------|-------------------------------|-----------------------------------------------------------------------------|
| **`ms-sdui-composer`**                     | Time de plataforma / Core UI  | Runtime. Compõe a árvore. Hot path stateless.                               |
| **Specs + Pointers**                       | Times de produto + plataforma | Definem as specs (PUBLISHED, imutáveis) e os pointers de canal              |
| **Catálogo de types**                      | Plataforma + Design System    | Define quais `type@version` existem e quais props/actions são válidas       |
| **Admin / Publisher** *(serviço separado)* | Plataforma ou merchandising   | Authoring, validação de schema, publish, maker-checker, rollback de pointer |
| **Projeções de dados**                     | Times de domínio              | Endpoints allowlist que o composer chama na hidratação                      |

**Regra de ouro de contribuição:** Várias equipes podem contribuir criando novas sections/componentes (desde que
homologados no catálogo e no Design System do app). Mas elas **não** escrevem código dentro do `ms-sdui-composer`. Elas
publicam specs versionadas e, se necessário, expõem projeções de dados dedicadas à apresentação.

---

## 2. Vocabulário (fechar nomes antes de codar)

| Termo                 | Significado                                                                                                                                                                                                        |
|-----------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Surface               | Superfície de produto. Neste MS: `home`. Depois pode existir `home.logged_out`.                                                                                                                                    |
| Skeleton              | Estrutura da tela: lista ordenada de **slots** com layout. Não contém copy nem dados.                                                                                                                              |
| Slot                  | Região nomeada da Home: `header`, `shortcuts`, `accounts`, `cards`, `offers`, `coverage`, `foryou` (opcional `footer`).                                                                                            |
| Component type        | Tipo do Design System versionado à parte do schema. Home MVP: `top_bar@1`, `shortcut_shelf@1`, `account_card@1`, `card_product@1`, `credit_offer@1`, `coverage_card@1`, `decision_card@1`.                         |
| Component instance    | Uso de um type num slot, com props e actions.                                                                                                                                                                      |
| Spec                  | Documento publicado: skeleton + instâncias + targeting + revisão. Imutável depois de `PUBLISHED`.                                                                                                                  |
| Overlay de plataforma | Patch JSON aplicado sobre um spec base para iOS **ou** Android.                                                                                                                                                    |
| Árvore hidratada      | JSON final enviado ao app (envelope + sections). Pode estar pré-materializada.                                                                                                                                     |
| Schema version        | Versão do **contrato** do envelope e dos tipos conhecidos. Independente da versão do app.                                                                                                                          |
| Capability            | Declaração do cliente: “eu sei renderizar `account_card@1`”.                                                                                                                                                       |
| Targeting             | Faixa de app/OS/schema/plataforma que uma spec atende.                                                                                                                                                             |
| Pointer               | Referência mutável `surface + platform + channel → specRevisionId` vigente. Rollback = mover o pointer.                                                                                                            |
| Channel               | `stable`, `canary`, `internal`. Separado de feature flag.                                                                                                                                                          |
| Screen                | A árvore hidratada de uma surface: o objeto que o compose devolve. Sinônimo textual de "árvore hidratada"; em código, cache e métrica usa-se `Screen`/`tree` de forma consistente, nunca os três nomes misturados. |

**"Fragment" não é termo deste vocabulário.** A pré-arquitetura o introduziu como camada nova (`Fragment`,
`FragmentStore`, `FragmentResolver`, `GET /v1/fragments/{id}`) e ele foi adiado por ADR-004. A hipótese a validar antes
de reabrir é que "fragmento estático" seja apenas *um conjunto de placements publicado e reutilizável, sem hidratação
dinâmica* — se for isso, cabe dentro do `SpecResolver`, sem entidade, sem store e sem endpoint. Só vale formalizar
quando existir uma segunda surface reusando o mesmo bloco.

Regra de ouro (Airbnb/Shopify): **section > screen**. Quem modela o bloco reutilizável ganha. Quem modela “a Home do iOS
18” perde o padrão.

---

## 3. Arquitetura lógica

```
App iOS/Android
  │  headers de negociação + GET /v1/surfaces/home
  ▼
┌─────────────────────────────────────────────────────────┐
│  ms-sdui-composer (stateless)                           │
│  1. Negotiate   (plataforma, app, schema, caps)         │
│  2. Select      (pointer + targeting min/max)           │
│  3. Resolve     (spec + overlay plataforma)             │
│  4. Filter      (omitir section desconhecida)           │
│  5. Hydrate     (projeções de seção, sem domínio)       │
│  6. Envelope    (fallback, etag, skeleton hash)         │
└──────────────┬──────────────────────────┬───────────────┘
               │                          │
               ▼                          ▼
        Redis (mesma AZ)           MongoDB 8.3+
        - spec write-through       - catalogo, skeleton
        - árvore hidratada TTL     - spec imutável
        - projeções de seção       - overlay plataforma
        - singleflight             - pointer, audit
                                   - publish request
```

Não há N+1 GET por widget. Hidratação de seções é fan-out controlado com timeout por seção. Seção que falha some; a tela
permanece.

inspiração cruzada:

- Composer/BFF: Lyft `lbsbff`, Shopify orchestrator.
- Section + layout: Shopify Shop App, Airbnb Ghost.
- Versionamento no servidor + omitir o desconhecido: Shopify, Zalando Appcraft, Delivery Hero.
- Console + maker-checker: PhonePe LiquidUI / Chimera.
- Actions desacopladas do layout: Lyft, PhonePe, Zalando.
- Observabilidade por componente: Netflix / Joud.

---

## 4. Contrato HTTP

### 4.1 Headers de negociação (nomes fechados)

| Header                   | Obrigatório  | Exemplo                      | Função                                                                   |
|--------------------------|--------------|------------------------------|--------------------------------------------------------------------------|
| `UI-Schema-Version`      | sim          | `3`                          | Contrato do envelope                                                     |
| `Client-Platform`        | sim          | `ios` \| `android`           | Documento/overlay                                                        |
| `Client-Version`         | sim          | `8.14.2`                     | Semver do app (não comparar iOS×Android)                                 |
| `Client-Build`           | sim          | `81420`                      | Desempate e canary                                                       |
| `Accept-Language`        | sim          | `pt-BR`                      | Copy já resolvida no servidor                                            |
| `API-Version`            | sim (Spring) | `1`                          | Versão HTTP do MS; ≠ UI-Schema-Version                                   |
| `OS-Version`             | recomendado  | `18.1` / `35`                | Targeting fino                                                           |
| `Component-Capabilities` | recomendado  | `top_bar@1,account_card@1,…` | Delta; fonte da verdade é a matriz servidor `(platform, Client-Version)` |

Única menção residual a `X-` no ecossistema: `X-Forwarded-For` (infra). Não criar `X-UI-*`.

API version do Spring MVC (Boot 4.0+):

```yaml
spring:
  mvc:
    apiversion:
      use:
        header: API-Version
      default: "1"
      required: true
      supported: "1"
```

O valor `1` é a versão **da API HTTP** deste MS. `UI-Schema-Version` é outra dimensão. Não misturar as duas.

### 4.2 Endpoint de compose (único no MVP)

```
GET /v1/surfaces/home
```

Resposta 200 — envelope estável (fio canônico iOS em `contrato-sdui-home-definitivo.json`):

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
    "generatedAt": "2026-09-11T11:25:00-03:00",
    "locale": "pt-BR",
    "channel": "stable",
    "fallback": false,
    "fallbackReason": "none",
    "omitted": [],
    "client": {
      "platform": "ios",
      "appVersion": "8.14.2",
      "build": "81420",
      "osVersion": "18.1",
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
      "sectionCount": 8,
      "fallback": false
    }
  },
  "skeleton": {
    "id": "home.default",
    "layout": "vertical_scroll",
    "slots": [
      { "id": "header", "layout": "fixed" },
      { "id": "shortcuts", "layout": "shelf" },
      { "id": "accounts", "layout": "list", "title": "Conta" },
      { "id": "cards", "layout": "list", "title": "Cartão de crédito" },
      { "id": "offers", "layout": "list", "title": "Crédito" },
      { "id": "coverage", "layout": "list", "title": "Seguros" },
      { "id": "foryou", "layout": "pager", "title": "Para você" }
    ]
  },
  "sections": [
    {
      "id": "sec_header_1",
      "slot": "header",
      "type": "top_bar",
      "typeVersion": 1,
      "layout": "fixed",
      "props": {
        "greetingName": "Mariana Silva",
        "avatarUrl": "https://cdn.example-allowlist.com/avatars/usr_9f3a.jpg",
        "loyaltyLabel": "53.500 pontos",
        "primaryActionLabel": "SOS"
      },
      "actions": [
        { "id": "act_sos", "type": "navigate", "label": "SOS", "payload": { "route": "app://assistance/sos" } }
      ],
      "analytics": {
        "event": "sdui_section_shown",
        "component": "top_bar",
        "componentVersion": 1,
        "slot": "header",
        "sectionId": "sec_header_1",
        "specRevisionId": "rev_01K8HOMEMAIN"
      }
    }
  ]
}
```

Regras do envelope:

- Seção cujo `type@version` o cliente não renderiza é **omitida** (lista em `omitted[]` com reason fechado). Não gera
  4xx.
- Capabilities efetivas = matriz servidor ∪ delta do header. Header sozinho não é fonte da verdade.
- Targeting sem spec vigente → 200 com última árvore boa e `fallback: true` + `fallbackReason` (escada em
  `skills/sdui-backend/references/fallback-e-versao.md`). Não 404 em `home`.
- `ETag` / `If-None-Match` → 304. First paint local usa cache do app.
- Rate limit no compose (token bucket por identidade + plataforma).
- Dinheiro só como string formatada (`valueDisplay` / `valueDisplayRevealed`). Olho é gesto local do app.
- Actions: `navigate` | `open_bottom_sheet` | `track` | `noop`. CTA visível leva `label`. Rota = `payload.route`
  `app://…`.

Não expor CRUD de spec neste mesmo host no MVP de runtime. Authoring pode ser o mesmo binário com perfil `admin` e path
`/admin/v1/**`, ou um segundo deploy. Preferência: **mesmo código, perfil separado**, para não duplicar o modelo.

### 4.3 Endpoints de governança (admin)

| Método | Path                                                         | Função                              |
|--------|--------------------------------------------------------------|-------------------------------------|
| GET    | `/admin/v1/catalog/components`                               | Catálogo versionado                 |
| PUT    | `/admin/v1/catalog/components/{type}/{ver}`                  | Registrar type                      |
| GET    | `/admin/v1/skeletons/{id}`                                   | Skeleton vigente + histórico        |
| PUT    | `/admin/v1/skeletons/{id}`                                   | Nova revisão de skeleton (rascunho) |
| GET    | `/admin/v1/specs`                                            | Lista com filtro platform/channel   |
| POST   | `/admin/v1/specs`                                            | Cria rascunho (base + overlays)     |
| GET    | `/admin/v1/specs/{id}/revisions`                             | Histórico imutável                  |
| GET    | `/admin/v1/specs/{id}/revisions/{from}..{to}/diff`           | Rastreio N-1 → N                    |
| POST   | `/admin/v1/publish-requests`                                 | Abre maker-checker                  |
| POST   | `/admin/v1/publish-requests/{id}/approve`                    | Checker publica (move pointer)      |
| POST   | `/admin/v1/publish-requests/{id}/reject`                     | Reprova                             |
| POST   | `/admin/v1/pointers/{surface}/{platform}/{channel}:rollback` | Rollback facilitado                 |
| GET    | `/admin/v1/audit`                                            | Consulta append-only                |

Tudo isso exige identidade corporativa + papel `maker` / `checker` / `auditor`. Maker não aprova o próprio publish.

---

## 5. Versionamento (mínima, intermediária, máxima)

Três eixos **independentes**. Não colapsar num único inteiro.

```
eixo A  UI-Schema-Version          contrato do envelope
eixo B  component type + typeVersion   o que o binário sabe desenhar
eixo C  Client-Version + OS-Version    faixa do app / SO
```

### 5.1 Faixa de app (o pedido “min / entre / max”)

Cada spec publicada carrega targeting:

```json
{
  "platform": "ios",
  "appVersion": { "min": "8.10.0", "max": "8.99.99" },
  "osVersion":  { "min": "16.0", "max": null },
  "schemaVersion": { "min": "3", "max": "3" },
  "build": { "min": null, "max": null },
  "requiredCapabilities": ["top_bar@1", "shortcut_shelf@1", "account_card@1"]
}
```

Semântica:

- `min` inclusivo, `max` inclusivo. `max: null` = sem teto (rolling).
- “Versão intermediária” não é um campo. É **o conjunto de specs** cujas faixas cobrem `8.12.x`, `8.14.x`, etc. Um app
  `8.14.2` casa com a spec cuja faixa o contém **e** que tenha a maior `priority` (ou a revisão mais nova, se priority
  empatar).
- iOS e Android têm **documentos e faixas próprios**. Nunca reutilizar a faixa de um no outro — o semver das lojas não é
  comparável.

Exemplos reais de cobertura:

| Spec                   | Platform | min    | max     | Uso                                 |
|------------------------|----------|--------|---------|-------------------------------------|
| `home.ios.legacy`      | ios      | 8.4.0  | 8.9.99  | Binários velhos, catálogo reduzido  |
| `home.ios.current`     | ios      | 8.10.0 | 8.19.99 | Frota majoritária                   |
| `home.ios.next`        | ios      | 8.20.0 | null    | Features que exigem componente novo |
| `home.android.legacy`  | android  | 8.4.0  | 8.9.99  | Idem                                |
| `home.android.current` | android  | 8.10.0 | 8.19.99 | Idem                                |

O app **não escolhe** a spec. O servidor escolhe. Cliente só declara quem ele é.

### 5.2 Algoritmo de seleção (determinístico)

Entrada: headers + channel (`stable` default; `canary` se build allowlist ou cookie interno).

1. Ler pointer `(surface, platform, channel)`.
2. Buscar candidatas `PUBLISHED` com `platform` igual e `schemaVersion` na faixa do header.
3. Filtrar por `appVersion` e `osVersion` com comparação semver (não string).
4. Filtrar por `requiredCapabilities ⊆ capabilities efetivas` (matriz servidor ∪ header delta).
5. Ordenar por `priority DESC`, depois `publishedAt DESC`.
6. Primeira candidata vence.
7. Aplicar overlay da plataforma (já materializado na publish — ver §7).
8. Omitir sections cujo `type@version` não está nas capabilities efetivas (defesa em profundidade; o filtro 4 já deveria
   ter coberto).

Invariante: a seleção é função pura dos headers + ponteiros. Dois requests iguais no mesmo instante devolvem o mesmo
`specRevisionId`.

### 5.3 Schema vs componente vs flag

- Schema sobe quando o **envelope** muda (campo novo obrigatório, rename de `sections`, etc.).
- Componente sobe quando o **renderer** muda (`account_card@1` → `@2` com prop obrigatória nova).
- Flag (Fowler) liga/desliga a **existência** da superfície ou de um experimento. Não versiona JSON.

Proibido: gerar uma spec nova para cada combinação flag × plataforma × schema. Flag escolhe pointer/channel ou omite uma
section no compose, não multiplica documentos.

**Form factor não é um quarto eixo.** Os eixos de seleção são schema, `type`+`typeVersion` e app/OS — três. Tablet,
foldable, rotação, densidade e breakpoint **não** entram no targeting e não geram header novo: `Client-FormFactor` não
existe e não deve ser criado. Um quarto eixo multiplica a matriz de specs por dois de um dia para o outro, e nenhuma das
dores que ele resolveria é de servidor.

Se um dia a Home precisar de arranjo diferente em tela larga, o caminho é o do Ghost: a resposta carrega as variantes de
skeleton e o **cliente** escolhe qual usar, pelos mesmos critérios com que já escolhe Dynamic Type e densidade. O
servidor continua decidindo quais sections e em que ordem; o arranjo por tamanho de tela é decisão do binário, como cor
e tipografia. Enquanto o app for só telefone, nem a variante existe.

### 5.4 Compatibilidade para frente e para trás

Herdado de Fluid / Zalando / Shopify:

| Situação                                       | Comportamento                                                                                          |
|------------------------------------------------|--------------------------------------------------------------------------------------------------------|
| Cliente novo, spec velha                       | Renderiza. Props novas ausentes usam default do renderer.                                              |
| Cliente velho, spec nova com type desconhecido | Section omitida. Tela sobra.                                                                           |
| Prop nova em type conhecido, cliente velho     | Renderer ignora a prop. Contrato: props adicionais são sempre opcionais.                               |
| Prop removida                                  | Só depois que `max` da faixa velha saiu de circulação.                                                 |
| Breaking change de type                        | Sobe `typeVersion`. Spec nova exige a capability `@2`. Spec velha continua viva na faixa `max` antiga. |

Não existe “um JSON único eterno”. Existe convivência de revisões.

---

## 6. Skeleton, componentes e integração

### 6.1 Skeleton

O skeleton é o osso da Home. Muda pouco. É versionado porque a ordem dos slots é produto.

```json
{
  "_id": "skel_home_default",
  "surface": "home",
  "revision": 4,
  "layout": "vertical_scroll",
  "slots": [
    { "id": "header",    "layout": "fixed", "maxInstances": 1, "allowedTypes": ["top_bar"] },
    { "id": "shortcuts", "layout": "shelf", "maxInstances": 1, "allowedTypes": ["shortcut_shelf"] },
    { "id": "accounts",  "layout": "list",  "maxInstances": 1, "allowedTypes": ["account_card"], "title": "Conta" },
    { "id": "cards",     "layout": "list",  "maxInstances": 3, "allowedTypes": ["card_product"], "title": "Cartão de crédito" },
    { "id": "offers",    "layout": "list",  "maxInstances": 4, "allowedTypes": ["credit_offer"], "title": "Crédito" },
    { "id": "coverage",  "layout": "list",  "maxInstances": 3, "allowedTypes": ["coverage_card"], "title": "Seguros" },
    { "id": "foryou",    "layout": "pager", "maxInstances": 2, "allowedTypes": ["decision_card"], "title": "Para você" }
  ],
  "status": "PUBLISHED"
}
```

Invariantes:

- Slot sem instância válida some no compose (não deixa buraco obrigatório, salvo `header` se produto exigir).
- `allowedTypes` impede plugar `credit_offer` no `header`.
- Layout é token do **slot** (`fixed` | `shelf` | `list` | `pager` | `grid`), não geometria em dp. `title` do slot é
  conteúdo de grupo (o quê), não estilo.
- Tab bar Início/Pagamentos/Invest/Seguros/Menu é chrome nativo — fora do skeleton.

### 6.2 Catálogo de componentes (reuso)

Um type vive no catálogo, não dentro da Home. A Home só instancia.

```json
{
  "_id": "cmp_shortcut_shelf_1",
  "type": "shortcut_shelf",
  "typeVersion": 1,
  "status": "ACTIVE",
  "sinceSchema": "3",
  "propsSchema": {
    "type": "object",
    "required": ["items"],
    "properties": {
      "variant": { "type": "string", "enum": ["compact", "regular"], "description": "Densidade de produto, não geometria" },
      "items": {
        "type": "array",
        "minItems": 0,
        "maxItems": 12,
        "items": {
          "type": "object",
          "required": ["id", "label", "icon", "actionId"],
          "properties": {
            "id": { "type": "string" },
            "label": { "type": "string" },
            "icon": { "type": "string", "description": "Token do DS, ex. icon.credit" },
            "badge": { "type": "string" },
            "selected": { "type": "boolean" },
            "actionId": { "type": "string" }
          }
        }
      }
    }
  }
}
```

Catálogo Home MVP (`type@version` = capability):

| type               | slot típico | Props de conteúdo (resumo)                                                  |
|--------------------|-------------|-----------------------------------------------------------------------------|
| `top_bar@1`        | header      | greetingName, avatarUrl, loyaltyLabel, primaryActionLabel                   |
| `shortcut_shelf@1` | shortcuts   | variant, items[] (label, icon token, selected, actionId)                    |
| `account_card@1`   | accounts    | title, concealable, concealed, rows[] (valueDisplay + valueDisplayRevealed) |
| `card_product@1`   | cards       | brandLabel, last4, rows de fatura/limite (mesmo shape)                      |
| `credit_offer@1`   | offers      | headlineDisplay, subtitle, primaryLabel, secondaryLabel                     |
| `coverage_card@1`  | coverage    | title, assetLabel, rows[] (valueDisplay + valueNorm ISO)                    |
| `decision_card@1`  | foryou      | kicker, body, rejectLabel, acceptLabel                                      |

Reuso:

- O mesmo `shortcut_shelf@1` pode aparecer na Home e, no futuro, numa surface `help`.
- Actions ficam em `sections[].actions[]` (`navigate`, `open_bottom_sheet`, `track`, `noop`). Item aponta por
  `actionId`.
- Não criar `IosShortcutShelf` / `AndroidShortcutShelf`. Divergência visual = Design System no app, não type novo.
- **Proibido no catálogo:** width, color, shimmer, haptic, ripple, padding, columns em dp — isso é “backend que manda
  CSS” (Joud).

### 6.3 Instância no spec (integração slot ← componente)

O spec **amarra** skeleton + instâncias. É aqui que a tela nasce.

```json
{
  "skeletonId": "home.default",
  "placements": [
    {
      "slot": "shortcuts",
      "instances": [
        {
          "id": "sec_shortcuts_1",
          "type": "shortcut_shelf",
          "typeVersion": 1,
          "props": {
            "variant": "compact",
            "items": [
              { "id": "sc_credito", "label": "Crédito", "icon": "icon.credit", "selected": true, "actionId": "act_sc_credito" }
            ]
          },
          "actions": [
            { "id": "act_sc_credito", "type": "navigate", "label": "Crédito", "payload": { "route": "app://credit" } }
          ]
        }
      ]
    },
    {
      "slot": "accounts",
      "instances": [
        {
          "id": "sec_account_1",
          "type": "account_card",
          "typeVersion": 1,
          "props": {
            "title": "Conta",
            "concealable": true,
            "concealed": true,
            "rows": [
              {
                "id": "row_balance",
                "label": "Saldo",
                "valueDisplay": "R$ ••••••",
                "valueDisplayRevealed": "R$ 5.289,38",
                "concealable": true
              }
            ]
          },
          "actions": [
            { "id": "act_pix", "type": "navigate", "label": "Pix", "payload": { "route": "app://pix" } }
          ]
        }
      ]
    }
  ]
}
```

Pipeline de integração no publish (não no request quente):

1. Validar que cada placement.slot existe no skeleton.
2. Validar `type` ∈ `allowedTypes` do slot.
3. Validar props contra `propsSchema` do catálogo, **mais** as `platformFields` da plataforma alvo.
4. Materializar duas árvores (ios, android) se o spec for “base + overlays”; ou uma árvore se o spec já for
   monoplataforma.
5. Gravar spec imutável + diff contra a revisão anterior + mover pointer só no approve.

No request quente o composer **não** remonta Lego. Ele seleciona a árvore já validada, omite o que o cliente não sabe,
hidrata projeções baratas (copy localizada, URL de mídia já absoluta) e envelopa.

### 6.4 Actions

Contrato fechado no MVP:

| type                | Payload                                              | Quem executa |
|---------------------|------------------------------------------------------|--------------|
| `navigate`          | `route` começando com `app://` (registry do binário) | Cliente      |
| `open_bottom_sheet` | `sheet:` id nativo                                   | Cliente      |
| `track`             | `event` + ids opacos (sem PII)                       | Cliente      |
| `noop`              | —                                                    | Ninguém      |

- CTA visível **exige** `label` (Pix, Contratar, Apólice…). Chevron/olho podem omitir label.
- `https` aberto: fora do MVP (só allowlist + maker-checker se entrar depois).
- Type desconhecido no binário = `noop` silencioso; a section permanece.
- Sem action de domínio neste MS. Sem `toggleSensitiveData` — o olho é local e pode emitir `track`
  `sdui_conceal_toggle`.

---

## 7. Modelo de dados — MongoDB 8.3+ (ou DocumentDB)

### 7.1 Por que documento, não relacional, neste MS

O payload **é** um documento. iOS e Android divergem em campos. Revisões são snapshots. Overlay é patch. Isso casa com
Mongo.

PostgreSQL 18 continua válido no ecossistema da empresa (auditoria financeira, domínio). **Não** é a fonte da verdade da
spec da Home. Se a organização exigir trilha relacional para SOX/SUSEP, replica-se o **audit_log** para Postgres via
outbox — o compose não lê Postgres.

DocumentDB (AWS): usar só se a conta já opera DocumentDB e o time aceita o recorte de API (transações multi-doc
limitadas, aggregations incompletas, sem some change stream features do Mongo 8). Preferência do plano: **MongoDB
8.3+**. DocumentDB entra como “mesmo modelo, driver compatível, testar cada índice e transação no ambiente real antes de
cravar”.

### 7.2 Coleções

| Coleção             | Chave                              | Mutável?                  | Função             |
|---------------------|------------------------------------|---------------------------|--------------------|
| `component_catalog` | `type` + `typeVersion`             | correção só em rascunho   | Contrato do type   |
| `skeletons`         | `skeletonId` + `revision`          | imutável após publish     | Osso da tela       |
| `specs`             | `specId` + `revision`              | imutável após `PUBLISHED` | Snapshot completo  |
| `spec_overlays`     | `specId` + `revision` + `platform` | imutável após publish     | Patch ios/android  |
| `spec_materialized` | `specRevisionId` + `platform`      | imutável                  | Árvore já mesclada |
| `pointers`          | `surface` + `platform` + `channel` | **sim**                   | Apontador vigente  |
| `publish_requests`  | `requestId`                        | até decisão               | Maker-checker      |
| `diffs`             | `specId` + `fromRev` + `toRev`     | imutável                  | Rastreio           |
| `audit_log`         | `_id` / `ts`                       | append-only               | Quem fez o quê     |
| `idempotency`       | `key`                              | TTL                       | Approve/rollback   |

Não guardar árvore hidratada por usuário no Mongo. Isso é Redis, TTL curto, sem PII.

### 7.3 Documento `specs` (canônico)

```json
{
  "_id": "spec_home_ios_current#12",
  "specId": "spec_home_ios_current",
  "revision": 12,
  "parentRevision": 11,
  "status": "PUBLISHED",
  "surface": "home",
  "platformScope": "ios",
  "skeletonId": "skel_home_default",
  "skeletonRevision": 4,
  "targeting": {
    "platform": "ios",
    "appVersion": { "min": "8.10.0", "max": "8.19.99" },
    "osVersion": { "min": "16.0", "max": null },
    "schemaVersion": { "min": "3", "max": "3" },
    "requiredCapabilities": [
      "top_bar@1", "shortcut_shelf@1", "account_card@1",
      "card_product@1", "credit_offer@1", "coverage_card@1", "decision_card@1"
    ],
    "priority": 100
  },
  "placements": [ ],
  "checksum": "sha256:...",
  "publishedAt": "2026-09-09T20:00:00Z",
  "publishedBy": "checker.matricula",
  "madeBy": "maker.matricula",
  "publishRequestId": "pr_01J..."
}
```

Dois modos de modelar divergência iOS/Android — escolher **um** e não misturar:

**Modo A — spec monoplataforma (recomendado no MVP)**

- `spec_home_ios_current` e `spec_home_android_current` são documentos irmãos.
- Compartilham `skeletonId` e types do catálogo.
- Cada um tem o JSON que aquela loja realmente entende (copy/rota/campos de conteúdo que divergem). Haptic/ripple/cor
  **não** viajam no JSON — ficam no Design System nativo.
- Publish em lote: um `publish_request` pode referenciar o par, para não dessincronizar produto.
- Simples de versionar, simples de dar rollback por plataforma, simples de diferenciar campos.

**Modo B — spec base + overlay**

- Um spec `platformScope: "shared"` + dois overlays JSON Patch (RFC 6902).
- Materializa em `spec_materialized` no approve.
- Menos duplicação de copy. Mais complexidade no diff e no maker-checker (“o checker está aprovando a base ou o
  overlay?”).

Recomendação Staff: **Modo A no MVP**. A Home tem poucas dezenas de seções, não mil templates. Duplicar o documento e
garantir diff lado a lado é mais barato do que um motor de patch no caminho quente. Se a duplicação doer depois,
introduz-se Modo B sem mudar o envelope do app — o app só vê `spec_materialized`.

### 7.4 Índices mínimos

```
pointers:        { surface:1, platform:1, channel:1 } unique
specs:           { specId:1, revision:-1 } unique
specs:           { status:1, surface:1, "targeting.platform":1, "targeting.priority":-1 }
specs:           { status:1, surface:1, "targeting.platform":1, "targeting.appVersion.min":1, "targeting.appVersion.max":1 }
spec_materialized: { specRevisionId:1, platform:1 } unique
diffs:           { specId:1, fromRev:1, toRev:1 } unique
audit_log:       { ts:-1 }, { actor:1, ts:-1 }, { specId:1, ts:-1 }
publish_requests:{ status:1, surface:1 }
```

Comparação semver **não** é query Mongo pura (`"8.10.0" < "8.9.99"` em string quebra). Por isso:

- persistir também `appVersionMinOrdinal` e `appVersionMaxOrdinal` (inteiro `major*1_000_000 + minor*1_000 + patch`);
- filtrar por ordinal no índice;
- confirmar semver no Java depois do fetch (defesa).

### 7.5 Transações

Approve e rollback tocam `pointers` + `publish_requests` + `audit_log` (+ `idempotency`). Usar transação multi-doc Mongo
(replica set). DocumentDB: validar suporte no cluster alvo; se não houver, usar compare-and-set em `pointers.version` +
outbox para o audit.

`@Transactional` **não** vai em controller. Serviço de publish é o único que abre transação. Compose **não** abre
transação.

**Como isso é implementado (ADR-003).** O caso de uso de publish/rollback vive na camada de aplicação, que é mantida
livre de Spring. Ele depende de uma porta pura:

```java
public interface TransactionalUnitOfWork {
    <T> T execute(Supplier<T> work);
}
```

A implementação anotada com `@Transactional` mora no adapter Mongo (`MongoTransactionalUnitOfWork`) e é a **única
exceção nominal** à regra "`@Transactional` não aparece em adapters" — exceção por nome de classe, não por pacote, para
aparecer no grep e na revisão. Se o cluster alvo for DocumentDB sem transação multi-documento, a segunda implementação
da mesma porta usa compare-and-set em `pointers.version` + outbox para o audit, sem alterar o caso de uso.

---

## 8. Cache (Redis, mesma AZ)

Três chaves, nenhuma com PII, nenhuma com mídia binária.

| Chave                                                                          | TTL            | Escrita                    | Leitura              |
|--------------------------------------------------------------------------------|----------------|----------------------------|----------------------|
| `sdui:spec:{specRevisionId}:{platform}`                                        | até invalidate | write-through no approve   | seleção              |
| `sdui:tree:{surface}:{platform}:{schema}:{appMajorMinor}:{capsHash}:{channel}` | 30–90 s        | write-through após compose | hit quente           |
| `sdui:section:{proj}:{id}`                                                     | curto          | write-through da projeção  | hidratação           |
| `sdui:lastgood:{surface}:{platform}:{channel}`                                 | longo          | só quando compose 200      | fallback             |
| `sdui:sf:{treeKey}`                                                            | segundos       | SET NX                     | singleflight do miss |

Regras:

- Caps hash = sha256 ordenado de `Component-Capabilities`. Evita explosão por ordem de header.
- App na chave é `major.minor`, não patch, salvo se o targeting usar patch. Reduz cardinalidade.
- **Não** colocar userId na chave. Este MS não personaliza por cliente.
- Singleflight: o primeiro miss popula; os demais esperam o mesmo future (virtual thread + `CompletableFuture` na chave,
  com timeout). Sem pool de VT.
- Miss estourado (dependência lenta ou Redis down): devolver `lastgood` com `envelope.fallback=true` e hidratar em
  background. Nunca 500 por timeout de seção.
- Invalidate: approve/rollback apaga `sdui:spec:*` da revisão nova/antiga e as `sdui:tree:*` daquele
  `surface+platform+channel`. Não flush global.

Métricas obrigatórias: `compose.hit`, `compose.miss`, `compose.fallback`, `compose.singleflight.wait`, `payload.bytes`,
`section.<tipo>.ms`, `serialize.ms`, tags `schemaVersion`, `appVersion`, `surface`, `platform`.

---

## 9. Rollback facilitado

Rollback **não** é “editar o JSON de volta”. É mover o pointer.

```
pointers {
  surface: "home",
  platform: "ios",
  channel: "stable",
  specId: "spec_home_ios_current",
  specRevisionId: "spec_home_ios_current#12",
  previousSpecRevisionId: "spec_home_ios_current#11",
  version: 44
}
```

Operação `rollback`:

1. Idempotency-Key obrigatória.
2. Checker (não o maker da revisão vigente, se política exigir).
3. `pointers.specRevisionId ← previousSpecRevisionId` (ou revisão alvo explícita, desde que `PUBLISHED` e targeting
   ainda válido).
4. Empilha `previous` para permitir roll-forward.
5. Audit + evento `sdui.pointer.rolled_back`.
6. Invalida Redis daquele `surface+platform+channel`.
7. Apps no próximo GET já saem na revisão antiga. Quem tem ETag velho recebe 200 com corpo novo (ou 304 se coincidir).

Por plataforma. Rollback iOS não mexe Android. Isso é requisito, não detalhe: incidente de campo iOS-only é o caso
Zalando/TNA que o plano quer evitar.

Canary: pointer `channel=canary` aponta para a revisão nova; `stable` permanece. Promote = copiar o revisionId de canary
para stable. Rollback de canary é barato e não atinge a frota.

Tempo alvo de rollback **até a borda do MS**: < 30 s (approve + invalidate + TTL residual da árvore). Se o TTL da árvore
for 90 s, aceitar janela ou forçar `UNLINK` das chaves `sdui:tree:*` por scan de prefixo curto (prefixo inclui
surface+platform+channel — cardinalidade baixa).

Esse número **não é o tempo que o usuário leva para parar de ver a Home ruim**. O app só enxerga a revisão restaurada no
próximo GET, e quando esse GET acontece é decisão do cliente, não do servidor. O tempo real é
`tempo de pointer + TTL residual + gatilho de refresh do cliente` (§14 item 9). Os dois tempos têm nomes distintos e são
medidos separadamente na H13/H18. Confundi-los faz o pós-incidente reportar 30 s para uma exposição que pode ter durado
minutos.

---

## 10. Rastreabilidade (o que mudou da última para a atual)

Toda transição `PUBLISHED` gera um documento em `diffs` **no approve**, não no request do app.

Conteúdo do diff (estrutural, não um `diff -u` opaco):

```json
{
  "specId": "spec_home_ios_current",
  "fromRevision": 11,
  "toRevision": 12,
  "skeleton": {
    "from": 4,
    "to": 4,
    "slotsAdded": [],
    "slotsRemoved": [],
    "slotsReordered": false
  },
  "sections": {
    "added":    [ { "id": "sec_foryou_ipva", "slot": "foryou", "type": "decision_card@1" } ],
    "removed":  [ { "id": "sec_offer_old", "slot": "offers", "type": "credit_offer@1" } ],
    "moved":    [],
    "changed":  [
      {
        "id": "sec_shortcuts_1",
        "slot": "shortcuts",
        "fields": [
          { "path": "/props/items/1/label", "from": "Crédito", "to": "Crédito fácil" },
          { "path": "/actions/1/payload/route", "from": "app://credit", "to": "app://credit?src=home" }
        ]
      }
    ]
  },
  "targeting": {
    "changed": [ { "path": "/appVersion/min", "from": "8.10.0", "to": "8.12.0" } ]
  },
  "checksumFrom": "sha256:...",
  "checksumTo": "sha256:..."
}
```

Regras:

- Diff é de spec materializado por plataforma. “O que o iOS passou a ver” é uma pergunta respondível sem abrir dois
  JSONs na mão.
- `GET /admin/v1/specs/{id}/revisions/{from}..{to}/diff` lê `diffs`. Se `from` e `to` não forem adjacentes, o servidor
  **encadeia** diffs adjacentes ou gera um diff sob demanda entre snapshots (os snapshots permanecem).
- Audit log guarda o evento, não o diff inteiro. Diff grande vive em `diffs`.
- Authoring UI (fase 3) mostra o diff no ticket do checker. Checker não aprova no escuro — lição PhonePe.

Não usar Git como fonte da verdade de runtime. Git pode versionar o *seed* do catálogo e skeletons iniciais. Runtime é
Mongo + pointer.

---

## 11. Pipeline de compose (request quente)

Código mental — Java 25, virtual threads, sem preview (`StructuredTaskScope` fora).

```
Negotiate
  └─ parse headers, semver, caps (fail-fast 400 se header obrigatório ausente)

Select
  └─ pointer → candidatos (Redis spec / Mongo) → targeting → specRevisionId

Load
  └─ spec_materialized[specRevisionId, platform]   // write-through

Filter
  └─ drop section se type@version ∉ caps
  └─ drop section se flag da superfície/slot estiver off
     (flag lida de config local / sidecar; timeout curto; default = keep last)

Hydrate
  └─ por seção, I/O paralelo em VT com timeout próprio (ex.: 80 ms)
  └─ sem N+1; lote quando a projeção for a mesma
  └─ seção lenta/errada some; não derruba a tela
  └─ este MS não chama domínio de apólice

Envelope
  └─ hashes, etag, fallback=false, payload.bytes

Write caches
  └─ tree + lastgood
```

Resiliência (instruções do projeto):

- Circuit breaker + timeout em cada dependência (Redis, Mongo, flag service, CDN metadata se houver).
- I/O com try-with-resources e tratamento terminal.
- `CompletableFuture` só na borda async, sempre com `.exceptionally()` / `.handle()`.
- Contexto: `ThreadLocal` para `surface`, `platform`, `specRevisionId`, com `remove()` garantido no `finally` da borda
  da request. Em Java 25, `ScopedValue` já é permanente (JEP 506), mas continua fora deste MS: dados funcionais devem
  viajar por parâmetros explícitos e o contexto de observabilidade permanece encapsulado na borda HTTP.
    - Componente nomeado (ADR-002): `ComposeTraceContext`, na camada HTTP, aberto e fechado no
      `CorrelationIdInterceptor`. Escopo **exclusivo de observabilidade** — MDC de log, tag de métrica, atributo de
      span. Dado funcional continua viajando por parâmetro explícito no `ComposeRequest`: quem precisa de `platform`
      para decidir algo recebe `platform`, não lê do `ThreadLocal`. Regra de arquitetura: nenhuma classe de domínio ou
      de aplicação referencia `ComposeTraceContext`.
- Sem `@Transactional` no compose.
- Rate limit no compose.

SLO interno: se Mongo/Redis da spec falhar, lastgood. Se lastgood não existir (boot frio), 503 com corpo estável e
retry-after. App deve ter skeleton local mínimo (splash + atalhos estáticos) — isso é contrato com o time mobile, não
código deste MS.

---

## 12. Observabilidade e auditoria regulada

Métricas (Micrometer): as do §8 + `select.candidates`, `select.ms`, `publish.approve.ms`, `rollback.ms`.

Logs: JSON estruturado com `specRevisionId`, `platform`, `schemaVersion`, `appVersion`, `fallback`, `payloadBytes`. Sem
token, sem identificador de cliente regulado.

Tracing: um span `sdui.compose` + filhos `sdui.select`, `sdui.hydrate.{type}`.

Auditoria (persona regulada):

- `audit_log` append-only: actor, role, action, specId, fromRev, toRev, ip, ts, requestId.
- Retention alinhada à política da empresa (não inventar prazo aqui — confirmar com compliance).
- Maker-checker obrigatório para `PUBLISHED` e para rollback de `stable`.
- Channel `internal` pode ter atalho de publish para dev, nunca para `stable`.

LGPD: catálogo e spec não carregam CPF, apólice, placa, endereço. Imagem de campanha é URL de CDN. Se alguém tentar
colar dado de cliente numa prop, o linter do publish rejeita padrões óbvios (não é DLP completo; é cerca).

---

## 13. Stack e módulos

### 13.1 Runtime

- Java 25 LTS Temurin
- Spring Boot 4.1.1 (nunca 3.x)
- Spring Web MVC + virtual threads (`spring.threads.virtual.enabled=true`)
- Spring Data MongoDB
- Spring Data Redis
- Resilience4j — **adiado, não entra no MVP (ADR-006)**. Sem client HTTP de hidratação não há consumidor de circuit
  breaker, e o `resilience4j-bom:2.4.0` não gerencia o artefato `resilience4j-spring-boot4`, então a versão teria de ser
  explícita de qualquer forma. No MVP: timeout por section com `Future.get(timeout)` em virtual thread, bounded fan-out
  com `Semaphore`, timeout de Mongo/Redis na configuração do driver, rate limit do compose distribuído no Redis (o
  `RateLimiter` do Resilience4j é in-process e não atenderia). Gatilho de reintrodução: o primeiro `SectionHydrator` com
  client HTTP real — nesse momento, versão explícita e `spring-boot-starter-aspectj` (no Boot 4 o
  `spring-boot-starter-aop` foi renomeado). Avaliar antes se `@Retryable`/`@ConcurrencyLimit` nativos do Boot 4 já
  bastam.
- Micrometer + tracing
- spring.mvc.apiversion.* conforme §4.1

Não: WebFlux neste MS (I/O bound cabe em VT + MVC). Não: preview flags (inclui `ScopedValue`, ver §11 — só finaliza no
Java 25).

### 13.2 Pacotes (simples, sem hexágono)

```
com.empresa.sdui
  api              # controllers compose + exception handler
  api.admin        # governança
  compose          # Negotiate, Select, Filter, Hydrate, Envelope
  catalog          # component types, props schema
  skeleton         # slots
  spec             # draft, publish, pointer, rollback
  diff             # gerador e leitura de diffs
  cache            # redis keys, singleflight
  targeting        # semver + ordinal + capabilities
  audit
  support          # semver, hash, clock
```

Composer não conhece MongoTemplate. Repositórios são finos.
O contrato interno usa `data class` imutáveis.
Hierarquias fechadas, como `Action`, usam `sealed interface` ou `sealed class`;
estados estáveis como `PublishStatus` e `Platform` usam `enum class`.

**Divergência aberta com a pré-arquitetura (ADR-001).** O `pre-arquitetura-sdui-home.md` propõe **sete módulos Maven**
(`contract`, `core`, `orchestrator`, `adapters`, `api`, `bootstrap`, `integration-test`) com `port/in` e `port/out` —
Ports & Adapters no vocabulário canônico, o que este parágrafo e a premissa 12 do §0 excluíram.

A decisão ainda não está fechada. Estado atual:

| Opção                                                       | Custo                                                              | Quando faz sentido                              |
|-------------------------------------------------------------|--------------------------------------------------------------------|-------------------------------------------------|
| Pacotes achatados num artefato (este §13.2)                 | Menor. Fronteira garantida por ArchUnit sobre pacotes              | Time pequeno, ownership único                   |
| Três módulos: `sdui-contract`, `sdui-app`, `sdui-bootstrap` | Baixo. Isola a fronteira de processo e o único executável          | **Recomendação atual**                          |
| Sete módulos (pré-arquitetura)                              | Alto: sete POMs, build mais lento, assinatura atravessando módulos | Squads distintas com deploy/ownership separados |

Com um único engenheiro de backend, o benefício de fronteira compilada por módulo não se materializa e o ArchUnit já
previsto cobre a mesma proteção. A recomendação é colapsar para três módulos: `sdui-contract` separado porque é a única
fronteira que outro processo consome, `sdui-bootstrap` separado para manter "só um módulo tem `@SpringBootApplication`",
todo o resto em pacotes.

**Enquanto não houver decisão, este §13.2 e o §2/§3/§9 da pré-arquitetura estão em conflito aberto.** Fechar antes do
primeiro PR de código, no mesmo commit que reescreve o documento perdedor.

### 13.3 O que não entra no MVP de código

- Console drag-and-drop (PhonePe). Admin HTTP + Bruno/JSON resolve a primeira Home. **Gatilho de reentrada:** reabrir
  quando (a) houver 10 revisões publicadas em `stable` sem nenhuma alteração de catálogo, skeleton ou
  `UI-Schema-Version` — sinal de que o schema parou de se mexer — **e** (b) existir um checker de produto nomeado
  (ADR-008) operando o diff há pelo menos um ciclo de publicação. Ferramenta de autoria sobre schema instável reescreve
  todo layout salvo a cada mudança de contrato; e console sem checker nomeado só troca quem edita no escuro.
- A/B genérico. Channel canary basta.
- Protobuf.
- Personalização por usuário.
- Segundo surface.

---

## 14. Integração com o app (contrato móvel)

O time mobile precisa entregar, **antes** do primeiro compose em produção:

1. Registry dos types do MVP com `typeVersion=1`: `top_bar`, `shortcut_shelf`, `account_card`, `card_product`,
   `credit_offer`, `coverage_card`, `decision_card`.
2. Capabilities efetivas via matriz servidor `(platform, Client-Version)`; header `Component-Capabilities` é delta, não
   lista completa.
3. Renderer que **ignora** prop desconhecida e **não crasha** se uma section some.
4. Cache local da última árvore boa (espelho do `lastgood` do servidor) + uso de `skeletonHash` / ETag. A chave do cache
   persistido **inclui o `UI-Schema-Version` que o binário fala**. Bump de schema invalida todo o cache local no próximo
   launch, sem lógica de migração. Sem isso, um binário novo lê do disco uma árvore composta para o schema anterior e a
   entrega ao renderer novo — falha que acontece antes de qualquer GET e que o `lastgood` do servidor não cobre.
5. Skeleton estático de emergência no binário (atalhos mínimos).
6. Telemetria: `sdui_home_composed`, `sdui_section_shown`, `sdui_action_tapped`, `sdui_conceal_toggle`,
   `sdui_decision_reject`, `sdui_section_omitted`, `sdui_render_error` — sem PII.

   Todo evento de section carrega, além do que já vem em `sections[].analytics` (`component`, `componentVersion`,
   `slot`, `sectionId`, `specRevisionId`), as dimensões que só existem no envelope e que o app precisa mesclar no
   evento: `schemaVersion`, `appVersion`, `build`, `platform`, `channel`. `sdui_render_error` usa exatamente o mesmo
   conjunto de chaves, mais o estágio da falha (`unknown_type`, `invalid_props`, `runtime`).

   O motivo é operacional e não analítico: sem essas dimensões no mesmo evento não existe a célula
   `type@typeVersion × appVersion × platform`, e sem essa célula ninguém consegue responder "qual componente, em qual
   versão de schema, em qual binário está falhando" — a pergunta que o Netflix trata como pré-requisito de velocidade.
   Em SDUI dois usuários no mesmo `Client-Version` veem telas diferentes, então a versão do app sozinha não identifica o
   defeito.
7. Olho de saldo/cartão: alterna `valueDisplay` ↔ `valueDisplayRevealed` localmente; não pede toggle ao MS.
8. Deep links: só `app://` do registry; fixtures do JSON de exemplo devem ser trocados pelos destinos reais da
   aplicação.
9. Política de refresh, fechada e igual nas duas plataformas: refetch em foreground do app, em focus da Home, em
   pull-to-refresh e na expiração do stale time local. O stale time local é **menor ou igual** ao TTL da árvore no
   servidor (30–90 s, §8), nunca maior. O app **não** recarrega a Home sozinho em background enquanto o usuário interage
   com ela: troca de árvore no meio de um toque é regressão de UX, não atualização.

   Esta política é o denominador do tempo real de rollback (§9). Enquanto ela não estiver escrita e implementada igual
   nas duas lojas, o alvo de "< 30 s" mede apenas a borda do MS e ninguém sabe por quanto tempo a Home ruim permaneceu
   em campo.

Sem isso o servidor versiona no vazio. Lição Joud: o contrato de fallback precisa estar no QA do app, não só no MS.

iOS e Android implementam o **mesmo** envelope. Divergência de conteúdo vive no documento monoplataforma (Modo A).
Haptic/ripple/cor ficam no binário.

---

## 15. Fases de implementação

Cada fase termina com critério de aceite testável. Não começar a fase N+1 sem o aceite.

### Fase 0 — Contrato e esqueleto do repo (3–5 dias)

- Envelope, headers, semver, capabilities hash.
- Coleções Mongo vazias + índices.
- `GET /v1/surfaces/home` devolvendo fixture em memória.
- Testes de negociação (400/200/omit section).
- Aceite: app iOS e Android renderizam a fixture.

### Fase 1 — Catálogo + skeleton + spec monoplataforma

- CRUD admin de catalog/skeleton/spec em rascunho.
- Validação propsSchema + allowedTypes.
- Dois specs irmãos (ios, android) alinhados ao catálogo da Home (types §6.2).
- Seed inicial = `contrato-sdui-home-definitivo.json` (iOS).
- Aceite: publish manual via Mongo/Bruno gera documento válido; compose lê do Mongo; app iOS pinta a fixture.

### Fase 2 — Targeting min/entre/max + pointers

- Ordinal semver, seleção determinística, fixtures para 8.9 / 8.14 / 8.21 em cada loja.
- Pointer stable por plataforma.
- Aceite: matriz de testes (plataforma × appVersion × caps) 100% determinística.

### Fase 3 — Maker-checker, diff, rollback

- `publish_requests`, approve/reject, diffs estruturais, rollback de pointer, audit_log.
- Idempotency no approve/rollback.
- Aceite: checker vê diff N-1→N; rollback iOS não altera Android; audit consulta por specId.

### Fase 4 — Cache e SLO

- Redis write-through, singleflight, lastgood, métricas, 304/ETag.
- Teste de carga do compose (P99 interno ≤ 400 ms no hit).
- Aceite: kill Redis → lastgood; approve → invalidate visível < 30 s.

### Fase 5 — Hidratação e flags

- Fan-out de projeções (copy, URL absoluta), timeout por section, circuit breaker.
- Flag só para existência de slot/superfície.
- Aceite: seção lenta some; tela sobrevive; payload sem PII.

### Fase 6 — Canary e operate

- Channel canary, promote, dashboards, runbook de rollback.
- Seed da Home real com o Design System vigente.
- Aceite: campanha sobe em canary iOS, rollback em < 30 s, stable Android intacto.

Fora desta escada, de propósito: console visual, A/B statistico, Protobuf, segunda surface.

---

## 16. Matriz de testes que o plano exige (não é opcional)

| Caso                                  | Esperado                                                                                                     |
|---------------------------------------|--------------------------------------------------------------------------------------------------------------|
| iOS 8.9.1 + schema 3 + caps velhas    | spec `legacy`, sections novas omitidas                                                                       |
| iOS 8.14.2 + schema 3 + caps current  | spec `current`                                                                                               |
| iOS 8.21.0 + schema 3 + caps next     | spec `next`                                                                                                  |
| Android 8.14.2 equivalente            | spec android current, **JSON pode ter campos que o iOS não tem**                                             |
| Capability sem `decision_card@1`      | slot foryou omitido (`omitted.reason=unknown_type`), 200                                                     |
| Capability sem `credit_offer@1`       | slot offers vazio / omitido, 200                                                                             |
| Header `Client-Platform` ausente      | 400                                                                                                          |
| App 7.0.0 fora de qualquer faixa      | 200 lastgood da mesma plataforma ou skeleton mínimo (`empty_guard`); app também tem Home estática no binário |
| Approve concorrente do mesmo rascunho | um vence, outro 409                                                                                          |
| Rollback sem checker                  | 403                                                                                                          |
| Redis down                            | lastgood + `fallback=true`                                                                                   |
| Seção de hidratação 200 ms            | seção omitida, resto 200                                                                                     |
| Diff 11→12                            | lista added/removed/changed fiel ao snapshot                                                                 |

Comparação iOS vs Android: mesmo envelope; teste de contrato falha se qualquer lado receber campo de aparência (color,
width, haptic, ripple, shimmer) ou type fora do catálogo.

---

## 17. Riscos e decisões já tomadas

| Risco                                               | Mitigação                                                               |
|-----------------------------------------------------|-------------------------------------------------------------------------|
| DSL genérico demais (Fluid v1, HubFramework, TNA)   | Types semânticos + layout no slot                                       |
| Explosão schema × componente × flag × plataforma    | Eixos independentes; flag não gera spec                                 |
| Incidente iOS vs Android por JSON único mal testado | Spec monoplataforma + diff por loja + rollback por loja                 |
| P99 estoura no miss                                 | Árvore pré-materializada + Redis + singleflight + lastgood              |
| Publish sem governança                              | Maker-checker + audit append-only                                       |
| Dado regulado no payload                            | Recorte do MS + linter de publish + review de props                     |
| Cardinalidade de cache                              | Chave sem userId, app em major.minor, caps hasheadas                    |
| DocumentDB incompleto vs Mongo 8                    | Preferir Mongo 8.3+; se DocumentDB, spike de transação/índice na Fase 0 |
| Mobile não implementa omit-unknown                  | Fase 0 bloqueada sem isso                                               |

Decisões já tomadas neste plano (para não reabrir em PR):

1. MongoDB 8.3+ como fonte da verdade da spec. Postgres só se compliance exigir réplica de audit.
2. Modo A (spec monoplataforma) no MVP.
3. Pointer + revisão imutável como mecanismo de rollback.
4. Diff estrutural persistido no approve.
5. Compose não remonta Lego no request quente.
6. Headers sem `X-`, nomes já fechados.
7. Um surface: `home`.
8. Sem personalização por usuário neste MS.
9. Catálogo Home MVP fechado: `top_bar`, `shortcut_shelf`, `account_card`, `card_product`, `credit_offer`,
   `coverage_card`, `decision_card` @1.
10. Fio canônico iOS: `artifacts/contrato-sdui-home-definitivo.json`.
11. `Component-Capabilities` é recomendado (delta); matriz servidor é a fonte.
12. Actions fechadas: `navigate` | `open_bottom_sheet` | `track` | `noop`; rota `app://`; CTA com `label`.
13. Jackson 3 (`tools.jackson`) no contrato; Jackson 2 não entra (ADR-005).
14. Resilience4j adiado até existir client HTTP de hidratação (ADR-006).
15. Escada de resposta do compose termina em `503` + `Retry-After` quando não há árvore nem `lastgood`; nunca `500`,
    nunca `404` (ADR-007).
16. `Screen` formalizado no §2; `Fragment` fora do MVP (ADR-004).
17. Transação de publish por porta `TransactionalUnitOfWork`, com exceção ArchUnit nominal (ADR-003).
18. Contexto de trace por `ComposeTraceContext`, só observabilidade (ADR-002).

Os ADRs completos, com contexto e consequência, vivem no §16 de `pre-arquitetura-sdui-home.md`. Divergência em relação a
este plano exige ADR antes de virar código.

Decisões em aberto (não inventar):

- **Estrutura de módulos**: sete módulos da pré-arquitetura vs. os três recomendados no §13.2 (ADR-001).
- **Quem é o checker** do maker-checker, com um único engenheiro de backend (ADR-008). A regra "maker ≠ checker" não
  deve ser desligada no código: a saída é atalho de publish em `internal` para o dia a dia — já autorizado no §12 — e um
  par de produto como checker em `stable`, já que aprovar diff de Home é decisão de produto. Conta de serviço aprovando
  sozinha está descartada: transforma a trilha de auditoria em ficção.

- Retention exacta do `audit_log` (compliance).
- Se app abaixo de qualquer `min` recebe 200 lastgood ou skeleton mínimo, nunca 404 da Home.
- Se authoring vive no mesmo deploy ou em perfil `admin` isolado na rede.
- Se DocumentDB é constraint de nuvem ou se Mongo Atlas/on-prem é aceitável.
- Mapa final de deep links `app://` com o time mobile (fixtures do JSON não são produção).
- Android: segundo payload/pointer (mesmo catálogo; foco atual é iOS).

---

## 18. Ordem do primeiro PR de código

Quando formos implementar, o primeiro PR não é o composer completo. É:

1. Módulo `targeting` (semver + ordinal + capabilities) com testes.
2. Envelope em `data class` Kotlin + serialização JSON gerenciada pelo Spring Boot/Jackson.
3. `GET /v1/surfaces/home` com fixture e headers reais.
4. Coleções e índices Mongo.
5. Só então Select + pointer.

Qualquer PR que comece por “framework de widget genérico” está fora deste plano.

---

## 19. Referências de trabalho

Consultar, não copiar:

- `artifacts/contrato-sdui-home-definitivo.json` — fio iOS (envelope + skeleton + sections).
- `artifacts/resumos-server-driven-ui.md` — síntese cruzada (Airbnb section, Joud quê/como, Fowler toggles).
- `artifacts/instrucoes-projeto.md` — stack, envelope, SLO, proibições.
- Skill `skills/sdui-backend/` — fallback 0–7, actions, versionamento.
- Paper local `Explorando a Arquitetura de Server Driven UI.pdf` — Beagle; lacunas; SDUI + BFF + flags.
- Fowler Feature Toggles; Circuit Breaker; Template/Transform/Two Step View; Application Controller; DTO.

Este documento é o mapa. Implementação seguinte deve caber nele ou atualizar este arquivo no mesmo PR em que a decisão
mudar.

*Última atualização tecnológica e alinhamento ao contrato Home iOS: 2026-09-16.*

*ADR-001 a ADR-008 incorporados: 2026-09-15.*
