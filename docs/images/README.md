# Referências Visuais e Fronteira de Tokens SDUI — MS SDUI Composer

> **O que este documento é:** um guia de leitura das imagens em `docs/images/`. Ele mostra, imagem por imagem, o que
> pertence ao backend (o **QUÊ**: tipos semânticos, ordem e layout de slots, conteúdo e intenções) e o que pertence ao
> app e ao Design System nativo (o **COMO**: cor, tipografia, espaçamento, raio, densidade, tema, animação).
>
> **Fase do serviço:** em desenvolvimento. O `ms-sdui-composer` compõe a árvore de UI de uma surface a partir de spec
> versionada, contexto do cliente e capabilities declaradas, e entrega um envelope REST/JSON para iOS e Android. A
> primeira surface é `home`; a segunda, `catalog` (ADR-020). Este documento descreve o estado atual **e** o que ainda
> é proposta, sempre marcando qual é qual. Exemplos executáveis das composições em
> [`docs/examples/screens`](../examples/screens/README.md).

## 0. Precedência

Em caso de conflito sobre o **estado atual**, vence o item mais alto:

1. Código de validação em `sdui-core`: `MvpCatalog.kt`, `Guards.kt`, `SkeletonValidator.kt`, `SpecValidator.kt`.
2. Fixture canônica `sdui-contract/src/test/resources/fixtures/contrato-sdui-home-definitivo.json` e os testes de
   `sdui-contract`.
3. ADRs `ACEITO` (`docs/arquitetura-de-referencia.md` §16 e `docs/adr/README.md`).
4. Este documento.

ADRs `PROPOSTO` indicam o **rumo**, não o estado. Neste documento eles aparecem marcados com **[proposto]**:

| ADR     | Tema                                       | Status     | Efeito neste documento                                                        |
|---------|--------------------------------------------|------------|-------------------------------------------------------------------------------|
| ADR-015 | Escopo de uso do SDUI                      | `ACEITO`   | Quais imagens retratam fluxos que ficam fora                                  |
| ADR-017 | Experimentação por revisão de spec         | `PROPOSTO` | Braços de experimento podem diferir em conteúdo e montagem                    |
| ADR-018 | Montagem variável da surface               | `ACEITO`   | Ordem dos slots é dado do skeleton; cada slot declara layouts permitidos      |
| ADR-019 | Remoção de `variant` do `shortcut_shelf@1` | `ACEITO`   | `variant` saiu da fixture e está no `VisualGuard`                             |
| ADR-020 | Múltiplas surfaces e contratos novos       | `PROPOSTO` | Surface `catalog` e três contratos implementados no servidor, não homologados |

Três regras de leitura que atravessam o documento:

- **O mecanismo é agnóstico de domínio; o catálogo, não.** Os 7 types da fixture são de produto financeiro. Comércio
  entrou por ADR (ADR-020), com surface própria (`catalog`) e contratos próprios. As imagens de café, pizza e logística
  continuam como referência de **padrão** (montagem, omissão, layout token, porta de entrada para fluxo nativo).
- **Types aprovados hoje:** os 7 da Home mais `transaction_summary@1`, `catalog_navigation@1` e `product_collection@1`
  **[proposto]** — aceitos pelo servidor, entregues só a quem declara a capability, não homologados pelos apps. Slots:
  os 7 da Home mais `transactions`; na surface `catalog`, `header`, `navigation`, `featured` e `products`. Qualquer
  outro
  nome é "candidato observado" (seção 5).
- **Fluxos hostis ficam fora** (ADR-015): autenticação, onboarding, checkout, mapa e formulários.

---

## 1. Inventário

| #  | Arquivo                                                                                    | Plataforma | Domínio                           | Relação com a Home                 |
|----|--------------------------------------------------------------------------------------------|------------|-----------------------------------|------------------------------------|
| 1  | [`banking-app-home-cards-transactions.jpg`](./banking-app-home-cards-transactions.jpg)     | iOS        | Banking: cartões e transações     | **Direta**                         |
| 2  | [`coffee-app-all-screens-flow.jpg`](./coffee-app-all-screens-flow.jpg)                     | iOS        | Cafeteria: fluxo de 16 telas      | Padrão; maioria das telas é hostil |
| 3  | [`coffee-app-wireframe-to-design-home.jpg`](./coffee-app-wireframe-to-design-home.jpg)     | iOS        | Cafeteria: wireframe vs design    | Padrão (a tese QUÊ × COMO)         |
| 4  | [`crypto-wallet-home-withdraw.jpg`](./crypto-wallet-home-withdraw.jpg)                     | iOS        | Carteira e saque                  | **Direta** na Home; saque hostil   |
| 5  | [`ecommerce-fashion-catalog-detail-cart.jpg`](./ecommerce-fashion-catalog-detail-cart.jpg) | iOS        | Moda: catálogo, detalhe, carrinho | Padrão; detalhe e carrinho fora    |
| 6  | [`finance-app-card-expenses-light-dark.jpg`](./finance-app-card-expenses-light-dark.jpg)   | iOS        | Finanças: claro vs escuro         | **Direta**                         |
| 7  | [`fintech-onboarding-passcode-phone.jpg`](./fintech-onboarding-passcode-phone.jpg)         | iOS        | Onboarding: passcode e telefone   | **Hostil**                         |
| 8  | [`food-delivery-pizza-home-categories.jpg`](./food-delivery-pizza-home-categories.jpg)     | iOS        | Delivery: Home                    | Padrão                             |
| 9  | [`logistics-shipment-tracking-map.jpg`](./logistics-shipment-tracking-map.jpg)             | iOS        | Logística: entregas e mapa        | Padrão; mapa hostil                |
| 10 | [`nubank-home-sections-comparison.jpg`](./nubank-home-sections-comparison.jpg)             | Android    | Banco digital: duas montagens     | **Direta**. Referência principal.  |

