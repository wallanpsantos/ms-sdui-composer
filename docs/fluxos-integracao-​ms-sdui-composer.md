# Fluxos de integração — ms-sdui-composer

**ms-sdui-composer** = o serviço que, a cada request, compõe a árvore de UI da surface a partir de uma spec versionada, do contexto do cliente e das capabilities, devolvendo um envelope pronto e seguro para o app.

> **Status:** proposta de arquitetura para alinhamento técnico.
>
> **Escopo:** `ms-sdui-composer` atendendo iOS e Android por meio de um **serviço consumidor/BFF Mobile** (com a Home como primeira surface). O `ms-sdui-composer` não é chamado diretamente pelo app neste desenho e não consulta domínios de negócio.

---

## 1. Visão geral

O Mobile chama o **Serviço Consumidor**, que autentica/orquestra a request e chama o **ms-sdui-composer**. O ms-sdui-composer compõe somente a estrutura e o conteúdo de apresentação permitidos no contrato; o Serviço Consumidor devolve o envelope ao Mobile.

O ms-sdui-composer é **stateless**. MongoDB é a fonte da verdade de catálogo, skeleton, specs, pointers, revisões, diffs e auditoria. Redis é cache de spec, árvore, projeções, singleflight e última árvore boa.

```mermaid
flowchart LR
    IOS[iOS App]
    AND[Android App]
    BFF[Serviço Consumidor / BFF Mobile]
    SDUI[ms-sdui-composer]
    REDIS[(Redis - mesma AZ)]
    MONGO[(MongoDB 8.3+)]

    IOS -->|REST JSON + contexto do cliente| BFF
    AND -->|REST JSON + contexto do cliente| BFF
    BFF -->|GET /v1/surfaces/home + headers negociados| SDUI
    SDUI --> REDIS
    SDUI --> MONGO
    SDUI -->|Envelope SDUI Home| BFF
    BFF -->|REST JSON| IOS
    BFF -->|REST JSON| AND
```

### Responsabilidades

| Camada | Responsabilidade | Não faz |
|---|---|---|
| Mobile iOS/Android | Envia identidade/capabilities, mantém cache local, renderiza sections conhecidas e despacha actions | Escolher spec, definir composição ou interpretar dados de domínio para a Home SDUI |
| Serviço Consumidor / BFF Mobile | Autentica, propaga contexto de negociação, chama o ms-sdui-composer e devolve a resposta ao app | Alterar, montar ou injetar sections no payload do SDUI sem contrato explícito |
| ms-sdui-composer | Negocia, seleciona, filtra, hidrata projeções de apresentação, envelopa, faz fallback e controla cache | Consultar contrato, cliente, apólice, sinistro ou outro domínio de negócio |
| MongoDB | Fonte da verdade de specs, catálogo, skeleton, pointers, revisão, diff, publish e auditoria | Servir árvore hidratada por usuário como cache |
| Redis | Cache e proteção de P99 | Guardar PII, dados regulados ou mídia binária |

---

## 2. Fluxo de request

O Serviço Consumidor deve propagar os headers de negociação sem criar nomes com prefixo `X-`. O contrato HTTP usa `API-Version` para a API do MS e `UI-Schema-Version` como eixo independente do contrato de UI.

```mermaid
sequenceDiagram
    autonumber
    participant M as Mobile iOS / Android
    participant C as Serviço Consumidor
    participant S as ms-sdui-composer
    participant R as Redis
    participant D as MongoDB

    M->>C: GET Home + contexto do app
    Note over M,C: platform, app version, build, SO,<br/>schema, locale, capabilities
    C->>S: GET /v1/surfaces/home
    Note over C,S: API-Version, UI-Schema-Version,<br/>Client-Platform, Client-Version,<br/>Client-Build, Accept-Language,<br/>OS-Version, Component-Capabilities

    S->>S: 1. Negotiate
    S->>R: Buscar árvore por treeKey

    alt Cache hit
        R-->>S: Árvore cacheada
        S->>S: 6. Envelope + ETag
    else Cache miss
        S->>S: 2. Select
        S->>R: Buscar spec cacheada
        alt Spec cache miss
            S->>D: Buscar pointer + candidatas PUBLISHED
            D-->>S: Pointer e specs
            S->>R: Cache write-through da spec
        else Spec cache hit
            R-->>S: Spec materializada
        end
        S->>S: 3. Resolve spec monoplataforma
        S->>S: 4. Filter por capabilities efetivas
        S->>S: 5. Hydrate projeções permitidas
        S->>R: Gravar árvore e lastgood quando 200
        S->>S: 6. Envelope + ETag
    end

    S-->>C: 200 JSON ou 304
    C-->>M: 200 JSON ou 304
    M->>M: Registry + renderer nativo + dispatcher de actions
```

