# Contrato proposto — `transaction_summary@1`

> **Status:** proposta de contrato (ADR-020). Validado no servidor; **não homologado** pelos apps.
> Nenhuma integração com extrato real existe: o conteúdo desta versão vem inteiro do spec e é
> sintético.

## Conceito

Resumo das transações mais recentes da conta, com entrada para o extrato e para o filtro nativos.
É um **resumo**: até cinco linhas. Extrato, busca, paginação e filtro acontecem nos destinos
nativos. O nome segue o conceito de produto (ADR-010), não a forma (`transaction_list` foi
recusado).

| Campo                | Valor                                                               |
|----------------------|---------------------------------------------------------------------|
| Surface              | `home`                                                              |
| Slot                 | `transactions` (opcional, layout `list`)                             |
| Capability           | `transaction_summary@1` — só com `Component-Capabilities`            |
| Portante             | Não. Sem a capability, a section é omitida com `unsupported_type`.  |

## Props

| Prop               | Obrigatória | Tipo    | Regra                                                          |
|--------------------|-------------|---------|----------------------------------------------------------------|
| `title`            | sim         | texto   | não vazio                                                      |
| `items`            | sim         | lista   | 1 a 5 objetos, `id` único                                       |
| `items[].id`       | sim         | texto   | identificador da linha, sem dado pessoal                        |
| `items[].description` | sim      | texto   | descrição exibível                                              |
| `items[].amountDisplay` | sim    | texto   | valor já formatado (`- R$ 10,99`)                                |
| `items[].direction` | sim        | texto   | `credit` ou `debit` — semântica; a cor é do renderer            |
| `items[].detail`   | não         | texto   | contraparte e data já formatadas                                |
| `items[].icon`     | não         | texto   | token de ícone do Design System                                  |
| `viewAllLabel` + `viewAllActionId` | juntos | texto | rótulo e action para o extrato nativo              |
| `filterLabel` + `filterActionId`   | juntos | texto | rótulo e action do filtro nativo                   |

## Actions

- `navigate` com rota `app://` para o extrato (`app://account/statement` nos exemplos).
- `open_bottom_sheet` para o filtro (`sheet_statement_filters` nos exemplos).
- Nenhuma action de mutação (estorno, contestação, pagamento). Rotas e sheets são ilustrativos até
  acordo com os apps.

## Proibições

- Cor, fonte, espaçamento e qualquer chave de `VISUAL_KEYS` (VisualGuard).
- PII e dado regulado: número de conta, agência, CPF, PAN, tokens (PiiGuard por chave e formato).
- Dado pessoal real no cache de árvore: a árvore é compartilhada entre clientes da mesma revisão.
  Transações reais exigem projeção segura por usuário e revisão da política de cache — fora desta
  entrega.

## Erros e omissão

| Situação                                 | Resultado                                                   |
|------------------------------------------|-------------------------------------------------------------|
| Cliente sem `transaction_summary@1`       | Section omitida (`unsupported_type`), Home entregue com 200 |
| Props inválidas                          | Publicação recusada com 400 e a lista de erros              |
| Falha de hidratação (futuro hidratador)  | Omitida com `hydration_failed`/`hydration_timeout`          |

Exemplo completo: [`banking.transactions`](../examples/screens/banking.transactions/README.md).
