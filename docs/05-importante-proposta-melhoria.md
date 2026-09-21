## Visão geral

Os dois artigos descrevem o mesmo padrão arquitetural — Server-Driven UI (SDUI) — com focos complementares: o texto de
Joud Awad é um guia prático de implementação, enquanto o post do Airbnb detalha a plataforma Ghost Platform em produção
para iOS e Android.[^1_1][^1_2]

## Os três conceitos fundamentais (comuns aos dois)

Ambos convergem na mesma tríade:

- **Sections/Components/Blocos:** peças autocontidas de UI (hero banner, card de listing, carrossel, botão CTA). Cada
  section carrega `type` (para o cliente escolher o renderer), `id` (para keying/diffing/A-B) e um `data` blob (strings,
  URLs, números que o componente precisa).[^1_2][^1_1]
- **Screens/Layouts/Surfaces:** composição ordenada de sections — quais sections renderizam, em que ordem, em qual rota.
  A mesma section pode aparecer em Home, Search e Checkout sem duplicação.[^1_1][^1_2]
- **Actions:** intenção de usuário serializada — `navigate`, `openSheet`, `callApi`, `trackEvent`. O cliente não decide
  o que um botão faz; ele lê a action `onTap` e a encaminha a um dispatcher central.[^1_2][^1_1]

## O que SDUI **não** é (ênfase explícita em ambos)

- **Não é “backend envia CSS”.** O cliente possui renderização, gestos, animações, accessibility labels, haptics, dark
  mode e convenções da plataforma. O servidor diz o **quê** mostrar; o cliente decide **como**.[^1_1]
- **Não é micro-frontend.** Sem bundles remotos, sem eval de JavaScript em runtime, sem download de código. O cliente
  shipa com um conjunto fixo de renderizadores de componente compilados no binário; o servidor só envia dados que
  mapeiam para esses renderizadores.[^1_1]

## Padrões de produção destacados (Joud) e como o Airbnb os aplica

Joud sintetiza cinco padrões recorrentes em empresas que operam SDUI em escala; o Airbnb exemplifica vários deles na
Ghost Platform:

- **Sections independentes de tela (Airbnb):** uma section não sabe em qual screen está. Isso permite reuso e A/B sem
  acoplar lógica de screen ao componente.[^1_2][^1_1]
- **Nomenclatura semântica (Lyft/Joud):** nomear componentes pelo conceito de produto (`RideOptionCard`, `PromoBanner`)
  em vez de genéricos (`Row`, `Column`) reduz ambiguidade no contrato, ainda que diminua reuso.[^1_1]
- **Schema first, authoring tool depois (DoorDash/Joud):** endurecer o contrato antes de liberar ferramentas visuais
  para PMs evita churn de schema quebrando layouts salvos.[^1_1]
- **Fallback no envelope (Shopify/Joud):** o payload carrega fallback como campo de primeira classe; assim, é
  estruturalmente impossível shipar uma section sem fallback, protegendo clientes antigos.[^1_1]
- **Observabilidade antes de velocidade (Netflix/Joud):** logar tipo do componente, versão do schema e versão do cliente
  permite diagnosticar “qual schema em qual build” quando algo dá errado — crucial porque dois usuários na mesma versão
  do app podem ver UIs diferentes.[^1_1]

## Contrato e payload (exemplo concreto)

Joud mostra um payload mínimo que ilustra os três conceitos em um único JSON:

```json
{
  "sections": [
    {
      "id": "header-001",
      "type": "HeaderSection",
      "data": {
        "title": "Good evening, Joud",
        "subtitle": "3 trips coming up"
      }
    },
    {
      "id": "banner-001",
      "type": "BannerSection",
      "data": {
        "imageUrl": "https://cdn.example.com/promo-spring.jpg",
        "headline": "Spring stays, 20% off",
        "cta": "Browse deals",
        "onTap": {
          "type": "navigate",
          "route": "search",
          "params": {
            "filter": "spring-promo"
          }
        }
      }
    }
  ],
  "screens": [
    {
      "id": "home",
      "layout": [
        {
          "sectionId": "header-001"
        },
        {
          "sectionId": "banner-001"
        }
      ]
    }
  ]
}
```

O cliente itera `screens[^1_0].layout`, busca cada `sectionId` em `sections`, escolhe o renderer por `type` e passa
`data`. Ao tapar, a ação é roteada ao dispatcher.[^1_1]

## Transporte (JSON, GraphQL, Protobuf) — recomendação prática

Joud argumenta que o transporte não faz o sistema ser bom ou ruim; disciplina de schema é o que importa. A recomendação
pragmática é:

- **Comece com JSON/REST** para validar o padrão rapidamente.
- **Migre para GraphQL** quando a polimorfia de componentes ficar pesada (uniões modelam bem
  `HeroBanner | ProductCard | ReviewList`).
- **Adote Protobuf/gRPC** quando erros humanos de schema causarem incidentes — o compilador passa a impedir breaking
  changes.[^1_1]

O Airbnb, por sua vez, padronizou em **GraphQL único** para iOS e Android, gerando modelos fortemente tipados em
todas as plataformas e permitindo reuso massivo de sections e layouts.[^1_2]

## Níveis de adoção e onde **não** usar SDUI

Joud propõe um espectro de adoção, não um “tudo ou nada”:

- **Mínimo:** remote config e overrides de copy (AppConfig, LaunchDarkly, Firebase Remote Config).
- **Parcial:** uma ou duas surfaces server-driven (home feed, search results, carrossel promocional). É onde 80% dos
  times começam.
- **Full:** app inteiro server-driven (Netflix Discovery, Airbnb Search). Exige time de plataforma dedicado.[^1_1]

Ele lista superfícies hostis a SDUI:

- **Alta performance:** vídeo, mapas, câmera, AR (orçamento de frame é crítico).
- **Baixa mudança:** settings, auth, onboarding (muda pouco; complexidade não se paga).
- **Offline-crítico:** checkout, recibos (servidor pode não estar disponível).[^1_1]

## Arquitetura de backend (NestJS) e “composer”

No guia de Joud, o backend é um serviço NestJS que:

- Usa um **pacote de contrato compartilhado** (`@sdui/contracts`) com DTOs e decorators (`class-validator`/
  `class-transformer`) para validar o payload antes de enviar.[^1_1]
- Mantém **repositórios** que retornam shapes de domínio (`Product`, `Promo`, `Category`), sem DTOs de UI.[^1_1]
- Possui um **composer** puro que recebe um **template** (documento persistido que diz quais sections e de onde vêm os
  dados) e um **contexto** (usuário, schema version, flags), caminha o template, chama repositórios e emite DTOs de
  componente (`ProductCardDto`, `PromoBanner`, etc.).[^1_1]
- Aplica **fallback builder** recursivo: todo componente tem fallback garantido (geralmente `Text`), evitando telas em
  branco em clientes antigos.[^1_1]

O composer também suporta **feature flags** no template: um nó pode ter `flagId` que substitui a section inteira por
bucket, permitindo experimentos sem release do cliente.[^1_1]

## Cliente (React Native) e registry de componentes

No renderer de Joud:

- O app importa o mesmo pacote de contrato para tipos.
- Um hook `useScreen` busca e cacheia o payload.
- Um walker recursivo (`SDUIScreen`) lê `type` e mapeia para componentes React via **registry**.
- **Actions** são tipos, não strings; um dispatcher central trata `navigate`, `openSheet`, `callApi`,`trackEvent`.[^1_1]

Isso mantém o cliente “burro” sobre o que mostrar e focado em como renderizar nativamente.[^1_1]

## Versionamento e negociação (dois eixos)

Joud enfatiza que há **dois eixos** de versão:

- **Schema version** (do payload/contrato).
- **Client version** (binário do app).

O fluxo de negociação típico:

- Cliente envia headers (`UI-Schema-Version`, `Client-Platform`, `Client-Version`).
- Backend valida e, se incompatível, retorna fallback ou outra árvore compatível.
- Rollback por pointer e spec `PUBLISHED` imutável é uma prática citada nas instruções do projeto para evitar breaking
  changes.[^1_1]

## Observabilidade e SLO

Joud recomenda instrumentar desde o dia 1:

- Logar **tipo do componente**, **versão do schema** e **versão do cliente** em cada render.
- Métricas como `compose.hit/miss`, `section.*`, `serialize`, `payload.bytes`, `schemaVersion`, `appVersion`, `surface`,
  `specRevisionId`.[^1_1]
- SLO citado nas instruções do projeto: **P99 < 1200 ms** (rede + compose + first paint), com meta interna de compose
  P99 ≤ 400 ms.[^1_1]

## Lições de falhas (Spotify HubFramework)

Joud traz um caso negativo: o HubFramework do Spotify foi depreciado porque tornou-se “arqueologia” — abstração genérica
prematura, com poucos primitivos compostos em milhares de layouts, dificultando rastreio de comportamento. A regra
derivada é: **comece semântico**, extraia genéricos só após o mesmo shape aparecer ≥3 vezes em produção.[^1_1]

## Como o Airbnb opera Ghost Platform

O post do Airbnb detalha:

- **Schema GraphQL unificado** para iOS e Android, com union de sections e metadados de placement.[^1_2]
- **SectionComponentType** controla **como** um modelo de section é renderizado (ex.: `TITLE` vs `PLUS_TITLE` usam o
  mesmo modelo com estilos diferentes).[^1_2]
- **Screens** definem layouts por form factor (`compact`/`wide`) e placements que apontam para `sectionId`, reusando
  sections entre configurações.[^1_2]
- **Actions (`IAction`)** são roteadas a handlers de feature; ações genéricas (navegar, scroll) são universais, e
  features podem definir ações próprias.[^1_2]

## Resumo prático para seu MS

Com base nos dois links e nas instruções do projeto:

- **Payload = DTO de view.** Backend decide estrutura, conteúdo e ordem; cliente decide aparência nativa.[^1_2][^1_1]
- **Catálogo semântico** (`hero_banner`, `shortcut_grid`), não DSL de pixel.[^1_1]
- **Envelope fechado** com headers de negociação (`UI-Schema-Version`, `Client-Platform`, `Client-Version`, etc.) e
  rollback por pointer; spec `PUBLISHED` imutável.[^1_1]
- **Composer stateless**, Redis write-through para specs, árvore hidratada com TTL curto, singleflight, sem N+1, sem
  mídia.[^1_1]
- **Fallback obrigatório** por componente; cliente renderiza fallback quando não conhece o type.[^1_1]
- **Observabilidade** com tipo de componente, versão de schema e versão do app em cada log de render.[^1_1]

<div align="center">⁂</div>

[^1_1]: https://joudwawad.medium.com/how-airbnb-netflix-and-lyft-ship-ui-without-touching-the-app-store-49c9f64f5e2b

[^1_2]: https://medium.com/airbnb-engineering/a-deep-dive-into-airbnbs-server-driven-ui-system-842244c5f5


---

## Leitura completa — pontos que valem destacar (sem perder nada)

Abaixo está um resumo estruturado do que há de mais relevante nos dois artigos, com ênfase nos detalhes que costumam
passar batido na primeira leitura. Mantive a linguagem próxima do texto original para não distorcer decisões de design.

## 1) O problema que SDUI resolve (motivação)

- Em UI client-driven, a lógica de “o que mostrar, quando e para quem” é duplicada em iOS, Android. Isso gera:
    - Paridade difícil de manter entre plataformas.
    - Lentidão para lançar experimentos e mudanças de layout (app-store review, beta, rollout).
    - Complexidade crescente de lógica de UI em cada cliente.[^2_1][^2_2]
- O gatilho clássico para adoção de SDUI é sempre o mesmo: “não conseguimos rodar esse A/B test por semanas”. A solução
  é mover decisões voláteis para a camada que deploya rápido (backend).[^2_1]

## 2) Os três conceitos fundamentais (comuns a todos os SDUI sérios)

Ambos os textos convergem exatamente aqui:

- **Sections / Components / Blocos**
    - Peça autocontida de UI (hero, card, carrossel, CTA).
    - Carrega: `type` (renderer), `id` (key/diff/A-B), `data` (strings, URLs, números).[^2_2][^2_1]