### Headers propagados pelo Serviço Consumidor

| Header | Obrigatório | Uso no SDUI |
|---|---:|---|
| `API-Version` | Sim | Versão HTTP do MS; no MVP é `1` |
| `UI-Schema-Version` | Sim | Versão do envelope/contrato UI |
| `Client-Platform` | Sim | `ios` ou `android`; escolhe a família de spec/pointer |
| `Client-Version` | Sim | Seleção por faixa semver da plataforma |
| `Client-Build` | Sim | Desempate e elegibilidade de canary |
| `Accept-Language` | Sim | Locale da copy já resolvida |
| `OS-Version` | Recomendado | Targeting fino por SO |
| `Component-Capabilities` | Recomendado | Delta de capabilities; não substitui a matriz do servidor |
| `If-None-Match` | Opcional | Revalidação da representação e retorno 304 |

> O Serviço Consumidor não deve alterar os valores de platform, versão, build, schema ou capabilities recebidos do Mobile. Se precisar validar autenticidade desses dados, essa validação pertence à sua borda de segurança antes da chamada ao SDUI.

---

## 3. Montagem da Home

A composição no MS segue sempre esta sequência. O composer não monta a tela a partir de dados de domínio no request quente; ele seleciona uma árvore previamente validada, remove sections não compatíveis e hidrata apenas projeções permitidas.

```mermaid
flowchart TD
    A[Request do Serviço Consumidor] --> B[Negotiate]
    B --> B1[Validar headers e normalizar contexto]
    B1 --> C[Select]
    C --> C1[Ler pointer: surface + platform + channel]
    C1 --> C2[Buscar specs PUBLISHED da mesma plataforma]
    C2 --> C3[Filtrar schema, app version, OS e capabilities]
    C3 --> C4[Ordenar priority DESC + publishedAt DESC]
    C4 --> D[Resolve]
    D --> D1[Carregar spec monoplataforma selecionada]
    D1 --> E[Filter]
    E --> E1[Omitir type@version não suportado]
    E1 --> E2[Registrar omitted com reason fechado]
    E2 --> F[Hydrate]
    F --> F1[Projeções de apresentação com timeout por section]
    F1 --> G[Envelope]
    G --> G1[Envelope + skeleton + sections + analytics]
    G1 --> H[Resposta 200 ou 304]
```

### Regras de sections

O catálogo Home MVP é semântico e compartilhado por iOS/Android:

```text
top_bar@1
shortcut_shelf@1
account_card@1
card_product@1
credit_offer@1
coverage_card@1
decision_card@1
```

O skeleton ordena os slots: `header`, `shortcuts`, `accounts`, `cards`, `offers`, `coverage` e `foryou`. Cada slot define `allowedTypes` e máximo de instâncias. Uma placement inválida é bloqueada no publish; uma section não suportada pelo cliente é omitida no compose.

```mermaid
flowchart LR
    SKEL[Skeleton home.default] --> H[header<br/>top_bar]
    SKEL --> SH[shortcuts<br/>shortcut_shelf]
    SKEL --> AC[accounts<br/>account_card]
    SKEL --> CA[cards<br/>card_product]
    SKEL --> OF[offers<br/>credit_offer]
    SKEL --> CO[coverage<br/>coverage_card]
    SKEL --> FY[foryou<br/>decision_card]

    CAP[Capabilities efetivas do cliente] --> FILTER[Filter]
    H --> FILTER
    SH --> FILTER
    AC --> FILTER
    CA --> FILTER
    OF --> FILTER
    CO --> FILTER
    FY --> FILTER
    FILTER --> OUT[Sections renderizáveis]
```