Nove das dez imagens são iOS. Enquanto a fixture Android (H14) não existir, a imagem 10 é a única referência visual do
lado Android.

---

## 2. Dois dicionários que não se misturam

| Dicionário                               | Quem decide         | Onde vive            | Exemplos                                                                                                                                           |
|------------------------------------------|---------------------|----------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| **Tokens SDUI (contrato)**               | Backend             | JSON da resposta     | `type`, `typeVersion`, `slot`, ordem dos slots, layout token do slot, `props` de conteúdo, `actions`, estados semânticos (`concealed`, `selected`) |
| **Tokens de renderização (`renderer/`)** | App e Design System | Código iOS e Android | Cores hex, fontes, raios, dp/pt, sombras, tema claro/escuro, densidade, dots, peek de carrossel, animações                                         |

Os valores `renderer/...` citados na seção 8 documentam o que o designer fez, para deixar claro o que **não** vai para
o JSON.

---

## 3. Layout tokens

O skeleton tem layout `vertical_scroll`. Cada slot declara um token do enum fechado `SlotLayout`. O token diz **como
os itens se organizam**, não quanto medem.

| Token   | Significado                       | O que fica com o renderer                                             |
|---------|-----------------------------------|-----------------------------------------------------------------------|
| `fixed` | Bloco único, sem rolagem própria  | Altura, alinhamento, elevação                                         |
| `shelf` | Prateleira com rolagem horizontal | Largura dos itens, espaçamento, inércia                               |
| `grid`  | Grade de itens                    | Número de colunas (`WindowSizeClass` no Android, size classes no iOS) |
| `list`  | Empilhamento vertical             | Divisores, highlight, altura das linhas                               |
| `pager` | Carrossel paginado                | Snap, transição, dots                                                 |

**Hoje (ADR-018, ADR-020):** cada slot declara `allowedLayouts`, e o `SkeletonValidator` recusa layout fora da regra
do slot na surface (`Surfaces`):

| Surface   | Slot           | `allowedLayouts` |
|-----------|----------------|------------------|
| `home`    | `header`       | `fixed`          |
| `home`    | `shortcuts`    | `shelf`, `grid`  |
| `home`    | `accounts`     | `list`, `fixed`  |
| `home`    | `cards`        | `list`, `pager`  |
| `home`    | `offers`       | `list`, `pager`  |
| `home`    | `coverage`     | `list`, `shelf`  |
| `home`    | `foryou`       | `pager`, `list`  |
| `home`    | `transactions` | `list`           |
| `catalog` | `header`       | `fixed`          |
| `catalog` | `navigation`   | `shelf`, `fixed` |
| `catalog` | `featured`     | `pager`, `list`  |
| `catalog` | `products`     | `grid`, `list`   |

---

## 4. Catálogo de section types (schema `3`)

Fonte: `MvpCatalog.TYPES` e fixture canônica. São 7 types, todos `@1`.

| Type               | Slot        | Props de conteúdo                                                                                                | Actions usadas                  |
|--------------------|-------------|------------------------------------------------------------------------------------------------------------------|---------------------------------|
| `top_bar@1`        | `header`    | `greetingName`, `greetingPrefix`, `avatarUrl`, `loyaltyLabel`, `primaryActionLabel`, `locationLabel`             | `navigate`, `track`             |
| `shortcut_shelf@1` | `shortcuts` | `title`, `items[].{id, label, icon, badge, selected, actionId}`                                                  | `navigate`, `track`             |
| `account_card@1`   | `accounts`  | `title`, `concealable`, `concealed`, `rows[].{id, label, valueDisplay, valueDisplayRevealed, concealable}`       | `navigate`                      |
| `card_product@1`   | `cards`     | `brandLabel`, `last4`, `rows[].{id, label, valueDisplay, valueDisplayRevealed, concealable}`, `secondaryDisplay` | `navigate`, `open_bottom_sheet` |
| `credit_offer@1`   | `offers`    | `badge`, `headlineDisplay`, `subtitle`, `primaryLabel`, `secondaryLabel`, `imageUrl`                             | `navigate`, `open_bottom_sheet` |
| `coverage_card@1`  | `coverage`  | `title`, `subtitle`, `assetLabel`, `primaryLabel`, `rows[].{id, label, valueDisplay, valueNorm}`, `imageUrl`     | `navigate`, `open_bottom_sheet` |
| `decision_card@1`  | `foryou`    | `kicker`, `body`, `rejectLabel`, `acceptLabel`, `imageUrl`                                                       | `navigate`, `noop`, `track`     |

