# ADR-020 — Múltiplas surfaces e contratos de componente com capability explícita

- **Status:** `PROPOSTO` — implementado no servidor em 2026-09-23; os contratos novos
  (`transaction_summary@1`, `catalog_navigation@1`, `product_collection@1`) aguardam
  homologação das equipes iOS e Android antes de qualquer uso em produção.
- **Data:** 2026-09-23
- **Relacionados:** ADR-004, ADR-007, ADR-009, ADR-010, ADR-011, ADR-015, ADR-016, ADR-018, ADR-021.
- **Planejamento:** [`tasks/plan.md`](../../tasks/plan.md), tarefas T01 e T04–T07 de
  [`tasks/todo.md`](../../tasks/todo.md).

## Contexto

O composer servia uma única surface, `home`. `Select`, fallback, catálogo e validação de
skeleton embutiam regras da Home financeira: sete types, sete slots, `header` e `accounts`
portantes. `CatalogValidator` exigia **exatamente** o conjunto ativo do MVP, então nenhum
componente novo podia entrar, e ao mesmo tempo aceitava componentes inativos de nome
arbitrário — que viravam tag de métrica (`admin.catalog.upsert{type}`). `SpecValidator` não
restringia `spec.surface`, que também virava tag (`admin.spec.draft{surface}`).

As referências visuais de `docs/images` pedem quatro composições: duas montagens da Home
bancária (Nubank), uma Home com resumo de transações e um catálogo de moda. As três primeiras
cabem na surface `home`; o catálogo não pode exigir `accounts`.

## Decisão

1. **Allowlist finita de surfaces.** `Surfaces` (em `sdui-core`) declara, por surface, os slots,
   os layouts permitidos por slot, quais slots são portantes, quais types podem ocupá-la, o
   primeiro slot obrigatório e o evento de analytics do envelope. Hoje: `home` (os sete slots do
   MVP mais `transactions`, opcional) e `catalog` (`header` e `products` portantes; `navigation`
   e `featured` opcionais). Uma surface fora da lista é recusada pelos validadores antes de virar
   dado, e não tem rota HTTP.
2. **Leitura por mapeamento literal.** `GET /v1/surfaces/home` continua idêntico (caminho,
   headers, corpo, ETag, Vary). `GET /v1/surfaces/catalog` é um segundo mapeamento literal no
   mesmo controller (`SurfaceController`); não há `/{surface}` genérico. A surface entra em toda
   leitura, chave e tag do pipeline: pointer, specs publicadas, chave de árvore, chave de
   singleflight, last good, `HydrationContext`, métricas. A seleção nunca cruza surface.
3. **Catálogo fechado em contratos aprovados.** `ComponentContracts.APPROVED` é o universo finito
   de `type@typeVersion`: os sete legados mais os três novos. `CatalogValidator` recusa qualquer
   componente fora dele, em qualquer status, e exige os sete legados ativos. O mesmo conjunto é o
   universo que `CapabilityMatrix` aceita no header `Component-Capabilities` — o que é publicável
   é declarável, e nada além disso.
4. **Nenhum type novo concedido automaticamente.** A matriz do servidor continua concedendo só os
   sete legados. Um contrato novo só chega a quem o declara em `Component-Capabilities`.
   Consequências de compatibilidade:
   - slot opcional com type não declarado → section omitida com `unsupported_type`
     (ex.: `transactions` na Home);
   - slot portante que dependa de type novo → o spec declara a capability em
     `targeting.requiredCapabilities`; cliente sem ela não seleciona o spec e cai na escada de
     fallback da própria surface (no catálogo, `503 no_compatible_spec`, nunca a árvore da Home);
   - a simulação de slot portante em `SpecValidator` soma `requiredCapabilities` às capabilities
     do servidor, porque Select garante que só clientes com elas recebem o spec;
   - na composição, um slot portante esvaziado pelo filtro de capabilities também dispara a
     escada de fallback (`required_slot_empty`), e não só uma falha de hidratação.
5. **Contratos novos nascem estritos.** `ComponentPropsValidator` valida props obrigatórias,
   tipos, limites de itens (transações ≤ 5, categorias ≤ 12, vitrine ≤ 12), gatilhos pareados
   (rótulo + `...ActionId`) e recusa chaves de operação comercial (`quantity`, `favorite`,
   `addToCart`, `cart`, `checkout`, `stock`, `sku`). Os sete legados continuam sem validador de
   props para não quebrar specs publicadas. Referências a action nas props passam a incluir
   `<papel>ActionId` além de `actionId`.
6. **Limites de entrada administrativa.** Props com mais de 16 níveis de aninhamento são recusadas
   na publicação — abaixo do teto de 32 do mapper da resposta, que portanto nunca trunca conteúdo
   publicado.
7. **Política de locale.** O conteúdo desta entrega é sintético e de locale fixo `pt-BR`
   (`SurfaceDefinition.contentLocale`). O envelope ecoa o locale pedido. A chave de cache não tem
   locale; localizar props exige incluir essa dimensão na chave **antes** de ativar tradução.
8. **Tags de métrica com vocabulário fechado.** A versão exata do app sai de toda tag (fica no
   MDC do log); `schemaVersion` só aparece como valor suportado ou `other`; surface e type só
   entram depois de validados. Um `MeterFilter` com teto de valores por tag nas métricas
   próprias é defesa em profundidade.

## Alternativas descartadas

- **`/v1/surfaces/{surface}` com validação no handler:** funcionaria, mas cria um padrão de rota
  que aceita qualquer texto e desloca para o código a garantia que o roteamento já dá.
- **Catálogo aberto a qualquer type não genérico:** reintroduz a cardinalidade ilimitada e deixa
  componente sem contrato chegar a produção.
- **Conceder os types novos às faixas atuais na matriz:** entregaria a apps sem renderer um
  componente que eles não sabem desenhar.
- **Representar produto com `card_product` ou categoria com `shortcut_shelf`:** fingir um conceito
  com o type de outro (proibido pelo ADR-010 e pela matriz de rastreabilidade).
- **Header `template` ou canal por exemplo:** a troca entre montagens é feita publicando e movendo
  o pointer; canal não é tenant.

## Consequências

- A Home canônica continua compatível: fixture, ETag, Vary e contrato v3 inalterados.
- Surface nova nunca recebe fallback nem árvore da Home: last good e cache são chaveados por
  surface.
- Os exemplos em [`docs/examples/screens`](../examples/screens/README.md) são **propostas**:
  a fixture Android canônica continua ausente e nenhum renderer foi validado.
- Nomes de rotas `app://` e sheets nativos dos exemplos são ilustrativos até acordo com os apps.

## Verificação

Fontes de teste: `SurfaceRulesTest`, `ComponentContractsTest`, `SelectSurfaceAndPropWalkTest`
(core); `ScreenExamplesTest`, `SurfaceIsolationAndSingleflightTest`, `SurfaceWebTest` (app);
`ExampleResponsesContractTest` (contract). Evidências de execução registradas em
[`tasks/todo.md`](../../tasks/todo.md).