- **Screens / Layouts / Surfaces**
    - Composição ordenada de sections: quais sections, em que ordem, em qual rota.
    - A mesma section pode aparecer em Home, Search, Checkout sem duplicação.[^2_2][^2_1]
- **Actions**
    - Intenção serializada: `navigate`, `openSheet`, `callApi`, `trackEvent`, etc.
    - O cliente não hard-code o que o botão faz; ele lê `onTap` e delega a um dispatcher central.[^2_1][^2_2]

Isso é a “anatomia” de qualquer tela SDUI. Todo o resto é infraestrutura em volta.[^2_1]

## 3) O que SDUI **não** é (definição negativa importante)

- **Não é “backend manda CSS”.**
    - Cliente continua dono de: renderização nativa, gestos, animações, accessibility labels, haptics, dark mode,
      convenções de plataforma.
    - Servidor diz **o quê** mostrar; cliente decide **como**.[^2_1]
- **Não é micro-frontend.**
    - Sem bundles remotos, sem eval de JS em runtime, sem download de código.
    - Cliente shipa com conjunto fixo de renderizadores compilados; servidor envia apenas dados que mapeiam nesses
      renderizadores.[^2_1]

Essa distinção é crítica para não cair em antipadrões do tipo “backend que manda estilo”.

## 4) Payload mínimo (exemplo concreto que mostra os três conceitos)

Joud mostra um JSON mínimo que ilustra sections, screens e actions juntos:

```json
{
  "sections": [
    {
      "id": "header-001",
      "type": "HeaderSection",
      "data": {
        "title": "Good evening, Joud",
        "subtitle": "3 trips coming up"
      }
    },
    {
      "id": "banner-001",
      "type": "BannerSection",
      "data": {
        "imageUrl": "https://cdn.example.com/promo-spring.jpg",
        "headline": "Spring stays, 20% off",
        "cta": "Browse deals",
        "onTap": {
          "type": "navigate",
          "route": "search",
          "params": {
            "filter": "spring-promo"
          }
        }
      }
    }
  ],
  "screens": [
    {
      "id": "home",
      "layout": [
        {
          "sectionId": "header-001"
        },
        {
          "sectionId": "banner-001"
        }
      ]
    }
  ]
}
```

- Cliente itera `screens[^2_0].layout`, resolve `sectionId` em `sections`, escolhe renderer por `type`, passa `data`.
- Ao tapar, ação é entregue ao dispatcher; cliente não sabe o que “spring-promo” significa.[^2_1]

## 5) Níveis de adoção (espectro, não binário)

Joud enfatiza que times erram ao tentar “SDUI total” de cara:

- **Mínimo:** remote config e copy overrides (AppConfig, LaunchDarkly, Firebase Remote Config).
    - Baixo risco, baixo ganho; muda texto/cor/flag, não layout.[^2_1]
- **Parcial:** uma ou duas surfaces server-driven (home feed, search results, carousel promocional).
    - É onde 80% dos casos reais começam; Airbnb, Lyft, Shopify começaram assim.[^2_2][^2_1]
- **Full:** app inteiro server-driven (Netflix Discovery, Airbnb Search, PhonePe 130+ screens).
    - Exige time de plataforma dedicado, registry de componentes, layout engine, observabilidade robusta.[^2_1]

### Onde **não** usar SDUI

- **Alta performance:** vídeo, mapas, câmera, AR (orçamento de frame crítico).[^2_1]
- **Baixa mudança:** settings, auth, onboarding (mudam pouco; complexidade não se paga).[^2_1]
- **Offline-crítico:** checkout, recibos, fluxos que precisam funcionar sem rede (a menos que já exista cache-and-replay
  maduro).[^2_1]

Recomendação prática: comece **Partial** em um feed ou listing; meça ganho de velocidade; depois decida se
expande.[^2_1]

## 6) Cinco padrões de produção (com casos reais) + uma lição negativa

### Pattern 1 — Airbnb: sections são independentes de screen

- Sections não sabem em qual screen estão.
- `PriceBreakdownSection` pode aparecer em checkout e trip details, idêntico, sem condicionais de screen.
- Isso evita que sections cresçam lógica específica de tela e permite reorder/hide/A-B sem mudar cliente.[^2_2][^2_1]

### Pattern 2 — Lyft: nomear componentes pelo produto, não pelo layout

- Em vez de genéricos (`Row`, `Column`, `Text`), usar semântico: `RideOptionCard`, `PromoBanner`, `VehicleStatusHeader`.
- Trade-off: menos reuso, mais tipos de componente; mas reduz ambiguidade no contrato e facilita evolução.[^2_1]

### Pattern 3 — DoorDash: schema first, authoring tool second

- Mosaic (GUI para PMs compor telas) veio **depois** de >1 ano endurecendo contratos de componente.
- Times que constroem a ferramenta visual antes do schema estável acabam com layouts salvos quebrando a cada churn de
  schema.[^2_1]

### Pattern 4 — Shopify: fallback é campo de primeira classe no envelope

- Cada section é um envelope versionado com: `identifier`, `data blob`, `fallback`.
- Fallback não é improvisado pelo cliente; é obrigatório no payload.
- Isso torna estruturalmente impossível shipar nova section sem fallback; clientes antigos continuam funcionando.[^2_1]

### Pattern 5 — Netflix: observabilidade antes de velocidade

- Cada componente renderizado emite log com:
    - tipo do componente
    - versão do schema que o gerou
    - versão do binário do cliente
- Em SDUI, dois usuários na mesma versão do app podem ver UIs diferentes; sem essas três dimensões, debugar bugs vira
  adivinhação.[^2_1]

### Cautionary tale — Spotify HubFramework

- Tentativa antiga de SDUI genérico em escala.
- Acabou descrito como “arqueologia”: poucos primitivos genéricos compostos em milhares de layouts, difícil de rastrear
  comportamento.
- Lição: começar semântico; extrair genéricos só após o mesmo shape aparecer ≥3 vezes em produção. Premature genericness
  mata legibilidade.[^2_1]

### Tabela de referência rápida (Joud)

| Empresa        | Transporte    | Estilo                 | Insight chave                      |
|:---------------|:--------------|:-----------------------|:-----------------------------------|
| Airbnb         | GraphQL       | Semântico              | Sections independentes de screen   |
| Lyft           | Protobuf/gRPC | Semântico              | Experimentos de 2 semanas → 2 dias |
| DoorDash       | JSON          | Mist → genérico        | Schema first, tool second          |
| Shopify        | GraphQL       | Semântico + versionado | Server ships the fallback          |
| Netflix        | Proprietário  | Semântico + logado     | Observability before velocity      |
| Spotify (mort) | JSON          | Totalmente genérico    | Over-abstraction kills             |

[^2_1]

## 7) Transporte: JSON vs GraphQL vs Protobuf (recomendação pragmática)

Joud deixa claro: **o transporte não faz SDUI bom ou ruim; disciplina de schema é que importa.** O que muda é quem
enforce essa disciplina.

- **JSON/REST**
    - Mais rápido para validar o padrão.
    - Risco: renomear campo quebra clientes antigos; disciplina vira code-review.
    - Recomendado para primeira superfície SDUI, time pequeno.[^2_1]
- **GraphQL**
    - Union types modelam bem polimorfia de componentes (`HeroBanner | ProductCard | ReviewList`).
    - Airbnb, Shopify, Yelp convergiram aqui.
    - Custo: montar Apollo/federation/codegen só por causa de SDUI pode ser pesado se não houver GraphQL já.[^2_2][^2_1]
- **Protobuf/gRPC**
    - Compiler impede breaking changes (field numbers permanentes, reserved, etc.).
    - Lyft e Faire escolheram por rigidez de schema.
    - Custo: `.proto`, codegen, gRPC gateway (React Native não fala gRPC nativo).
    - Recomendado quando erros humanos de schema já causaram incidente.[^2_1]

Regra em uma linha: **comece JSON, migre para GraphQL quando a polimorfia ficar pesada, migre para Protobuf quando
disciplina humana falhar.**[^2_1]

## 8) Arquitetura de backend (NestJS) — detalhes que importam

### Contrato único (`@sdui/contracts`)

- Pacote TS compartilhado entre server e client.
    - Server: valida payloads outbound com `class-validator`/`class-transformer`.
    - Client: usa tipos TS para compile-time safety; decorators tree-shake.
- Enum fechado de `ComponentType` (ex.: `text`, `image`, `button`, `stack`, `productCard`, `promoBanner`, etc.).
    - Adicionar novo tipo = poucas linhas; compiler arrasta obrigatoriedade de tratar em todos os lugares.[^2_1]

### DTOs de componente (ex.: `ProductCardDto`)

- Forma padrão:
    - `type` literal (discriminador).
    - Props validadas (`@IsString`, `@IsUrl`, etc.).
    - `onTap` como `ActionDto` aninhado.
- Polimorfia via `@Type({ discriminator })`: `class-transformer` instancia subclasse correta pelo `type`;
  `class-validator` valida o conjunto certo de campos.[^2_1]

### Actions como tipos, não strings

- `ActionType`: `NAVIGATE`, `OPEN_SHEET`, `CALL_API`, `TRACK_EVENT`.
- Cada ação é uma classe com `type` literal e campos específicos.
- Vantagem: botão não branchia por string; delega ação tipada a dispatcher único por `ActionType`.
- Isso impede que complexidade do cliente cresça linearmente com features.[^2_1]

### Repositórios vs Composer (separação de responsabilidades)

- **Repositórios** retornam shapes de domínio (`Product`, `Promo`, `Category`, `User`, `ScreenTemplate`).
    - Não conhecem DTOs de UI.
    - Em produção, seriam Prisma/TypeORM/etc.; no exemplo, arrays em memória.
- **Composer** é o único lugar que mapeia domínio → DTOs de componente.
    - Regra importante: “Repos não sabem de cards; cards não sabem de Postgres.”
    - Quebrar isso gera acoplamento eterno entre data layer e UI vocabulary.[^2_1]

### Template de screen (não é “descrição de tela”, é “receita”)

- Template = JSON persistido (editado pelo admin) que diz:
    - Quais sections existem, em que ordem.
    - De onde vêm os dados de cada section (`source.repo`, `source.segment`, `source.limit`).
- Exemplo de nó:

```json
{
  "id": "h-picked-list",
  "type": "productCardList",
  "source": {
    "repo": "products",
    "segment": "picked",
    "limit": 8
  },
  "layout": "horizontal"
}
```

- “Layout layer”: tipo de section e disposição.
- “Source layer”: de onde buscar dados.
- Merchandising pode mudar ordem, segmento, limite; não pode inventar novo tipo de componente (Zod + enum fechado
  impedem).[^2_1]

### Contexto de request

- Tudo escopo de request: usuário, schema version, flag bucket.
- Template não conhece contexto; composer usa contexto para personalizar.[^2_1]

### Resolver (dentro do composer)

- Função pura que caminha o template:
    - Para cada nó com `source`, chama repositório.
    - Emite array de `BaseComponentDto` concretos.
- Exemplo: `productCardList`:
    - Chama `products.list({ segment, limit })`.
    - Mapeia cada `Product` → `ProductCardDto` (com `onTap` tipado).
    - Se `layout === 'horizontal'`, envolve N cards em `stack(horizontal)`.
- Observações importantes:
    - Retorna **arrays** de componentes, não singletons.
    - Vocabulário de componente é pequeno; template é expressivo.
    - `onTap` é construído inline como `NavigateActionDto`; card nunca carrega string de rota.[^2_1]

### Feature flags no template

- Nó pode ter `flagId`:

```json
{
  "type": "productCardList",
  "flagId": "homeHero",
  ...
}
```

- Resolver consulta bucket do usuário e substitui nó inteiro (ex.: carousel → promoBanner).
- Cliente vê apenas árvore final; experimentos shipam sem release.[^2_1]

### Fallback builder (regra de ouro)

- Regra: **nenhum componente shipa sem fallback.**
- Fallback é um componente `Text` (ou outro garantido em todas as versões).
- Builder recursivo anexa fallback a todo componente; se cliente não conhece type ou falha validação, renderiza
  fallback.
