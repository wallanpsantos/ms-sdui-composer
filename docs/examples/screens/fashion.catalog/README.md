# `fashion.catalog` — Catálogo de moda na surface `catalog` (T08)

> Proposta de demonstração. `catalog_navigation@1` e `product_collection@1` são contratos novos
> não homologados. Produtos, preços e imagens são fictícios.

- **Referência:** [Moda, tela esquerda](../../../images/ecommerce-fashion-catalog-detail-cart.jpg).
  Só o catálogo vira spec; detalhe, carrinho e checkout continuam telas nativas.
- **Surface / plataforma / canal:** `catalog` / `ios` / `stable` — `GET /v1/surfaces/catalog`.
- **Skeleton:** `catalog.default` (novo): `header` → `navigation` (`shelf`) → `featured`
  (`pager`) → `products` (`grid`, portante). Nenhum slot financeiro.
- **Spec:** `spec_catalog_ios_fashion`, revisão `rev_demo_ios_fashion_catalog`, app iOS
  `>= 8.10.0`, `requiredCapabilities` = `top_bar@1`, `product_collection@1`.

## Mapeamento

| Section          | Type                     | Slot         | Conteúdo fictício                                       |
|------------------|--------------------------|--------------|---------------------------------------------------------|
| `sec_header`     | `top_bar@1`              | `header`     | "Olá, Cliente"; ação "Sacola" → carrinho nativo         |
| `sec_navigation` | `catalog_navigation@1`   | `navigation` | Busca, filtro e 4 categorias ("Em alta" selecionada)    |
| `sec_featured`   | `product_collection@1`   | `featured`   | 1 produto em destaque                                    |
| `sec_products`   | `product_collection@1`   | `products`   | 4 produtos e "Ver todos"                                 |

Favorito, tamanho, cor, quantidade, "Add to Cart", "Buy Now", carrinho e checkout não existem no
contrato — o validador recusa essas chaves. Ver a
[matriz](../README.md#moda--catálogo-detalhe-e-carrinho-ios).

## Comportamento por capability

| Cliente declara                                    | Resultado                                                      |
|----------------------------------------------------|----------------------------------------------------------------|
| `catalog_navigation@1,product_collection@1`         | 4 sections — [`response.json`](response.json)                 |
| Só `product_collection@1`                           | 3 sections, navegação omitida (`unsupported_type`)             |
| Nenhuma                                             | 503 `no_compatible_spec`: o spec exige a vitrine; a Home nunca é servida no lugar |

## Actions e destinos nativos (ilustrativos)

`app://shop/cart`, `app://shop/search`, `sheet_catalog_filters` (bottom sheet),
`app://shop/catalog/{categoria}`, `app://shop/products/{produto}`, `app://shop/catalog`.

## Consulta

```
GET /v1/surfaces/catalog
UI-Schema-Version: 3
Client-Platform: ios
Client-Version: 8.14.2
Client-Build: 81420
Accept-Language: pt-BR
API-Version: 1
OS-Version: 18.1
Component-Capabilities: catalog_navigation@1,product_collection@1
```

ETag: `W/"rev_demo_ios_fashion_catalog-ios-3-09a9a0815606"`. Evento de analytics:
`sdui_catalog_composed`.