Cada type representa um **conceito de produto**, não uma forma (regra do Lyft, ADR-010): `card_product` é cartão de
crédito, `credit_offer` é oferta de crédito, `coverage_card` é seguro, `decision_card` é uma decisão aceitar/recusar.
Usar `credit_offer` para cupom de café ou `card_product` para um moletom é erro semântico, mesmo que os campos caibam.

**`variant`.** Removido da fixture e do seed (ADR-019, aceito) e bloqueado pelo `VisualGuard`: densidade é decisão
do app.

**Contratos novos [proposto].** `transaction_summary@1` (slot `transactions` da Home) e, na surface `catalog`,
`catalog_navigation@1` e `product_collection@1`. Props, limites e actions em
[`docs/contratos`](../contratos/transaction-summary-v1.md) e
[`componentes-comercio-v1.md`](../contratos/componentes-comercio-v1.md).

**Dados de cartão.** `holderName` e `expirationDate` não estão na fixture e são dado pessoal de cartão. Não adicionar:
`brandLabel` e `last4` bastam na Home.

---

## 5. Candidatos observados fora do catálogo

Nomes que surgem ao analisar as imagens, mas que não existem no catálogo. O `SpecValidator` recusa todos.

| Nome                                                 | Onde               | Diagnóstico                                                                                                   | Destino                                                                                                                                                   |
|------------------------------------------------------|--------------------|---------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `transaction_list`                                   | Imagens 1 e 6      | Conceito bancário legítimo; o sufixo `_list` nomeia forma.                                                    | **Proposto como `transaction_summary@1`** (ADR-020), slot `transactions`, `required: false`, conteúdo sintético; extrato real depende de projeção segura. |
| `asset_list`                                         | Imagem 4           | Ativos cripto fora do produto atual.                                                                          | Referência de padrão.                                                                                                                                     |
| `hero_banner`                                        | Imagens 3 e 8      | Nome por forma. Em banco, o equivalente é `credit_offer` ou `decision_card`.                                  | Não adotar.                                                                                                                                               |
| `product_card`, `product_shelf`, `product_list`      | Imagens 2, 3, 8, 9 | Varejo; `_shelf`/`_list` repetem o layout token.                                                              | Resolvido pelo conceito `product_collection@1` na surface `catalog` (ADR-020), não por esses nomes.                                                       |
| `service_grid`                                       | Imagem 9           | Serviços são atalhos: `shortcut_shelf` em `grid`.                                                             | Não adotar.                                                                                                                                               |
| `search_bar`                                         | Imagens 2, 5, 8, 9 | Busca é chrome do app.                                                                                        | Não adotar como componente. No catálogo, só a **entrada** para a busca nativa, em `catalog_navigation@1`.                                                 |
| `order_status_card`                                  | Imagem 9           | Card de entrada para fluxo nativo; nenhum type atual representa esse conceito (`decision_card` não é status). | Só com equivalente de produto, por ADR.                                                                                                                   |
| `onboarding_header`, `passcode_input`, `phone_input` | Imagem 7           | Autenticação e cadastro.                                                                                      | Hostil (ADR-015).                                                                                                                                         |

---

## 6. Actions

Conjunto fechado do ADR-011. No fio, `ActionPayload` tem apenas `route` e `sheet`.

| Type                | Payload                    | Regras do `ActionGuard`                        | Uso                                                                                 |
|---------------------|----------------------------|------------------------------------------------|-------------------------------------------------------------------------------------|
| `navigate`          | `{ "route": "app://..." }` | Rota `app://` obrigatória; `label` obrigatório | Ir a outra tela ou a um fluxo nativo                                                |
| `open_bottom_sheet` | `{ "sheet": "sheet_..." }` | `label` obrigatório                            | Abrir sheet nativo                                                                  |
| `track`             | sem payload                | —                                              | Registrar interação; identificada pelo `id` da action e pelo `analytics` da section |
| `noop`              | sem payload                | —                                              | Dispensar (ex.: "Lembrar mais tarde")                                               |

Todo `actionId` em `props` precisa apontar para uma action da mesma section. `track` não navega: filtrar um catálogo é
`navigate` para a rota filtrada, ou estado local. Não existem actions de mutação (ADR-011, ADR-015).