- Isso evita telas em branco e reduz tickets de suporte.[^2_1]

### Controller, guard, header

- Controller chama serviço → carrega template → composer → fallback builder → JSON.
- Guard valida header de schema version antes de compor.
- Header típico: `UI-Schema-Version`, `Client-Platform`, `Client-Version`, etc.[^2_1]

### Admin tool (Next.js)

- UI drag-drop para editar template.
- Valida layout com **Zod schemas** exportados do mesmo pacote de contrato.
- Escreve via backend (nunca direto no DB).
- Zod schemas são fonte da verdade para “este layout é válido?” sem depender de Nest internals.[^2_1]

## 9) Cliente React Native — renderer e registry

### `useScreen` e cache

- Hook que busca e cacheia payload da screen.
- Componente raiz (`SDUIScreen`) recebe `screenId` e delega a walker recursivo.[^2_1]

### Walker recursivo (`SDUIScreen`)

- Lê `type` de cada componente.
- Mapeia para componente React via **registry**.
- Passa `data` como props.
- Ações (`onTap`) são objetos tipados, não strings.[^2_1]

### Registry de componentes

- Mapa `type` → componente React.
- Adicionar novo componente = registrar no map + garantir fallback.
- Cliente não sabe o que é “checkout” ou “promo”; só sabe renderizar `productCard`, `promoBanner`, etc.[^2_1]

### Dispatcher de actions

- Hook central com handler por `ActionType` (`navigate`, `openSheet`, `callApi`, `trackEvent`).
- Componentes apenas invocam `onTap(action)`; dispatcher decide o que fazer.
- Isso mantém componentes puros e testáveis.[^2_1]

### SDUI parcial dentro de native parent

- Exemplo: nav bar nativa fixa + conteúdo abaixo server-driven.
- `HomeScreen.tsx` renderiza nav nativa e delega o resto a `<SDUIScreen screenId="home" />`.
- Isso ilustra onde SDUI começa e termina numa tela híbrida.[^2_1]

### Static screens

- Telas de baixa mudança (settings, auth) podem permanecer 100% nativas.
- SDUI é usado apenas onde há ganho real de velocidade.[^2_1]

## 10) Versionamento e negociação (dois eixos)

- Dois eixos independentes:
    - **Schema version** (do payload/contrato).
    - **Client version** (binário do app).
- Fluxo típico:
    - Cliente envia headers: `UI-Schema-Version`, `Client-Platform`, `Client-Version`, etc.
    - Backend valida; se incompatível, retorna fallback ou outra árvore compatível.
    - Rollback por pointer e spec `PUBLISHED` imutável evita breaking changes.[^2_1]

No projeto, isso aparece como “envelope fechado” com headers próprios (sem `X-`) e política de rollback.[^2_1]

## 11) Observabilidade e SLO

### O que logar (Netflix pattern)

- Para cada componente renderizado:
    - tipo do componente
    - versão do schema
    - versão do cliente
- Isso permite responder: “qual schema em qual build está gerando isso?”[^2_1]

### Métricas sugeridas (alinhadas ao projeto)

- `compose.hit` / `compose.miss`
- `section.*` (por tipo)
- `serialize`
- `payload.bytes`
- `schemaVersion`, `appVersion`, `surface`, `specRevisionId`[^2_1]

### SLO citado nas instruções do projeto

- **P99 < 1200 ms** (rede + compose + first paint).
- Meta interna: compose P99 ≤ 400 ms.
- Redis: spec write-through; árvore hidratada com TTL curto; singleflight; sem N+1; sem mídia.[^2_1]

## 12) Airbnb Ghost Platform — detalhes específicos que valem reter

### Schema GraphQL unificado

- Mesmo schema para iOS e Android.
- Gera modelos fortemente tipados em todas as plataformas.
- Permite reuso massivo de sections e layouts.[^2_2]

### Sections independentes de contexto

- Sections não têm lógica de screen; são reutilizáveis em qualquer feature.
- Isso é explicitamente chamado como “key feature” do Ghost Platform.[^2_2]

### `SectionComponentType`

- Controla **como** um data model de section é renderizado.
- Ex.: `TITLE` vs `PLUS_TITLE` usam o mesmo `TitleSection`, mas com estilos/logos diferentes.
- Permite flexibilidade sem duplicar schema.[^2_2]

### Screens e `ILayout`

- `ScreenContainer` define layout e placements.
- `LayoutsPerFormFactor` especifica layouts para compact/wide.
- `ILayout` tem placements com `SectionDetail` apontando para `sectionId` (não inline), reduzindo payload.[^2_2]

### Actions (`IAction`)

- Interface de ação no schema.
- Seção component especifica **quando** disparar a ação (ex.: onClick).
- GP roteia ação para handler da feature; handlers podem conter lógica específica.
- Ações genéricas (navegar, scroll) são universais; features podem definir ações próprias.[^2_2]

### Exemplo de resposta GP (figura 12)

- `sections` array com múltiplas sections.
- `screens` array com um screen `ROOT`.
- Layout `SingleColumnLayout` com placements: `nav`, `main`, `footer`.
- Cada placement lista `SectionDetail` com `sectionId`.
- Seções com `onClickAction` são disparadas via `GPActionHandler.handleIAction(...)`.[^2_2]

### Próximos passos do GP (na época do post)

- Nested sections (UI mais composável).
- Melhor discoverability em Figma.
- WYSIWYG editing de sections e placements (no-code changes).[^2_2]

## 13) Como isso se encaixa no seu MS Home

Com base nos dois links e nas instruções do projeto:

- **Payload = DTO de view.** Backend decide estrutura, conteúdo e ordem; cliente decide aparência nativa.[^2_2][^2_1]
- **Catálogo semântico** (`hero_banner`, `shortcut_grid`), não DSL de pixel.[^2_1]
- **Envelope fechado** com headers de negociação (`UI-Schema-Version`, `Client-Platform`, `Client-Version`, etc.) e
  rollback por pointer; spec `PUBLISHED` imutável.[^2_1]
- **Composer stateless**, Redis write-through para specs, árvore hidratada com TTL curto, singleflight, sem N+1, sem
  mídia.[^2_1]
- **Fallback obrigatório** por componente; cliente renderiza fallback quando não conhece o type.[^2_1]
- **Observabilidade** com tipo de componente, versão de schema e versão do app em cada log de render.[^2_1]
- **Sections independentes de screen** e **nome semântico** (produto, não layout).[^2_2][^2_1]
- **Schema first, authoring tool second**; evitar genéricos prematuros (lição do HubFramework).[^2_1]

<div align="center">⁂</div>

[^2_1]: https://joudwawad.medium.com/how-airbnb-netflix-and-lyft-ship-ui-without-touching-the-app-store-49c9f64f5e2b

[^2_2]: https://medium.com/airbnb-engineering/a-deep-dive-into-airbnbs-server-driven-ui-system-842244c5f5


---

## Resposta direta

Sim — é possível construir um backend Server-Driven UI que retorne **qualquer montagem de tela** dentro de um **catálogo
finito de componentes** (sections) e de um **conjunto finito de layouts** (screens). Os dois artigos mostram exatamente
isso em produção: Airbnb, Lyft, Netflix, Shopify, DoorDash e outros operam SDUI com centenas de telas diferentes usando
o mesmo mecanismo de composer + sections + screens + actions.[^3_1][^3_2]

O limite não é “quantas telas diferentes”, mas sim:

- **Catálogo de components:** quantos tipos de section você tem (hero, banner, grid, list, card, carousel, etc.).
- **Layouts de screen:** quantos padrões de composição você suporta (single column, two column, tabbed, scrollable,
  etc.).
- **Ações:** quantas intenções de usuário você modela (navigate, openSheet, callApi, trackEvent, etc.).

Dentro desse espaço, **qualquer tela** que possa ser descrita como “composição de sections conhecidas em ordem
conhecida, com dados conhecidos e ações conhecidas” pode ser montada pelo backend e renderizada pelo
cliente.[^3_2][^3_1]

## Como isso cobre as telas das imagens (Modern Mobile UI UX)

Sem entrar em detalhe visual de cada imagem (o importante é a estrutura), os exemplos que você anexou se enquadram em
padrões típicos de SDUI:

- **Coffee ordering / Coffee shop app**
    - Sections típicas: `hero_banner`, `category_grid`, `product_list`, `product_card`, `promo_banner`, `footer_cta`.
    - Screens: home, menu, product detail, cart, checkout.
    - Actions: `navigate`, `addToCart`, `openSheet` (customizações), `trackEvent`.[^3_1][^3_2]
- **Fintech / Banking app**
    - Sections: `account_summary`, `balance_card`, `transaction_list`, `transaction_row`, `quick_action_grid`,
      `promo_banner`, `insight_card`.
    - Screens: home, accounts, transactions, transfers, profile.
    - Actions: `navigate`, `callApi` (iniciar transferência), `openSheet` (detalhes), `trackEvent`.[^3_2][^3_1]
- **Onboarding screens (Zand Bank, etc.)**
    - Sections: `onboarding_page`, `onboarding_content`, `onboarding_cta`, `pagination_indicator`.
    - Screen: onboarding (com pages compostas por sections).
    - Actions: `navigate`, `completeOnboarding`, `trackEvent`.[^3_1][^3_2]
- **Food delivery / Pizza Hut**
    - Sections: `restaurant_hero`, `menu_category_list`, `menu_item_card`, `cart_summary`, `delivery_tracker`,
      `promo_banner`.
    - Screens: home, restaurant, menu, cart, tracking, profile.
    - Actions: `navigate`, `addToCart`, `callApi` (checkout), `trackEvent`.[^3_2][^3_1]
- **Delivery tracking com chat**
    - Sections: `order_status_card`, `timeline`, `driver_card`, `chat_thread`, `chat_message`, `chat_input`,
      `action_buttons`.
    - Screens: tracking, chat.
    - Actions: `navigate`, `callApi` (enviar mensagem), `openSheet`, `trackEvent`.[^3_1][^3_2]
- **Medium-like feed**
    - Sections: `feed_header`, `article_card`, `author_card`, `tag_pill`, `pagination`, `subscribe_banner`.
    - Screens: home, topic, article, profile.
    - Actions: `navigate`, `callApi` (bookmark, follow), `trackEvent`.[^3_2][^3_1]

Em todos esses casos, a tela é **composição de sections** com **dados** e **ações**; o backend decide quais sections
aparecem, em que ordem, com quais dados e quais ações. O cliente só renderiza e dispara ações.[^3_1][^3_2]

## O que o backend precisa ter para montar “qualquer tela”

Com base nos dois artigos e nas instruções do projeto, o mínimo para um motor SDUI capaz de montar qualquer tela é:

### 1) Catálogo de Sections (Components)

- Enum fechado de `type` (ex.: `hero_banner`, `promo_banner`, `product_list`, `product_card`, `category_grid`,
  `transaction_list`, `transaction_row`, `chat_thread`, `chat_message`, `onboarding_page`, etc.).
- Para cada `type`:
    - DTO de dados (ex.: `HeroBannerDto`, `ProductCardDto`, `TransactionRowDto`).
    - DTO de ação (`onTap` / `onClick` como `ActionDto`).
    - Fallback garantido (ex.: `Text` ou versão simplificada).[^3_2][^3_1]

### 2) Layouts de Screen

- Definição de screens:
    - `screenId` (ex.: `home`, `menu`, `transactions`, `tracking`).
    - `layout` (ex.: `single_column`, `tabbed`, `two_pane`).
    - `placements` (ex.: `header`, `main`, `footer`).
    - Lista de `sectionId` por placement.[^3_2]
- O layout pode ser:
    - Fixo por screen (ex.: `home` sempre `single_column`).
    - Ou parametrizado por form factor (compact/wide), como no Ghost Platform do Airbnb.[^3_2]

### 3) Templates de Screen (receita de composição)

- Template persistido (editado por admin/tooling) que diz:
    - Quais sections compõem a screen.
    - Em que ordem.
    - De onde vêm os dados de cada section (repositório, segmento, limite, filtros).
    - Flags de feature (ex.: `flagId` para A/B test).[^3_1]

