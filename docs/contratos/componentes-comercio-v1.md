# Contratos propostos — catálogo de comércio (`catalog_navigation@1`, `product_collection@1`)

> **Status:** proposta de contrato (ADR-020). Validado no servidor; **não homologado** pelos apps.
> O conteúdo dos exemplos é fictício; não há integração com catálogo, estoque, preço ou carrinho.

## Surface `catalog`

| Slot         | Obrigatório | Layouts          | Type                   |
|--------------|-------------|------------------|------------------------|
| `header`     | sim         | `fixed`          | `top_bar@1`            |
| `navigation` | não         | `shelf`, `fixed` | `catalog_navigation@1` |
| `featured`   | não         | `pager`, `list`  | `product_collection@1` |
| `products`   | sim         | `grid`, `list`   | `product_collection@1` |

Evento de analytics do envelope: `sdui_catalog_composed`. Não há slot financeiro. A surface é
servida em `GET /v1/surfaces/catalog`, com a mesma negociação de headers da Home.

## `catalog_navigation@1`

Entradas para navegar o catálogo: busca, filtro e categorias. Buscar e filtrar acontecem nos
destinos nativos; o composer entrega só a intenção de ir até eles.

| Prop                                   | Obrigatória       | Regra                                                                                      |
|----------------------------------------|-------------------|--------------------------------------------------------------------------------------------|
| `searchPlaceholder` + `searchActionId` | juntos, opcionais | texto e action `navigate` para a busca nativa                                              |
| `filterLabel` + `filterActionId`       | juntos, opcionais | texto e action `open_bottom_sheet` para o filtro                                           |
| `categories`                           | opcional          | 1 a 12 itens `{id, label, selected?, actionId}`, `id` único, no máximo um `selected: true` |

Ao menos uma das três entradas precisa existir. Cada categoria é uma rota `navigate` para o
catálogo filtrado; `selected` é estado semântico, não visual.

## `product_collection@1`

Vitrine limitada de produtos. Não é o catálogo inteiro: paginação e busca completas são do destino
nativo, alcançado por `viewAllActionId`.

| Prop                                      | Obrigatória | Regra                                       |
|-------------------------------------------|-------------|---------------------------------------------|
| `title`                                   | não         | texto                                       |
| `items`                                   | sim         | 1 a 12 itens, `id` único                    |
| `items[].id`, `name`, `priceDisplay`      | sim         | texto; preço já formatado                   |
| `items[].actionId`                        | sim         | `navigate` para o detalhe nativo do produto |
| `items[].priceLabel`, `imageUrl`, `badge` | não         | texto                                       |
| `viewAllLabel` + `viewAllActionId`        | juntos      | rótulo e action para o catálogo completo    |

## Fora do contrato (responsabilidade nativa)

Detalhe do produto, tamanho, cor, quantidade, favorito, carrinho, checkout e pagamento. O
validador recusa as chaves `quantity`, `favorite`, `favorited`, `addToCart`, `cart`, `cartId`,
`checkout`, `stock` e `sku` em qualquer nível das props, além das chaves visuais de sempre (`color` e `size` já estão em
`VISUAL_KEYS`). Checkout é tela hostil (ADR-015). O contador da
sacola é dado do usuário e fica no app.

## Compatibilidade

- Os dois types só chegam a quem declara em `Component-Capabilities`.
- O spec de catálogo exige `product_collection@1` em `targeting.requiredCapabilities`: sem ela o
  cliente não seleciona o spec e recebe `503 no_compatible_spec` — nunca a Home.
- Sem `catalog_navigation@1`, a navegação é omitida (`unsupported_type`) e o catálogo segue.

Exemplo completo: [`fashion.catalog`](../examples/screens/fashion.catalog/README.md).