---

## 7. Skeleton e montagem da Home

### 7.1 Skeleton canônico

`home.default` é o skeleton da fixture canônica. A ordem é dado do skeleton (ADR-018): o seed também publica
`home.cards_first`, e os exemplos acrescentam `home.shortcuts_first`, `home.transactions` e `catalog.default`. O que
varia por spec é qual skeleton ela usa, quais sections ocupam cada slot e o conteúdo delas.

```json
{
  "id": "home.default",
  "surface": "home",
  "layout": "vertical_scroll",
  "slots": [
    {
      "id": "header",
      "layout": "fixed",
      "required": true,
      "allowedTypes": [
        "top_bar"
      ]
    },
    {
      "id": "shortcuts",
      "layout": "shelf",
      "required": false,
      "allowedTypes": [
        "shortcut_shelf"
      ]
    },
    {
      "id": "accounts",
      "layout": "list",
      "required": true,
      "allowedTypes": [
        "account_card"
      ],
      "title": "Conta"
    },
    {
      "id": "cards",
      "layout": "list",
      "required": false,
      "allowedTypes": [
        "card_product"
      ],
      "title": "Cartão de crédito"
    },
    {
      "id": "offers",
      "layout": "list",
      "required": false,
      "allowedTypes": [
        "credit_offer"
      ],
      "title": "Crédito"
    },
    {
      "id": "coverage",
      "layout": "list",
      "required": false,
      "allowedTypes": [
        "coverage_card"
      ],
      "title": "Seguros"
    },
    {
      "id": "foryou",
      "layout": "pager",
      "required": false,
      "allowedTypes": [
        "decision_card"
      ],
      "title": "Para você"
    }
  ]
}
```

`maxInstances` também faz parte de cada slot no modelo persistido; os valores estão no seed. `required`,
`allowedTypes` e `maxInstances` são validados no publish e não vão para o fio.

- **Slots portantes:** `header` e `accounts` (ADR-009). Vazios, disparam a escada de fallback (ADR-007).
- **Omitíveis:** os demais. Falha ou falta de capability gera `envelope.omitted`, com `200`.

### 7.2 Montagem variável (ADR-018, implementado)

A ordem é dado do skeleton versionado. Uma surface pode ter vários skeletons, e cada spec escolhe o seu. O
`SkeletonValidator` aplica invariantes por surface: slots do vocabulário, ids únicos, portantes presentes e marcados,
`header` sempre primeiro e layout dentro de `allowedLayouts` e da regra do slot.

Exemplo: a montagem da direita da imagem 10.

```json
{
  "id": "home.cards_first",
  "surface": "home",
  "layout": "vertical_scroll",
  "slots": [
    {
      "id": "header",
      "layout": "fixed"
    },
    {
      "id": "accounts",
      "layout": "list"
    },
    {
      "id": "cards",
      "layout": "list"
    },
    {
      "id": "shortcuts",
      "layout": "grid"
    },
    {
      "id": "offers",
      "layout": "list"
    },
    {
      "id": "coverage",
      "layout": "list"
    },
    {
      "id": "foryou",
      "layout": "pager"
    }
  ]
}
```

O **contrato de fio não muda**: o envelope já envia `skeleton.slots` em ordem. O que muda para os apps é uma regra
explícita: **renderizar os slots na ordem recebida**, nunca com posição fixa no código. Dois skeletons diferem por
produto, nunca por tamanho de tela.

### 7.3 Regiões visuais não são slots

Ao descrever uma imagem, é natural nomear regiões ("Popular Picks", "Recent shipments"). Esses nomes descrevem a
imagem. Na seção 8 eles aparecem como **regiões**; slots são só os do vocabulário de cada surface (seção 3).

---

## 8. Análise por imagem

Cada entrada traz: o que a tela mostra, o que ensina, como se expressa na Home (quando se aplica), o que fica com o
renderer e o que foi corrigido em relação à versão 2.0.

### Imagem 1 — [`banking-app-home-cards-transactions.jpg`](./banking-app-home-cards-transactions.jpg)

- **Tela:** Home bancária iOS. Topo com avatar e saudação, carrossel de cartões com peek (saldo e cartão fundidos num
  mesmo elemento), atalhos em tiles e lista de últimas transações com filtro.
- **Ensina:** carrossel é `pager`; o peek é do renderer. Saldo e cartão são conceitos distintos, mesmo quando o design
  os funde.
- **Na Home:** `top_bar@1` (`header`), `account_card@1` (`accounts`), `card_product@1` (`cards`, com `pager` sob o
  ADR-018), `shortcut_shelf@1` (`shortcuts`). Filtro de transações é `open_bottom_sheet`. Nada de type híbrido como
  `card_and_account`.
- **Transações:** proposta `transaction_summary@1` — exemplo [
  `banking.transactions`](../examples/screens/banking.transactions/README.md).
