# Catálogo Visual Humano e Fronteira de Tokens SDUI — MS SDUI Composer

> **Objetivo:** Este documento estabelece o guia visual humano e a fonte canônica da verdade do catálogo (Sections,
> Layouts, Actions) do **MS SDUI Composer** a partir da análise empírica e exaustiva de cada uma das referências em
> `docs/images/`. Ele traça a fronteira inegociável entre o **QUÊ** (responsabilidade do backend: tipos semânticos,
> ordenação de slots, conteúdo estruturado e intenções de navegação) e o **COMO** (responsabilidade exclusiva do
> aplicativo e do Design System nativo: cores, hex, tipografia, margens, raios de borda, densidade em dp/pt, dark mode,
> estados de shimmer e renderização).

---

## 1. Inventário de Arquivos de Referência

A tabela abaixo lista os 10 arquivos visuais presentes no diretório, sua extensão e a plataforma mobile observável pelas
características de interface do sistema operacional (status bar, home bar, fontes e controles nativos).

| #  | Arquivo                                                                                    | Extensão | Plataforma Aparente | Domínio / Tema de Interface                                    |
|----|--------------------------------------------------------------------------------------------|----------|---------------------|----------------------------------------------------------------|
| 1  | [`banking-app-home-cards-transactions.jpg`](./banking-app-home-cards-transactions.jpg)     | `.jpg`   | iOS                 | Banking / Cartões e Transações Recentes                        |
| 2  | [`coffee-app-all-screens-flow.jpg`](./coffee-app-all-screens-flow.jpg)                     | `.jpg`   | iOS                 | Coffee Shop / Fluxo Completo de Telas                          |
| 3  | [`coffee-app-wireframe-to-design-home.jpg`](./coffee-app-wireframe-to-design-home.jpg)     | `.jpg`   | iOS                 | Coffee Shop / Comparação Wireframe vs Design Final             |
| 4  | [`crypto-wallet-home-withdraw.jpg`](./crypto-wallet-home-withdraw.jpg)                     | `.jpg`   | iOS                 | Crypto Wallet / Carteira e Fluxo de Saque                      |
| 5  | [`ecommerce-fashion-catalog-detail-cart.jpg`](./ecommerce-fashion-catalog-detail-cart.jpg) | `.jpg`   | iOS                 | Ecommerce Fashion / Catálogo, Detalhe e Modal de Carrinho      |
| 6  | [`finance-app-card-expenses-light-dark.jpg`](./finance-app-card-expenses-light-dark.jpg)   | `.jpg`   | iOS                 | Finanças Pessoais / Comparação Modo Claro vs Modo Escuro       |
| 7  | [`fintech-onboarding-passcode-phone.jpg`](./fintech-onboarding-passcode-phone.jpg)         | `.jpg`   | iOS                 | Fintech Onboarding / Telas de Passcode e Telefone              |
| 8  | [`food-delivery-pizza-home-categories.jpg`](./food-delivery-pizza-home-categories.jpg)     | `.jpg`   | iOS                 | Food Delivery / Home Rica, Promoções e Categorias              |
| 9  | [`logistics-shipment-tracking-map.jpg`](./logistics-shipment-tracking-map.jpg)             | `.jpg`   | iOS                 | Logística / Dashboard de Entregas e Rastreamento em Mapa       |
| 10 | [`nubank-home-sections-comparison.jpg`](./nubank-home-sections-comparison.jpg)             | `.jpg`   | Android             | Fintech Bancária / Comparação de Reordenação e Layout de Slots |

---

## 2. Análise Detalhada por Arquivo de Imagem

---

### Imagem 1: [`banking-app-home-cards-transactions.jpg`](./banking-app-home-cards-transactions.jpg)

- **Arquivo:** [`./banking-app-home-cards-transactions.jpg`](./banking-app-home-cards-transactions.jpg)
- **Tela / estado:** Home | estado populado com cartões e histórico de transações
- **Plataforma aparente:** iOS (Status bar 09:41, Dynamic Island / notch, indicadores de sinal e bateria da Apple, home
  indicator inferior)
- **Hierarquia (topo → base):**
    1. Top Bar com saudação textual e avatar do usuário à direita
    2. Carrossel de cartões de débito/crédito em exibição horizontal com prévia do próximo cartão
    3. Prateleira horizontal de atalhos rápidos com 4 itens
    4. Cabeçalho de transações recentes com botão de filtro
    5. Lista vertical de transações financeiras
    6. Barra de navegação global inferior do app (Home, Statistics, FAB (+), Card, Settings)
- **Slots visíveis:**
    - `header` (layout: `fixed`)
    - `cards` (layout: `pager`)
    - `shortcuts` (layout: `shelf`)
    - `feed` (layout: `list`)
- **Sections candidatas:**
    - **Nome semântico:** Barra Superior do Usuário
        - **type + typeVersion:** `top_bar@1`
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `greetingName: "Alexandra"`, `greetingPrefix: "Good Morning"`,
          `avatarUrl: "https://cdn.example-allowlist.com/avatars/alexandra.jpg"`
        - **actions visíveis:** `act_profile` (type: `navigate`, payload: `{ "route": "app://profile" }`)
    - **Nome semântico:** Cartão Bancário com Saldo
        - **type + typeVersion:** `card_product@1`
        - **layout token:** `pager`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `brandLabel: "VISA"`, `last4: "3413"`,
          `rows: [{ "id": "row_bal", "label": "Balance", "valueDisplay": "$423,812.00", "concealable": false }]`
        - **actions visíveis:** `act_card_details` (type: `navigate`, payload: `{ "route": "app://cards/3413" }`)
    - **Nome semântico:** Prateleira de Atalhos Financeiros
        - **type + typeVersion:** `shortcut_shelf@1`
        - **layout token:** `shelf`
        - **variant:** `compact`
        - **props de CONTEÚDO:**
          `items: [{ "id": "sc_send", "label": "Send", "icon": "icon.send", "actionId": "act_send" }, { "id": "sc_req", "label": "Request", "icon": "icon.request", "actionId": "act_req" }, { "id": "sc_pay", "label": "Pay bills", "icon": "icon.bill", "actionId": "act_pay" }, { "id": "sc_exchange", "label": "Exchange", "icon": "icon.exchange", "actionId": "act_exchange" }]`
        - **actions visíveis:** `act_send` (type: `navigate`, payload: `{ "route": "app://transfers/send" }`), `act_pay`
          (type: `navigate`, payload: `{ "route": "app://payments" }`)
    - **Nome semântico:** Extrato de Transações Recentes
        - **type + typeVersion:** `transaction_list@1`
        - **layout token:** `list`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `title: "Latest transactions"`,
          `items: [{ "id": "tx_1", "title": "Grocery Shopping", "subtitle": "Fresh Mart | 14 Nov 2024", "amountDisplay": "+$75,68", "icon": "icon.cart" }, { "id": "tx_2", "title": "Online Subscription", "subtitle": "Tech News | 13 Nov 2024", "amountDisplay": "-$10,99", "icon": "icon.subscription" }, { "id": "tx_3", "title": "Adsense Youtibi", "subtitle": "Italian Bistro | 12 Nov 2024", "amountDisplay": "+$65,34", "icon": "icon.income" }]`
        - **actions visíveis:** `act_filter_tx` (type: `open_bottom_sheet`, payload:
          `{ "sheet": "sheet_transaction_filters" }`), item action (type: `navigate`, payload:
          `{ "route": "app://transactions/tx_1" }`)
- **Componentes de DS aparentes (renderer):** Avatar circular, card com gradiente e linhas orgânicas em relevo,
  microchip de cartão impresso, tiles quadrados com cantos arredondados, list rows com ícones circulares e indicador de
  valor positivo/negativo por cor.
- **Padrão de scroll / agrupamento:** Scroll vertical unificado da tela (`vertical_scroll`); paginação horizontal no
  slot de cartões (`pager`) com peek visível do cartão adjacente; prateleira de atalhos horizontal (`shelf`); lista
  vertical empilhada (`list`).
- **Conteúdo de negócio vs chrome do SO:** A barra de status superior (09:41, bateria, wifi) e o home indicator são
  chrome do iOS. A bottom bar pertence ao app shell (navegação mestre fora da árvore SDUI da Home).
- **Tokens visuais observados (renderer/, não contrato):** `renderer/primary-gradient: #FF6B4A -> #FF885E`,
  `renderer/card-peek-offset: 24dp`, `renderer/card-radius: 20dp`, `renderer/shortcut-tile-size: 64x64dp`,
  `renderer/amount-green: #34C759`, `renderer/amount-dark: #1F2937`, `renderer/bg-cream: #FDFBF9`.
- **Acessibilidade aparente:** Contraste do texto "Balance" branco sobre laranja claro no cartão pode ficar abaixo de
  4.5:1; botões de atalho possuem área adequada (>= 44x44pt); o peek do segundo cartão necessita de anúncio adequado no
  leitor de tela (ex: "Cartão 1 de 2").
- **Inconsistências com outras imagens do diretório:** Combina saldo da conta e cartão de crédito em um único elemento
  gráfico em carrossel, divergindo do Nubank ([
  `nubank-home-sections-comparison.jpg`](./nubank-home-sections-comparison.jpg)), onde a Conta e o Cartão ocupam seções
  verticais distintas.
- **O que melhorar (máx. 5, priorizados):**
    1. *compose:* Marcar `transaction_list` como section omitível (`required: false`) para que indisponibilidade do
       serviço de extrato não derrube a Home.
    2. *catálogo:* Mapear o carrossel no slot `cards` com layout `pager` sem inventar tipos híbridos como
       `card_and_account`.
    3. *produto/UX:* O saldo exibido não possui botão visível de ocultação/privacidade (`concealable: true`), violando
       boas práticas de segurança em interfaces bancárias.
    4. *o que NÃO deve ir para o JSON:* Cores hexadecimais (laranja, lilás), raio de curvatura de 20dp, largura de peek
       em pixels e o desenho vetorial de fundo do cartão.

---

### Imagem 2: [`coffee-app-all-screens-flow.jpg`](./coffee-app-all-screens-flow.jpg)

- **Arquivo:** [`./coffee-app-all-screens-flow.jpg`](./coffee-app-all-screens-flow.jpg)
- **Tela / estado:** Home e Fluxo Completo (Onboarding, Login, Home, Menu, Detalhe, Carrinho, Checkout, Rastreamento,
  Perfil)
- **Plataforma aparente:** iOS (Controles modais, status bar com notch, segmented controls característicos de iOS)
- **Hierarquia (topo → base) na tela Home (coluna 4, linha 1):**
    1. Top Bar com nome da cafeteria ("Cavosh Cafe, Legnicka 20"), saudação "Good morning, user" e sino
    2. Barra de busca textual ("Search")
    3. Prateleira horizontal de novidades ("New in")
    4. Lista vertical de mais pedidos ("Frequently ordered")
    5. Barra de navegação inferior nativa
- **Slots visíveis na Home:**
    - `header` (layout: `fixed`)
    - `search` (layout: `fixed`)
    - `hero` / `new_in` (layout: `shelf`)
    - `feed` / `frequently_ordered` (layout: `list`)
