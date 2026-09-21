# Mobile UI/UX and Design System - Visual Reference Guide

> **Objective:** This document establishes the Mobile UI/UX and Design System architectural guide based on visual references in `docs/images/`. It serves as the source of truth for the MS SDUI Composer Sections, Layouts, and Actions catalog.
>
> **Core principle:** The same Server-Driven UI mechanism can build screens from different domains (coffee, crypto, banking, ecommerce, delivery, logistics, fintech) using the **same Sections and Layouts catalog**.

---

## Fundamental SDUI Principles

1. **Backend decides what** (structure, content, order, actions); **client decides how** (native appearance, dark mode, typography, spacing).
2. **Semantic Sections catalog** - Components named by product concept (`hero_banner`, `product_card`, `transaction_list`), not by generic layout (`Row`, `Column`).
3. **Screen-independent Sections** - The same Section can appear in Home, Search, Detail without duplication.
4. **Not "backend sends CSS"** - Server does not send color, typography, margin, padding, gap, dp/pt. This is client's native Design System.

---

## Global Mapping Table

| # | Image | Domain | Screens | Main Sections | Layout |
|---|-------|---------|---------|---------------|--------|
| 1 | `coffee-app-wireframe-to-design-home.jpg` | Coffee Shop | home, menu, detail, cart | `hero_banner`, `category_grid`, `product_list`, `product_card`, `cart_summary`, `footer_cta` | `single_column` |
| 2 | `coffee-app-all-screens-flow.jpg` | Coffee Shop | home, menu, detail, cart, checkout | `hero_banner`, `category_grid`, `product_list`, `promo_banner`, `cart_summary`, `footer_cta` | `single_column` |
| 3 | `crypto-wallet-home-withdraw.jpg` | Crypto Wallet | home, withdraw | `balance_card`, `quick_action_grid`, `product_list`, `transaction_list`, `withdraw_form` | `single_column` |
| 4 | `banking-app-home-cards-transactions.jpg` | Banking / Fintech | home | `account_list`, `quick_action_grid`, `transaction_list`, `insight_card` | `single_column` |
| 5 | `finance-app-card-expenses-light-dark.jpg` | Finance (Card) | card_detail | `balance_card`, `transaction_list`, `insight_card`, `tag_pill` | `single_column` |
| 6 | `fintech-onboarding-passcode-phone.jpg` | Fintech Onboarding | onboarding, setup_passcode | `onboarding_page`, `pagination_indicator`, `footer_cta`, `passcode_input` | `single_column` |
| 7 | `ecommerce-fashion-catalog-detail-cart.jpg` | Ecommerce Fashion | catalog, product_detail, cart | `tag_pill`, `product_list`, `product_card`, `hero_banner`, `quick_action_grid`, `cart_summary`, `footer_cta` | `single_column` / `scrollable_grid` |
| 8 | `food-delivery-pizza-home-categories.jpg` | Food Delivery | home, restaurant_menu, cart | `hero_banner`, `category_grid`, `product_list`, `promo_banner`, `cart_summary`, `footer_cta` | `single_column` |
| 9 | `logistics-shipment-tracking-map.jpg` | Logistics / Tracking | shipment_tracking | `order_status_card`, `delivery_timeline`, `driver_card`, `chat_thread`, `chat_input` | `single_column` / `two_pane` |
| 10 | `nubank-home-sections-comparison.jpg` | Fintech (Nubank) | home | `balance_card`, `quick_action_grid`, `transaction_list`, `product_list`, `insight_card` | `single_column` |

---

## Sections Catalog by Domain

### Coffee Shop
- `hero_banner` - Top hero with daily promotion
- `category_grid` - Categories grid (Espresso, Latte, Pastries)
- `category_pill` - Individual category pill
- `product_list` - Vertical or horizontal product list
- `product_card` - Individual product card (name, price, image)
- `promo_banner` - Seasonal promotional banner
- `cart_summary` - Cart summary (itemCount, subtotal, total)
- `footer_cta` - Footer CTA (e.g., "Become a member")

### Crypto / Banking
- `balance_card` - Balance card (label, amount, currency)
- `account_list` - Accounts list (checking, savings, credit)
- `quick_action_grid` - Quick actions grid (Transfer, Pay, Pix, Invest)
- `quick_action` - Individual action (label, iconUrl)
- `transaction_list` - Transactions list
- `transaction_row` - Individual transaction row
- `insight_card` - Spending insight (spending by category, goal)
- `withdraw_form` - Withdraw form (amount, address, network)