Exemplo de nó de template:

```json
{
  "id": "home-hero",
  "type": "hero_banner",
  "source": {
    "repo": "promos",
    "segment": "homepage",
    "limit": 1
  },
  "flagId": "homeHeroV2"
}
```

O composer lê o template, chama repositórios, emite DTOs de component e monta a árvore final.[^3_1]

### 4) Composer (motor de composição)

- Stateless, por request.
- Recebe:
    - `screenId`
    - contexto (usuário, schema version, flags, form factor)
    - template da screen
- Caminha o template:
    - Para cada nó com `source`, chama repositório.
    - Mapeia domínio → DTOs de component.
    - Aplica fallback builder.
    - Aplica feature flags (substitui nós inteiros por bucket).
- Emite array de `BaseComponentDto` concretos + screen layout.[^3_1]

No projeto, isso é o “composer stateless” com Redis write-through para specs, árvore hidratada com TTL curto,
singleflight, sem N+1.[^3_1]

### 5) Actions (intenções de usuário)

- Interface de ação (`IAction` / `ActionDto`) com:
    - `type` (ex.: `navigate`, `openSheet`, `callApi`, `trackEvent`).
    - Campos específicos por tipo (ex.: `route`, `params`, `endpoint`, `payload`, `eventName`).
- Sections especificam **quando** disparar a ação (ex.: `onClick`, `onTap`).
- Cliente roteia ação para handler central por `type`.[^3_2]

Isso permite que o backend defina o comportamento de botões, cards, banners etc., sem hard-code no cliente.[^3_2][^3_1]

### 6) Negociação de versão (envelope fechado)

- Headers de request:
    - `UI-Schema-Version`
    - `Client-Platform`
    - `Client-Version`
    - `Client-Build`
    - `OS-Version`
    - `Component-Capabilities` (opcional)
- Backend valida schema version; se incompatível:
    - Retorna fallback ou outra árvore compatível.
    - Usa pointer + rollback; spec `PUBLISHED` imutável.[^3_1]

Isso garante que clientes antigos não quebrem quando novas sections forem introduzidas.[^3_1]

### 7) Observabilidade

- Logar por componente renderizado:
    - tipo do componente
    - versão do schema
    - versão do cliente
- Métricas:
    - `compose.hit` / `compose.miss`
    - `section.*` (por tipo)
    - `serialize`
    - `payload.bytes`
    - `schemaVersion`, `appVersion`, `surface`, `specRevisionId`[^3_1]

Isso permite debugar “qual schema em qual build está gerando isso?” — essencial em SDUI.[^3_1]

## Limites reais (o que SDUI não cobre bem)

Mesmo com todo esse poder, há limites:

- **Componentes desconhecidos:** se o cliente não tem renderer para um `type`, ele renderiza fallback (ou omite). Não há
  “download de novo componente em runtime”.[^3_1]
- **Layouts fora do catálogo:** se uma tela exige um layout que não existe no catálogo de screens (ex.: grid 3x3 com
  comportamento específico), é preciso:
    - Ou criar nova section (`grid_3x3`),
    - Ou criar novo layout de screen.
- **Interações muito complexas / alta performance:** vídeo, mapas, câmera, AR, animações pesadas —这些地方通常更适合纯原生实现，SDUI
  只控制外层结构或入口。[^3_1]

Na prática, isso não impede cobrir **todas as telas de negócio** (home, feed, list, detail, cart, checkout, profile,
tracking, onboarding, etc.); só significa que o catálogo de components e layouts precisa evoluir junto com o
produto.[^3_2][^3_1]

## Como isso se aplica ao seu MS Home

Com base nos dois links e nas instruções do projeto:

- **Sim**, é possível construir um backend SDUI que retorne qualquer montagem de tela dentro de um catálogo de sections
  e layouts.
- O MS Home pode:
    - Ter um **catálogo semântico** de sections (`hero_banner`, `shortcut_grid`, `product_list`, `product_card`,
      `promo_banner`, `transaction_list`, etc.).
    - Ter um conjunto de **layouts de screen** (home, feed, detail, list, profile, etc.).
    - Usar um **composer stateless** que, dado um template e contexto, emite a árvore de components.
    - Garantir **fallback obrigatório** por component.
    - Negociar versão via headers (`UI-Schema-Version`, `Client-Platform`, `Client-Version`, etc.) e rollback por
      pointer.
    - Instrumentar **observabilidade** por component (tipo, schema version, app version).[^3_2][^3_1]

<div align="center">⁂</div>

[^3_1]: https://joudwawad.medium.com/how-airbnb-netflix-and-lyft-ship-ui-without-touching-the-app-store-49c9f64f5e2b

[^3_2]: https://medium.com/airbnb-engineering/a-deep-dive-into-airbnbs-server-driven-ui-system-842244c5f5


---

## Proposta inicial executável — Catálogo de Sections, Layouts e Contrato SDUI

Abaixo está uma especificação concreta, baseada nos dois artigos e em referências de 2026. O objetivo é provar que o
backend pode montar **qualquer tela** dentro de um catálogo finito de sections e layouts, cobrindo os domínios das
imagens (coffee, fintech, delivery, feed, onboarding).[^4_1][^4_2][^4_3][^4_4][^4_5][^4_6][^4_7]

______________________________________________________________________

## 1) Catálogo inicial de Sections (Components)

Este catálogo cobre os padrões visuais das imagens anexadas. Cada section tem:

- `type` (string, enum fechado)
- `id` (string, único por instância na tela)
- `data` (DTO específico por type)
- `fallback` (sempre presente, pelo menos `text`)
- `actions` (`onTap`, `onLongPress`, etc., como `ActionDto`)[^4_2][^4_4][^4_7][^4_1]

### 1.1) Sections de Hero / Banner

| Type              | Uso típico                                  | Campos principais (`data`)                                 | Actions                              |
|:------------------|:--------------------------------------------|:-----------------------------------------------------------|:-------------------------------------|
| `hero_banner`     | Topo de home, destaque principal            | `title`, `subtitle`, `imageUrl`, `ctaLabel`                | `onTap: navigate/callApi`            |
| `promo_banner`    | Promoções, campanhas sazonais               | `headline`, `subhead`, `imageUrl`, `ctaLabel`, `badgeText` | `onTap: navigate/callApi`            |
| `onboarding_page` | Tela de onboarding (uma section por página) | `title`, `body`, `imageUrl`, `ctaLabel`, `skipLabel`       | `onTap: navigate/completeOnboarding` |

[^4_3][^4_6][^4_1][^4_2]

### 1.2) Sections de Lista / Grid de Produtos

| Type            | Uso                                   | Campos principais                                                         | Actions                               |
|:----------------|:--------------------------------------|:--------------------------------------------------------------------------|:--------------------------------------|
| `product_list`  | Lista vertical de itens (menu, feed)  | `title` (opcional), `items: [ProductCard]`, `layout: vertical/horizontal` | — (cada card tem `onTap`)             |
| `product_card`  | Card de produto/item                  | `title`, `subtitle`, `price`, `imageUrl`, `rating`, `ctaLabel`            | `onTap: navigate/callApi (addToCart)` |
| `category_grid` | Grid de categorias (coffee, delivery) | `title`, `items: [CategoryPill]`, `columns: 2/3/4`                        | `onTap: navigate`                     |
| `category_pill` | Pill de categoria/tag                 | `label`, `iconUrl` (opcional)                                             | `onTap: navigate`                     |

[^4_6][^4_8][^4_1][^4_2][^4_3]

### 1.3) Sections Financeiras / Transações

| Type               | Uso                         | Campos principais                                                                  | Actions                      |
|:-------------------|:----------------------------|:-----------------------------------------------------------------------------------|:-----------------------------|
| `account_summary`  | Resumo de conta (fintech)   | `accountName`, `balance`, `currency`, `availableBalance`                           | `onTap: navigate (detalhes)` |
| `balance_card`     | Card de saldo simples       | `label`, `amount`, `currency`, `trend` (opcional)                                  | `onTap: navigate`            |
| `transaction_list` | Lista de transações         | `title` (opcional), `items: [TransactionRow]`                                      | —                            |
| `transaction_row`  | Linha de transação          | `title`, `subtitle`, `amount`, `currency`, `date`, `iconUrl`, `type: credit/debit` | `onTap: navigate (detalhes)` |
| `insight_card`     | Insight/gasto por categoria | `title`, `body`, `amount`, `currency`, `imageUrl`                                  | `onTap: navigate`            |

[^4_4][^4_7][^4_1][^4_2][^4_6]

### 1.4) Sections de Delivery / Tracking

| Type                | Uso                | Campos principais                                           | Actions                       |
|:--------------------|:-------------------|:------------------------------------------------------------|:------------------------------|
| `order_status_card` | Status do pedido   | `statusLabel`, `estimatedTime`, `iconUrl`                   | `onTap: navigate`             |
| `delivery_timeline` | Timeline de etapas | `steps: [TimelineStep]`                                     | —                             |
| `timeline_step`     | Etapa da timeline  | `label`, `time` (opcional), `completed: bool`               | —                             |
| `driver_card`       | Card do entregador | `name`, `rating`, `vehicle`, `imageUrl`, `phoneLabel`       | `onTap: callApi (ligar/chat)` |
| `cart_summary`      | Resumo do carrinho | `itemCount`, `subtotal`, `deliveryFee`, `total`, `currency` | `onTap: navigate (checkout)`  |

[^4_1][^4_2][^4_3][^4_6]

### 1.5) Sections de Chat

| Type           | Uso                                | Campos principais                                                               | Actions                           |
|:---------------|:-----------------------------------|:--------------------------------------------------------------------------------|:----------------------------------|
| `chat_thread`  | Thread de chat (tracking, suporte) | `messages: [ChatMessage]`, `participantName`                                    | —                                 |
| `chat_message` | Mensagem individual                | `text`, `timestamp`, `from: user/driver/support`, `status: sent/delivered/read` | `onLongPress: openSheet (opções)` |
| `chat_input`   | Input de mensagem                  | `placeholder`, `sendLabel`                                                      | `onSend: callApi`                 |

[^4_2][^4_6][^4_1]

### 1.6) Sections de Conteúdo / Feed

| Type               | Uso                  | Campos principais                                                    | Actions                      |
|:-------------------|:---------------------|:---------------------------------------------------------------------|:-----------------------------|
| `article_card`     | Card de artigo/post  | `title`, `subtitle`, `author`, `publishDate`, `imageUrl`, `readTime` | `onTap: navigate`            |
| `author_card`      | Card de autor        | `name`, `bio`, `imageUrl`, `followerCount`                           | `onTap: navigate`            |
| `tag_pill`         | Pill de tag/tópico   | `label`                                                              | `onTap: navigate`            |
| `subscribe_banner` | Banner de assinatura | `title`, `body`, `ctaLabel`                                          | `onTap: callApi (subscribe)` |

[^4_8][^4_3][^4_1][^4_2]

### 1.7) Sections de Navegação / Estrutura

| Type                   | Uso                                        | Campos principais               | Actions                   |
|:-----------------------|:-------------------------------------------|:--------------------------------|:--------------------------|
| `quick_action_grid`    | Grid de ações rápidas (fintech, home)      | `title`, `items: [QuickAction]` | —                         |
| `quick_action`         | Ação rápida individual                     | `label`, `iconUrl`              | `onTap: navigate/callApi` |
| `footer_cta`           | CTA no fim da tela                         | `title`, `ctaLabel`             | `onTap: navigate/callApi` |
| `pagination_indicator` | Indicador de página (onboarding, carousel) | `currentPage`, `totalPages`     | —                         |

[^4_3][^4_6][^4_1][^4_2]

### 1.8) Primitivos de Layout (se necessário no catálogo)

Alguns projetos incluem primitivos de layout como sections:

- `stack` (vertical/horizontal)
- `spacer`
- `divider`
- `text`
- `image`

No seu caso, como as instruções do projeto pedem **catálogo semântico** e **não DSL de pixel**, recomendo usar esses
primitivos apenas internamente no compositor, não expô-los como sections de alto nível.[^4_1]

______________________________________________________________________