- **Sections candidatas (foco na Home):**
    - **Nome semântico:** Barra de Topo com Filial e Saudação
        - **type + typeVersion:** `top_bar@1`
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `greetingName: "user"`, `greetingPrefix: "Good morning"`,
          `locationLabel: "Cavosh Cafe Legnicka 20, Wroclaw"`
        - **actions visíveis:** `act_select_store` (type: `navigate`, payload: `{ "route": "app://cafe/select" }`),
          `act_notifications` (type: `navigate`, payload: `{ "route": "app://notifications" }`)
    - **Nome semântico:** Campo de Busca
        - **type + typeVersion:** `search_bar@1`
        - **layout token:** `fixed`
        - **variant:** `compact`
        - **props de CONTEÚDO:** `placeholder: "Search"`
        - **actions visíveis:** `act_open_search` (type: `navigate`, payload: `{ "route": "app://search" }`)
    - **Nome semântico:** Prateleira de Lançamentos
        - **type + typeVersion:** `product_shelf@1`
        - **layout token:** `shelf`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `title: "New in"`,
          `items: [{ "id": "prod_1", "title": "Caramel Macchiato", "priceDisplay": "$4.00", "imageUrl": "https://cdn.example-allowlist.com/coffee/macchiato.png", "actionId": "act_prod_1" }, { "id": "prod_2", "title": "Vanilla Latte", "priceDisplay": "$3.00", "imageUrl": "https://cdn.example-allowlist.com/coffee/latte.png", "actionId": "act_prod_2" }]`
        - **actions visíveis:** `act_prod_1` (type: `navigate`, payload:
          `{ "route": "app://products/caramel-macchiato" }`)
    - **Nome semântico:** Lista de Pedidos Frequentes
        - **type + typeVersion:** `product_list@1`
        - **layout token:** `list`
        - **variant:** `compact`
        - **props de CONTEÚDO:** `title: "Frequently ordered"`,
          `items: [{ "id": "fav_1", "title": "Caramel Macchiato", "subtitle": "Large, Oat milk", "priceDisplay": "$6.70", "actionId": "act_quick_add_1" }]`
        - **actions visíveis:** `act_quick_add_1` (type: `navigate`, payload: `{ "route": "app://cart/add?id=fav_1" }`)
- **Componentes de DS aparentes (renderer):** Cards brancos com fotos de produtos recortadas, stepper numérico de volume
  (- 01 +), segmented controls em pílula (Pick up / Delivery), timeline vertical com status em bolinhas coloridas,
  switch toggles de notificações.
- **Padrão de scroll / agrupamento:** A Home utiliza rolagem vertical contínua (`vertical_scroll`), agrupando itens em
  prateleira horizontal deslizante no slot `new_in` (`shelf`) e empilhamento vertical no slot `frequently_ordered`
  (`list`).
- **Conteúdo de negócio vs chrome do SO:** A imagem abrange 16 telas do ciclo do cliente; telas de checkout, mapa e
  timeline de pedido são fluxos dedicados fora da surface Home. O chrome do SO inclui a status bar com entalhe e home
  bar.
- **Tokens visuais observados (renderer/, não contrato):** `renderer/theme-bg: #1B2B38 (azul petróleo profundo)`,
  `renderer/accent-orange: #E25E3E`, `renderer/card-bg: #FFFFFF`, `renderer/card-radius: 16dp`,
  `renderer/stepper-size: 32dp`.
- **Acessibilidade aparente:** O contraste no fundo escuro com textos secundários cinzas requer atenção para não ficar
  abaixo de 4.5:1; botões (+) de adição rápida precisam de texto alternativo claro para tecnologias assistivas.
- **Inconsistências com outras imagens do diretório:** Mostra telas com mapas de geolocalização e formulários de
  checkout em etapas, que nunca devem ser modelados como sections da Home.
