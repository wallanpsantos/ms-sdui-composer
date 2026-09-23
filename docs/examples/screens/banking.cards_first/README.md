# `banking.cards_first` — Home Android com cartões antes dos atalhos (T03)

> Proposta de demonstração, não contrato Android homologado. Dados sintéticos.

- **Referência:** [Nubank, montagem direita](../../../images/nubank-home-sections-comparison.jpg).
- **Surface / plataforma / canal:** `home` / `android` / `stable`.
- **Skeleton:** `home.cards_first`, **o mesmo que o seed canônico já publica** (ADR-018):
  `header` → `accounts` → `cards` → `shortcuts` (`grid`) → `offers` → `coverage` → `foryou`.
  O `skeleton.json` desta pasta documenta esse skeleton; ele não é recriado.
- **Spec:** `spec_home_android_cards_first`, revisão `rev_demo_android_cards_first`, mesmo
  targeting de [`banking.shortcuts_first`](../banking.shortcuts_first/README.md).
- **Capabilities:** só os sete types legados.

## O que muda em relação a `banking.shortcuts_first`

| Aspecto               | shortcuts-first                                  | cards-first                                |
|-----------------------|--------------------------------------------------|--------------------------------------------|
| Ordem                 | atalhos antes de cartões                         | cartões antes de atalhos                   |
| Layout de `shortcuts` | `shelf`, 4 itens                                 | `grid`, 8 itens                            |
| Types exigidos        | os mesmos                                        | os mesmos                                  |
| Títulos de slot       | do skeleton novo ("Empréstimo", "Descubra mais") | do skeleton do seed ("Crédito", "Seguros") |

Não há campo visual novo, nem header de template, nem roteamento experimental: a troca é de
revisão pelo pointer. Um app que já renderiza `shortcut_shelf@1` em `grid` e respeita a ordem de
`skeleton.slots` exibe as duas montagens sem release. O número de colunas da grade é decisão do
renderer.

A referência mostra "Descubra mais" como carrossel horizontal. O slot `coverage` aceita `shelf`,
mas este exemplo reusa o skeleton do seed (`list`) de propósito, para a comparação isolar ordem e
layout de atalhos; mudar isso é uma revisão nova do skeleton.

## Troca e rollback

Publicar esta spec depois de `banking.shortcuts_first` move o pointer `home/android/stable` para
`rev_demo_android_cards_first` e guarda `rev_demo_android_shortcuts_first` como anterior. O
rollback sem alvo volta para a anterior; ver o
[README da pasta](../README.md#troca-de-montagem-e-rollback).

Resposta esperada: [`response.json`](response.json) (headers iguais aos de shortcuts-first).
