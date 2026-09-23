# Guia — criar uma tela e um componente no ms-sdui-composer

Do contrato à publicação, ao rollback e à depreciação. Usa os exemplos de
[`docs/examples/screens`](examples/screens/README.md) como referência executável. Decisões em
[ADR-020](memoria-operacional-e-arquitetural.md#adr-020--múltiplas-surfaces-e-contratos-de-componente); persistência em
[ADR-021](memoria-operacional-e-arquitetural.md#adr-021--persistência-mongodb-83-e-cache-redis).

> **O que é proposto, implementado e homologado.** Surfaces `home` e `catalog`, contratos novos e
> modo demo estão **implementados no servidor** e cobertos por testes. Os contratos novos e os
> exemplos são **propostas**: nenhum renderer iOS/Android foi homologado com eles, e a fixture
> Android canônica continua ausente. Fidelidade visual depende dos apps e está fora deste guia.

## 1. Três artefatos, três formatos

| Artefato   | O que descreve                                        | Onde entra                              | Quem valida            |
|------------|-------------------------------------------------------|-----------------------------------------|------------------------|
| Skeleton   | Estrutura: slots, ordem, layouts permitidos, portantes | `PUT /admin/v1/skeletons/{id}`          | `SkeletonValidator`    |
| Spec       | Conteúdo: sections, props, actions, targeting         | `POST /admin/v1/specs`                  | `SpecValidator`        |
| Envelope   | A resposta composta para um cliente                    | `GET /v1/surfaces/{home\|catalog}`      | Contrato v3 (`sdui-contract`) |

Nunca publique um envelope como spec: o envelope é **saída** (já filtrada por capabilities, com
analytics e eco do cliente); a spec é **entrada** administrativa. Os exemplos mantêm os três em
arquivos separados.

## 2. Antes de tudo: é SDUI?

- Home, hubs, vitrines e banners de campanha: sim (ADR-015).
- Autenticação, PIN, onboarding regulado, checkout, chat, mapas: **não**. Viram destino nativo
  alcançado por `navigate`.
- Nada de aparência no JSON: cor, fonte, margem, tamanho, tema e colunas são do renderer
  (`VisualGuard`). Nada de dado regulado (`PiiGuard`).
- Nada de primitiva genérica (`row`, `card`, `container`): cada type é um conceito de produto.

## 3. Nova tela numa surface existente (exemplo: `banking.shortcuts_first`)

1. **Escolha ou crie o skeleton.** Os slots vêm do vocabulário da surface (`Surfaces.HOME`); a
   ordem é livre, o primeiro slot é sempre `header`, os portantes (`header`, `accounts`) precisam
   estar lá marcados como `required`, e o layout de cada slot tem de estar entre os permitidos.
   Reaproveite um skeleton publicado quando a estrutura já existir (`banking.cards_first` usa o
   `home.cards_first` do seed).
2. **Escreva a spec.** Cada section cita um slot do skeleton, um type permitido no slot e na
   surface, props de conteúdo e actions (`navigate` com rota `app://`, `open_bottom_sheet`,
   `track`, `noop`). Toda referência a action nas props (`actionId`, `searchActionId`...) precisa
   existir na mesma section. O `checksum` segue o formato `sha256:<hex>`.
3. **Defina o targeting.** Plataforma, faixa de app e de SO, faixa de schema e as capabilities sem
   as quais o spec não faz sentido (`requiredCapabilities`). Um cliente fora do targeting não
   seleciona o spec.
4. **Crie os rascunhos.** `PUT` do skeleton e `POST` da spec com um ator `MAKER`. Erros voltam
   todos de uma vez (400 `VALIDATION`).
5. **Maker-checker.** O maker abre o pedido (`POST /admin/v1/publish-requests`, com
   `Idempotency-Key`). Um `CHECKER` **diferente** aprova (`.../approve`); fora do canal interno,
   quem abriu não aprova. A aprovação revalida a spec, publica, move o pointer por
   compare-and-set, audita e registra a invalidação de cache — tudo na mesma unidade de trabalho.
6. **Consulte.** `GET /v1/surfaces/home` com os headers do exemplo. Compare com o `response.json`.

A sequência completa em `curl` está no [README dos exemplos](examples/screens/README.md#opção-b--passo-a-passo-pela-api).

## 4. Montagem diferente da mesma tela (exemplo: `banking.cards_first`)

Duas montagens são duas specs da mesma surface, cada uma com seu skeleton. Vale a que o pointer
aponta. Não há header de template, experimento ou canal por montagem (ADR-017 continua adiado).
Para trocar: publique a outra spec. Para voltar: rollback. O app só precisa renderizar os slots
na ordem de `skeleton.slots` e saber cada layout token.

## 5. Novo componente (exemplos: `transaction_summary@1`, `product_collection@1`)

1. **Contrato primeiro.** Nome pelo conceito (`transaction_summary`, não `transaction_list`),
   versão, surfaces e slots, props obrigatórias e opcionais com limites, actions permitidas,
   comportamento de omissão. Registre em `docs/contratos/` e num ADR.
2. **Servidor.** Adicione o `type@typeVersion` a `ComponentContracts.APPROVED`, o type aos
   `types` da surface (e ao slot, em `Surfaces`), e as regras de props em
   `ComponentPropsValidator`. Escreva os testes negativos (campo ausente, limite, operação
   proibida) junto.
3. **Catálogo.** O componente entra no catálogo publicado por `PUT /admin/v1/catalog/components/{type}/{v}`
   com `status: ACTIVE`. Sem contrato aprovado, o validador recusa — inclusive inativo.
4. **Compatibilidade.** A matriz do servidor **não** concede o type a nenhuma faixa de app. Ele só
   chega a quem declara `Component-Capabilities: <type>@<v>`. Decida por slot:
   - slot opcional → sem a capability, a section é omitida (`unsupported_type`) e a tela segue;
   - slot portante → coloque a capability em `requiredCapabilities` do spec; cliente sem ela não
     seleciona o spec e cai no fallback da surface (nunca na árvore de outra surface).
5. **Homologação móvel.** Só depois de o renderer existir nos apps e declarar a capability o
   componente aparece para usuários. Quando os apps homologarem, uma faixa pode passar a recebê-lo
   pela matriz do servidor — decisão registrada, não automática.

## 6. Nova surface (exemplo: `catalog`)

Adicione um `SurfaceDefinition` em `Surfaces` (slots com layouts e portantes, types, primeiro slot,
evento de analytics, locale do conteúdo) e um mapeamento literal em `SurfaceController`. Não use
`/{surface}` genérico. Tudo o que é por surface — pointer, cache, singleflight, last good, métricas
— passa a separar a nova automaticamente.

## 7. Fallback, rollback e diagnóstico

- **Escada (ADR-007):** composição → árvore do cache → last good da **mesma** surface (até 24 h)
  → `503` com `Retry-After` com jitter.
- **Rollback:** `POST /admin/v1/pointers/{surface}/{platform}/{channel}:rollback` com ator
  `CHECKER`. Sem corpo, volta para a revisão anterior; com `targetSpecRevisionId`, para uma
  publicada específica da mesma surface e plataforma. Surface desconhecida: 404.
- **Depois da publicação ou rollback**, a árvore antiga não é mais servida (chave por revisão) e o
  last good ganha lápide na versão nova do pointer — o fallback não reintroduz o que saiu.

| Sintoma                                   | Onde olhar                                                              |
|-------------------------------------------|-------------------------------------------------------------------------|
| Section some com `unsupported_type`        | Cliente sem a capability; `section.omitted{type,reason}`                |
| `503 no_compatible_spec`                  | Targeting não atende o cliente; `select.no_candidate`                   |
| `503 required_slot_empty`                 | Slot portante esvaziado por hidratação ou por capability                |
| `200` com `fallback: true`                | Dependência falhou; `store.failure{stage}`, `compose.fallback`          |
| `409 CONFLICT` na aprovação ou rollback    | Outra escrita moveu o pointer ou o pedido; releia e decida              |
| `422 IDEMPOTENCY_KEY_REUSED`               | Mesma `Idempotency-Key` com outra operação ou outro alvo                |
| `503 ADMIN_UNAVAILABLE`                    | Registro de idempotência em memória no teto só com chaves vivas        |

## 8. Locale

O conteúdo das specs é de locale fixo (`pt-BR`, `SurfaceDefinition.contentLocale`); o envelope
ecoa o `Accept-Language` do cliente. Para localizar props, o locale precisa entrar na chave de
cache **antes** de existir conteúdo traduzido — senão um cliente recebe a árvore no idioma de quem
compôs primeiro.

## 9. Depreciar uma versão de componente

Siga o [guia de depreciação](guia-depreciacao-e-migracao.md): publique a versão nova ao lado da
antiga (`@2` com contrato próprio), faça os apps declararem a nova, migre as specs, e só depois
tire a antiga do catálogo ativo. Os sete types da Home não podem sair do catálogo ativo enquanto a
Home publicada depender deles (`CatalogValidator`).