### Ecommerce Fashion
- `tag_pill` - Filter pill (category, size, color)
- `product_list` - Product grid or list
- `product_card` - Product card (image, name, price, reviews)
- `hero_banner` - Hero with product images
- `quick_action_grid` - Sizes, colors, add-ons
- `cart_summary` - Cart summary
- `footer_cta` - CTA (Add to cart, Buy now, Checkout)

### Food Delivery
- `hero_banner` - Hero with main promotion
- `category_grid` - Categories grid (Pizzas, Drinks, Sides)
- `product_list` - Restaurants or menu items list
- `promo_banner` - Free shipping, coupon banner
- `cart_summary` - Cart summary
- `footer_cta` - CTA (Checkout)

### Logistics / Tracking
- `order_status_card` - Order status (statusLabel, estimatedTime)
- `delivery_timeline` - Timeline of stages (confirmed, in transit, delivered)
- `timeline_step` - Individual timeline step
- `driver_card` - Driver card (name, rating, vehicle, photo)
- `chat_thread` - Chat thread with messages
- `chat_message` - Individual message (text, timestamp, from)
- `chat_input` - Message input (placeholder, sendLabel)

### Fintech Onboarding
- `onboarding_page` - Onboarding page (title, body, imageUrl, ctaLabel)
- `pagination_indicator` - Page indicator (dots)
- `footer_cta` - CTA (Skip, Next, Get started)
- `passcode_input` - Passcode input (4 or 6 digits)

---

## Screen Layouts

| Layout | Description | Placements | Typical Use |
|--------|-------------|------------|-------------|
| `single_column` | Single vertical column with scroll | `header`, `main`, `footer` | Home, feed, detail, onboarding |
| `two_pane` | Two columns (left/right) | `left`, `right` | Detail + preview, tracking (map + chat) |
| `tabbed` | Content with tabs on top or bottom | `tabs`, `main` | Profile, settings, categories |
| `scrollable_grid` | Scrollable grid (2/3/4 columns) | `header`, `grid`, `footer` | Menu, categories, products |

**Note:** Backend sends structure (which layout, which sections, in what order); client decides appearance (dark mode, Dynamic Type, platform conventions).

---

## Actions (User Intentions)

Backend defines **when** to trigger actions; client routes actions to native handlers.

| Action Type | Main Fields | Use |
|-------------|-------------|-----|
| `navigate` | `route`, `params` (map) | Navigate to another screen |
| `openSheet` | `sheetId`, `params` | Open modal/sheet |
| `callApi` | `endpoint`, `method`, `payload` (optional) | Call API (checkout, send message, withdraw) |
| `trackEvent` | `eventName`, `properties` (map) | Analytics (view_product, add_to_cart, checkout_started) |
| `completeOnboarding` | - | Complete onboarding |
| `addToCart` | `productId`, `quantity` | Add to cart |

---

## Next Implementation Steps

1. **Implement Sections catalog in Java 25** - DTOs using Records and sealed classes for each type listed.
2. **Implement Layouts** - `single_column`, `two_pane`, `tabbed`, `scrollable_grid` with placements support.
3. **Persist templates in MongoDB** - `screen_templates` collection with JSON documents like examples above.
4. **Implement composer** - Pure method that walks template, calls domain repositories, emits Sections and Layout.
5. **Instrument observability** - Log Section type, schema version, app version on each render; metrics for `compose.hit/miss`, `section.*`, `payload.bytes`.

---

## References

- **Airbnb Ghost Platform** - [A Deep Dive into Airbnb's Server-Driven UI System](https://medium.com/airbnb-engineering/a-deep-dive-into-airbnbs-server-driven-ui-system-842244c5f5)
- **Joud Awad** - [Server-Driven UI: Ship mobile UI without app-store reviews](https://joudwawad.medium.com/how-airbnb-netflix-and-lyft-ship-ui-without-touching-the-app-store-49c9f64f5e2b)

---

## Revision History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-09-20 | Wallan Pereira | Initial document creation. |
| 1.1 | 2026-09-20 | Wallan Pereira | Rewritten to improve readability and organization. |
| 1.2 | 2026-09-20 | Wallan Pereira | Fixed encoding - removed special characters (ASCII only). |