- **Renderer:** gradiente laranja, chip desenhado, peek de 24dp, raio de 20dp, verde/vermelho dos valores.
- **Produto:** o saldo não mostra controle de ocultação; na Home, `concealable`/`concealed` cobrem isso.

### Imagem 2 — [`coffee-app-all-screens-flow.jpg`](./coffee-app-all-screens-flow.jpg)

- **Tela:** 16 telas de cafeteria: onboarding, login, Home, menu, detalhe, carrinho, checkout, rastreamento, perfil. A
  Home tem saudação, busca, prateleira "New in" e lista "Frequently ordered".
- **Ensina:** a Home é uma tela entre muitas, e cada uma tem ciclo próprio. Modelar o fluxo inteiro como SDUI é o que o
  ADR-015 evita.
- **Na Home:** sem mapeamento direto. Padrão útil: `shelf` + `list` na mesma surface.
- **Hostil:** onboarding, login, checkout, rastreamento com mapa.
- **Renderer:** fundo `#1B2B38`, laranja `#E25E3E`, cards com raio de 16dp.
- **Correção:** a 2.0 mapeava para `search_bar@1`, `product_shelf@1`, `product_list@1`, fora do catálogo.

### Imagem 3 — [`coffee-app-wireframe-to-design-home.jpg`](./coffee-app-wireframe-to-design-home.jpg)

- **Tela:** a mesma Home em wireframe e em design final: top bar com busca, hero em carrossel, categorias, populares,
  banner de cupom e lista de mais vendidos.
- **Ensina:** a tese do projeto numa imagem. O wireframe é o **QUÊ** que o backend descreve; o design é o **COMO** que o
  app desenha. O JSON serve às duas.
- **Na Home:** sem mapeamento direto. Padrões úteis: carrossel no topo de conteúdo (`pager`) e blocos promocionais
  omitíveis.
- **Renderer:** tema `#1E120B`, laranja `#E57A3C`, raio de 24dp no hero, dot ativo alongado.
- **Acessibilidade:** "View all" com área mínima de 44×44pt; botão (+) com rótulo que inclua o produto.
- **Correção:** a 2.0 mapeava o cupom como `credit_offer@1` (erro semântico) e os populares como `product_card@1`.

### Imagem 4 — [`crypto-wallet-home-withdraw.jpg`](./crypto-wallet-home-withdraw.jpg)