### Quando um componente não aparece

Uma section pode não aparecer na Home por dois motivos distintos:

1. **A spec não a posiciona:** não existe placement para aquele slot/type na revisão selecionada.
2. **O cliente não sabe renderizá-la:** `type@version` não pertence às capabilities efetivas; o MS a omite e preenche `envelope.omitted`.

Não é responsabilidade do Mobile escolher sections. O Mobile recebe o que é compatível com seu binário e renderiza apenas renderers compilados no app.

---

## 4. Versionamento e compatibilidade

Há três eixos independentes. Eles não devem ser reduzidos a um único inteiro, nem ligados por uma matriz combinatória de flags.

```mermaid
flowchart TB
    SCHEMA[UI-Schema-Version<br/>Contrato do envelope]
    COMPONENT[Component type + typeVersion<br/>Renderer conhecido]
    APP[Client-Version + OS-Version + Build<br/>Faixa do app]
    FLAG[Feature flag<br/>Existência de surface/experimento]

    SCHEMA --> SELECT[Seleção e compatibilidade]
    COMPONENT --> SELECT
    APP --> SELECT
    FLAG --> POINTER[Escolhe channel/pointer ou omite section]
    POINTER --> SELECT
```

| Eixo | Exemplo | Regra |
|---|---|---|
| Schema | `UI-Schema-Version: 3` | Muda quando o envelope muda de forma incompatível |
| Component | `account_card@1` | Muda quando o renderer exige prop incompatível nova |
| App/OS | App `8.14.2`, Android `35` | Seleciona spec compatível por faixa da própria plataforma |
| Channel | `stable`, `canary`, `internal` | Seleciona pointer; não é feature flag nem versão de schema |

Form factor, breakpoint, densidade e rotação **não** são eixos. Não entram na seleção, não entram na chave de cache e não viram header. São decisão do cliente no momento do render.

### Seleção por plataforma

```mermaid
flowchart TD
    A[Client-Platform] --> B{ios ou android?}
    B -->|ios| I[Pointer home + ios + channel]
    B -->|android| N[Pointer home + android + channel]
    I --> SI[Specs iOS PUBLISHED]
    N --> SA[Specs Android PUBLISHED]
    SI --> T[Filtrar targeting e capabilities]
    SA --> T
    T --> W[Spec vencedora]
```

- iOS e Android compartilham catálogo, skeleton e regras de compose.
- iOS e Android possuem specs, faixas de versão, pointers, árvores cacheadas, canary e rollback independentes.
- Não comparar semver de iOS com Android.
- No MVP, a estratégia é **spec monoplataforma**, não base + overlay JSON Patch.

---

## 5. Cache, resiliência e fallback

Redis reduz latência e protege o Mongo. As chaves não têm `userId`, pois este MS não personaliza a Home por cliente.

```mermaid
flowchart TD
    REQ[Compose request] --> TREE{Tree cache hit?}
    TREE -->|Sim| RESP[Envelope 200 / ETag]
    TREE -->|Não| SF{Singleflight lock?}
    SF -->|Outro request compõe| WAIT[Aguardar future com timeout]
    WAIT --> TREE
    SF -->|Primeiro request| SEL[Selecionar + filtrar + hidratar]
    SEL --> OK{Compose 200?}
    OK -->|Sim| SAVE[Salvar tree TTL curto<br/>e lastgood]
    SAVE --> RESP
    OK -->|Não / timeout / Redis down| LG{Existe lastgood?}
    LG -->|Sim| FALLBACK[200 + fallback=true<br/>+ fallbackReason]
    LG -->|Não| POLICY[Aplicar escada de fallback definida]
```

