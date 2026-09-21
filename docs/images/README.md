# Mobile UI/UX e Design System — Guia de Referência Visual

> Este documento estabelece o guia arquitetural de Mobile UI/UX e Design System baseado nas referê£ªncias visuais disponí£§veis neste diretó³¢¢rio.

## Visã££o geral

Este documento documenta as interfaces de Mobile UI/UX que o backend SDUI deve ser capaz de montar via Server-Driven UI. Cada imagem representa um domí£§nio de produto diferente, mas todas sã££o compostas pelo **mesmo catá¡¢logo de Sections e Layouts**.

**Princí£§pios fundamentais:**

1. **Backend decide o quê** (estrutura, conteú‚do, ordem, açöµ£§es); cliente decide como (aparê£ªncia nativa).** [web:1]
2. **Catá¡¢logo semâ£¢ntico de Sections** — componentes nomeados pelo conceito de produto (`hero_banner`, `product_card`, `transaction_list`), nã££o por layout gené©¢rico (`Row`, `Column`). [web:1][web:2]
3. **Sections independentes de Screen** — a mesma Section pode aparecer em Home, Search, Detail sem duplicaç££o. [web:1][web:2]
4. **Nã££o é "backend manda CSS"** — o servidor nã££o envia cor, tipografia, margin, padding, gap, width, height, rounded, dp/pt. Isso é Design System nativo do cliente. [web:1]

## Tabela de mapeamento global

| Imagem | Domí£§nio | Screens | Sections principais | Layout |
|--------|---------|---------|---------------------|--------|
| `coffee-app-wireframe-to-design-home.jpg` | Coffee Shop | home, menu, detail, cart | `hero_banner`, `category_grid`, `product_list`, `product_card`, `cart_summary`, `footer_cta` | `single_column` |
| `coffee-app-all-screens-flow.jpg` | Coffee Shop | home, menu, detail, cart, checkout | `hero_banner`, `category_grid`, `product_list`, `promo_banner`, `cart_summary`, `footer_cta` | `single_column` |
| `crypto-wallet-home-withdraw.jpg` | Crypto Wallet | home, withdraw | `balance_card`, `quick_action_grid`, `product_list`, `transaction_list`, `withdraw_form` | `single_column` |
| `banking-app-home-cards-transactions.jpg` | Banking / Fintech | home | `account_list`, `quick_action_grid`, `transaction_list`, `insight_card` | `single_column` |
| `finance-app-card-expenses-light-dark.jpg` | Finance (Cartã££o) | card_detail | `balance_card`, `transaction_list`, `insight_card`, `tag_pill` | `single_column` |
| `fintech-onboarding-passcode-phone.jpg` | Fintech Onboarding | onboarding, setup_passcode | `onboarding_page`, `pagination_indicator`, `footer_cta`, `passcode_input` | `single_column` |
| `ecommerce-fashion-catalog-detail-cart.jpg` | Ecommerce Fashion | catalog, product_detail, cart | `tag_pill`, `product_list`, `product_card`, `hero_banner`, `quick_action_grid`, `cart_summary`, `footer_cta` | `single_column` / `scrollable_grid` |
| `food-delivery-pizza-home-categories.jpg` | Food Delivery | home, restaurant_menu, cart | `hero_banner`, `category_grid`, `product_list`, `promo_banner`, `cart_summary`, `footer_cta` | `single_column` |
| `logistics-shipment-tracking-map.jpg` | Logistics / Tracking | shipment_tracking | `order_status_card`, `delivery_timeline`, `driver_card`, `chat_thread`, `chat_input` | `single_column` / `two_pane` |
| `nubank-home-sections-comparison.jpg` | Fintech (Nubank) | home | `balance_card`, `quick_action_grid`, `transaction_list`, `product_list`, `insight_card` | `single_column` |

## Catá¡¢logo de Sections por domí£§nio

| Domí£§nio | Sections |
|----------|----------||
| **Coffee Shop** | `hero_banner`, `category_grid`, `category_pill`, `product_list`, `product_card`, `promo_banner`, `cart_summary`, `footer_cta` |
| **Crypto / Banking** | `balance_card`, `account_list`, `quick_action_grid`, `quick_action`, `transaction_list`, `transaction_row`, `insight_card`, `withdraw_form` |
| **Ecommerce** | `tag_pill`, `product_list`, `product_card`, `hero_banner`, `quick_action_grid`, `cart_summary`, `footer_cta` |
| **Food Delivery** | `hero_banner`, `category_grid`, `product_list`, `promo_banner`, `cart_summary`, `footer_cta` |
| **Logistics** | `order_status_card`, `delivery_timeline`, `timeline_step`, `driver_card`, `chat_thread`, `chat_message`, `chat_input` |
| **Fintech Onboarding** | `onboarding_page`, `pagination_indicator`, `footer_cta`, `passcode_input` |

## Layouts de Screen

| Layout | Descriç££o | Placements | Uso tí­‚pico |
|--------|-----------|------------|------------||
| `single_column` | Uma coluna vertical, scroll | `header`, `main`, `footer` | Home, feed, detail, onboarding |
| `two_pane` | Duas colunas (esq/dir) | `left`, `right` | Detail + preview, tracking (mapa + chat) |
| `tabbed` | ConteÚ¢do com tabs | `tabs`, `main` | Perfil, configuraçµ£μes |
| `scrollable_grid` | Grid rolá¡¢vel (2/3/4 colunas) | `header`, `grid`, `footer` | Menu, categorias, produtos |

## Actions

| Action Type | Uso |
|-------------|-----||
| `navigate` | Navegar para outra tela |
| `openSheet` | Abrir modal/sheet |
| `callApi` | Chamar API (checkout, enviar mensagem) |
| `trackEvent` | Analytics |
| `completeOnboarding` | Finalizar onboarding |
| `addToCart` | Adicionar ao carrinho |

## Prí£¢ximos passos

1. Implementar catá¡¢logo de Sections em Java 25 (Records, sealed classes).
2. Implementar Layouts (`single_column`, `two_pane`, `tabbed`, `scrollable_grid`).
3. Persistir templates em MongoDB (coleç££o `screen_templates`).
4. Implementar composer (mé©¢todo puro que caminha template, chama repositó³¢¢rios, emite Sections).
5. Instrumentar observabilidade (logar tipo de Section, schema version, app version).

**Referê£ªncias:**

- [Airbnb Ghost Platform](https://medium.com/airbnb-engineering/a-deep-dive-into-airbnbs-server-driven-ui-system-842244c5f5) [web:2]
- [Server-Driven UI: Ship mobile UI without app-store reviews](https://joudwawad.medium.com/how-airbnb-netflix-and-lyft-ship-ui-without-touching-the-app-store-49c9f64f5e2b) [web:1]

## Histó³¢¢rico

| Versã££o | Data | Autor | Mudanç£§as |
|---------|------|-------|-----------||
| 1.0 | 2026-09-20 | Wallan Pereira | Criaç££o do documento. |