- **Tela:** Home da carteira (top bar, saldo total com ID da carteira, quatro ações circulares, abas "My Assets"/"My
  Transaction", lista de ativos) e fluxo de saque com teclado numérico e controle de arrastar.
- **Ensina:** a fronteira do ADR-015 numa só imagem. A Home é SDUI; o saque é nativo.
- **Na Home:** `top_bar@1`, `account_card@1` (saldo), `shortcut_shelf@1` (ações). O atalho de saque faz `navigate` para
  `app://wallet/withdraw`.
- **Fora:** o saque (hostil) e a lista de ativos (seção 5).
- **Renderer:** terracota `#D86A3E`, amarelo `#F5D382`, máscara curva de 36dp, saldo em 40sp.
- **Acessibilidade:** o gesto "Swipe to Withdraw" precisa de alternativa por toque simples.

### Imagem 5 — [`ecommerce-fashion-catalog-detail-cart.jpg`](./ecommerce-fashion-catalog-detail-cart.jpg)

- **Tela:** catálogo (top bar, saudação, busca, tags de filtro, destaque, grade de produtos), detalhe e carrinho em
  bottom sheet.
- **Ensina:** bottom sheet é `open_bottom_sheet` com `sheet` nomeado; altura e raio são do renderer. Estado de seleção
  (`selected`) é conteúdo semântico, como já existe em `shortcut_shelf`.
- **Na surface `catalog`:** exemplo [`fashion.catalog`](../examples/screens/fashion.catalog/README.md) com
  `top_bar@1`, `catalog_navigation@1` e `product_collection@1` **[proposto]**.
- **Fora:** detalhe, carrinho e checkout (nativos).
- **Renderer:** barra inferior com blur, grade de 2 colunas, dourado `#F4B255`, sheet com raio de 28dp.
- **Correção:** a 2.0 mapeava o moletom como `card_product@1` e o filtro por tag como `track` com rota.

### Imagem 6 — [`finance-app-card-expenses-light-dark.jpg`](./finance-app-card-expenses-light-dark.jpg)

- **Tela:** a mesma Home em claro e escuro: top bar, cartões empilhados, atalhos (Send, Request, TopUp, More) e lista
  de despesas.
- **Ensina:** tema é 100% do renderer. O JSON é idêntico nos dois modos; `isDarkMode` e `theme` não entram no contrato.
- **Na Home:** `top_bar@1`, `card_product@1`, `shortcut_shelf@1`. "+ Add Card" é `navigate` para `app://cards/new`.
- **Fora do catálogo:** lista de despesas (mesmo caso de `transaction_list`).
- **Renderer:** fundos `#F8F8F8` e `#000000`, roxo `#8A46E4`, amarelo `#FED45B`, raio de 24dp.
- **Correção:** a 2.0 incluía `holderName` e `expirationDate` nas props do cartão.

### Imagem 7 — [`fintech-onboarding-passcode-phone.jpg`](./fintech-onboarding-passcode-phone.jpg)

- **Tela:** criação de passcode de 4 dígitos e cadastro de telefone para dois fatores.
- **Ensina:** o limite. É o exemplo canônico de fluxo hostil.
- **Na Home:** não se aplica. Não há slot, type nem spec para esta tela, em nenhuma surface.
- **Por quê:** entrada segura exige `isSecureTextEntry` (iOS), `FLAG_SECURE` (Android), teclado dedicado e anúncio de
  posição do dígito no leitor de tela. Nenhum valor digitado, hash ou token trafega pelo composer.
- **Correção:** a 2.0 descrevia um SDUI hipotético com `onboarding_header@1`, `passcode_input@1`, `phone_input@1`.

### Imagem 8 — [`food-delivery-pizza-home-categories.jpg`](./food-delivery-pizza-home-categories.jpg)

- **Tela:** Home densa de delivery: top bar com badges, busca, hero em carrossel, categorias circulares (com "All"
  selecionado), populares com badges e botão (+), banner de combos, selos de confiança.
- **Ensina:** uma Home comercial densa ainda se decompõe em poucos blocos. Promocionais (hero, combos) são os
  candidatos naturais a omissão, e a ordem deles é o tipo de coisa que o ADR-018 deixa variar.
- **Na Home:** sem mapeamento direto.
- **Renderer:** vermelho `#E31837`, tipografia condensada, botão central flutuante, raio de 20dp.
- **Correção:** a 2.0 mapeava os combos como `credit_offer@1` e os populares como `product_card@1`.

### Imagem 9 — [`logistics-shipment-tracking-map.jpg`](./logistics-shipment-tracking-map.jpg)

- **Tela:** dashboard de entregas (top bar, busca, grade de serviços, remessa atual, remessas recentes) e rastreamento
  com mapa em tempo real.
- **Ensina:** o padrão **porta de entrada**. O card vive na Home; o toque faz `navigate` para `app://tracking/{id}`; o
  mapa é nativo.
- **Na Home:** a grade de serviços é conceitualmente atalho: `shortcut_shelf@1` com `grid`. O card da remessa não tem
  type equivalente (seção 5).
- **Hostil:** mapa e rastreamento. Coordenadas, polilinhas e telemetria nunca entram no JSON.
- **Renderer:** laranja `#FF6633`, rota desenhada, altura do sheet.
- **Correção:** a 2.0 mapeava a remessa como `decision_card@1` (não é decisão) e listava `service_grid@1` e
  `product_list@1`.

### Imagem 10 — [`nubank-home-sections-comparison.jpg`](./nubank-home-sections-comparison.jpg)

- **Tela:** a mesma Home Android em duas montagens.
    - **Esquerda:** header, saldo, atalhos em prateleira (4 itens, um com badge "R$ 12.500"), cartões, empréstimo,
      seguros em prateleira.
    - **Direita:** `cards` sobe para logo abaixo de `accounts`; `shortcuts` vira grade 2×4 com 8 atalhos.
- **Ensina:** é o objetivo do serviço em uma imagem. A **ordem** dos blocos e o **layout** de um slot mudam por
  configuração, sem release; o Design System resolve o resto.
- **Na Home:** mapeia quase 1:1: `top_bar@1`, `account_card@1`, `shortcut_shelf@1`, `card_product@1`,
  `credit_offer@1`, `coverage_card@1`.
- **O que o serviço faz hoje:** as duas montagens, por skeleton (ADR-018): `shelf`/`grid` em `shortcuts` e `cards`
  acima de `shortcuts`. Exemplos [`banking.shortcuts_first`](../examples/screens/banking.shortcuts_first/README.md) e
  [`banking.cards_first`](../examples/screens/banking.cards_first/README.md); a troca é de revisão pelo pointer.
- **Renderer:** roxo `#820AD1`, botões `#8A05BE`, badge lilás `#E8D5F5`, círculos de 56dp, divisores de 1dp.
- **Produto:** ocultar saldo deveria ocultar também o limite do cartão. É regra a combinar com os apps, não campo novo.
- **Acessibilidade:** o controle de visibilidade anuncia "Saldos ocultos" / "Saldos exibidos".

---

## 9. Rastreabilidade: imagem → contrato

| Imagem              | Slots da Home                                                    | Types do catálogo                                                                             | Fora de escopo ou do catálogo              |
|---------------------|------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|--------------------------------------------|
| 1. banking          | `header`, `accounts`, `cards`, `shortcuts`, `transactions`       | `top_bar`, `account_card`, `card_product`, `shortcut_shelf`, `transaction_summary` [proposto] | —                                          |
| 2. coffee flow      | —                                                                | —                                                                                             | Fluxo completo (padrão)                    |
| 3. coffee wireframe | —                                                                | —                                                                                             | Varejo (padrão)                            |
| 4. crypto           | `header`, `accounts`, `shortcuts`                                | `top_bar`, `account_card`, `shortcut_shelf`                                                   | Saque; lista de ativos                     |
| 5. ecommerce        | `catalog`: `header`, `navigation`, `featured`, `products`        | `top_bar`, `catalog_navigation`, `product_collection` [proposto]                              | Detalhe, carrinho e checkout (nativos)     |
| 6. finance          | `header`, `cards`, `shortcuts`                                   | `top_bar`, `card_product`, `shortcut_shelf`                                                   | Lista de despesas                          |
| 7. onboarding       | —                                                                | —                                                                                             | Fluxo inteiro (hostil)                     |
| 8. food delivery    | —                                                                | —                                                                                             | Varejo (padrão)                            |
| 9. logistics        | `shortcuts`                                                      | `shortcut_shelf`                                                                              | Card de remessa; mapa                      |
| 10. nubank          | `header`, `accounts`, `shortcuts`, `cards`, `offers`, `coverage` | `top_bar`, `account_card`, `shortcut_shelf`, `card_product`, `credit_offer`, `coverage_card`  | NuEnsina e "Acompanhe também" sem contrato |

`decision_card` (`foryou`) não tem correspondente visual; sua referência é a fixture.

---

## 10. Chaves que nunca entram no JSON

### 10.1 Bloqueadas hoje pelo `VisualGuard`

Lista exata de `MvpCatalog.VISUAL_KEYS`, sem diferenciar maiúsculas, em qualquer profundidade de `props`:

```text
color, background, font, typography,
margin, padding, gap,
width, height, radius, rounded, cornerRadius, shadow,
orientation, circle, rectangle, shimmer, ripple, haptic,
dp, pt, itemWidth, itemHeight, breakpoint, formFactor,
columns, componentType, appearance, presentation, style, size, variant
```

A cópia dessa lista no `NoVisualAttributesTest` é conferida contra `MvpCatalog.VISUAL_KEYS` pelo
`VisualKeysAlignmentTest`.

### 10.2 Proibidas por regra, ainda sem guard

A regra existe (ADR-010), a verificação ainda não:

```text
isDarkMode, theme, prominent
```

### 10.3 Dado regulado

Bloqueado pelo `PiiGuard`, por chave (`MvpCatalog.PII_KEYS`) e por formato (CPF e PAN):

```text
cpf, pan, cvv, password, senha, token, jwt, secret, accountNumber, agencia, conta, pin, otp, passcode
```

No catálogo de comércio, o `ComponentPropsValidator` recusa ainda chaves de operação comercial (`quantity`, `favorite`,
`addToCart`, `cart`, `checkout`, `stock`, `sku`).

---

## 11. Backlog

Priorizado pelo custo de adiar: numa fase de desenvolvimento, o que mexe no modelo vem antes do que só acrescenta.

| Prioridade | Item                                                                                                         | Status        | Por que agora                                                                                             |
|------------|--------------------------------------------------------------------------------------------------------------|---------------|-----------------------------------------------------------------------------------------------------------|
| **P0**     | ADR-018: ordem dos slots como dado do skeleton, `allowedLayouts` por slot, regra de ordem para os apps       | **CONCLUÍDO** | Invariantes no `SkeletonValidator`, `allowedLayouts` no `SlotDefinition`, seed e testes implementados.    |
| **P0**     | ADR-019: remover `variant` da fixture e do teste de exceção                                                  | **CONCLUÍDO** | `variant` removido de fixtures/seed e proibido em `VISUAL_KEYS` e guards.                                 |
| **P0**     | Fixture com segunda montagem da Home, para testes de renderer iOS e Android                                  | **CONCLUÍDO** | `contrato-sdui-home-cards-first.json` criada e testada em `CardsFirstHomeContractTest`.                   |
| **P1**     | Unificar `VISUAL_KEYS` e a lista do `NoVisualAttributesTest`; levar a seção 10.2 ao guard                    | **CONCLUÍDO** | `VisualKeysAlignmentTest` garante integridade estrita entre os módulos.                                   |
| **P1**     | Fixture de regressão com spec no formato do `docs/05`                                                        | **CONCLUÍDO** | `proposta-docs-05-hostil.json` criada e validada em `RejectedProposalContractTest` e `SpecValidatorTest`. |
| **P1**     | Revisar o ADR-015 com o time e aplicá-lo à próxima surface                                                   | **APLICADO**  | Surface `catalog` (ADR-020): detalhe, carrinho e checkout ficaram nativos; revisão com o time pendente.   |
| **P2**     | Avaliar `pin`, `otp`, `passcode` em `PII_KEYS`                                                               | **CONCLUÍDO** | `pin`, `otp`, `passcode` adicionados a `MvpCatalog.PII_KEYS` e validados pelo `PiiGuard`.                 |
| **P2**     | Decidir transações recentes na Home                                                                          | **PROPOSTO**  | `transaction_summary@1` implementado no servidor com conteúdo sintético; aguarda homologação dos apps.    |
| **P2**     | Reservar o campo `experiment` no pointer (ADR-017)                                                           | **CONCLUÍDO** | `ExperimentArm`, `ExperimentConfig` e campo `experiment` adicionados ao `Pointer`.                        |
| **P3**     | Guia de renderer para iOS e Android (registry por `type@typeVersion`, dispatcher de actions, ordem recebida) | Em aberto     | Item aproveitado do `docs/05` para documentação dos apps clientes.                                        |

---

## 12. Nomes e eixos de compatibilidade

- **Nomes:** `snake_case`, pelo conceito de produto. Recusados: genéricos (`GENERIC_TYPE_NAMES`: `row`, `column`,
  `container`, `stack`, `card`, `generic_card`, `list_item`), sufixos que repetem layout token em types novos (`_list`,
  `_grid`, `_shelf`) e nomenclatura de frameworks de terceiros.
- **Três eixos de versão, independentes:**
    1. **A, envelope:** `API-Version: 1` e `schemaVersion: "3"`.
    2. **B, renderer:** `type@typeVersion`; capabilities efetivas = matriz do servidor por plataforma/versão + delta de
       `Component-Capabilities`.
    3. **C, app e SO:** `Client-Version`, `Client-Build` e versão de SO.

---

## 13. Referências

- ADRs do projeto: 004, 007, 009, 010, 011 (em `docs/arquitetura-de-referencia.md` §16) e 014 a 019 (em
  `docs/memoria-operacional-e-arquitetural.md` §8 e `docs/adr/README.md`).
- Martin
  Fowler — [Presentation and Application Controller](https://martinfowler.com/eaaCatalog/applicationController.html).
- Airbnb
  Engineering — [A Deep Dive into Airbnb's Server-Driven UI System](https://medium.com/airbnb-engineering/a-deep-dive-into-airbnbs-server-driven-ui-system-842244c5f5).
- Joud
  Wawad — [How Airbnb, Netflix and Lyft ship UI without touching the App Store](https://joudwawad.medium.com/how-airbnb-netflix-and-lyft-ship-ui-without-touching-the-app-store-49c9f64f5e2b).
- Lyft Engineering — [eng.lyft.com](https://eng.lyft.com/).
- Spotify Engineering — [engineering.atspotify.com](https://engineering.atspotify.com/).
- [RFC 6648](https://datatracker.ietf.org/doc/html/rfc6648).

---

## 14. Histórico de revisões

| Versão | Data       | Autor                        | Mudanças                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
|--------|------------|------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `1.0`  | 2026-09-20 | Wallan Pereira               | Criação inicial do guia visual.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `1.1`  | 2026-09-20 | Wallan Pereira               | Reescrita para legibilidade e organização.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `1.2`  | 2026-09-20 | Wallan Pereira               | Correção de encoding.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `2.0`  | 2026-09-20 | Antigravity + Wallan Pereira | Análise das 10 imagens, dicionários de tokens, catálogos, skeleton, rastreabilidade e backlog.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `3.0`  | 2026-09-21 | Wallan Pereira               | **Alinhamento ao código, aos ADRs e à fase de desenvolvimento.** Precedência explícita e separação entre estado atual e rumo proposto. Catálogo reduzido aos 7 types reais; types inventados viram candidatos observados. Regiões visuais separadas de slots. Montagem variável documentada como proposta (ADR-018), com exemplo da segunda montagem da imagem 10. `variant` retirado do catálogo documentado (ADR-019). Removidos `holderName` e `expirationDate`. Corrigidos usos errados de `credit_offer`, `card_product`, `decision_card` e `track`. Onboarding retirado inteiro (ADR-015). Chaves proibidas separadas entre bloqueio real e regra sem guard. Backlog repriorizado pelo custo de adiar. |
| `3.1`  | 2026-09-23 | Wallan Pereira               | **Sincronização com ADR-018/019 aceitos e ADR-020 proposto.** Ordem de slots e `allowedLayouts` como estado atual; surface `catalog`; contratos `transaction_summary@1`, `catalog_navigation@1` e `product_collection@1` como propostas; lista de `VISUAL_KEYS` e `PII_KEYS` alinhada ao código; rastreabilidade das imagens 1, 5 e 10 apontando para `docs/examples/screens`.                                                                                                                                                                                                                                                                                                                               |