| Chave | Conteúdo | Regra principal |
|---|---|---|
| `sdui:spec:{specRevisionId}:{platform}` | Spec publicada/materializada | Write-through no approve; invalida por revisão |
| `sdui:tree:{surface}:{platform}:{schema}:{appMajorMinor}:{capsHash}:{channel}` | Árvore pronta para resposta | TTL curto, 30–90 s |
| `sdui:section:{proj}:{id}` | Projeção de section | TTL curto |
| `sdui:lastgood:{surface}:{platform}:{channel}` | Última árvore 200 válida | Usada em fallback |
| `sdui:sf:{treeKey}` | Coordenação de miss | Evita tempestade de composições |

### Regras de degradação

- Falha de uma section: omitir a section e responder com a tela restante.
- Timeout de hidratação: aplicar timeout por section, sem bloquear toda a Home.
- Miss lento ou Redis indisponível: usar `lastgood` quando disponível, com `fallback: true`.
- Targeting sem spec vigente: retornar 200 com última árvore boa e fallback; nunca 404 para `home`.
- Aprovação ou rollback: invalidar somente as chaves do `surface + platform + channel` e revisões afetadas; nunca executar flush global.

---

## 6. Persistência e governança

MongoDB mantém o ciclo de vida de configuração. O request de compose não modifica specs e não abre transação.

```mermaid
flowchart LR
    CAT[component_catalog] --> SPEC[specs revisionadas]
    SKEL[skeletons revisionados] --> SPEC
    SPEC --> DIFF[diffs N-1 para N]
    SPEC --> PR[publish_requests]
    PR -->|checker aprova| PTR[pointers mutáveis]
    PTR --> CACHE[Redis write-through + invalidação seletiva]
    PR --> AUDIT[audit_log append-only]
    RB[Rollback] --> PTR
    RB --> AUDIT
    RB --> CACHE
```

| Coleção | Papel |
|---|---|
| `component_catalog` | Contrato de cada `type + typeVersion` |
| `skeletons` | Estrutura e slots da Home |
| `specs` | Snapshot de placements, targeting e revisão |
| `pointers` | Revisão vigente por `surface + platform + channel` |
| `publish_requests` | Fluxo maker-checker |
| `diffs` | Diff estrutural entre revisões |
| `audit_log` | Trilha append-only de ações administrativas |
| `idempotency` | Protege approve e rollback contra repetição |

### Publish e rollback

```mermaid
sequenceDiagram
    participant M as Maker
    participant A as Admin SDUI
    participant K as Checker
    participant DB as MongoDB
    participant R as Redis

    M->>A: Criar rascunho / abrir publish request
    A->>DB: Validar skeleton, allowedTypes, props e actions
    A->>DB: Persistir diff N-1 -> N
    K->>A: Aprovar ou rejeitar
    alt Aprovação
        A->>DB: Transação: publicar revisão + mover pointer + audit + idempotência
        A->>R: Write-through spec + invalidar árvores afetadas
    else Rejeição
        A->>DB: Registrar decisão no audit
    end

    K->>A: Rollback com Idempotency-Key
    A->>DB: Transação: mover pointer para revisão anterior + audit
    A->>R: Invalidar cache do escopo afetado
```

#### Invariantes

- Maker não aprova o próprio publish.
- Spec `PUBLISHED` é imutável.
- Rollback não edita JSON: move o pointer.
- Rollback iOS não altera Android; rollback Android não altera iOS.
- `@Transactional` pertence ao serviço de publish/rollback; não ao controller nem ao compose.

---

## 7. Canary e promoção

Canary é um channel com pointer próprio, não uma duplicação de contrato por flag.

```mermaid
flowchart TD
    REQ[Request do Serviço Consumidor] --> BUILD{Build allowlist ou mecanismo interno?}
    BUILD -->|Sim| CANARY[Channel canary]
    BUILD -->|Não| STABLE[Channel stable]
    CANARY --> CPTR[Pointer da plataforma + canary]
    STABLE --> SPTR[Pointer da plataforma + stable]
    CPTR --> COMP[Compose normal]
    SPTR --> COMP
    COMP --> OBS[Observabilidade por revisão/platform/channel]
    OBS --> GATE{Gates atendidos?}
    GATE -->|Sim| PROMOTE[Promover revisão para stable]
    GATE -->|Não| ROLLBACK[Rollback por pointer]
```

### Gates de promoção

