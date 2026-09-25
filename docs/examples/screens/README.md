# Exemplos de telas — quatro composições a partir das referências visuais

> **Status:** propostas executáveis no modo demo (em memória). Validadas pela governança do
> servidor e cobertas por testes; **não** são contratos móveis homologados. A fixture Android
> canônica continua ausente e não foi inferida destes exemplos. Nenhum renderer foi validado.

Decisões
em [ADR-020](../../arquitetura-de-referencia.md#adr-020--múltiplas-surfaces-e-contratos-de-componente).
Contratos novos em [`transaction-summary-v1.md`](../../contratos/transaction-summary-v1.md) e
[`componentes-comercio-v1.md`](../../contratos/componentes-comercio-v1.md). Tutorial completo em
[`guia-criacao-telas-componentes.md`](../../guia-criacao-telas-componentes.md).

| Exemplo                                                        | Referência                                                                    | Surface   | Plataforma | Skeleton                      | Revisão                            | Capabilities além das sete                                          |
|----------------------------------------------------------------|-------------------------------------------------------------------------------|-----------|------------|-------------------------------|------------------------------------|---------------------------------------------------------------------|
| [`banking.shortcuts_first`](banking.shortcuts_first/README.md) | [Nubank, esquerda](../../images/nubank-home-sections-comparison.jpg)          | `home`    | Android    | `home.shortcuts_first` (novo) | `rev_demo_android_shortcuts_first` | nenhuma                                                             |
| [`banking.cards_first`](banking.cards_first/README.md)         | [Nubank, direita](../../images/nubank-home-sections-comparison.jpg)           | `home`    | Android    | `home.cards_first` (seed)     | `rev_demo_android_cards_first`     | nenhuma                                                             |
| [`banking.transactions`](banking.transactions/README.md)       | [Home bancária](../../images/banking-app-home-cards-transactions.jpg)         | `home`    | iOS        | `home.transactions` (novo)    | `rev_demo_ios_transactions`        | `transaction_summary@1` (opcional)                                  |
| [`fashion.catalog`](fashion.catalog/README.md)                 | [Moda, tela esquerda](../../images/ecommerce-fashion-catalog-detail-cart.jpg) | `catalog` | iOS        | `catalog.default` (novo)      | `rev_demo_ios_fashion_catalog`     | `product_collection@1` (exigida), `catalog_navigation@1` (opcional) |

Cada diretório tem três formatos que não se misturam:

- `skeleton.json` — corpo de `PUT /admin/v1/skeletons/{id}` (estrutura da surface);
- `spec.json` — corpo de `POST /admin/v1/specs` (rascunho de conteúdo e targeting);
- `response.json` — envelope que `GET /v1/surfaces/{home|catalog}` devolve depois da publicação,
  com os headers indicados no README do exemplo (`generatedAt` varia a cada chamada).

Os JSON de entrada são os mesmos que o modo demo carrega (`sdui-app/src/main/resources/demo/screens`,
cópia verificada por teste). O `checksum` de cada spec é o SHA-256 do `skeleton.json`
canonicalizado (chaves ordenadas, sem espaços, UTF-8) — é o valor que vira `skeletonHash` no
envelope.

## Matriz de rastreabilidade (T01)

Classificação de todo bloco visível nas três imagens. **Reutilizável**: type existente com o
significado dele. **Contrato novo**: type proposto neste ciclo. **Nativo**: responsabilidade do
app (chrome, dado do usuário, interação local ou fluxo hostil). **Não mapeado**: sem contrato
nesta entrega; não foi fingido com outro type.

### Nubank — duas montagens da Home (Android)

| Bloco                                                                     | Classificação | Mapeamento                                                                             |
|---------------------------------------------------------------------------|---------------|----------------------------------------------------------------------------------------|
| Avatar, "Olá, Théo"                                                       | Reutilizável  | `top_bar@1` (`greetingPrefix`, `greetingName` sintético "Cliente")                     |
| Ícone de olho (ocultar valores)                                           | Nativo        | Estado local; `concealable`/`concealed` já vêm em `account_card`                       |
| Ícone de ajuda                                                            | Reutilizável  | `top_bar@1.primaryActionLabel` + `navigate app://help`                                 |
| Ícone de convidar                                                         | Nativo        | Chrome do header, fora do contrato do `top_bar@1`                                      |
| Conta e saldo                                                             | Reutilizável  | `account_card@1` em `accounts`                                                         |
| Atalhos (Pix, Pagar, Pegar emprestado com badge, Transferir…)             | Reutilizável  | `shortcut_shelf@1` em `shortcuts` — `shelf` à esquerda, `grid` à direita               |
| Botão "Meus cartões"                                                      | Reutilizável  | Action `navigate app://cards` da section `card_product`; posição é do renderer         |
| Banner "NuEnsina"                                                         | Não mapeado   | Conteúdo editorial sem contrato; candidato futuro, não fingido como `decision_card`    |
| Cartão de crédito (fatura, limite, débito automático, "Parcelar compras") | Reutilizável  | `card_product@1` em `cards` com linhas e actions                                       |
| "Acompanhe também" (assistente, pedacinho)                                | Não mapeado   | Não há slot nem type para links de acompanhamento na Home                              |
| Empréstimo ("Valor disponível de até…")                                   | Reutilizável  | `credit_offer@1` em `offers`                                                           |
| "Descubra mais" (seguro celular, seguro vida)                             | Reutilizável  | `coverage_card@1` em `coverage` (layout `list` nos dois exemplos; `shelf` é permitido) |
| Barra de navegação inferior, status bar                                   | Nativo        | Chrome do app                                                                          |
| Cores, raios, grade 2×4, badge lilás                                      | Nativo        | Renderer / Design System                                                               |

### Home bancária com transações (iOS)

| Bloco                                    | Classificação          | Mapeamento                                                                                                            |
|------------------------------------------|------------------------|-----------------------------------------------------------------------------------------------------------------------|
| "Good Morning, Alexandra" e avatar       | Reutilizável           | `top_bar@1` (saudação sintética) + `navigate app://profile`                                                           |
| Cartão "Balance" fundindo saldo e cartão | Reutilizável, separado | Saldo em `account_card@1` (`accounts`, `fixed`); cartões em `card_product@1` (`cards`, `pager`). Nada de type híbrido |
| Carrossel com peek do próximo cartão     | Nativo                 | `pager` é o token; peek, gradiente e chip são do renderer                                                             |
| Send, Request, Pay bills, Exchange       | Reutilizável           | `shortcut_shelf@1` em `shortcuts` (`grid`)                                                                            |
| "Latest transactions" e ícone de filtro  | Contrato novo          | `transaction_summary@1` em `transactions`; filtro = `open_bottom_sheet`, extrato = `navigate`                         |
| Valores verde/vermelho                   | Nativo                 | `direction: credit                                                                                                    |debit` é semântica; cor é do renderer               |
| Barra inferior com botão "+"             | Nativo                 | Chrome do app                                                                                                         |

### Moda — catálogo, detalhe e carrinho (iOS)

| Bloco                                                                | Classificação         | Mapeamento                                                                                               |
|----------------------------------------------------------------------|-----------------------|----------------------------------------------------------------------------------------------------------|
| "TRENDORA" (marca)                                                   | Nativo                | Chrome do app                                                                                            |
| Avatar, "Hello Tavorian"                                             | Reutilizável          | `top_bar@1` na surface `catalog` (saudação sintética)                                                    |
| Frase "Fashion confidence…"                                          | Não mapeado           | `top_bar@1` não tem campo de slogan; não improvisado                                                     |
| Sacola com contador                                                  | Reutilizável + Nativo | Entrada: `top_bar@1.primaryActionLabel` + `navigate app://shop/cart`. Contador é dado do usuário: nativo |
| Busca e botão "Filter"                                               | Contrato novo         | `catalog_navigation@1` (`searchActionId` → busca nativa; `filterActionId` → sheet nativo)                |
| Tags (Trending, Shoes, Bag, Shirts)                                  | Contrato novo         | `catalog_navigation@1.categories`, cada uma `navigate` para o catálogo filtrado                          |
| Produto em destaque com preço                                        | Contrato novo         | `product_collection@1` em `featured` (`pager`)                                                           |
| Grade de produtos                                                    | Contrato novo         | `product_collection@1` em `products` (`grid`, até 12 itens, "Ver todos")                                 |
| Coração (favorito)                                                   | Nativo                | Interação e estado do usuário; chave `favorite` é recusada no spec                                       |
| Tela de detalhe (tamanho, cor, quantidade, "Add to Cart", "Buy Now") | Nativo                | Destino de `navigate app://shop/products/{id}`; nenhuma dessas operações no composer                     |
| Carrinho em bottom sheet e checkout                                  | Nativo / hostil       | Checkout é tela hostil (ADR-015); carrinho é dado do usuário                                             |
| Barra inferior                                                       | Nativo                | Chrome do app                                                                                            |

## Reprodução no ambiente de demonstração (T10)

**Ambiente:** instância única, em memória. Tudo é perdido no restart; publicar em uma réplica
não afeta outra (AGENTS.md §17). Os dados são sintéticos. Nada disso vale para produção.

### Opção A — carga automática

```bash
SDUI_DEMO_ENABLED=true ./gradlew :sdui-bootstrap:bootRun
```

O `DemoScreensLoader` publica, nesta ordem e pelo fluxo administrativo real (`demo.maker` → `demo.checker`, chaves
`demo:<exemplo>:open|approve`):

1. `banking.shortcuts_first` → pointer `home/android/stable` = `rev_demo_android_shortcuts_first`;
2. `banking.cards_first` → pointer vai para `rev_demo_android_cards_first`, anterior = shortcuts-first;
3. `banking.transactions` → pointer `home/ios/stable` = `rev_demo_ios_transactions`, anterior = `rev_01K8HOMEMAIN`;
4. `fashion.catalog` → pointer `catalog/ios/stable` = `rev_demo_ios_fashion_catalog`.

Os componentes novos entram no catálogo como `ACTIVE` antes das specs que os usam. A carga é
idempotente: reexecutá-la não republica nada.

### Opção B — passo a passo pela API

Com o serviço no ar em memória (seed canônico), para cada exemplo `EX` e skeleton `SK`:

```bash
H='-H Content-Type:application/json'
MAKER='-H Actor-Id:maker-1 -H Actor-Role:MAKER'
CHECKER='-H Actor-Id:checker-1 -H Actor-Role:CHECKER'

# 0. Contratos novos (só transactions e catalog): um PUT por componente
curl -X PUT $H $MAKER localhost:8080/admin/v1/catalog/components/transaction_summary/1 \
  -d '{"type":"transaction_summary","typeVersion":1,"status":"ACTIVE","sinceSchema":"3","requiredProps":[]}'

# 1. Skeleton (dispensado em banking.cards_first: home.cards_first já vem do seed)
curl -X PUT $H $MAKER localhost:8080/admin/v1/skeletons/SK --data @docs/examples/screens/EX/skeleton.json

# 2. Rascunho validado da spec
curl -X POST $H $MAKER localhost:8080/admin/v1/specs --data @docs/examples/screens/EX/spec.json

# 3. Maker abre o pedido (specId e revision do passo 2)
curl -X POST $H $MAKER -H Idempotency-Key:EX-open localhost:8080/admin/v1/publish-requests \
  -d '{"specId":"<specId>","revision":1,"channel":"stable"}'

# 4. Checker aprova (requestId do passo 3)
curl -X POST $H $CHECKER -H Idempotency-Key:EX-approve localhost:8080/admin/v1/publish-requests/<requestId>/approve

# 5. Consulta com os headers do README do exemplo
curl -i localhost:8080/v1/surfaces/home -H UI-Schema-Version:3 -H Client-Platform:android \
  -H Client-Version:8.14.2 -H Client-Build:81420 -H Accept-Language:pt-BR -H API-Version:1 -H OS-Version:18.1
```

### Troca de montagem e rollback

As duas montagens Nubank são duas specs da mesma surface: vale a que o pointer aponta. Depois de
publicar `banking.cards_first`, o rollback volta para a anterior sem republicar nada:

```bash
curl -X POST $H $CHECKER -H Idempotency-Key:rb-android-1 \
  localhost:8080/admin/v1/pointers/home/android/stable:rollback -d '{"reason":"voltar para atalhos primeiro"}'
```

O mesmo comando repetido com a mesma chave devolve o pointer sem mover de novo. Um novo rollback (outra chave) alterna
de volta. O rollback invalida a árvore e grava a lápide do last good na
versão nova do pointer: o fallback nunca serve a montagem retirada.

### Diagnóstico rápido

| Sintoma                                                   | Causa provável                                             |
|-----------------------------------------------------------|------------------------------------------------------------|
| 400 `VALIDATION` "componente sem contrato aprovado"       | Type fora de `ComponentContracts.APPROVED`                 |
| 400 "type X fora do catalogo"                             | Faltou o passo 0 (componente não está no catálogo)         |
| 400 "slot required 'products' pode ficar vazio"           | Spec de catálogo sem `product_collection@1` no targeting   |
| 200 com `omitted` `unsupported_type`                      | Cliente não declarou a capability do type novo             |
| 503 `no_compatible_spec` no catálogo                      | Cliente sem `product_collection@1`, ou plataforma sem spec |
| 409 `IDEMPOTENT_IN_FLIGHT` / 422 `IDEMPOTENCY_KEY_REUSED` | Mesma chave ainda em voo / reusada com outros parâmetros   |