## 2) Catálogo inicial de Layouts de Screen

Cada screen define **como** as sections são dispostas. O Airbnb usa `ILayout` com placements (`nav`, `main`, `footer`) e
layouts por form factor (compact/wide).[^4_5][^4_9][^4_2]

### 2.1) Layouts base

| Layout ID         | Descrição                                 | Placements                 | Uso típico                        |
|:------------------|:------------------------------------------|:---------------------------|:----------------------------------|
| `single_column`   | Uma coluna vertical, scroll               | `header`, `main`, `footer` | Home, feed, detail, onboarding    |
| `two_pane`        | Duas colunas (esq/dir) em tablets/desktop | `left`, `right`            | Detail + preview, account         |
| `tabbed`          | Conteúdo com tabs no topo/baixo           | `tabs`, `main`             | Perfil, configurações, categorias |
| `scrollable_grid` | Grid rolável (2/3/4 colunas)              | `header`, `grid`, `footer` | Menu, categorias, produtos        |

[^4_9][^4_6][^4_2]

### 2.2) Screen definition (exemplo)

```json
{
  "id": "home_coffee",
  "title": "Home - Coffee Shop",
  "layout": {
    "type": "single_column",
    "placements": {
      "header": [
        {
          "sectionId": "home-hero"
        },
        {
          "sectionId": "category-grid"
        }
      ],
      "main": [
        {
          "sectionId": "product-list-featured"
        },
        {
          "sectionId": "promo-banner"
        }
      ],
      "footer": [
        {
          "sectionId": "footer-cta"
        }
      ]
    }
  }
}
```

______________________________________________________________________

## 3) Contrato do Payload (Screen Response)

Baseado nos artigos, o payload de resposta do composer pode seguir esta estrutura:

```json
{
  "schemaVersion": "1.0.0",
  "screenId": "home_coffee",
  "sections": [
    {
      "id": "home-hero",
      "type": "hero_banner",
      "data": {
        "title": "Good morning, Wallan",
        "subtitle": "Ready for your espresso?",
        "imageUrl": "https://cdn.example.com/coffee-hero.jpg",
        "ctaLabel": "Order now"
      },
      "fallback": {
        "type": "text",
        "data": {
          "text": "Welcome"
        }
      },
      "actions": {
        "onTap": {
          "type": "navigate",
          "route": "menu",
          "params": {}
        }
      }
    },
    {
      "id": "category-grid",
      "type": "category_grid",
      "data": {
        "title": "Browse by category",
        "columns": 3,
        "items": [
          {
            "label": "Espresso",
            "iconUrl": "..."
          },
          {
            "label": "Latte",
            "iconUrl": "..."
          },
          {
            "label": "Pastries",
            "iconUrl": "..."
          }
        ]
      },
      "fallback": {
        "type": "text",
        "data": {
          "text": "Categories"
        }
      },
      "actions": {}
    }
    // ... outras sections
  ],
  "layout": {
    "type": "single_column",
    "placements": {
      "header": [
        {
          "sectionId": "home-hero"
        },
        {
          "sectionId": "category-grid"
        }
      ],
      "main": [
        {
          "sectionId": "product-list-featured"
        },
        {
          "sectionId": "promo-banner"
        }
      ],
      "footer": [
        {
          "sectionId": "footer-cta"
        }
      ]
    }
  }
}
```

### Headers de negociação (envelope fechado)

No request, o cliente envia:

- `UI-Schema-Version`
- `Client-Platform` (iOS, Android)
- `Client-Version`
- `Client-Build`
- `OS-Version`
- `Component-Capabilities` (opcional, ex.: `["chat_input", "delivery_timeline"]`)

No response, o backend pode ecoar:

- `UI-Schema-Version`
- `Spec-Revision-Id` (para observabilidade)[^4_1]

______________________________________________________________________

## 4) Actions (intenções de usuário)

Baseado no Airbnb (`IAction`) e em Joud, defina um enum fechado de ações:

| Action Type          | Campos principais                          | Uso                                    |
|:---------------------|:-------------------------------------------|:---------------------------------------|
| `navigate`           | `route`, `params` (map)                    | Navegar para outra tela                |
| `openSheet`          | `sheetId`, `params`                        | Abrir modal/sheet                      |
| `callApi`            | `endpoint`, `method`, `payload` (opcional) | Chamar API (checkout, enviar mensagem) |
| `trackEvent`         | `eventName`, `properties` (map)            | Analytics                              |
| `completeOnboarding` | —                                          | Finalizar onboarding                   |
| `addToCart`          | `productId`, `quantity`                    | Adicionar ao carrinho                  |

[^4_6][^4_2][^4_1]

Exemplo de `ActionDto`:

```json
{
  "type": "navigate",
  "route": "product_detail",
  "params": {
    "productId": "espresso-001"
  }
}
```

______________________________________________________________________

## 5) Templates de Screen (receita de composição)

O template é o documento persistido que o composer lê para montar a tela. Exemplo (JSON):

```json
{
  "screenId": "home_coffee",
  "nodes": [
    {
      "id": "home-hero",
      "type": "hero_banner",
      "source": {
        "repo": "promos",
        "segment": "homepage",
        "limit": 1
      },
      "placement": "header",
      "order": 0
    },
    {
      "id": "category-grid",
      "type": "category_grid",
      "source": {
        "repo": "categories",
        "segment": "all",
        "limit": 6
      },
      "placement": "header",
      "order": 1
    },
    {
      "id": "product-list-featured",
      "type": "product_list",
      "source": {
        "repo": "products",
        "segment": "featured",
        "limit": 8
      },
      "layout": "vertical",
      "placement": "main",
      "order": 0
    },
    {
      "id": "promo-banner",
      "type": "promo_banner",
      "source": {
        "repo": "promos",
        "segment": "seasonal",
        "limit": 1
      },
      "placement": "main",
      "order": 1,
      "flagId": "summerPromo"
    },
    {
      "id": "footer-cta",
      "type": "footer_cta",
      "data": {
        "title": "Become a member",
        "ctaLabel": "Join now"
      },
      "placement": "footer",
      "order": 0
    }
  ]
}
```

O composer:

1. Lê o template.
2. Para cada nó com `source`, chama repositório.
3. Mapeia domínio → DTOs de section.
4. Aplica fallback builder.
5. Aplica feature flags (ex.: `flagId: summerPromo`).
6. Monta `sections[]` e `layout` do payload.[^4_1]

______________________________________________________________________

## 6) Exemplos de Telas por Domínio

### 6.1) Coffee Shop App

**Screens:**

- `home_coffee`
    - Sections: `hero_banner`, `category_grid`, `product_list` (featured), `promo_banner`, `footer_cta`.
- `menu`
    - Sections: `category_grid`, `product_list` (por categoria), `cart_summary`.
- `product_detail`
    - Sections: `hero_banner` (imagem do produto), `product_card` (detalhes), `quick_action_grid` (adicionais),
      `footer_cta` (addToCart).

[^4_2][^4_3][^4_1]

### 6.2) Fintech / Banking App

**Screens:**

- `home_fintech`
    - Sections: `account_summary`, `quick_action_grid`, `transaction_list` (recentes), `insight_card`.
- `transactions`
    - Sections: `transaction_list` (com filtro), `pagination_indicator`.
- `account_detail`
    - Sections: `balance_card`, `transaction_list`, `footer_cta` (extrato).

[^4_4][^4_6][^4_2][^4_1]

### 6.3) Food Delivery / Pizza Hut

**Screens:**

- `home_delivery`
    - Sections: `hero_banner`, `category_grid`, `product_list` (restaurantes/populares), `promo_banner`.
- `restaurant_menu`
    - Sections: `hero_banner` (restaurante), `category_grid` (categorias do menu), `product_list` (itens).
- `cart`
    - Sections: `cart_summary`, `product_list` (itens do carrinho), `footer_cta` (checkout).
- `tracking`
    - Sections: `order_status_card`, `delivery_timeline`, `driver_card`, `chat_thread`, `chat_input`.

[^4_3][^4_6][^4_2][^4_1]

### 6.4) Medium-like Feed

**Screens:**

- `home_feed`
    - Sections: `tag_pill` (tópicos), `article_card` (feed), `subscribe_banner`.
- `article_detail`
    - Sections: `article_card` (conteúdo), `author_card`, `footer_cta` (subscribe).

[^4_8][^4_2][^4_3][^4_1]

### 6.5) Onboarding (Zand Bank, etc.)

**Screen:**

- `onboarding`
    - Sections: `onboarding_page` (repetida N vezes, cada uma com `pagination_indicator`), `footer_cta` (skip/next).

[^4_6][^4_2][^4_1]

______________________________________________________________________

## 7) Próximos passos para implementação no seu MS

Com essa proposta, você pode:

1. **Definir o pacote de contrato** (`@sdui/contracts` equivalente em Java):
    - Enums de `ComponentType`, `ActionType`, `LayoutType`.
    - DTOs de sections e actions (Records, sealed classes).
    - Schemas de validação (Bean Validation / custom validators).[^4_1]
2. **Implementar o composer**:
    - Stateless, por request.
    - Lê template, chama repositórios, emite DTOs, aplica fallback e flags.[^4_1]
3. **Criar repositórios de domínio** (sem conhecimento de UI):
    - `ProductRepository`, `PromoRepository`, `CategoryRepository`, `TransactionRepository`, `OrderRepository`,
      `ChatRepository`, etc.[^4_1]
4. **Persistir templates de screen**:
    - Em MongoDB (conforme instruções do projeto).
    - Admin tool (futura) para editar templates.[^4_1]
5. **Instrumentar observabilidade**:
    - Logar tipo de section, schema version, app version.
    - Métricas de `compose.hit/miss`, `section.*`, `payload.bytes`, etc.[^4_1]
6. **Negociação de versão**:
    - Headers de request/response.
    - Fallback e rollback por pointer.[^4_1]

<span style="display:none">[^4_10][^4_11][^4_12][^4_13][^4_14][^4_15][^4_16][^4_17]</span>

<div align="center">⁂</div>

[^4_1]: https://joudwawad.medium.com/how-airbnb-netflix-and-lyft-ship-ui-without-touching-the-app-store-49c9f64f5e2b

[^4_2]: https://medium.com/airbnb-engineering/a-deep-dive-into-airbnbs-server-driven-ui-system-842244c5f5

[^4_3]: https://blog.bolajiayodeji.com/using-graphql-for-building-server-driven-uis

[^4_4]: https://edilec.com/blog/proeng-11018/server-driven-ui-mobile-apps-governance/

[^4_5]: https://keninkujovic.com/blog/server-driven-ui

[^4_6]: https://vladblajovan.github.io/articles/server-driven-ui-mobile-guide/

[^4_7]: https://sph.sh/en/posts/server-driven-ui-mobile/

[^4_8]: https://www.design-extractor.com/gallery/airbnb

[^4_9]: https://www.tothenew.com/blog/design-once-stream-everywhere-responsive-ui-for-ott-applications/

[^4_10]: .gitattributes

[^4_11]: https://neciudan.dev/implementing-server-driven-ui

[^4_12]: https://deepwiki.com/leejet/sdcpp-webui/5.2-layout-and-responsive-behaviour

[^4_13]: https://resumegeni.com/blog/ios-engineer/at-airbnb

[^4_14]: https://superdesign.dev/blog/airbnb-design-system

[^4_15]: https://a2aprotocol.ai/blog/2026-a2ui-developer-guide

[^4_16]: https://uzori.ai/blog/server-driven-ui-swiftui-schema

[^4_17]: https://everyonesdesign.pages.dev/companies/airbnb


---

## 1) Exemplo de template persistido em MongoDB

Abaixo está um exemplo realista de documento que você persistiria em MongoDB (coleção `screen_templates`). Ele descreve
a **home de um coffee shop** usando o catálogo de sections proposto. O documento é a “receita” que o composer vai
caminhar para emitir o payload final.[^5_1][^5_2]