- Compatibilidade do binário, schema e capabilities validada.
- Sem aumento operacional material de fallback ou erro.
- Compose em cache hit dentro da meta P99 de até 400 ms.
- SLO fim a fim acompanhado: rede + compose + first paint abaixo de 1200 ms no P99.
- Rollback testado e executável pelo checker autorizado.

---

## 8. Observabilidade

A observabilidade deve permitir identificar uma regressão por plataforma, canal, revisão, app e section sem registrar PII ou props completas.

```mermaid
flowchart LR
    SDUI[ms-sdui-composer] --> MET[Metrics]
    SDUI --> LOG[Logs estruturados]
    SDUI --> TRACE[Tracing]
    MET --> DASH[Dashboard/alertas]
    LOG --> DASH
    TRACE --> DASH

    DASH --> OPS[On-call / time Tech]
    OPS --> RB[Rollback por pointer]
```

### Métricas mínimas

```text
compose.hit
compose.miss
compose.fallback
compose.singleflight.wait
payload.bytes
serialize.ms
section.<tipo>.ms
```

### Tags mínimas

```text
surface
platform
channel
schemaVersion
appVersion
specRevisionId
```

Não usar em métrica/log: nome, identificadores pessoais, valores financeiros individuais, payload integral ou dados regulados.

---

## 9. Fluxos de falha relevantes

### Section falha, Home continua

```mermaid
flowchart LR
    H[Hydrate sections] --> A[Section A OK]
    H --> B[Section B timeout/falha]
    H --> C[Section C OK]
    A --> O[Resposta Home]
    B --> OMIT[Omitir Section B + registrar observabilidade]
    C --> O
    OMIT --> O
```

### Cliente não suporta componente

```mermaid
flowchart LR
    S[Section credit_offer@2] --> C{Capability efetiva contém credit_offer@2?}
    C -->|Sim| R[Manter section]
    C -->|Não| O[Omitir section]
    O --> E[Adicionar item em envelope.omitted]
    R --> OUT[Payload final]
    E --> OUT
```

### Sem spec compatível

```mermaid
flowchart LR
    T[Targeting não encontra spec] --> L{lastgood disponível para platform/channel?}
    L -->|Sim| F[200 + árvore lastgood + fallback=true]
    L -->|Não| P[Aplicar escada de fallback definida pela skill]
```

---

## 10. Decisões para o time

1. O Serviço Consumidor é a borda Mobile neste desenho; o ms-sdui-composer mantém contrato REST/JSON interno e não conversa com domínios.
2. O Serviço Consumidor **propaga** o contexto do app; não escolhe spec e não recompõe payload.
3. iOS e Android usam o mesmo endpoint e catálogo, mas têm specs, pointers, caches e rollbacks isolados.
4. O Mobile precisa implementar omit-unknown e manter renderers nativos compilados para o catálogo suportado.
5. Uma alteração de campo incompatível exige novo schema ou `typeVersion`, conforme o que mudou; não deve ser resolvida por flag.
6. Flags escolhem existência de superfície/experimento ou channel/pointer; não geram matriz `schema × componente × flag × plataforma`.
7. O payload é de apresentação: nenhum dado bruto regulado ou entidade de domínio deve atravessar o contrato SDUI.
8. Antes de produção, o contrato Android deve ser homologado pelo time Mobile Android; o contrato iOS existente é referência estrutural, não uma garantia de campos Android.

---

## 11. Checklist de apresentação

- [ ] Serviço Consumidor definido como caller do ms-sdui-composer e dono da borda Mobile
- [ ] Headers de negociação propagados sem `X-`
- [ ] Contract tests para iOS e Android
- [ ] Matriz de capabilities mantida no servidor por plataforma e versão do app
- [ ] Specs/pointers/cache/lastgood separados por plataforma e channel
- [ ] Maker-checker, diff, audit e idempotência ativos antes de canary
- [ ] ETag/304 e fallback testados
- [ ] SLO e métricas por section/revisão/platform/channel publicados
- [ ] Runbook de rollback validado para iOS e Android
- [ ] Contrato Android homologado antes de publicar spec Android em stable
