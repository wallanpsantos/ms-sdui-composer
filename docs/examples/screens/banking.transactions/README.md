# `banking.transactions` — Home iOS com resumo de transações (T09)

> Proposta de demonstração. `transaction_summary@1` é contrato novo não homologado. Todo o
> conteúdo, inclusive as transações, é sintético e vem do spec: não há consulta a extrato real.

- **Referência:** [Home bancária](../../../images/banking-app-home-cards-transactions.jpg).
- **Surface / plataforma / canal:** `home` / `ios` / `stable`.
- **Skeleton:** `home.transactions` (novo): `header` → `accounts` (`fixed`) → `cards` (`pager`)
  → `shortcuts` (`grid`) → `transactions` (`list`, opcional).
- **Spec:** `spec_home_ios_transactions`, revisão `rev_demo_ios_transactions`, app iOS
  `8.10.0`–`8.19.99`, SO ≥ 16.0, prioridade 100. Publicada, passa a ser a revisão apontada da Home
  iOS no ambiente demo (a anterior é a canônica `rev_01K8HOMEMAIN`).

## Separação semântica

A imagem funde saldo e cartão num mesmo cartão visual. Aqui são dois conceitos: saldo em
`account_card@1` (`accounts`) e cartões em `card_product@1` (`cards`, `pager`). O peek do
carrossel, o gradiente e o chip são do renderer.

## Comportamento por capability

| Cliente                                        | Resultado                                                                 |
|------------------------------------------------|---------------------------------------------------------------------------|
| Declara `Component-Capabilities: transaction_summary@1` | 6 sections, com o resumo — [`response.json`](response.json)      |
| Não declara                                    | 5 sections, `omitted: [sec_transactions, unsupported_type]`, 200 — [`response-without-capability.json`](response-without-capability.json) |

O slot `transactions` não é portante: a ausência do componente nunca derruba a Home.

## Actions e destinos nativos (ilustrativos)

- Extrato: `navigate app://account/statement` (saldo e "Ver extrato" do resumo).
- Filtro de transações: `open_bottom_sheet sheet_statement_filters`.
- Cartões: `navigate app://cards/visa`, `app://cards/mastercard`.
- Atalhos: `app://transfer/send`, `app://transfer/request`, `app://payments/bills`, `app://exchange`.

## Consulta

```
GET /v1/surfaces/home
UI-Schema-Version: 3
Client-Platform: ios
Client-Version: 8.14.2
Client-Build: 81420
Accept-Language: pt-BR
API-Version: 1
OS-Version: 18.1
Component-Capabilities: transaction_summary@1
```

ETag com a capability: `W/"rev_demo_ios_transactions-ios-3-8fe43179c015"`; sem ela,
`...-ad4e3e6a255a` — representações diferentes nunca compartilham ETag.