```json
{
  "_id": "home_coffee_v1",
  "screenId": "home_coffee",
  "title": "Home - Coffee Shop",
  "schemaVersion": "1.0.0",
  "status": "PUBLISHED",
  "createdAt": "2026-09-20T22:00:00Z",
  "updatedAt": "2026-09-20T22:00:00Z",
  "nodes": [
    {
      "id": "home-hero",
      "type": "hero_banner",
      "source": {
        "repo": "promos",
        "segment": "homepage",
        "limit": 1
      },
      "placement": "header",
      "order": 0,
      "fallback": {
        "type": "text",
        "data": {
          "text": "Welcome"
        }
      }
    },
    {
      "id": "category-grid",
      "type": "category_grid",
      "source": {
        "repo": "categories",
        "segment": "all",
        "limit": 6
      },
      "placement": "header",
      "order": 1,
      "fallback": {
        "type": "text",
        "data": {
          "text": "Categories"
        }
      }
    },
    {
      "id": "product-list-featured",
      "type": "product_list",
      "source": {
        "repo": "products",
        "segment": "featured",
        "limit": 8
      },
      "layout": "vertical",
      "placement": "main",
      "order": 0,
      "fallback": {
        "type": "text",
        "data": {
          "text": "Featured products"
        }
      }
    },
    {
      "id": "promo-banner",
      "type": "promo_banner",
      "source": {
        "repo": "promos",
        "segment": "seasonal",
        "limit": 1
      },
      "placement": "main",
      "order": 1,
      "flagId": "summerPromo",
      "fallback": {
        "type": "text",
        "data": {
          "text": "Promotion"
        }
      }
    },
    {
      "id": "footer-cta",
      "type": "footer_cta",
      "data": {
        "title": "Become a member",
        "ctaLabel": "Join now"
      },
      "placement": "footer",
      "order": 0,
      "actions": {
        "onTap": {
          "type": "navigate",
          "route": "membership",
          "params": {}
        }
      },
      "fallback": {
        "type": "text",
        "data": {
          "text": "Join us"
        }
      }
    }
  ],
  "layout": {
    "type": "single_column",
    "placements": {
      "header": {
        "order": 0
      },
      "main": {
        "order": 1
      },
      "footer": {
        "order": 2
      }
    }
  }
}
```

Pontos importantes (alinhados aos artigos):

- `schemaVersion` e `status: PUBLISHED` permitem negociação e imutabilidade da spec publicada.[^5_1]
- Cada nó tem `fallback` obrigatório (regra do Shopify pattern).[^5_1]
- `flagId` em `promo-banner` permite A/B test ou feature flag no composer.[^5_1]
- `placement` + `order` definem onde e em que ordem a section aparece no layout.[^5_2]

Você pode ter documentos similares para:

- `home_fintech` (fintech home)
- `home_delivery` (food delivery home)
- `tracking` (delivery tracking com chat)
- `onboarding` (onboarding de fintech)
- `home_feed` (Medium-like feed)

Cada um com `nodes` descrevendo as sections específicas do domínio.[^5_3][^5_4][^5_5][^5_2][^5_1]

______________________________________________________________________

## 2) Esqueleto do composer em Java (método puro)

Abaixo está um esqueleto conceitual de como o **composer** pode ser estruturado em Kotlin. A ideia é mostrar o fluxo,
não detalhes de framework. O composer é **stateless**, recebe contexto + template, caminha os nós, chama repositórios e
emite o payload.[^5_2][^5_1]

```java
// Pseudo-código conceitual (sem detalhes de Spring, apenas fluxo)

public record ComposerContext(
        String userId,
        String schemaVersion,
        String clientPlatform,
        String clientVersion,
        Map<String, Object> flags,
        FormFactor formFactor
) {
}

public record ScreenTemplate(
        String screenId,
        String schemaVersion,
        List<TemplateNode> nodes,
        LayoutDefinition layout
) {
}

public record TemplateNode(
        String id,
        String type,
        SourceSpec source,        // repo, segment, limit, filters
        String placement,
        int order,
        ComponentData data,       // para nós sem source (ex.: footer_cta estático)
        String flagId,
        ComponentData fallback,
        ActionSpec actions
) {
}

public record ScreenPayload(
        String schemaVersion,
        String screenId,
        List<SectionDto> sections,
        LayoutDto layout
) {
}

public class Composer {

    private final ProductRepository products;
    private final PromoRepository promos;
    private final CategoryRepository categories;
    private final TransactionRepository transactions;
    private final OrderRepository orders;
    private final ChatRepository chat;
    // ... outros repositórios de domínio

    public ScreenPayload compose(ComposerContext context, ScreenTemplate template) {
        // 1) Filtrar nós por flag (se flagId presente e usuário não está no bucket, pular ou substituir)
        List<TemplateNode> activeNodes = filterByFlags(context, template.nodes());

        // 2) Para cada nó, resolver dados e emitir SectionDto
        List<SectionDto> sections = new ArrayList<>();
        for (TemplateNode node : activeNodes) {
            SectionDto section = resolveNode(context, node);
            sections.add(section);
        }

        // 3) Ordenar sections por placement + order (ou deixar cliente ordenar via layout)
        //    Aqui você pode agrupar por placement se quiser.

        // 4) Montar layout final (pode ser o mesmo do template ou adaptado por formFactor)
        LayoutDto layoutDto = adaptLayout(context, template.layout());

        return new ScreenPayload(
                template.schemaVersion(),
                template.screenId(),
                sections,
                layoutDto
        );
    }

    private SectionDto resolveNode(ComposerContext context, TemplateNode node) {
        // Se tem source, chama repositório
        if (node.source() != null) {
            return resolveWithSource(context, node);
        }
        // Se não tem source, usa data estático (ex.: footer_cta hard-coded)
        return resolveStaticNode(context, node);
    }

    private SectionDto resolveWithSource(ComposerContext context, TemplateNode node) {
        SourceSpec src = node.source();

        List<?> domainObjects = switch (src.repo()) {
            case "products" -> products.list(src.segment(), src.limit(), src.filters());
            case "promos" -> promos.list(src.segment(), src.limit(), src.filters());
            case "categories" -> categories.list(src.segment(), src.limit(), src.filters());
            case "transactions" -> transactions.list(src.segment(), src.limit(), src.filters());
            case "orders" -> orders.list(src.segment(), src.limit(), src.filters());
            case "chat" -> chat.list(src.segment(), src.limit(), src.filters());
            default -> throw new IllegalArgumentException("Unknown repo: " + src.repo());
        };

        // Mapear domínio → DTO de section (ex.: Product → ProductCardDto, etc.)
        SectionDto section = mapToSectionDto(node.type(), node.id(), domainObjects, node.fallback(), node.actions());

        return section;
    }

    private SectionDto resolveStaticNode(ComposerContext context, TemplateNode node) {
        // Para nós sem source (ex.: footer_cta estático)
        SectionDto section = new SectionDto(
                node.id(),
                node.type(),
                node.data(),          // já é ComponentData
                node.fallback(),
                node.actions()
        );
        return section;
    }

    private SectionDto mapToSectionDto(
            String type,
            String id,
            List<?> domainObjects,
            ComponentData fallback,
            ActionSpec actions
    ) {
        // Aqui você tem o "vocabulário" de sections:
        // - product_list → mapeia List<Product> → List<ProductCardDto>
        // - transaction_list → mapeia List<Transaction> → List<TransactionRowDto>
        // - category_grid → mapeia List<Category> → List<CategoryPillDto>
        // - chat_thread → mapeia List<ChatMessage> → List<ChatMessageDto>
        // etc.

        // Exemplo conceitual:
        return switch (type) {
            case "product_list" -> {
                List<ProductCardDto> cards = ((List<Product>) domainObjects)
                        .stream()
                        .map(p -> new ProductCardDto(p.id(), p.name(), p.price(), p.imageUrl(), buildNavigateAction(p)))
                        .toList();
                yield new SectionDto(id, type, new ProductListData(cards), fallback, actions);
            }
            case "transaction_list" -> {
                List<TransactionRowDto> rows = ((List<Transaction>) domainObjects)
                        .stream()
                        .map(t -> new TransactionRowDto(t.id(), t.title(), t.amount(), t.date(), buildNavigateAction(t)))
                        .toList();
                yield new SectionDto(id, type, new TransactionListData(rows), fallback, actions);
            }
            // ... outros types
            default -> throw new IllegalArgumentException("Unknown section type: " + type);
        };
    }

    private LayoutDto adaptLayout(ComposerContext context, LayoutDefinition layout) {
        // Aqui você pode adaptar layout por formFactor (compact/wide) como o Airbnb faz.
        // Por enquanto, retorna o mesmo layout.
        return new LayoutDto(layout.type(), layout.placements());
    }

    private ActionDto buildNavigateAction(Product p) {
        return new ActionDto("navigate", Map.of("route", "product_detail", "productId", p.id()));
    }

    private ActionDto buildNavigateAction(Transaction t) {
        return new ActionDto("navigate", Map.of("route", "transaction_detail", "transactionId", t.id()));
    }

    private List<TemplateNode> filterByFlags(ComposerContext context, List<TemplateNode> nodes) {
        // Implementação de feature flag:
        // - Se node.flagId == null → manter
        // - Se node.flagId != null → consultar contexto.flags e decidir se mantém ou substitui por fallback/outra section
        return nodes; // simplificado
    }
}
```

Pontos-chave (alinhados aos artigos):

- **Repositórios não sabem de UI**; só o composer mapeia domínio → DTOs de section.[^5_1]
- **Fallback** é anexado a cada section (regra do Shopify pattern).[^5_1]
- **Feature flags** podem substituir nós inteiros (ex.: `promo-banner` só para bucket específico).[^5_1]
- **Layout adaptado por formFactor** (como o Airbnb faz com `ILayout` por compact/wide).[^5_2]

Esse esqueleto é suficiente para você implementar o composer no seu usando Kotlin e Spring Boot 4.1.x.[^5_2][^5_1]

______________________________________________________________________

## 3) Exemplos de diferentes interfaces (mapeando para as imagens do projeto)

Abaixo, descrevo como cada tipo de interface das imagens anexadas pode ser montada pelo backend SDUI usando o catálogo
de sections e layouts proposto. A ideia é mostrar que **o mesmo mecanismo** cobre domínios diferentes (coffee, fintech,
delivery, feed, onboarding).[^5_4][^5_5][^5_3][^5_2][^5_1]

### 3.1) Coffee Ordering Mobile App (imagens: `coffee-app-wireframe-to-design-home.jpg`,

`coffee-app-all-screens-flow.jpg`)

**Telas típicas:**

1. **Home (coffee)**
    - Sections:
        - `hero_banner` (destaque do dia: “Espresso duplo, 20% off”)
        - `category_grid` (Espresso, Latte, Cappuccino, Pastries)
        - `product_list` (featured products)
        - `promo_banner` (campanha sazonal)
        - `footer_cta` (“Become a member”)
    - Layout: `single_column` com placements `header`, `main`, `footer`.[^5_2][^5_1]
2. **Menu**
    - Sections:
        - `category_grid` (filtros por categoria)
        - `product_list` (lista vertical de produtos por categoria)
        - `cart_summary` (resumo do carrinho no topo ou footer)
    - Layout: `single_column` ou `scrollable_grid` se quiser grid de produtos.[^5_2][^5_1]
3. **Product Detail**
    - Sections:
        - `hero_banner` (imagem grande do produto)
        - `product_card` (detalhes: descrição, preço, customizações)
        - `quick_action_grid` (adicionais: leite, açúcar, tamanho)
        - `footer_cta` (“Add to cart”)
    - Layout: `single_column`.[^5_2][^5_1]
4. **Cart / Checkout**
    - Sections:
        - `cart_summary` (itemCount, subtotal, deliveryFee, total)
        - `product_list` (itens do carrinho, cada um com `product_card` simplificado)
        - `footer_cta` (“Checkout”)
    - Layout: `single_column`.[^5_1][^5_2]

### 3.2) Fintech / Banking App (imagens: `banking-app-home-cards-transactions.jpg`,

`fintech-onboarding-passcode-phone.jpg`)

**Telas típicas:**