- **O que melhorar (máx. 5, priorizados):**
    1. *compose:* Não tentar modelar o fluxo de checkout ou mapa como spec da Home; cada surface possui seu próprio
       ciclo de composição e envelope.
    2. *catálogo:* Reutilizar o type `product_shelf@1` e `product_list@1` sem inventar componentes específicos de café
       (`coffee_card`).
    3. *produto/UX:* O botão (+) no card pode abrir bottom sheet de customização rápida de tamanho/leite antes de
       adicionar ao carrinho.
    4. *o que NÃO deve ir para o JSON:* Cores de fundo hex (#1B2B38), coordenadas de mapa, número de colunas do grid do
       Menu e estados de animação de stepper.

---

### Imagem 3: [`coffee-app-wireframe-to-design-home.jpg`](./coffee-app-wireframe-to-design-home.jpg)

- **Arquivo:** [`./coffee-app-wireframe-to-design-home.jpg`](./coffee-app-wireframe-to-design-home.jpg)
- **Tela / estado:** Home | Comparação direta entre Wireframe (baixa fidelidade) e Design Final (alta fidelidade)
- **Plataforma aparente:** iOS (Status bar 9:41, notch / Dynamic Island, indicadores nativos de rede e bateria)
- **Hierarquia (topo → base):**
    1. Top Bar com menu hambúrguer, busca central ("Search coffee..."), sino com badge e avatar
    2. Saudação personalizada: "Good morning, User! ☕"
    3. Hero Banner em carrossel ("Your perfect coffee, delivered to you", CTA "Order Now ->", foto heroica, dots
       indicadores)
    4. Prateleira de Categorias com header ("Categories", link "View all", 5 itens: Hot Coffee, Iced Coffee,
       Frappuccino, Desserts, Tea)
    5. Prateleira de Bebidas Populares ("Popular Drinks", link "View all", 3 cards com foto, favorito, título, rating e
       preço)
    6. Banner Promocional de Desconto ("Special Offer": "Get 20% OFF on your first order", cupom "Use Code: COFFEE20")
    7. Lista vertical de Mais Vendidos ("Best Selling", link "View all", 2 itens com botão (+) laranja)
    8. Barra de navegação inferior (Home, Menu, Orders, Favorites, Profile)
- **Slots visíveis:**
    - `header` (layout: `fixed`)
    - `hero` (layout: `pager`)
    - `categories` (layout: `shelf`)
    - `popular` (layout: `shelf`)
    - `offers` (layout: `fixed`)
    - `best_selling` (layout: `list`)
- **Sections candidatas:**
    - **Nome semântico:** Barra Superior com Busca Integrada
        - **type + typeVersion:** `top_bar@1`
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `greetingName: "User"`, `greetingPrefix: "Good morning"`,
          `avatarUrl: "https://cdn.example-allowlist.com/avatars/user.jpg"`, `searchPlaceholder: "Search coffee..."`
        - **actions visíveis:** `act_search` (type: `navigate`, payload: `{ "route": "app://search" }`), `act_notif`
          (type: `navigate`, payload: `{ "route": "app://notifications" }`)
    - **Nome semântico:** Banner Heroico de Destaque
        - **type + typeVersion:** `hero_banner@1`
        - **layout token:** `pager`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `headlineDisplay: "Your perfect coffee, delivered to you"`,
          `subtitle: "Order your favorite coffee from top cafes near you."`, `primaryLabel: "Order Now"`,
          `imageUrl: "https://cdn.example-allowlist.com/banners/hero_cappuccino.jpg"`
        - **actions visíveis:** `act_hero_order` (type: `navigate`, payload: `{ "route": "app://menu" }`)
    - **Nome semântico:** Prateleira de Categorias
        - **type + typeVersion:** `shortcut_shelf@1`
        - **layout token:** `shelf`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `title: "Categories"`,
          `items: [{ "id": "cat_hot", "label": "Hot Coffee", "icon": "icon.coffee.hot", "actionId": "act_cat_hot" }, { "id": "cat_iced", "label": "Iced Coffee", "icon": "icon.coffee.iced", "actionId": "act_cat_iced" }, { "id": "cat_frap", "label": "Frappuccino", "icon": "icon.coffee.frap", "actionId": "act_cat_frap" }]`
        - **actions visíveis:** `act_view_all_cat` (type: `navigate`, payload: `{ "route": "app://categories" }`)
    - **Nome semântico:** Prateleira de Produtos Populares
        - **type + typeVersion:** `product_card@1`
        - **layout token:** `shelf`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `title: "Popular Drinks"`,
          `items: [{ "id": "prod_cap", "title": "Cappuccino", "priceDisplay": "$3.99", "ratingDisplay": "4.5", "imageUrl": "https://cdn.example-allowlist.com/products/cappuccino.jpg", "actionId": "act_prod_cap" }, { "id": "prod_latte", "title": "Iced Latte", "priceDisplay": "$3.99", "ratingDisplay": "4.6", "imageUrl": "https://cdn.example-allowlist.com/products/latte.jpg", "actionId": "act_prod_latte" }]`
        - **actions visíveis:** `act_prod_cap` (type: `navigate`, payload: `{ "route": "app://products/cappuccino" }`)
    - **Nome semântico:** Card de Oferta Especial com Cupom
        - **type + typeVersion:** `credit_offer@1`
        - **layout token:** `fixed`
        - **variant:** `compact`
        - **props de CONTEÚDO:** `headlineDisplay: "Get 20% OFF on your first order"`, `subtitle: "Use Code: COFFEE20"`,
          `imageUrl: "https://cdn.example-allowlist.com/promos/coffee_cup.jpg"`
        - **actions visíveis:** `act_apply_coupon` (type: `navigate`, payload:
          `{ "route": "app://checkout?coupon=COFFEE20" }`)
- **Componentes de DS aparentes (renderer):** Carrossel hero paginado com indicador de três pontos (sendo o ativo
  alongado), cards com bordas arredondadas e sombra suave, badges de cupom com cantos em pill, ícone de coração de
  favoritos flutuante no canto superior do card de produto.
- **Padrão de scroll / agrupamento:** A imagem sintetiza visualmente a tese do SDUI: o **Wireframe à esquerda é o QUÊ
  estrutural gerado pelo backend**, enquanto o **Design à direita é o COMO renderizado pelo aplicativo**. Scroll
  vertical contínuo (`vertical_scroll`) integrando pager, prateleiras e lista.
- **Conteúdo de negócio vs chrome do SO:** A barra de status e o home indicator pertencem ao iOS; a tab bar pertence ao
  app shell. As sections do cabeçalho até mais vendidos são compostas pelo backend.
- **Tokens visuais observados (renderer/, não contrato):** `renderer/theme-bg: #1E120B (marrom café)`,
  `renderer/accent-orange: #E57A3C`, `renderer/card-bg: #2B1E17`, `renderer/hero-radius: 24dp`,
  `renderer/category-box-size: 60x60dp`, `renderer/dot-active-width: 16dp`.
- **Acessibilidade aparente:** Alto contraste na versão Design Final; botões "View all" possuem texto reduzido e
  necessitam de área mínima de toque de 44x44pt; botões (+) de compra rápida exigem rótulo para leitor de tela indicando
  o nome do produto.
- **Inconsistências com outras imagens do diretório:** É a única referência no repositório com o contraste explícito
  entre Wireframe e Design Final, provando que o payload JSON não muda entre as duas visualizações.
- **O que melhorar (máx. 5, priorizados):**
    1. *compose:* Definir `hero_banner` e oferta promocional como omitíveis (`required: false`); caso o serviço de
       marketing falhe, a Home continua entregando categorias e produtos.
    2. *catálogo:* Utilizar `shortcut_shelf@1` com `variant: regular` para as categorias, evitando inflar o catálogo
       desnecessariamente.
    3. *produto/UX:* O código de cupom "COFFEE20" deve disparar ação de cópia com feedback (`track`) ou aplicar
       diretamente na cesta (`navigate`).
    4. *o que NÃO deve ir para o JSON:* Cores marrom/laranja, raio de 24dp do banner, formato dos dots do carrossel e
       dimensões físicas das caixas de ícone.

---

### Imagem 4: [`crypto-wallet-home-withdraw.jpg`](./crypto-wallet-home-withdraw.jpg)

- **Arquivo:** [`./crypto-wallet-home-withdraw.jpg`](./crypto-wallet-home-withdraw.jpg)
- **Tela / estado:** Home / Carteira (esquerda) e Fluxo de Saque / Modal de Transação (direita)
- **Plataforma aparente:** iOS (iPhone 14/15 Pro com Dynamic Island, status bar 9:41, home indicator)
- **Hierarquia (topo → base) na tela Home (esquerda):**
    1. Top Bar com título "Wallet", sino com badge e avatar do usuário
    2. Bloco em destaque de Saldo Total ("$23,867") com ID da carteira e ícone de cópia
    3. Prateleira de 4 botões circulares de ações rápidas: Receive, Send, Swap, Buy/Add
    4. Abas de alternância de visualização: "My Assets" (ativa) e "My Transaction"
    5. Lista vertical de criptoativos (Ethereum, Binance, Tether usd) com quantidades e valores
    6. Barra de navegação inferior (Home, Wallet, Chart, Settings)
- **Slots visíveis na Home:**
    - `header` (layout: `fixed`)
    - `accounts` (layout: `fixed`)
    - `shortcuts` (layout: `shelf`)
    - `feed` / `assets` (layout: `list`)
- **Sections candidatas:**
    - **Nome semântico:** Barra Superior da Carteira
        - **type + typeVersion:** `top_bar@1`
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `title: "Wallet"`,
          `avatarUrl: "https://cdn.example-allowlist.com/avatars/crypto_user.jpg"`
        - **actions visíveis:** `act_notif` (type: `navigate`, payload: `{ "route": "app://notifications" }`)
    - **Nome semântico:** Card Principal de Saldo de Cripto
        - **type + typeVersion:** `account_card@1`
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `title: "Wallet"`, `concealable: false`,
          `rows: [{ "id": "row_total_bal", "label": "Total Balance", "valueDisplay": "$23,867" }]`,
          `secondaryDisplay: "Wallet id: 2678900085"`
        - **actions visíveis:** `act_copy_id` (type: `track`, payload: `{ "route": "app://wallet/copy-id" }`)
    - **Nome semântico:** Prateleira de Ações de Cripto
        - **type + typeVersion:** `shortcut_shelf@1`
        - **layout token:** `shelf`
        - **variant:** `compact`
        - **props de CONTEÚDO:**
          `items: [{ "id": "sc_rec", "label": "Receive", "icon": "icon.arrow.down.left", "actionId": "act_rec" }, { "id": "sc_send", "label": "Send", "icon": "icon.arrow.up.right", "actionId": "act_send" }, { "id": "sc_swap", "label": "Swap", "icon": "icon.swap", "actionId": "act_swap" }, { "id": "sc_add", "label": "Add", "icon": "icon.plus", "actionId": "act_withdraw" }]`
        - **actions visíveis:** `act_withdraw` (type: `navigate`, payload: `{ "route": "app://wallet/withdraw" }`)
    - **Nome semântico:** Lista de Ativos da Carteira
        - **type + typeVersion:** `account_card@1` (ou `asset_list@1`)
        - **layout token:** `list`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `title: "My Assets"`,
          `rows: [{ "id": "ast_eth", "label": "Ethereum", "valueDisplay": "79.006 ETH", "valueNorm": "$100,000.00" }, { "id": "ast_bnb", "label": "Binance", "valueDisplay": "107.70 BNB", "valueNorm": "$30,812.92" }, { "id": "ast_usdt", "label": "Tether usd", "valueDisplay": "79.006 ETH", "valueNorm": "$100,000.00" }]`
        - **actions visíveis:** `act_view_eth` (type: `navigate`, payload: `{ "route": "app://assets/eth" }`)
- **Componentes de DS aparentes (renderer):** Top bar com fundo laranja e máscara curva ("sheet mask") para o container
  branco inferior, botões circulares amarelos, segmented control sem borda, teclado numérico customizado em grade 3x4 na
  tela de saque, botão de confirmação tipo slider ("Swipe to Withdraw").
- **Padrão de scroll / agrupamento:** A Home utiliza rolagem vertical (`vertical_scroll`) com cabeçalho de saldo em
  layout estático (`fixed`), prateleira horizontal de atalhos (`shelf`) e lista vertical de ativos (`list`).
- **Conteúdo de negócio vs chrome do SO:** A tela de saque à direita é um formulário de transação financeira altamente
  sensível que não pertence à surface Home e deve ser operada por fluxo nativo seguro.
- **Tokens visuais observados (renderer/, não contrato):** `renderer/brand-terracotta: #D86A3E`,
  `renderer/btn-yellow: #F5D382`, `renderer/sheet-mask-curve: 36dp`, `renderer/balance-font-size: 40sp`,
  `renderer/slider-height: 56dp`.
- **Acessibilidade aparente:** O texto de ID da carteira em branco sobre fundo laranja possui contraste inferior a 4.5:
  1; o controle gestual de arrastar ("Swipe to Withdraw") é uma barreira motora e requer alternativa de toque simples
  via tecnologia assistiva.
- **Inconsistências com outras imagens do diretório:** Uso de máscara curva ornamental entre o cabeçalho e a lista de
  ativos, que é uma decisão exclusiva do renderer e nunca deve se tornar propriedade no contrato SDUI.
- **O que melhorar (máx. 5, priorizados):**
    1. *compose:* A tela de saque ("Withdraw Money") deve ser aberta via `navigate` para rota transacional nativa
       (`app://wallet/withdraw`), sem compor formulários pelo Composer.
    2. *catálogo:* Mapear o saldo principal no slot `accounts` utilizando o tipo canônico `account_card@1`.
    3. *produto/UX:* Corrigir a notação de valor no mock ("$19,29.00" com pontuação inválida).
    4. *o que NÃO deve ir para o JSON:* Cores terracota e amarela, curvatura de máscara em pixels, layout do teclado
       numérico e o componente gestual de slider.

---

### Imagem 5: [`ecommerce-fashion-catalog-detail-cart.jpg`](./ecommerce-fashion-catalog-detail-cart.jpg)

- **Arquivo:** [`./ecommerce-fashion-catalog-detail-cart.jpg`](./ecommerce-fashion-catalog-detail-cart.jpg)
- **Tela / estado:** Jornada Completa: Catálogo / Home (esquerda), Detalhe de Produto (centro) e Modal de Carrinho em
  Bottom Sheet (direita)
- **Plataforma aparente:** iOS (Status bar 9:41, entalhe de câmera, controles nativos do iOS)
- **Hierarquia (topo → base) na tela de Catálogo (esquerda):**
    1. Top Bar com avatar, logotipo "TRENDORA" e sacola com badge indicador '8'
    2. Saudação "Hello Tavorian" e slogan de moda
    3. Barra de busca com botão de filtro lateral
    4. Prateleira horizontal de filtros em tags/pílulas ("Trending" selecionada, "Shows", "Bag", "Shirts")
    5. Card de produto em destaque ("Men's Pullover Hoodie", preço "$199.00", botão de favorito)
    6. Grade de produtos abaixo (2 colunas de cards)
    7. Barra flutuante de navegação inferior (Home, Bag, Cart, Profile)
- **Slots visíveis no Catálogo:**
    - `header` (layout: `fixed`)
    - `search` (layout: `fixed`)
    - `categories` (layout: `shelf`)
    - `featured` (layout: `fixed`)
    - `feed` (layout: `grid`)
- **Sections candidatas:**
    - **Nome semântico:** Barra de Topo da Marca
        - **type + typeVersion:** `top_bar@1`
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `greetingName: "Tavorian"`, `greetingPrefix: "Hello"`,
          `subtitle: "Fashion confidence and reveals beauty."`,
          `avatarUrl: "https://cdn.example-allowlist.com/avatars/tavorian.jpg"`
        - **actions visíveis:** `act_open_cart` (type: `open_bottom_sheet`, payload: `{ "sheet": "sheet_cart" }`)
    - **Nome semântico:** Prateleira de Tags de Filtro
        - **type + typeVersion:** `shortcut_shelf@1`
        - **layout token:** `shelf`
        - **variant:** `compact`
        - **props de CONTEÚDO:**
          `items: [{ "id": "tag_trend", "label": "Trending", "selected": true, "actionId": "act_tag_trend" }, { "id": "tag_shows", "label": "Shows", "selected": false, "actionId": "act_tag_shows" }, { "id": "tag_bag", "label": "Bag", "selected": false, "actionId": "act_tag_bag" }, { "id": "tag_shirts", "label": "Shirts", "selected": false, "actionId": "act_tag_shirts" }]`
        - **actions visíveis:** `act_tag_trend` (type: `track`, payload: `{ "route": "app://catalog?tag=trending" }`)
    - **Nome semântico:** Card de Produto em Destaque
        - **type + typeVersion:** `card_product@1`
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `brandLabel: "Men's Pullover Hoodie"`,
          `rows: [{ "id": "row_price", "label": "Price", "valueDisplay": "$199.00" }]`,
          `imageUrl: "https://cdn.example-allowlist.com/fashion/hoodie_yellow.png"`
        - **actions visíveis:** `act_view_product` (type: `navigate`, payload:
          `{ "route": "app://products/mens-pullover-hoodie" }`)
- **Componentes de DS aparentes (renderer):** Bottom sheet modal nativa com backdrop escurecido, tags em formato pill
  oval, barra de navegação flutuante com efeito de vidro fosco (blur/glassmorphism), seletores de cores em matriz
  circular e botões primários amarelos/dourados com bordas arredondadas.
- **Padrão de scroll / agrupamento:** Scroll vertical contínuo (`vertical_scroll`); tags em prateleira horizontal
  deslizante (`shelf`); produto em destaque fixo (`fixed`) e grade inferior de 2 colunas (`grid`); tela de carrinho
  apresentada como modal de gaveta (`open_bottom_sheet`).
- **Conteúdo de negócio vs chrome do SO:** A terceira tela valida perfeitamente o papel da action `open_bottom_sheet`
  (com payload `sheet: "sheet_cart"`), mantendo a navegação leve sem abrir telas completas.
- **Tokens visuais observados (renderer/, não contrato):** `renderer/accent-gold: #F4B255`,
  `renderer/badge-dark: #1E1E1E`, `renderer/glass-blur: 20px`, `renderer/card-radius: 20dp`,
  `renderer/sheet-radius: 28dp`.
- **Acessibilidade aparente:** Alto contraste dos textos pretos sobre fundo bege e dourado; chips de tamanho (XS a 3XL)
  no detalhe devem ter área de toque mínima de 44x44pt; a bottom sheet de carrinho precisa gerenciar o foco do leitor de
  tela ao ser apresentada.
- **Inconsistências com outras imagens do diretório:** Apresenta layout em grade (`grid`) no catálogo, contrastando com
  o padrão de lista/shelf predominante nos apps bancários.
- **O que melhorar (máx. 5, priorizados):**
    1. *compose:* O carrinho de compras exemplifica o uso canônico da action `open_bottom_sheet` disparada a partir da
       Home.
    2. *catálogo:* Utilizar o layout token `grid` no slot de produtos sem enviar o número de colunas (2) no JSON.
    3. *produto/UX:* A seleção de tags na prateleira deve filtrar o feed dinamicamente.
    4. *o que NÃO deve ir para o JSON:* Blur da barra flutuante, quantidade de colunas do grid, cores dos botões e raio
       de curvatura da gaveta modal.

---

### Imagem 6: [`finance-app-card-expenses-light-dark.jpg`](./finance-app-card-expenses-light-dark.jpg)

- **Arquivo:** [`./finance-app-card-expenses-light-dark.jpg`](./finance-app-card-expenses-light-dark.jpg)
- **Tela / estado:** Home / Card Detail | Comparação rigorosa entre Modo Claro (Light Mode) e Modo Escuro (Dark Mode)
- **Plataforma aparente:** iOS (Dois iPhones com Dynamic Island, status bar 9:41, indicadores nativos de bateria e
  conectividade)
- **Hierarquia (topo → base):**
    1. Top Bar com menu hambúrguer, sino com notificação e avatar do usuário
    2. Saudação ("Hello Nasara!", subtítulo "Let's save your money.")
    3. Cartões bancários sobrepostos (Cartão amarelo de fundo final `7216`; Cartão roxo frontal com logo da Apple,
       número `**** **** **** 4364`, Saldo "$3,922.40", Exp. Date "08/28", Nome "Nasara Friday G.", botão "+ Add Card")
    4. Prateleira de 4 botões circulares de atalhos rápidos: Send, Request, TopUp, More
    5. Seção de Despesas ("Manage Expenses", link "View All")
    6. Lista vertical de despesas (House Rent, Internet Bill, Groceries, Taxes)
    7. Barra flutuante de navegação inferior (Home, Wallet, Analytics, Profile)
- **Slots visíveis:**
    - `header` (layout: `fixed`)
    - `cards` (layout: `fixed`)
    - `shortcuts` (layout: `shelf`)
    - `feed` / `expenses` (layout: `list`)
- **Sections candidatas:**
    - **Nome semântico:** Barra Superior de Perfil
        - **type + typeVersion:** `top_bar@1`
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `greetingName: "Nasara"`, `greetingPrefix: "Hello"`,
          `subtitle: "Let's save your money."`, `avatarUrl: "https://cdn.example-allowlist.com/avatars/nasara.jpg"`
        - **actions visíveis:** `act_notif` (type: `navigate`, payload: `{ "route": "app://notifications" }`)
    - **Nome semântico:** Cartão de Crédito com Saldo e Adição
        - **type + typeVersion:** `card_product@1`
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `brandLabel: "Apple Card"`, `last4: "4364"`, `holderName: "Nasara Friday G."`,
          `expirationDate: "08/28"`,
          `rows: [{ "id": "row_bal", "label": "Balance", "valueDisplay": "$3,922.40", "concealable": false }]`,
          `secondaryActionLabel: "+ Add Card"`
        - **actions visíveis:** `act_add_card` (type: `navigate`, payload: `{ "route": "app://cards/new" }`)
    - **Nome semântico:** Prateleira de Atalhos de Pagamento
        - **type + typeVersion:** `shortcut_shelf@1`
        - **layout token:** `shelf`
        - **variant:** `compact`
        - **props de CONTEÚDO:**
          `items: [{ "id": "sc_send", "label": "Send", "icon": "icon.send", "actionId": "act_send" }, { "id": "sc_req", "label": "Request", "icon": "icon.request", "actionId": "act_req" }, { "id": "sc_topup", "label": "TopUp", "icon": "icon.topup", "actionId": "act_topup" }, { "id": "sc_more", "label": "More", "icon": "icon.more", "actionId": "act_more" }]`
        - **actions visíveis:** `act_send` (type: `navigate`, payload: `{ "route": "app://transfers/send" }`)
    - **Nome semântico:** Lista de Gerenciamento de Despesas
        - **type + typeVersion:** `transaction_list@1`
        - **layout token:** `list`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `title: "Manage Expenses"`,
          `items: [{ "id": "exp_1", "title": "House Rent", "subtitle": "09:00 am • 12 September, 2025", "amountDisplay": "$400.00", "icon": "icon.home" }, { "id": "exp_2", "title": "Internet Bill", "subtitle": "14:21 pm • 11 September, 2025", "amountDisplay": "$55.00", "icon": "icon.globe" }, { "id": "exp_3", "title": "Groceries", "amountDisplay": "$78.00", "icon": "icon.cart" }]`
        - **actions visíveis:** `act_view_all_expenses` (type: `navigate`, payload: `{ "route": "app://expenses" }`)
- **Componentes de DS aparentes (renderer):** Empilhamento visual de cartões com deslocamento vertical, botões
  circulares com inversão de contraste cromático, ícones de despesas em badges neutros e barra de navegação em cápsula
  flutuante.
- **Padrão de scroll / agrupamento:** Scroll vertical contínuo (`vertical_scroll`); o bloco de cartões possui arranjo
  estático empilhado (`fixed`), atalhos em prateleira (`shelf`) e despesas em lista (`list`).
- **Conteúdo de negócio vs chrome do SO:** A imagem constitui a evidência definitiva de que **Modo Claro / Modo Escuro é
  100% responsabilidade do cliente (renderer)**. O JSON emitido pelo MS SDUI Composer é rigorosamente idêntico para os
  dois modos!
- **Tokens visuais observados (renderer/, não contrato):** `renderer/theme-light-bg: #F8F8F8`,
  `renderer/theme-dark-bg: #000000`, `renderer/card-front-purple: #8A46E4`, `renderer/card-back-yellow: #FED45B`,
  `renderer/card-radius: 24dp`, `renderer/text-primary-light: #111111`, `renderer/text-primary-dark: #FFFFFF`.
- **Acessibilidade aparente:** No modo escuro, o contraste dos textos brancos sobre fundo preto atinge conformidade AAA;
  no modo claro, o texto branco sobre o cartão amarelo superior poderia violar contraste sem escurecimento de contraste
  pelo renderer nativo.
- **Inconsistências com outras imagens do diretório:** Apresenta cartões empilhados estaticamente com deslocamento
  vertical, diferindo da rolagem paginada horizontal de `banking-app-home-cards-transactions.jpg` e da lista vertical do
  Nubank.
- **O que melhorar (máx. 5, priorizados):**
    1. *compose:* Proibir terminantemente o envio de flags de tema (`isDarkMode`, `theme: "dark"`) no contrato SDUI; o
       aplicativo móvel aplica seu tema nativo via tokens de design.
    2. *catálogo:* Mapear o cartão via `card_product@1` no slot `cards`.
    3. *produto/UX:* O botão "+ Add Card" dentro do cartão deve ter intenção clara de navegação (`navigate` para
       `app://cards/new`).
    4. *o que NÃO deve ir para o JSON:* Cores hexadecimais (roxo, amarelo), flags de modo escuro/claro, raio de 24dp e
       coordenadas de sobreposição dos cartões.

---

### Imagem 7: [`fintech-onboarding-passcode-phone.jpg`](./fintech-onboarding-passcode-phone.jpg)

- **Arquivo:** [`./fintech-onboarding-passcode-phone.jpg`](./fintech-onboarding-passcode-phone.jpg)
- **Tela / estado:** Fluxo de Onboarding / Autenticação (Telas "Set up your passcode" e "What's your phone number?")
- **Plataforma aparente:** iOS (iPhone com entalhe, status bar 9:41, teclado numérico nativo do sistema)
- **Hierarquia (topo → base):**
    - Tela de Passcode (esquerda):
        1. Navigation bar com botão voltar (<), indicador de progresso em pontos (`...`) e fechar (X)
        2. Título principal ("Set up your passcode") e subtítulo explicativo
        3. Grupo de 4 caixas de entrada de dígitos para PIN/passcode
        4. Botão primário de ação ("Continue")
        5. Teclado numérico nativo do sistema operacional
    - Tela de Telefone (direita):
        1. Navigation bar (<, `...`, X)
        2. Título principal ("What's your phone number?") e subtítulo de autenticação de dois fatores
        3. Campos de entrada de DDI ("Code +971") e número de telefone ("Mobile Number: 58 598-43-5")
        4. Link secundário ("Already have an acoount? Log in!")
        5. Botão primário de ação ("Next Step")
- **Slots visíveis:** Não aplicável à surface Home (`home.default`). Caso houvesse surface de onboarding: `nav`
  (`fixed`), `form_content` (`fixed`), `form_footer` (`fixed`).
- **Sections candidatas:** Fora do escopo da Home. Se modeladas em SDUI de onboarding:
    - **Nome semântico:** Cabeçalho de Onboarding
        - **type + typeVersion:** `onboarding_header@1`
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `title: "Set up your passcode"`,`subtitle: "Which will be used to log in into the app"`
        - **actions visíveis:** `act_back` (type: `navigate`, payload: `{ "route": "app://back" }`)
    - **Nome semântico:** Campo de Entrada de Passcode / Telefone
        - **type + typeVersion:** `passcode_input@1` / `phone_input@1`
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `codeLength: 4`, `defaultCountryCode: "+971"` (NUNCA trafegar valores digitados pelo
          usuário!)
        - **actions visíveis:** `act_submit_passcode` (type: `navigate`, payload:
          `{ "route": "app://onboarding/phone" }`)
- **Componentes de DS aparentes (renderer):** Caixas quadradas de digitação de PIN, campo de entrada com seletor de DDI
  em dropdown, indicador de passos em bolinhas, botão primário terracota com cantos arredondados, teclado numérico
  nativo iOS.
- **Padrão de scroll / agrupamento:** Layout vertical fixo (`fixed`) sem scroll, ancorado pelo teclado numérico do SO na
  parte inferior.
- **Conteúdo de negócio vs chrome do SO:** A imagem aborda credenciais e dados regulados (senhas, números de telefone).
  **Regra inegociável de segurança e PII**: Senhas, tokens e credenciais NUNCA trafegam nem são manipulados pelo MS SDUI
  Composer. Fluxos de autenticação devem ser nativos e protegidos.
- **Tokens visuais observados (renderer/, não contrato):** `renderer/bg-cream: #FAF7F2`,
  `renderer/cta-terracotta: #D35427`, `renderer/input-border: #E8E2D9`, `renderer/text-dark: #1C1917`,
  `renderer/pin-box-size: 56x56dp`.
- **Acessibilidade aparente:** Caixas de digitação de PIN precisam anunciar a posição do dígito ("Dígito 1 de 4") no
  leitor de tela; o mock possui erro ortográfico ("acoount") no link inferior; o botão "Continue" deve indicar estado
  desabilitado até a digitação dos 4 dígitos.
- **Inconsistências com outras imagens do diretório:** É a única referência composta exclusivamente por formulários de
  entrada de dados cadastrais/segurança, fora do padrão de feed e apresentação da Home.
- **O que melhorar (máx. 5, priorizados):**
    1. *compose:* Telas de entrada de senhas e dados regulados NÃO devem ser compostas pelo Composer para evitar
       exposição a riscos de segurança e violação da política de PII.
    2. *catálogo:* Não adicionar inputs de credenciais ao catálogo da Home.
    3. *produto/UX:* Corrigir a grafia no texto ("acoount" -> "account").
    4. *o que NÃO deve ir para o JSON:* Valores de PIN digitados, hashes de senha, tokens de autorização, dimensões das
       caixas de texto e altura do teclado virtual.

---

### Imagem 8: [`food-delivery-pizza-home-categories.jpg`](./food-delivery-pizza-home-categories.jpg)

- **Arquivo:** [`./food-delivery-pizza-home-categories.jpg`](./food-delivery-pizza-home-categories.jpg)
- **Tela / estado:** Home | Aplicativo de delivery de comida (Pizza Hut) totalmente populado com ofertas, categorias e
  produtos
- **Plataforma aparente:** iOS (Status bar 9:41, indicadores de wifi e bateria da Apple, home indicator)
- **Hierarquia (topo → base):**
    1. Top Bar com menu hambúrguer, saudação ("Hi, Pizza Lover! 🍕"), logo Pizza Hut ("Great Taste. Delivered."), sino
       (badge '3') e sacola (badge '2')
    2. Campo de busca de pizzas ("Search your favorite pizza...") com botão de filtro
    3. Hero Banner em carrossel ("LIMITED TIME OFFER", manchete "MORE CHEESE. MORE HAPPINESS.", foto rica de pizza com
       queijo puxando, botão "Order Now ->", dots indicadores)
    4. Prateleira horizontal de categorias em miniaturas circulares (All [selecionado], Pizzas, Garlic Bread, Sides,
       Drinks, Desserts)
    5. Prateleira horizontal de produtos populares com cabeçalho ("Popular Picks", link "View All ->", 3 cards com
       badges "BESTSELLER", "POPULAR", "NEW", foto, título, descrição, rating, preço e botão (+) vermelho)
    6. Banner promocional de combos ("EXCLUSIVE COMBOS: UP TO 30% OFF", botão "Order Now ->", foto com combo e badge"30%
       OFF")
    7. Faixa de badges de proposta de valor (Fast Delivery 30-40 mins, Best Quality Always Fresh, Exciting Offers
       Everyday, Safe & Secure Payments)
    8. Barra de navegação inferior (Home, Menu, Botão central flutuante Order, Offers, Profile)
- **Slots visíveis:**
    - `header` (layout: `fixed`)
    - `search` (layout: `fixed`)
    - `hero` (layout: `pager`)
    - `categories` (layout: `shelf`)
    - `popular` (layout: `shelf`)
    - `offers` (layout: `fixed`)
    - `trust_badges` (layout: `shelf`)
- **Sections candidatas:**
    - **Nome semântico:** Barra Superior da Marca com Badges de Notificação
        - **type + typeVersion:** `top_bar@1`
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `greetingPrefix: "Hi, Pizza Lover! 🍕"`, `brandTitle: "Pizza Hut"`,
          `brandSubtitle: "Great Taste. Delivered."`
        - **actions visíveis:** `act_notif` (type: `navigate`, payload: `{ "route": "app://notifications" }`),`act_cart`
          (type: `open_bottom_sheet`, payload: `{ "sheet": "sheet_cart" }`)
    - **Nome semântico:** Carrossel Heroico de Promoção por Tempo Limitado
        - **type + typeVersion:** `hero_banner@1`
        - **layout token:** `pager`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `badge: "LIMITED TIME OFFER"`, `headlineDisplay: "MORE CHEESE. MORE HAPPINESS."`,
          `subtitle: "Hot, cheesy and baked with love, just for you."`, `primaryLabel: "Order Now"`,
          `imageUrl: "https://cdn.example-allowlist.com/banners/pizza_hero.jpg"`
        - **actions visíveis:** `act_order_hero` (type: `navigate`, payload: `{ "route": "app://menu/deals" }`)
    - **Nome semântico:** Prateleira de Categorias de Cardápio
        - **type + typeVersion:** `shortcut_shelf@1`
        - **layout token:** `shelf`
        - **variant:** `compact`
        - **props de CONTEÚDO:**
          `items: [{ "id": "cat_all", "label": "All", "imageUrl": "https://cdn.example-allowlist.com/cat/slice.png", "selected": true }, { "id": "cat_pizzas", "label": "Pizzas", "imageUrl": "https://cdn.example-allowlist.com/cat/pizza.png", "selected": false }, { "id": "cat_bread", "label": "Garlic Bread", "imageUrl": "https://cdn.example-allowlist.com/cat/bread.png", "selected": false }]`
        - **actions visíveis:** `act_filter_cat` (type: `track`, payload: `{ "route": "app://menu?category=pizzas" }`)
    - **Nome semântico:** Prateleira de Produtos Populares com Badges
        - **type + typeVersion:** `product_card@1`
        - **layout token:** `shelf`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `title: "Popular Picks"`,
          `items: [{ "id": "pz_supreme", "badge": "BESTSELLER", "title": "Supreme Pan Pizza", "subtitle": "Loaded with veggies, olives, mushrooms & more", "ratingDisplay": "4.8 (12.5K+)", "priceDisplay": "$12.49", "imageUrl": "https://cdn.example-allowlist.com/products/supreme.jpg", "actionId": "act_add_supreme" }, { "id": "pz_cheese", "badge": "POPULAR", "title": "Cheese Lovers Pizza", "subtitle": "Extra cheese, extra delight for true cheese lovers", "ratingDisplay": "4.7 (8.7K+)", "priceDisplay": "$11.49", "imageUrl": "https://cdn.example-allowlist.com/products/cheese.jpg", "actionId": "act_add_cheese" }]`
        - **actions visíveis:** `act_add_supreme` (type: `navigate`, payload:
          `{ "route": "app://cart/add?id=pz_supreme" }`)
    - **Nome semântico:** Card Promocional de Combos Exclusivos
        - **type + typeVersion:** `credit_offer@1`
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `badge: "EXCLUSIVE COMBOS"`, `headlineDisplay: "UP TO 30% OFF"`,
          `subtitle: "On selected combos"`, `primaryLabel: "Order Now"`,
          `imageUrl: "https://cdn.example-allowlist.com/banners/combo.jpg"`
        - **actions visíveis:** `act_combo_offer` (type: `navigate`, payload: `{ "route": "app://offers/combos" }`)
- **Componentes de DS aparentes (renderer):** Badges em pílula vermelha com texto branco ("BESTSELLER", "POPULAR",
  "NEW"), cards brancos com cantos arredondados, botão circular (+) vermelho de adição ao pedido, FAB central da bottom
  bar com ícone de pizza e contorno elevado.
- **Padrão de scroll / agrupamento:** Scroll vertical unificado da tela (`vertical_scroll`); carrossel hero com
  paginação por gestos (`pager`); prateleiras horizontais para categorias e produtos mais pedidos (`shelf`); banner
  estático de combo (`fixed`).
- **Conteúdo de negócio vs chrome do SO:** A tela apresenta alta densidade comercial com fotos de alimentos
  (`imageUrl`). O chrome do SO restringe-se à status bar e barra de home; a tab bar pertence ao app shell.
- **Tokens visuais observados (renderer/, não contrato):** `renderer/brand-red: #E31837`, `renderer/bg-cream: #FAF7F2`,
  `renderer/card-radius: 20dp`, `renderer/hero-radius: 24dp`, `renderer/font-headline: Condensed Bold`,
  `renderer/badge-red: #D9232D`.
- **Acessibilidade aparente:** Excelente legibilidade; badges vermelhos com texto em branco possuem alto contraste;
  botões (+) precisam de rótulo para leitor de tela ("Adicionar Supreme Pan Pizza"); contadores de notificações e sacola
  precisam anunciar quantidade.
- **Inconsistências com outras imagens do diretório:** É a imagem com maior variedade de badges contextuais de produto,
  ressaltando a relevância de suportar o campo opcional `badge: String` em `product_card@1` e `hero_banner@1`.
- **O que melhorar (máx. 5, priorizados):**
    1. *compose:* As sections `hero_banner`, `category_shelf` e `promo_card` devem ser omitíveis (`required: false`);
       caso falhem, a Home não sofre interrupção.
    2. *catálogo:* Padronizar a prop opcional `badge` no catálogo em `product_card@1` e `hero_banner@1`.
    3. *produto/UX:* O toque na sacola de compras deve disparar `open_bottom_sheet` com resumo do pedido.
    4. *o que NÃO deve ir para o JSON:* O tom de vermelho (#E31837), a tipografia condensed, o raio das bordas e o
       desenho em relevo do botão central da barra inferior.

---

### Imagem 9: [`logistics-shipment-tracking-map.jpg`](./logistics-shipment-tracking-map.jpg)

- **Arquivo:** [`./logistics-shipment-tracking-map.jpg`](./logistics-shipment-tracking-map.jpg)
- **Tela / estado:** Logistics Home (esquerda) e Tracking / Live Map com Bottom Sheet Persistente (direita)
- **Plataforma aparente:** iOS (iPhones com Dynamic Island, status bar 9:41, controles padrão iOS)
- **Hierarquia (topo → base) na tela Home (esquerda):**
    1. Top Bar com ícone de caminhão, endereço de entrega ("Delivery to 11/2 Diriyah, Riyadh") e sino com notificação
    2. Barra de busca com botão de leitura óptica de QR/código de barras
    3. Grid de serviços principais: "New Delivery" (caminhão 3D) e "Track Package" (caixa 3D)
    4. Seção "Current Shipment" com link "See All": Card da remessa em curso com ID "H314315796", "Mac Mini M4 Pro",
       chip "Transit", mini-timeline de 4 etapas, previsão "4h Away", ilustração 3D de pacote, origem e destino
    5. Seção "Recent Shipment" com link "See All": Lista vertical de encomendas passadas (Mac Mini M4 Pro - "On
       Process", Sonos Speaker - "Delivered", DJI Drone)
    6. Barra flutuante de navegação inferior (Home, Shipment, (+), Tracking, Profile)
- **Slots visíveis na Home:**
    - `header` (layout: `fixed`)
    - `search` (layout: `fixed`)
    - `services` (layout: `grid`)
    - `current_shipment` (layout: `fixed`)
    - `recent_shipments` (layout: `list`)
- **Sections candidatas na Home:**
    - **Nome semântico:** Barra Superior de Entrega e Notificação
        - **type + typeVersion:** `top_bar@1`
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `locationLabel: "11/2 Diriyah, Riyadh"`, `icon: "icon.truck"`
        - **actions visíveis:** `act_notif` (type: `navigate`, payload: `{ "route": "app://notifications" }`)
    - **Nome semântico:** Grade de Serviços de Logística
        - **type + typeVersion:** `service_grid@1`
        - **layout token:** `grid`
        - **variant:** `regular`
        - **props de CONTEÚDO:**
          `items: [{ "id": "srv_new", "title": "New Delivery", "imageUrl": "https://cdn.example-allowlist.com/3d/truck.png", "actionId": "act_new_delivery" }, { "id": "srv_track", "title": "Track Package", "imageUrl": "https://cdn.example-allowlist.com/3d/box.png", "actionId": "act_track_pkg" }]`
        - **actions visíveis:** `act_new_delivery` (type: `navigate`, payload: `{ "route": "app://shipment/new" }`),
          `act_track_pkg` (type: `navigate`, payload: `{ "route": "app://shipment/track" }`)
    - **Nome semântico:** Card de Status da Remessa Atual
        - **type + typeVersion:** `decision_card@1` (ou `order_status_card@1`)
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `kicker: "Transit"`,
          `body: "ID: H314315796 • Mac Mini M4 Pro\nOrigem: Diriyah, Riyadh • Destino: Jawhra, Jeddah"`,
          `acceptLabel: "Ver Rastreamento"`, `imageUrl: "https://cdn.example-allowlist.com/3d/parcel.png"`
        - **actions visíveis:** `act_track_current` (type: `navigate`, payload:
          `{ "route": "app://tracking/H314315796" }`)
    - **Nome semântico:** Lista de Encomendas Recentes
        - **type + typeVersion:** `product_list@1`
        - **layout token:** `list`
        - **variant:** `compact`
        - **props de CONTEÚDO:** `title: "Recent Shipment"`,
          `items: [{ "id": "sh_1", "title": "ID: H314315796", "subtitle": "Mac Mini M4 Pro", "badge": "On Process", "imageUrl": "https://cdn.example-allowlist.com/products/macmini.png", "actionId": "act_view_sh_1" }, { "id": "sh_2", "title": "ID: K37856307", "subtitle": "Sonos Speaker", "badge": "Delivered", "imageUrl": "https://cdn.example-allowlist.com/products/sonos.png", "actionId": "act_view_sh_2" }]`
        - **actions visíveis:** `act_view_sh_1` (type: `navigate`, payload: `{ "route": "app://tracking/H314315796" }`)
- **Componentes de DS aparentes (renderer):** Mini-stepper horizontal com marcadores circulares e linhas tracejadas,
  chips ovais de status com fundo cinza, ilustrações 3D recortadas em relevo suave, mapa vetorial com traçado de rota de
  trânsito e marcadores, bottom sheet escura com botões de chamada telefônica e chat.
- **Padrão de scroll / agrupamento:** Scroll vertical contínuo na Home (`vertical_scroll`); na tela de rastreamento
  (direita), há divisão em duas áreas (mapa interativo estático superior + bottom sheet inferior rolável).
- **Conteúdo de negócio vs chrome do SO:** A tela de rastreamento é uma surface dedicada de mapa com streaming de GPS em
  tempo real e ações de chamada/chat, devendo ser aberta via `navigate` e não orquestrada como section da Home.
- **Tokens visuais observados (renderer/, não contrato):** `renderer/card-bg: #FFFFFF`,
  `renderer/sheet-bg: #1C1C1E (preto grafite)`, `renderer/accent-orange: #FF6633`, `renderer/accent-teal: #008080`,
  `renderer/radius: 20dp`, `renderer/map-route-color: #2C2C2E`.
- **Acessibilidade aparente:** O mini-stepper horizontal possui detalhes muito pequenos e exige descrição acessível
  completa ("Etapa 2 de 4: Em trânsito, entrega prevista em 4 horas"); botões de telefone e mensagem precisam de rótulos
  claros para leitor de tela.
- **Inconsistências com outras imagens do diretório:** É a única tela contendo mapa vetorial e rastreamento em tempo
  real, ilustrando a separação de responsabilidades entre telas dinâmicas nativas e surfaces SDUI compostas.
- **O que melhorar (máx. 5, priorizados):**
    1. *compose:* O mapa em tempo real (tela direita) deve ser acionado por `navigate` para rota de rastreamento nativo
       (`app://tracking/{id}`), sem trafegar polilinhas ou streaming de telemetria pelo Composer.
    2. *catálogo:* Mapear o card da remessa atual via `decision_card@1` ou propor `order_status_card@1`.
    3. *produto/UX:* O clique no card da remessa deve navegar diretamente para o mapa detalhado.
    4. *o que NÃO deve ir para o JSON:* Coordenadas de GPS, polilinhas de mapa, cores da rota e altura da bottom sheet
       em pixels.

---

### Imagem 10: [`nubank-home-sections-comparison.jpg`](./nubank-home-sections-comparison.jpg)

- **Arquivo:** [`./nubank-home-sections-comparison.jpg`](./nubank-home-sections-comparison.jpg)
- **Tela / estado:** Home (Fintech Nubank) | Comparação de dois estados da mesma aplicação demonstrando reordenação
  dinâmica de slots e variação de layout de atalhos
- **Plataforma aparente:** Android (Status bar superior com alarme, NFC, bateria 78%, horário 15:06; barra de navegação
  inferior com ícones Android nativos)
- **Hierarquia (topo → base):**
    - Tela da Esquerda:
        1. Header com fundo roxo institucional, ícone de perfil, alternador de visibilidade (olho), ajuda (?) e convidar
           amigos
        2. Saudação: "Olá, Théo"
        3. Slot `accounts`: "Conta" com chevron >, saldo visível "R$ 1.264,06"
        4. Slot `shortcuts`: prateleira horizontal (`shelf`) de 4 botões circulares ("Área Pix", "Pagar", "Pegar
           emprestado" com badge "R$ 12.500", "Transferir")
        5. Card "Meus cartões"
        6. Carrossel informativo ("NuEnsina: aprenda novas formas de lidar com sua grana.")
        7. Slot `cards`: "Cartão de crédito" >, fatura atual "R$ 0,00", limite disponível "R$ 1.375,43", status "Débito
           automático ativado"
        8. Botão secundário "Parcelar compras"
        9. Seção "Acompanhe também" (Atalhos: "Assistente de pagamentos", "Meu Pedacinho do Nubank")
        10. Slot `offers`: "Empréstimo" >, "Valor disponível de até R$ 12.500,00"
        11. Slot `coverage` / `foryou`: "Descubra mais" (carrossel de seguros: Celular Seguro com foto e CTA "Conhecer";
            Seguro de Vida)
        12. Barra de navegação inferior nativa
    - Tela da Direita:
        1. Header com perfil, olho, ajuda e convite
        2. Slot `accounts`: "Conta" >, saldo "R$ 1.264,06"
        3. Slot `cards`: "Cartão de crédito" > posicionado **imediatamente abaixo da Conta, invertendo a ordem com
           shortcuts!**
        4. Card "Meus cartões"
        5. Slot `shortcuts`: grade vertical (`grid`) de 2 linhas x 4 colunas com 8 atalhos (Pagar, Pegar
           emprestado [badge R$ 12.500], Transferir, Depositar, Depositar, Recarga de celular, Cobrar, Doação)
        6. Slot `offers`: "Empréstimo" >, "Valor disponível de até R$ 12.500,00"
        7. Slot "Descubra mais" (carrossel de seguros)
        8. Barra de navegação inferior nativa
- **Slots visíveis:**
    - `header` (layout: `fixed`)
    - `accounts` (layout: `list`)
    - `shortcuts` (layout: `shelf` na esquerda vs `grid` na direita!)
    - `cards` (layout: `list`)
    - `offers` (layout: `list`)
    - `coverage` / `foryou` (layout: `shelf` ou `pager`)
- **Sections candidatas:**
    - **Nome semântico:** Barra Superior com Ações de Privacidade e Ajuda
        - **type + typeVersion:** `top_bar@1`
        - **layout token:** `fixed`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `greetingName: "Théo"`, `greetingPrefix: "Olá"`, `primaryActionLabel: "Ajuda"`
        - **actions visíveis:** `act_toggle_eye` (type: `track`, payload: `{ "route": "app://ui/toggle-conceal" }`),
          `act_help` (type: `navigate`, payload: `{ "route": "app://help" }`)
    - **Nome semântico:** Card de Conta Bancária e Saldo
        - **type + typeVersion:** `account_card@1`
        - **layout token:** `list`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `title: "Conta"`, `concealable: true`, `concealed: false`,
          `rows: [{ "id": "row_balance", "label": "Saldo em conta", "valueDisplay": "R$ 1.264,06", "valueDisplayRevealed": "R$ 1.264,06", "concealable": true }]`
        - **actions visíveis:** `act_account_statement` (type: `navigate`, payload:
          `{ "route": "app://account/statement" }`)
    - **Nome semântico:** Prateleira / Grade de Atalhos Rápidos
        - **type + typeVersion:** `shortcut_shelf@1`
        - **layout token:** `shelf` (tela esquerda) vs `grid` (tela direita)
        - **variant:** `compact`
        - **props de CONTEÚDO:**
          `items: [{ "id": "sc_pix", "label": "Área Pix", "icon": "icon.pix", "actionId": "act_pix" }, { "id": "sc_pay", "label": "Pagar", "icon": "icon.barcode", "actionId": "act_pay" }, { "id": "sc_loan", "label": "Pegar emprestado", "badge": "R$ 12.500", "icon": "icon.loan", "actionId": "act_loan" }, { "id": "sc_transfer", "label": "Transferir", "icon": "icon.transfer", "actionId": "act_transfer" }]`
        - **actions visíveis:** `act_pix` (type: `navigate`, payload: `{ "route": "app://pix" }`), `act_loan` (type:
          `navigate`, payload: `{ "route": "app://credit/loan" }`)
    - **Nome semântico:** Card de Produto de Cartão de Crédito
        - **type + typeVersion:** `card_product@1`
        - **layout token:** `list`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `brandLabel: "Cartão de crédito"`,
          `rows: [{ "id": "row_inv", "label": "Fatura atual", "valueDisplay": "R$ 0,00", "concealable": false }, { "id": "row_lim", "label": "Limite disponível", "valueDisplay": "R$ 1.375,43", "concealable": true }]`,
          `secondaryDisplay: "Débito automático ativado"`
        - **actions visíveis:** `act_card_details` (type: `navigate`, payload: `{ "route": "app://cards/details" }`)
    - **Nome semântico:** Oferta de Empréstimo Pessoal
        - **type + typeVersion:** `credit_offer@1`
        - **layout token:** `list`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `headlineDisplay: "Empréstimo"`, `subtitle: "Valor disponível de até R$ 12.500,00"`,
          `primaryLabel: "Simular empréstimo"`
        - **actions visíveis:** `act_simulate_loan` (type: `navigate`, payload:
          `{ "route": "app://credit/loan/simulate" }`)
    - **Nome semântico:** Card de Seguro e Cobertura
        - **type + typeVersion:** `coverage_card@1`
        - **layout token:** `shelf`
        - **variant:** `regular`
        - **props de CONTEÚDO:** `title: "Nubank Celular Seguro"`,
          `subtitle: "100% cobertura, 0% estresse. Simule agora mesmo."`, `primaryLabel: "Conhecer"`,
          `imageUrl: "https://cdn.example-allowlist.com/insurance/celular_seguro.jpg"`
        - **actions visíveis:** `act_insurance_cell` (type: `navigate`, payload:
          `{ "route": "app://insurance/cellphone" }`)
- **Componentes de DS aparentes (renderer):** Top bar roxa característica (`#820AD1`), círculos cinza-claros de fundo
  para os atalhos, botões em pill roxo ("Conhecer"), linhas divisórias sutis de 1dp separando seções verticais, badges
  lilás nos atalhos com valor de limite.
- **Padrão de scroll / agrupamento:** Scroll vertical unificado da tela (`vertical_scroll`); atalhos alternam entre
  prateleira com rolagem horizontal (`shelf`) e grade vertical de 2 linhas x 4 colunas (`grid`); seção de seguros em
  prateleira horizontal (`shelf`).
- **Conteúdo de negócio vs chrome do SO:** A imagem comprova a maturidade máxima do SDUI em produção:
    1. A **ordem dos slots foi reordenada no servidor** (`cards` promovido para cima de `shortcuts` na tela direita).
    2. O **layout token do slot `shortcuts` mudou de `shelf` para `grid`** por configuração de spec, sem novo deploy na
       Google Play Store.
    3. O Design System nativo no Android resolve fontes, cores e raios de curvatura.
- **Tokens visuais observados (renderer/, não contrato):** `renderer/brand-purple: #820AD1`,
  `renderer/btn-purple: #8A05BE`, `renderer/badge-lilac: #E8D5F5`, `renderer/shortcut-circle-size: 56dp`,
  `renderer/divider-height: 1dp`, `renderer/text-dark: #191919`.
- **Acessibilidade aparente:** O botão de alternar visibilidade (olho) precisa de acessibilidade auditiva clara ("Saldos
  ocultados / Saldos exibidos"); valores monetários em fonte de grande porte e alto contraste facilitam leitura;
  chevrons (>) indicam que a linha inteira é clicável.
- **Inconsistências com outras imagens do diretório:** É a única imagem extraída do ecossistema Android e mapeia
  praticamente 1:1 os 7 types canônicos do nosso MVP em produção (`top_bar`, `shortcut_shelf`, `account_card`,
  `card_product`, `credit_offer`, `coverage_card`, `decision_card`).
- **O que melhorar (máx. 5, priorizados):**
    1. *compose:* Utilizar canary ou seleção de spec para chavear o `layout` de `shortcuts` entre `shelf` e `grid`,
       validando a flexibilidade de layout tokens sem quebrar a capability do cliente.
    2. *catálogo:* Os 7 tipos canônicos do nosso MVP cobrem com precisão cirúrgica a totalidade dos componentes
       observados.
    3. *produto/UX:* O estado de ocultar saldo (`concealable: true`) deve ocultar simultaneamente o saldo da conta e os
       limites do cartão.
    4. *o que NÃO deve ir para o JSON:* O roxo Nubank (#820AD1), as dimensões de 56dp dos círculos de atalhos, divisores
       de 1dp e espaçamentos em pixels.

---

## 3. Catálogo Consolidado de Layout Tokens

No MS SDUI Composer, o arranjo espacial é estritamente semântico. A lista de tokens é fechada no enum `SlotLayout`.

| Layout Token | Quando Usar                                                                                      | Slots Típicos                                      | O que o App / DS Resolve                                                                                      | Imagens de Origem                                                                                                                                                                                      |
|--------------|--------------------------------------------------------------------------------------------------|----------------------------------------------------|---------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `fixed`      | Componente único, estático ou ancorado, sem rolagem interna ou paginação.                        | `header`, `search`, `featured`, `current_shipment` | Margens laterais, padding interno, altura intrínseca, sticky header no topo da tela.                          | [`banking-app...`](./banking-app-home-cards-transactions.jpg), [`coffee-app-wireframe...`](./coffee-app-wireframe-to-design-home.jpg), [`nubank-home...`](./nubank-home-sections-comparison.jpg)       |
| `shelf`      | Prateleira com rolagem horizontal contínua de itens compactos ou médios (cards, pills, atalhos). | `shortcuts`, `categories`, `popular`, `coverage`   | Espaçamento entre itens (gap), peek do próximo item, snapping ao soltar o dedo, scrollbar oculta.             | [`banking-app...`](./banking-app-home-cards-transactions.jpg), [`food-delivery...`](./food-delivery-pizza-home-categories.jpg), [`nubank-home...`](./nubank-home-sections-comparison.jpg)              |
| `grid`       | Grade de itens distribuídos em linhas e colunas uniformes (ex: 2x2, 2x4).                        | `shortcuts`, `services`, `feed`                    | Cálculo de colunas por largura de tela, densidade de pixels (dp), alinhamento de texto e quebra de linha.     | [`ecommerce-fashion...`](./ecommerce-fashion-catalog-detail-cart.jpg), [`logistics-shipment...`](./logistics-shipment-tracking-map.jpg), [`nubank-home...`](./nubank-home-sections-comparison.jpg)     |
| `list`       | Empilhamento vertical sequencial de seções ou itens dentro do mesmo slot.                        | `accounts`, `cards`, `offers`, `foryou`, `feed`    | Divisores nativos, altura automática por linha, animações de expansão e feedback de toque (ripple/highlight). | [`banking-app...`](./banking-app-home-cards-transactions.jpg), [`coffee-app-wireframe...`](./coffee-app-wireframe-to-design-home.jpg), [`nubank-home...`](./nubank-home-sections-comparison.jpg)       |
| `pager`      | Carrossel horizontal com paginação estrita (um item completo por vez ou snap centrado).          | `hero`, `cards`, `foryou`                          | Gesto de arrasto com inércia, transição de página, desenho e posição dos indicadores de página (dots).        | [`banking-app...`](./banking-app-home-cards-transactions.jpg), [`coffee-app-wireframe...`](./coffee-app-wireframe-to-design-home.jpg), [`food-delivery...`](./food-delivery-pizza-home-categories.jpg) |

---

## 4. Catálogo Consolidado de Section Types

O catálogo de sections define os componentes semânticos aceitos pelo Composer na versão de schema `3`. Cada section
declara o tipo, versão, props permitidas e actions suportadas.

| Section Type     | typeVersion | Slot Típico                  | Layout Token             | Variant              | Props de Conteúdo Permitidas                                                                                                                     | Actions Permitidas              | Capability         | Imagens de Origem                                                                                                                                                                                  |
|------------------|-------------|------------------------------|--------------------------|----------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------|--------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `top_bar`        | `@1`        | `header`                     | `fixed`                  | `regular`            | `greetingName`, `greetingPrefix`, `avatarUrl`, `loyaltyLabel`, `primaryActionLabel`, `locationLabel`                                             | `navigate`, `track`             | `top_bar@1`        | [`banking-app...`](./banking-app-home-cards-transactions.jpg), [`coffee-app-wireframe...`](./coffee-app-wireframe-to-design-home.jpg), [`nubank-home...`](./nubank-home-sections-comparison.jpg)   |
| `shortcut_shelf` | `@1`        | `shortcuts`, `categories`    | `shelf`, `grid`          | `compact`, `regular` | `variant`, `title`, `items[].{id, label, icon, badge, selected, actionId}`                                                                       | `navigate`, `track`             | `shortcut_shelf@1` | [`banking-app...`](./banking-app-home-cards-transactions.jpg), [`food-delivery...`](./food-delivery-pizza-home-categories.jpg), [`nubank-home...`](./nubank-home-sections-comparison.jpg)          |
| `account_card`   | `@1`        | `accounts`                   | `list`, `fixed`          | `regular`            | `title`, `concealable`, `concealed`, `rows[].{id, label, valueDisplay, valueDisplayRevealed, concealable}`                                       | `navigate`, `track`             | `account_card@1`   | [`crypto-wallet...`](./crypto-wallet-home-withdraw.jpg), [`nubank-home...`](./nubank-home-sections-comparison.jpg)                                                                                 |
| `card_product`   | `@1`        | `cards`                      | `list`, `pager`, `fixed` | `regular`            | `brandLabel`, `last4`, `holderName`, `expirationDate`, `rows[].{id, label, valueDisplay, valueDisplayRevealed, concealable}`, `secondaryDisplay` | `navigate`, `open_bottom_sheet` | `card_product@1`   | [`banking-app...`](./banking-app-home-cards-transactions.jpg), [`finance-app...`](./finance-app-card-expenses-light-dark.jpg), [`nubank-home...`](./nubank-home-sections-comparison.jpg)           |
| `credit_offer`   | `@1`        | `offers`                     | `list`, `fixed`          | `compact`, `regular` | `badge`, `headlineDisplay`, `subtitle`, `primaryLabel`, `secondaryLabel`, `imageUrl`                                                             | `navigate`, `open_bottom_sheet` | `credit_offer@1`   | [`coffee-app-wireframe...`](./coffee-app-wireframe-to-design-home.jpg), [`food-delivery...`](./food-delivery-pizza-home-categories.jpg), [`nubank-home...`](./nubank-home-sections-comparison.jpg) |
| `coverage_card`  | `@1`        | `coverage`                   | `list`, `shelf`          | `regular`            | `title`, `subtitle`, `assetLabel`, `primaryLabel`, `rows[].{id, label, valueDisplay, valueNorm}`, `imageUrl`                                     | `navigate`, `open_bottom_sheet` | `coverage_card@1`  | [`nubank-home...`](./nubank-home-sections-comparison.jpg)                                                                                                                                          |
| `decision_card`  | `@1`        | `foryou`, `current_shipment` | `pager`, `fixed`         | `regular`            | `kicker`, `body`, `rejectLabel`, `acceptLabel`, `imageUrl`                                                                                       | `navigate`, `noop`, `track`     | `decision_card@1`  | [`logistics-shipment...`](./logistics-shipment-tracking-map.jpg), [`contrato-sdui-home-definitivo.json`](../artifacts/contrato-sdui-home-definitivo.json)                                          |

---

## 5. Catálogo Consolidado de Actions

As actions representam intenções serializadas despachadas pelo cliente nativo. O vocabulário é estritamente fechado em 4
tipos no MVP (`MvpCatalog.ALLOWED_ACTIONS`), sem rotas `http(s)` abertas e sem tráfego de dados pessoais sensíveis.

| Action Type         | Payload Mínimo Obrigatório                                   | Quem Despacha                                     | Sections que Utilizam                                                                                         | Imagens de Origem                                                                                                                                               |
|---------------------|--------------------------------------------------------------|---------------------------------------------------|---------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `navigate`          | `{ "route": "app://..." }`                                   | Roteador / Deeplink Handler Nativo                | `top_bar`, `shortcut_shelf`, `account_card`, `card_product`, `credit_offer`, `coverage_card`, `decision_card` | Todas as 10 imagens do catálogo                                                                                                                                 |
| `open_bottom_sheet` | `{ "sheet": "sheet_..." }`                                   | Gerenciador de Modais / Sheet Manager Nativo      | `top_bar`, `card_product`, `credit_offer`, `coverage_card`, `product_card`                                    | [`ecommerce-fashion...`](./ecommerce-fashion-catalog-detail-cart.jpg), [`food-delivery...`](./food-delivery-pizza-home-categories.jpg)                          |
| `track`             | `{ "route": "app://..." }` ou `{}` (evento implícito por ID) | Analytics Dispatcher Nativo                       | `shortcut_shelf`, `top_bar`, `decision_card`                                                                  | [`banking-app...`](./banking-app-home-cards-transactions.jpg), [`nubank-home...`](./nubank-home-sections-comparison.jpg)                                        |
| `noop`              | `{}` (payload vazio ou nulo)                                 | Descartado no cliente / apenas fecha o componente | `decision_card` (ex: "Lembrar mais tarde")                                                                    | [`coffee-app-wireframe...`](./coffee-app-wireframe-to-design-home.jpg), [`contrato-sdui-home-definitivo.json`](../artifacts/contrato-sdui-home-definitivo.json) |

---

## 6. Skeleton Canônico da Home (`home.default`)

A Home no MS SDUI Composer é um **Skeleton de slots ordenados**, e **NÃO** uma tela estática ou rígida. A composição
respeita o princípio **Section > Screen**: o catálogo é compartilhado e as plataformas (iOS/Android) utilizam a mesma
gramática semântica, isolando suas specs e pointers em pipelines independentes.

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
      "title": "Conta",
      "required": true,
      "allowedTypes": [
        "account_card"
      ]
    },
    {
      "id": "cards",
      "layout": "list",
      "title": "Cartão de crédito",
      "required": false,
      "allowedTypes": [
        "card_product"
      ]
    },
    {
      "id": "offers",
      "layout": "list",
      "title": "Crédito",
      "required": false,
      "allowedTypes": [
        "credit_offer"
      ]
    },
    {
      "id": "coverage",
      "layout": "list",
      "title": "Seguros",
      "required": false,
      "allowedTypes": [
        "coverage_card"
      ]
    },
    {
      "id": "foryou",
      "layout": "pager",
      "title": "Para você",
      "required": false,
      "allowedTypes": [
        "decision_card"
      ]
    }
  ]
}
```

- **Slots Portantes Obrigatórios (`required: true`):** `header` e `accounts`. Se a hidratação destes slots falhar ou o
  cliente não possuir a capability correspondente, o pipeline ativa a escada de fallback (ADR-007 / ADR-009).
- **Slots Omitíveis Graciosos (`required: false`):** `shortcuts`, `cards`, `offers`, `coverage`, `foryou`. Se qualquer
  um destes slots falhar na hidratação remota ou for incompatível com o cliente, ele é omitido (`omitted: [...]`) e a
  Home é entregue com `200 OK`.

---

## 7. Mapa de Rastreabilidade: Screenshot → Contrato

Este mapa conecta cada imagem de referência aos slots e types gerados para o backend, isolando categoricamente o que
pertence ao Design System nativo (`renderer/`).

| Arquivo de Imagem                                                                          | Slots Identificados                                                           | Types Mapeados                                                                                           | O que ficou restrito ao Renderer (`renderer/`)                                                                                           |
|--------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| [`banking-app-home-cards-transactions.jpg`](./banking-app-home-cards-transactions.jpg)     | `header`, `cards`, `shortcuts`, `feed`                                        | `top_bar@1`, `card_product@1`, `shortcut_shelf@1`, `transaction_list@1`                                  | Gradiente laranja/laranja claro do cartão, microchip desenhado, peek de 24dp, raio de borda de 20dp, cores verde/vermelho dos valores.   |
| [`coffee-app-all-screens-flow.jpg`](./coffee-app-all-screens-flow.jpg)                     | `header`, `search`, `new_in`, `frequently_ordered`                            | `top_bar@1`, `search_bar@1`, `product_shelf@1`, `product_list@1`                                         | Fundo azul petróleo escuro (#1B2B38), cor laranja queimado (#E25E3E), steppers numéricos, telas de mapa, checkout e timeline de preparo. |
| [`coffee-app-wireframe-to-design-home.jpg`](./coffee-app-wireframe-to-design-home.jpg)     | `header`, `hero`, `categories`, `popular`, `offers`, `best_selling`           | `top_bar@1`, `hero_banner@1`, `shortcut_shelf@1`, `product_card@1`, `credit_offer@1`                     | Tema escuro (#1E120B), raio de curvatura de 24dp do hero, dots de paginação, sombras e tamanho dos placeholders do wireframe.            |
| [`crypto-wallet-home-withdraw.jpg`](./crypto-wallet-home-withdraw.jpg)                     | `header`, `accounts`, `shortcuts`, `feed`                                     | `top_bar@1`, `account_card@1`, `shortcut_shelf@1`, `asset_list@1`                                        | Curvatura orgânica da máscara branca (36dp), cor terracota e amarela dos botões circulares, tela de saque, teclado numérico e slider.    |
| [`ecommerce-fashion-catalog-detail-cart.jpg`](./ecommerce-fashion-catalog-detail-cart.jpg) | `header`, `search`, `categories`, `featured`, `feed`                          | `top_bar@1`, `shortcut_shelf@1`, `card_product@1`                                                        | Efeito de vidro fosco da barra inferior, 2 colunas do grid, cor dourada (#F4B255), altura e raio de 28dp da bottom sheet modal.          |
| [`finance-app-card-expenses-light-dark.jpg`](./finance-app-card-expenses-light-dark.jpg)   | `header`, `cards`, `shortcuts`, `feed`                                        | `top_bar@1`, `card_product@1`, `shortcut_shelf@1`, `transaction_list@1`                                  | Modo claro (#F8F8F8) vs Modo escuro (#000000), cores roxo e amarelo dos cartões, deslocamento do empilhamento e raio de 24dp.            |
| [`fintech-onboarding-passcode-phone.jpg`](./fintech-onboarding-passcode-phone.jpg)         | *Nenhum na Home* (onboarding nativo)                                          | *Nenhum* (fluxo de segurança)                                                                            | Caixas de digitação de PIN de 56x56dp, cor terracota (#D35427), teclado numérico iOS e campos de autenticação de dois fatores.           |
| [`food-delivery-pizza-home-categories.jpg`](./food-delivery-pizza-home-categories.jpg)     | `header`, `search`, `hero`, `categories`, `popular`, `offers`, `trust_badges` | `top_bar@1`, `hero_banner@1`, `shortcut_shelf@1`, `product_card@1`, `credit_offer@1`                     | Vermelho Pizza Hut (#E31837), tipografia condensed, relevo do botão central flutuante, raio de 20dp dos cards.                           |
| [`logistics-shipment-tracking-map.jpg`](./logistics-shipment-tracking-map.jpg)             | `header`, `search`, `services`, `current_shipment`, `recent_shipments`        | `top_bar@1`, `service_grid@1`, `decision_card@1`, `product_list@1`                                       | Mapa interativo com GPS em tempo real, polilinhas de rota, cor laranja (#FF6633), bottom sheet de entrega e botões de chamada.           |
| [`nubank-home-sections-comparison.jpg`](./nubank-home-sections-comparison.jpg)             | `header`, `accounts`, `shortcuts`, `cards`, `offers`, `coverage`              | `top_bar@1`, `account_card@1`, `shortcut_shelf@1`, `card_product@1`, `credit_offer@1`, `coverage_card@1` | Roxo institucional (#820AD1), botões de 56dp de diâmetro, divisores de 1dp, raio dos pills lilás (#E8D5F5) e fontes nativas Android.     |

---

## 8. Gaps e Conformidade Técnica

Durante a auditoria visual e correlação com a arquitetura do Composer, foram identificados e resolvidos os seguintes
gaps:

1. **Screenshot sem Type no Catálogo MVP:** Telas com mapas interativos em tempo real ([
   `logistics-shipment...`](./logistics-shipment-tracking-map.jpg)) e fluxos de entrada de PIN/Telefone ([
   `fintech-onboarding...`](./fintech-onboarding-passcode-phone.jpg)) não possuem e **não devem possuir** types na Home
   do Composer. São fluxos transacionais nativos acessados por rotas `navigate`.
2. **Type sem Screenshot:** Todos os 7 types do MVP canônico (`top_bar`, `shortcut_shelf`, `account_card`,
   `card_product`, `credit_offer`, `coverage_card`, `decision_card`) possuem correspondência direta em screenshots,
   especialmente em [`nubank-home-sections-comparison.jpg`](./nubank-home-sections-comparison.jpg) e [
   `contrato-sdui-home-definitivo.json`](../artifacts/contrato-sdui-home-definitivo.json).
3. **Action sem Destino:** Banners e botões em mocks conceituais frequentemente omitem a intenção de clique. Toda action
   no Composer exige um `id`, um `type` fechado (`navigate`, `open_bottom_sheet`, `track`, `noop`) e payload mínimo
   preenchido (`route` ou `sheet`), garantindo zero ações mortas.
4. **Section Omitível por Capability:** Seções como `credit_offer`, `coverage_card` e `card_product` são configuradas
   como `required: false` no skeleton. Caso um cliente com versão legada não envie a capability correspondente no header
   `Component-Capabilities`, o serviço omite o bloco graciosamente sem causar erro 500 ou quebrar a Home.
5. **Ícone sem Nome do Design System:** Mocks utilizam imagens rasterizadas ou ícones desenhados. No Composer, ícones
   são estritamente strings com prefixo do recurso nativo (ex: `icon.pix`, `icon.barcode`, `icon.transfer`,
   `icon.credit`), cabendo ao app desenhar o SVG ou vetor correto. URLs de imagem (`imageUrl`) são restritas
   exclusivamente a fotos/mídias de conteúdo hospedadas em hosts permitidos.
6. **Layout Inferido como Geometria:** Proibido inferir layouts com dimensões em pixels (ex: `grid_4`,
   `itemWidth: 120dp`). A representação foi normalizada para os tokens semânticos fechados: `pager`, `shelf`, `grid`,
   `list`, `fixed`.

---

## 9. Diretrizes de Naming e Eixos de Compatibilidade

- **Convenção de Nomes:** Todos os types e slots utilizam estritamente `snake_case` (ex: `top_bar`, `shortcut_shelf`,
  `card_product`, `decision_card`). Proibido terminologias genéricas como `row`, `column`, `container`, `card` genérico,
  ou nomenclaturas de frameworks de terceiros (Beagle, DivKit, Stac, Adaptive Cards).
- **Três Eixos Independentes de Versão (Proibido Colapsar):**
    1. **Eixo A (Envelope / Protocolo):** Header `API-Version: 1` e campo `schemaVersion: "3"` no envelope da Home.
    2. **Eixo B (Renderer / Capabilities):** Par `type@typeVersion` (ex: `shortcut_shelf@1`) declarado pelo cliente e
       negociado no header `Component-Capabilities`.
    3. **Eixo C (Aplicativo / SO):** Versão semântica do app no header `Client-Version: 8.14.2` e build
       `Client-Build: 81420`.

---

## 10. Tokens que NUNCA Entram no JSON

Estes atributos são de responsabilidade exclusiva do aplicativo e do Design System nativo (`renderer/`). A presença de
qualquer uma destas chaves no payload do Composer é estritamente bloqueada por guardas em tempo de compilação e teste
(`Guards.kt` / `NoVisualAttributesTest.kt`):

```text
color, background, font, typography,
margin, padding, gap,
width, height, radius, rounded, cornerRadius, shadow,
orientation, circle, rectangle, shimmer, ripple, haptic,
dp, pt, itemWidth, itemHeight, breakpoint, formFactor,
columns, isDarkMode, theme, appearance, prominent, size
```

---

## 11. Backlog de Melhorias Priorizado

Abaixo está o plano de ação priorizado pelo impacto na estabilidade e flexibilidade do runtime de composição:

1. **Blindagem de Capabilities e Omissão Graciosa (P0 - Compose):**
    - Garantir que todos os slots não-portantes (`shortcuts`, `cards`, `offers`, `coverage`, `foryou`) possuam
      `required: false` no skeleton.
    - Em caso de falha de hidratação ou falta de capability do app, preencher `envelope.omitted` com `unsupported_type`
      ou `hydration_failed`, mantendo resposta `200 OK`.
2. **Saneamento e Validação Estrita de Actions (P1 - Contrato):**
    - Validar que nenhuma action possua rotas abertas `http://` ou `https://`.
    - Assegurar que todas as rotas comecem com `app://` ou modais com `sheet: sheet_...`.
    - Proibir tráfego de identificadores sensíveis ou PII nos parâmetros de query das rotas.
3. **Formalização de Types e Layouts de Expansão (P2 - Catálogo):**
    - Documentar `hero_banner@1` e `product_card@1` no catálogo formal para expansão pós-MVP de surfaces comerciais.
    - Habilitar suporte a `SlotLayout.GRID` para atalhos em dispositivos com telas largas sem alterar o contrato de
      dados.
4. **Harmonização de Inconsistências Visuais entre Imagens (P3 - UX/DS):**
    - Alinhar com os times mobile de iOS e Android que o comportamento de ocultar saldo (`concealable: true`) deve atuar
      globalmente em todos os blocos financeiros (conta e cartão).
5. **Polimento Visual Exclusivo no Renderer Mobile (P4 - Design System):**
    - Implementar tokens de acessibilidade no Design System mobile garantindo hit target mínimo de 44x44pt em botões
      compactos ("View all", (+), filtros).
    - Aplicar suporte nativo a Dynamic Type e modo escuro automático sem qualquer interferência do backend.

---

## 12. Referências Técnicas e Arquiteturais

A arquitetura de Server-Driven UI do MS SDUI Composer e os princípios deste catálogo fundamentam-se na literatura
técnica consolidada e em referências reais de produção da indústria móvel:

- **Airbnb Ghost Platform:**
    - [A Deep Dive into Airbnb's Server-Driven UI System](https://medium.com/airbnb-engineering/a-deep-dive-into-airbnbs-server-driven-ui-system-842244c5f5) —
      Análise arquitetural da separação estrita entre o backend que define o QUÊ e o aplicativo móvel que renderiza
      componentes nativos.
- **Joud Awad:**
    - [Server-Driven UI: Ship mobile UI without app-store reviews](https://joudwawad.medium.com/how-airbnb-netflix-and-lyft-ship-ui-without-touching-the-app-store-49c9f64f5e2b) —
      Princípios fundamentais de governança, decoupling, ciclo de release e flexibilidade de catálogo sem submissão às
      lojas.
- **Martin Fowler:**
    - [Backend For Frontend (BFF) Pattern](https://martinfowler.com/articles/bliki/BackendsForFrontends.html) —
      Isolamento de complexidade de múltiplos microsserviços de negócio e adaptação sob medida para a experiência de
      cada cliente móvel.
    - [Presentation and Application Controller](https://martinfowler.com/eaaCatalog/applicationController.html) — Papel
      arquitetural do Composer como orquestrador de apresentação stateless.
- **Lyft Engineering:**
    - [Server-Driven UI at Lyft](https://eng.lyft.com/) — Estratégias de resiliência, fallback graceful em runtime e
      schemas versionados com backward compatibility.
- **Spotify Engineering:**
    - [Hub Framework: Building Component-Driven Mobile Apps](https://engineering.atspotify.com/) — Conceito de blocos
      atômicos e estruturação de feeds com independência de layout e tema.
- **RFC 6648:**
    - [Deprecation of "X-" Prefix and Similar Inventions in Application-Level Protocols](https://datatracker.ietf.org/doc/html/rfc6648) —
      Diretriz normativa respeitada no Composer, assegurando headers limpos sem prefixos desnecessários.

---

## 13. Histórico de Revisões (Revision History)

| Versão | Data       | Autor                        | Mudanças / Descrição                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
|--------|------------|------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `1.0`  | 2026-09-20 | Wallan Pereira               | Criação inicial do documento de guia visual e design system mobile.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `1.1`  | 2026-09-20 | Wallan Pereira               | Reescrita para aprimorar a legibilidade e organização das seções e tabelas.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `1.2`  | 2026-09-20 | Wallan Pereira               | Correção de encoding e padronização para caracteres compatíveis.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `2.0`  | 2026-09-20 | Antigravity + Wallan Pereira | **Reestruturação Arquitetural Canônica e Catálogo Humano:**<br>• Análise visual individual exaustiva dos 10 arquivos de imagem em `docs/images/` preenchendo o template obrigatório completo.<br>• Definição dos dois dicionários estritos: Tokens SDUI do contrato vs Tokens visuais de renderização (`renderer/`).<br>• Consolidação das tabelas: Catálogo de Layout Tokens (`fixed`, `shelf`, `grid`, `list`, `pager`), Catálogo de Section Types do MVP, Catálogo de Actions (`navigate`, `open_bottom_sheet`, `track`, `noop`).<br>• Especificação do Skeleton canônico da Home (`home.default`) com distinção entre slots portantes e omitíveis.<br>• Mapeamento de rastreabilidade screenshot → contrato, auditoria de gaps, regras de isolamento visual e backlog priorizado de melhorias (P0 a P4).<br>• Restauração e ampliação das referências arquiteturais da indústria e preservação do histórico de versões. |

