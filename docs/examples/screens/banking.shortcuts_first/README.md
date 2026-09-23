# `banking.shortcuts_first` — Home Android com atalhos antes dos cartões (T02)

> Proposta de demonstração, não contrato Android homologado. Dados sintéticos.

- **Referência:** [Nubank, montagem esquerda](../../../images/nubank-home-sections-comparison.jpg).
- **Surface / plataforma / canal:** `home` / `android` / `stable`.
- **Skeleton:** `home.shortcuts_first` (novo, rev. 1): `header` → `accounts` → `shortcuts` (`shelf`)
  → `cards` → `offers` → `coverage`. Sem `foryou`: a referência não tem bloco de decisão.
- **Spec:** `spec_home_android_shortcuts_first`, revisão `rev_demo_android_shortcuts_first`,
  app Android `>= 8.10.0`, schema 3, prioridade 100, `requiredCapabilities` = `top_bar@1`,
  `account_card@1`.
- **Capabilities:** só os sete types legados — nenhum header `Component-Capabilities` necessário.

## Mapeamento

| Section               | Type                | Slot        | Conteúdo sintético                                   |
|-----------------------|---------------------|-------------|------------------------------------------------------|
| `sec_header`          | `top_bar@1`         | `header`    | "Olá, Cliente"; ação "Ajuda"                         |
| `sec_account`         | `account_card@1`    | `accounts`  | Saldo ocultável                                      |
| `sec_shortcuts`       | `shortcut_shelf@1`  | `shortcuts` | Área Pix, Pagar, Pegar emprestado (badge), Transferir |
| `sec_card`            | `card_product@1`    | `cards`     | Fatura, limite, débito automático; "Meus cartões"     |
| `sec_offer`           | `credit_offer@1`    | `offers`    | Empréstimo disponível                                 |
| `sec_coverage_phone`, `sec_coverage_life` | `coverage_card@1` | `coverage` | Seguro celular, seguro de vida            |

Blocos não mapeados (NuEnsina, "Acompanhe também") e responsabilidades nativas estão na
[matriz](../README.md#nubank--duas-montagens-da-home-android).

## Actions e destinos nativos (ilustrativos)

`app://help`, `app://account`, `app://pix`, `app://payments`, `app://credit/loan`,
`app://transfer`, `app://cards/invoice`, `app://cards/installments`, `app://cards`,
`app://credit/loan/simulate`, `app://insurance/phone`, `app://insurance/life`. Todas `navigate`.

## Consulta

```
GET /v1/surfaces/home
UI-Schema-Version: 3
Client-Platform: android
Client-Version: 8.14.2
Client-Build: 81420
Accept-Language: pt-BR
API-Version: 1
OS-Version: 18.1
```

Resposta esperada: [`response.json`](response.json) — ETag
`W/"rev_demo_android_shortcuts_first-android-3-ad4e3e6a255a"`.

Publicação passo a passo e rollback: [README da pasta](../README.md#reprodução-no-ambiente-de-demonstração-t10).