1. **Home (fintech)**
    - Sections:
        - `account_summary` ou `balance_card` (saldo principal)
        - `quick_action_grid` (Transferir, Pagar, Pix, Investir)
        - `transaction_list` (últimas transações)
        - `insight_card` (gasto por categoria, meta de economia)
    - Layout: `single_column`.[^5_5][^5_4][^5_2][^5_1]
2. **Transactions**
    - Sections:
        - `transaction_list` (com filtro por período/categoria)
        - `pagination_indicator` (se houver paginação)
    - Layout: `single_column`.[^5_2][^5_1]
3. **Account Detail**
    - Sections:
        - `balance_card` (saldo detalhado)
        - `transaction_list` (transações da conta)
        - `footer_cta` (“Download statement”)
    - Layout: `single_column`.[^5_1][^5_2]
4. **Onboarding (Zand Bank)**
    - Screen: `onboarding`
    - Sections:
        - `onboarding_page` (repetida N vezes, cada uma com título, corpo, imagem, CTA)
        - `pagination_indicator` (bolinhas indicando página atual)
        - `footer_cta` (“Skip”, “Next”, “Get started”)
    - Layout: `single_column` com scroll horizontal ou pages controladas pelo cliente.[^5_5][^5_2][^5_1]

### 3.3) Food Delivery / Pizza Hut (imagens: `food-delivery-pizza-home-categories.jpg`)

**Telas típicas:**

1. **Home (delivery)**
    - Sections:
        - `hero_banner` (promoção principal: “Pizza grande + bebida”)
        - `category_grid` (Pizzas, Bebidas, Acompanhamentos)
        - `product_list` (restaurantes/populares)
        - `promo_banner` (frete grátis, cupom)
    - Layout: `single_column`.[^5_3][^5_2][^5_1]
2. **Restaurant Menu**
    - Sections:
        - `hero_banner` (foto do restaurante, nota, tempo de entrega)
        - `category_grid` (categorias do menu: Pizzas, Bebidas, etc.)
        - `product_list` (itens do menu por categoria)
    - Layout: `single_column`.[^5_2][^5_1]
3. **Cart**
    - Sections:
        - `cart_summary` (itemCount, subtotal, deliveryFee, total)
        - `product_list` (itens do carrinho)
        - `footer_cta` (“Checkout”)
    - Layout: `single_column`.[^5_1][^5_2]
4. **Tracking**
    - Sections:
        - `order_status_card` (status: “Em preparo”, “Saiu para entrega”, tempo estimado)
        - `delivery_timeline` (etapas: confirmado, em preparo, saiu, entregue)
        - `driver_card` (nome, foto, veículo, rating, botão de ligar)
        - `chat_thread` (mensagens com o entregador)
        - `chat_input` (campo para enviar mensagem)
    - Layout: `single_column` ou `two_pane` em tablets (mapa à direita, chat à esquerda).[^5_5][^5_2][^5_1]

### 3.4) Medium-like Feed (imagem: `Medium.jpg`)

**Telas típicas:**

1. **Home Feed**
    - Sections:
        - `tag_pill` (tópicos: Technology, Culture, AI, etc.)
        - `article_card` (feed de artigos: título, subtítulo, autor, imagem, readTime)
        - `subscribe_banner` (“Subscribe to Medium”)
    - Layout: `single_column`.[^5_6][^5_3][^5_2][^5_1]
2. **Article Detail**
    - Sections:
        - `article_card` (conteúdo do artigo, pode ser uma section específica `article_body`)
        - `author_card` (foto, bio, followerCount)
        - `footer_cta` (“Subscribe to author”)
    - Layout: `single_column`.[^5_2][^5_1]
3. **Topic / Tag**
    - Sections:
        - `tag_pill` (tópicos relacionados)
        - `article_card` (artigos do tópico)
    - Layout: `single_column`.[^5_1][^5_2]

### 3.5) Creative \& Modern UI (imagens: `ecommerce-fashion-catalog-detail-cart.jpg`,

`logistics-shipment-tracking-map.jpg`)

Essas imagens mostram variações visuais (cores, tipografia, espaçamento), mas estruturalmente se enquadram nos mesmos
padrões:

- **Home genérica**: `hero_banner` + `category_grid` + `product_list` + `promo_banner` + `footer_cta`.
- **Detail genérico**: `hero_banner` (imagem) + `product_card` (detalhes) + `quick_action_grid` + `footer_cta`.
- **Tracking com chat**: `order_status_card` + `delivery_timeline` + `driver_card` + `chat_thread` +
  `chat_input`.[^5_3][^5_5][^5_2][^5_1]

O backend SDUI não envia cor, tipografia, margin, padding, gap, width, height, rounded, orientation, radius, dp/pt —
isso é **Design System nativo** do cliente. O servidor envia apenas **estrutura, conteúdo, ordem e ações**; o cliente
decide aparência (dark mode, Dynamic Type, densidade de tela, convenção de plataforma).[^5_1]

______________________________________________________________________

## Como usar isso no seu MS SDUI Home

Com essa proposta, você tem:

- **Template persistido em MongoDB** (ex.: `home_coffee_v1`) que descreve a receita da tela.[^5_2][^5_1]
- **Esqueleto do composer** que caminha o template, chama repositórios, emite sections e layout.[^5_2][^5_1]
- **Mapeamento claro** de como cada interface das imagens (coffee, fintech, delivery, feed, onboarding) pode ser montada
  pelo mesmo mecanismo.[^5_4][^5_3][^5_5][^5_1][^5_2]

Próximos passos práticos:

1. Criar a coleção `screen_templates` em MongoDB e persistir templates como o exemplo acima.
2. Implementar o composer (stateless) que lê template + contexto e emite `ScreenPayload`.
3. Implementar repositórios de domínio (products, promos, categories, transactions, orders, chat).
4. Instrumentar headers de negociação (`UI-Schema-Version`, `Client-Platform`, `Client-Version`, etc.) e observabilidade
   (tipo de section, schema version, app version).[^5_1][^5_2]

Se quiser, posso refinar algum ponto específico (ex.: detalhar mais um domínio, ajustar catálogo de sections, ou propor
um schema JSON formal para o payload).

<div align="center">⁂</div>

[^5_1]: https://joudwawad.medium.com/how-airbnb-netflix-and-lyft-ship-ui-without-touching-the-app-store-49c9f64f5e2b

[^5_2]: https://medium.com/airbnb-engineering/a-deep-dive-into-airbnbs-server-driven-ui-system-842244c5f5

[^5_3]: https://blog.bolajiayodeji.com/using-graphql-for-building-server-driven-uis

[^5_4]: https://edilec.com/blog/proeng-11018/server-driven-ui-mobile-apps-governance/

[^5_5]: https://vladblajovan.github.io/articles/server-driven-ui-mobile-guide/

[^5_6]: https://www.design-extractor.com/gallery/airbnb


---

## Exemplos de diferentes interfaces — mapeamento SDUI por imagem

Abaixo, descrevo cada uma das imagens que você mencionou (e outras do mesmo conjunto) e mostro como o backend SDUI pode
montar essas telas usando o catálogo de sections e layouts proposto. O objetivo é provar que **o mesmo mecanismo** cobre
domínios diferentes (coffee, crypto, banking, ecommerce, delivery, logistics, fintech
onboarding).[^6_1][^6_2][^6_3][^6_4][^6_5]

______________________________________________________________________

## 1) `coffee-app-wireframe-to-design-home.jpg`

**Domínio:** Coffee shop app (home).[^6_3]

**Estrutura típica observada em wireframes desse tipo:**

- Hero/banner no topo com promoção do dia.
- Grid de categorias (Espresso, Latte, Cappuccino, Pastries).
- Lista de produtos em destaque (vertical ou horizontal).
- Banner promocional sazonal.
- CTA no footer (“Become a member”, “Order now”).

**Mapeamento SDUI:**

- **Screen:** `home_coffee`
- **Layout:** `single_column` com placements `header`, `main`, `footer`.[^6_2]
- **Sections:**
    - `hero_banner` (promoção do dia)
    - `category_grid` (categorias de bebidas/comidas)
    - `product_list` (featured products)
    - `promo_banner` (campanha sazonal)
    - `footer_cta` (membership/CTA)[^6_1][^6_2]

**Template (resumo):**

```json
{
  "screenId": "home_coffee",
  "nodes": [
    {
      "id": "home-hero",
      "type": "hero_banner",
      "source": {
        "repo": "promos",
        "segment": "homepage"
      },
      "placement": "header",
      "order": 0
    },
    {
      "id": "category-grid",
      "type": "category_grid",
      "source": {
        "repo": "categories",
        "segment": "all"
      },
      "placement": "header",
      "order": 1
    },
    {
      "id": "product-list-featured",
      "type": "product_list",
      "source": {
        "repo": "products",
        "segment": "featured"
      },
      "placement": "main",
      "order": 0
    },
    {
      "id": "promo-banner",
      "type": "promo_banner",
      "source": {
        "repo": "promos",
        "segment": "seasonal"
      },
      "placement": "main",
      "order": 1,
      "flagId": "summerPromo"
    },
    {
      "id": "footer-cta",
      "type": "footer_cta",
      "data": {
        "title": "Become a member",
        "ctaLabel": "Join now"
      },
      "placement": "footer",
      "order": 0
    }
  ]
}
```

______________________________________________________________________

## 2) `coffee-app-all-screens-flow.jpg`

**Domínio:** Coffee shop app (flow completo: home, menu, detail, cart, checkout).[^6_3]

**Telas típicas:**

1. **Home** (já descrita acima).
2. **Menu**
    - Sections:
        - `category_grid` (filtros por categoria)
        - `product_list` (lista de produtos por categoria)
        - `cart_summary` (resumo do carrinho, se visível)
    - Layout: `single_column` ou `scrollable_grid`.[^6_2]
3. **Product Detail**
    - Sections:
        - `hero_banner` (imagem do produto)
        - `product_card` (detalhes: descrição, preço, customizações)
        - `quick_action_grid` (adicionais: leite, açúcar, tamanho)
        - `footer_cta` (“Add to cart”)
    - Layout: `single_column`.[^6_1][^6_2]
4. **Cart**
    - Sections:
        - `cart_summary` (itemCount, subtotal, deliveryFee, total)
        - `product_list` (itens do carrinho)
        - `footer_cta` (“Checkout”)
    - Layout: `single_column`.[^6_2][^6_1]

**Prova de conceito:** o mesmo catálogo de sections cobre todas as telas do flow; o backend só muda o template de
screen.[^6_1][^6_2]

______________________________________________________________________

## 3) `crypto-wallet-home-withdraw.jpg`

**Domínio:** Crypto wallet (home + withdraw).[^6_4][^6_5]

**Estrutura típica:**

- Home:
    - Card de saldo principal (balance em BTC/ETH/USD).
    - Grid de ações rápidas (Send, Receive, Buy, Sell, Swap).
    - Lista de ativos (portfolio: coin, amount, value, change %).
    - Lista de transações recentes.
    - Banner promocional (earn, staking, new coin).
- Withdraw:
    - Formulário de withdraw (amount, destination address, network).
    - Resumo de taxas.
    - CTA (“Withdraw”).

**Mapeamento SDUI — Home:**

- **Screen:** `home_crypto`
- **Layout:** `single_column`.
- **Sections:**
    - `balance_card` (saldo principal em crypto/fiat)
    - `quick_action_grid` (Send, Receive, Buy, Sell, Swap)
    - `product_list` (portfolio: cada item como `crypto_asset_card`)
    - `transaction_list` (transações recentes)
    - `promo_banner` (earn/staking)[^6_5][^6_4][^6_2][^6_1]

**Mapeamento SDUI — Withdraw:**

- **Screen:** `withdraw_crypto`
- **Layout:** `single_column`.
- **Sections:**
    - `hero_banner` (título: “Withdraw BTC”)
    - `product_card` (formulário: amount, address, network — aqui você pode usar uma section específica `withdraw_form`
      ou `form_field` se quiser)
    - `cart_summary` (resumo: amount, fee, total)
    - `footer_cta` (“Withdraw”)[^6_2][^6_1]

Se quiser ser mais fiel ao padrão SDUI (sem enviar formulário complexo), você pode modelar o withdraw como:

- `text` (label)
- `input_field` (section específica para input de amount/address)
- `quick_action_grid` (seleção de network)
- `footer_cta` (submit)

Mas, novamente, o importante é que **o mesmo mecanismo** cobre home e withdraw; só muda o template.[^6_1][^6_2]

______________________________________________________________________

## 4) `banking-app-home-cards-transactions.jpg`

**Domínio:** Banking app (home com cards de conta e lista de transações).[^6_4][^6_5]

**Estrutura típica:**

- Cards de conta (checking, savings, credit card).
- Ações rápidas (Transferir, Pagar, Pix, Investir).
- Lista de transações recentes.
- Insights (gasto por categoria, meta de economia).

**Mapeamento SDUI:**

- **Screen:** `home_banking`
- **Layout:** `single_column`.
- **Sections:**
    - `account_summary` ou múltiplos `balance_card` (uma section por conta, ou uma section `account_list` que contém
      vários cards)
    - `quick_action_grid` (Transferir, Pagar, Pix, Investir)
    - `transaction_list` (transações recentes)
    - `insight_card` (gasto por categoria, meta)[^6_5][^6_4][^6_2][^6_1]

**Template (resumo):**

```json
{
  "screenId": "home_banking",
  "nodes": [
    {
      "id": "account-cards",
      "type": "account_list",
      "source": {
        "repo": "accounts",
        "segment": "all"
      },
      "placement": "header",
      "order": 0
    },
    {
      "id": "quick-actions",
      "type": "quick_action_grid",
      "source": {
        "repo": "quickActions",
        "segment": "home"
      },
      "placement": "main",
      "order": 0
    },
    {
      "id": "recent-transactions",
      "type": "transaction_list",
      "source": {
        "repo": "transactions",
        "segment": "recent",
        "limit": 5
      },
      "placement": "main",
      "order": 1
    },
    {
      "id": "spending-insight",
      "type": "insight_card",
      "source": {
        "repo": "insights",
        "segment": "spending"
      },
      "placement": "main",
      "order": 2
    }
  ]
}
```

______________________________________________________________________

## 5) `finance-app-card-expenses-light-dark.jpg`

**Domínio:** Finance app (visão de cartão de crédito + despesas, com temas light/dark).[^6_4][^6_5]

**Estrutura típica:**

- Card de crédito (limite, gasto atual, fatura).
- Lista de despesas do cartão.
- Gráficos de gasto por categoria (pode ser uma section `chart_card` ou `insight_card`).
- Filtros (mês, categoria).

**Mapeamento SDUI:**

- **Screen:** `card_detail`
- **Layout:** `single_column`.
- **Sections:**
    - `balance_card` (limite, gasto atual, fatura)
    - `transaction_list` (despesas do cartão, com filtro por mês)
    - `insight_card` (gráfico de gasto por categoria — o cliente renderiza o gráfico nativo; o servidor envia apenas
      dados)
    - `category_grid` ou `tag_pill` (filtros de categoria)[^6_5][^6_4][^6_2][^6_1]

Observação: o backend **não envia** o gráfico como imagem ou DSL de pixel; envia apenas os dados (labels, valores) e o
cliente renderiza o gráfico nativo com o Design System da plataforma.[^6_1]

______________________________________________________________________

## 6) `fintech-onboarding-passcode-phone.jpg`

**Domínio:** Fintech onboarding (telas de onboarding + setup de passcode).[^6_5]

**Estrutura típica:**

- Onboarding:
    - Páginas sequenciais com título, corpo, imagem, CTA.
    - Indicador de página (bolinhas).
    - CTA “Skip”, “Next”, “Get started”.
- Setup de passcode:
    - Tela com título (“Create your passcode”).
    - Input de passcode (4 ou 6 dígitos).
    - CTA (“Continue”).

**Mapeamento SDUI — Onboarding:**

- **Screen:** `onboarding`
- **Layout:** `single_column` (com scroll horizontal ou pages controladas pelo cliente).
- **Sections:**
    - `onboarding_page` (repetida N vezes, cada uma com título, corpo, imagem, CTA)
    - `pagination_indicator` (bolinhas)
    - `footer_cta` (“Skip”, “Next”, “Get started”)[^6_2][^6_5][^6_1]

**Mapeamento SDUI — Passcode:**

- **Screen:** `setup_passcode`
- **Layout:** `single_column`.
- **Sections:**
    - `hero_banner` (título: “Create your passcode”)
    - `product_card` (input de passcode — ou section específica `passcode_input`)
    - `footer_cta` (“Continue”)[^6_2][^6_1]

Novamente, o mesmo mecanismo cobre onboarding e setup; só muda o template.[^6_1][^6_2]

______________________________________________________________________

## 7) `ecommerce-fashion-catalog-detail-cart.jpg`

**Domínio:** Ecommerce de moda (catalog, detail, cart).[^6_3][^6_5]

**Telas típicas:**

1. **Catalog (lista de produtos)**
    - Sections:
        - `category_grid` ou `tag_pill` (filtros: categoria, tamanho, cor)
        - `product_list` (grid ou lista de produtos)
        - `footer_cta` (filtros avançados, sort)
    - Layout: `scrollable_grid` ou `single_column`.[^6_2]
2. **Product Detail**
    - Sections:
        - `hero_banner` (imagens do produto)
        - `product_card` (detalhes: descrição, preço, avaliações, tamanho, cor)
        - `quick_action_grid` (tamanhos, cores)
        - `footer_cta` (“Add to cart”, “Buy now”)
    - Layout: `single_column`.[^6_1][^6_2]
3. **Cart**
    - Sections:
        - `cart_summary` (itemCount, subtotal, deliveryFee, total)
        - `product_list` (itens do carrinho)
        - `footer_cta` (“Checkout”)
    - Layout: `single_column`.[^6_2][^6_1]

**Prova de conceito:** o mesmo catálogo de sections cobre catalog, detail e cart; só muda o template de
screen.[^6_1][^6_2]

______________________________________________________________________

## 8) `food-delivery-pizza-home-categories.jpg`

**Domínio:** Food delivery (pizza) — home com categorias.[^6_3][^6_5]

**Estrutura típica:**

- Hero/banner com promoção principal.
- Grid de categorias (Pizzas, Bebidas, Acompanhamentos).
- Lista de restaurantes/populares.
- Banner promocional (frete grátis, cupom).

**Mapeamento SDUI:**

- **Screen:** `home_delivery`
- **Layout:** `single_column`.
- **Sections:**
    - `hero_banner` (promoção principal)
    - `category_grid` (categorias)
    - `product_list` (restaurantes/populares)
    - `promo_banner` (frete grátis, cupom)[^6_3][^6_5][^6_2][^6_1]

**Template (resumo):**

```json
{
  "screenId": "home_delivery",
  "nodes": [
    {
      "id": "home-hero",
      "type": "hero_banner",
      "source": {
        "repo": "promos",
        "segment": "homepage"
      },
      "placement": "header",
      "order": 0
    },
    {
      "id": "category-grid",
      "type": "category_grid",
      "source": {
        "repo": "categories",
        "segment": "all"
      },
      "placement": "header",
      "order": 1
    },
    {
      "id": "restaurant-list",
      "type": "product_list",
      "source": {
        "repo": "restaurants",
        "segment": "popular"
      },
      "placement": "main",
      "order": 0
    },
    {
      "id": "promo-banner",
      "type": "promo_banner",
      "source": {
        "repo": "promos",
        "segment": "delivery"
      },
      "placement": "main",
      "order": 1
    }
  ]
}
```

______________________________________________________________________

## 9) `logistics-shipment-tracking-map.jpg`

**Domínio:** Logistics / shipment tracking (com mapa e timeline).[^6_5][^6_3]

**Estrutura típica:**

- Card de status do pedido (status, tempo estimado).
- Timeline de etapas (confirmado, em trânsito, saiu para entrega, entregue).
- Mapa (renderizado nativamente pelo cliente).
- Card do motorista/entregador (nome, foto, veículo, rating).
- Chat com o entregador (thread + input).

**Mapeamento SDUI:**

- **Screen:** `shipment_tracking`
- **Layout:** `single_column` ou `two_pane` (em tablets: mapa à direita, chat à esquerda).[^6_2]
- **Sections:**
    - `order_status_card` (status, tempo estimado)
    - `delivery_timeline` (etapas)
    - `driver_card` (dados do entregador)
    - `chat_thread` (mensagens)
    - `chat_input` (input de mensagem)[^6_5][^6_1][^6_2]

Observação: o backend **não envia** o mapa como imagem ou DSL de pixel; envia apenas os dados (coordenadas, status,
etapas) e o cliente renderiza o mapa nativo com o Design System da plataforma.[^6_1]

______________________________________________________________________

## 10) `nubank-home-sections-comparison.jpg`

**Domínio:** Fintech (estilo Nubank) — home com sections comparativas.[^6_4][^6_5]

**Estrutura típica:**

- Card de saldo principal.
- Ações rápidas (Transferir, Pagar, Pix, Investir, Empréstimo).
- Lista de transações recentes.
- Cards de produtos (cartão de crédito, conta digital, investimentos).
- Insights (gasto por categoria, meta).

**Mapeamento SDUI:**

- **Screen:** `home_fintech`
- **Layout:** `single_column`.
- **Sections:**
    - `balance_card` (saldo principal)
    - `quick_action_grid` (ações rápidas)
    - `transaction_list` (transações recentes)
    - `product_list` (produtos: cartão, conta, investimentos — cada um como `product_card`)
    - `insight_card` (gasto por categoria, meta)[^6_4][^6_5][^6_2][^6_1]

**Template (resumo):**

```json
{
  "screenId": "home_fintech",
  "nodes": [
    {
      "id": "balance-card",
      "type": "balance_card",
      "source": {
        "repo": "accounts",
        "segment": "primary"
      },
      "placement": "header",
      "order": 0
    },
    {
      "id": "quick-actions",
      "type": "quick_action_grid",
      "source": {
        "repo": "quickActions",
        "segment": "home"
      },
      "placement": "main",
      "order": 0
    },
    {
      "id": "recent-transactions",
      "type": "transaction_list",
      "source": {
        "repo": "transactions",
        "segment": "recent",
        "limit": 5
      },
      "placement": "main",
      "order": 1
    },
    {
      "id": "products",
      "type": "product_list",
      "source": {
        "repo": "products",
        "segment": "home"
      },
      "placement": "main",
      "order": 2
    },
    {
      "id": "spending-insight",
      "type": "insight_card",
      "source": {
        "repo": "insights",
        "segment": "spending"
      },
      "placement": "main",
      "order": 3
    }
  ]
}
```

______________________________________________________________________

## Conclusão — prova de conceito

Com o catálogo de sections e layouts proposto, você pode montar **qualquer tela** dos domínios das imagens (coffee,
crypto, banking, ecommerce, delivery, logistics, fintech onboarding) usando o **mesmo mecanismo SDUI**:

- **Backend:** persiste templates de screen em MongoDB; composer caminha o template, chama repositórios, emite sections
  e layout.[^6_2][^6_1]
- **Cliente:** registra renderizadores nativos para cada `type` de section; lê `data` e `actions`; renderiza com Design
  System nativo (dark mode, Dynamic Type, convenções de plataforma).[^6_1][^6_2]

Isso prova que o backend SDUI pode retornar **qualquer montagem de tela** dentro de um catálogo finito de sections e
layouts, cobrindo todos os exemplos das imagens anexadas.[^6_3][^6_4][^6_5][^6_2][^6_1]

<div align="center">⁂</div>

[^6_1]: https://joudwawad.medium.com/how-airbnb-netflix-and-lyft-ship-ui-without-touching-the-app-store-49c9f64f5e2b

[^6_2]: https://medium.com/airbnb-engineering/a-deep-dive-into-airbnbs-server-driven-ui-system-842244c5f5

[^6_3]: https://blog.bolajiayodeji.com/using-graphql-for-building-server-driven-uis

[^6_4]: https://edilec.com/blog/proeng-11018/server-driven-ui-mobile-apps-governance/

[^6_5]: https://vladblajovan.github.io/articles/server-driven-ui-mobile-guide/

