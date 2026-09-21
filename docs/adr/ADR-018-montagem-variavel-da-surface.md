# ADR-018: Montagem Variável da Surface — Ordem e Layout dos Slots como Dado Versionado

**Status:** `PROPOSTO`

**Data:** 2026-09-21

**Relaciona-se com:** ADR-004 (Screen sem Fragment), ADR-009 (slot portante), ADR-010 (sem seletor de renderização),
ADR-017 (experimentação). **Originado por:** ADR-016.

## Contexto

O objetivo do serviço é atender **montagens diferentes de tela** para iOS e Android. Hoje o modelo já permite variar:

- **quais sections** ocupam cada slot (por spec);
- **o conteúdo** de cada section (por spec e hidratação);
- **o layout token** de cada slot, declarado no skeleton.

O que ele não permite variar é a **ordem dos slots**. A ordem da Home está fixada em `MvpCatalog.SLOT_ORDER`, e o
`SkeletonValidator` recusa skeleton cuja sequência seja outra. Existe um único skeleton, `home.default`.

A imagem de referência `docs/images/nubank-home-sections-comparison.jpg` mostra exatamente o caso que falta: a mesma
Home em duas montagens, com `cards` subindo para logo abaixo de `accounts` e `shortcuts` trocando de prateleira para
grade. A troca de layout já cabe no modelo; a mudança de ordem, não.

Há também um furo do lado do layout: o skeleton aceita qualquer token do enum `SlotLayout` em qualquer slot. Nada
impede, por exemplo, `accounts` em `pager`, que não faz sentido para o produto.

O serviço está em desenvolvimento, e esta é a hora de corrigir: o skeleton é persistido e referenciado pela spec
(`skeletonId` + `skeletonRevision`), mas ainda não há surface nem app em produção dependendo da ordem fixa.

Ponto importante: o **contrato de fio não precisa mudar**. O envelope já envia `skeleton.slots` em ordem, cada um com
seu `layout`. O que muda é quem decide essa ordem (dado versionado, em vez de constante) e uma regra explícita para os
apps.

## Decisão (proposta)

### 1. A ordem dos slots é dado do skeleton, não constante do código

- O skeleton continua sendo o documento versionado e imutável após publicação, referenciado pela spec por `skeletonId`
  e `skeletonRevision`.
- Uma surface pode ter **mais de um skeleton** (ex.: `home.default`, `home.cards_first`). Cada spec escolhe o seu.
- `MvpCatalog.SLOT_ORDER` deixa de ser a ordem obrigatória e passa a ser apenas o **vocabulário de slots** da surface:
  quais ids existem.

### 2. O skeleton ganha invariantes de surface, no lugar da ordem fixa

O `SkeletonValidator` passa a verificar, por surface:

| Invariante                                | Home                                                                       | Motivo                                             |
|-------------------------------------------|----------------------------------------------------------------------------|----------------------------------------------------|
| Slots pertencem ao vocabulário da surface | `header`, `shortcuts`, `accounts`, `cards`, `offers`, `coverage`, `foryou` | Não inventar slot por spec.                        |
| Ids únicos                                | —                                                                          | —                                                  |
| Slots portantes presentes                 | `header`, `accounts`                                                       | ADR-009 continua valendo.                          |
| Slot fixado no topo                       | `header` sempre primeiro                                                   | O topo da tela não é negociável por spec.          |
| Layout dentro do permitido para o slot    | ver seção 3                                                                | Fecha o furo do "qualquer token em qualquer slot". |
| `allowedTypes` dentro do catálogo         | —                                                                          | Já existe.                                         |

Qualquer outra ordem que respeite essas invariantes é válida.

### 3. Cada slot declara os layouts que aceita

A definição persistida do slot ganha `allowedLayouts`. O skeleton escolhe um deles; o fio continua enviando só o
escolhido.

| Slot        | `allowedLayouts` | Padrão  |
|-------------|------------------|---------|
| `header`    | `fixed`          | `fixed` |
| `shortcuts` | `shelf`, `grid`  | `shelf` |
| `accounts`  | `list`, `fixed`  | `list`  |
| `cards`     | `list`, `pager`  | `list`  |
| `offers`    | `list`, `pager`  | `list`  |
| `coverage`  | `list`, `shelf`  | `list`  |
| `foryou`    | `pager`, `list`  | `pager` |

Os valores acima são ponto de partida para revisão com os times mobile, não decisão fechada.

### 4. Regra de contrato para os apps

Passa a ser obrigação explícita dos renderers iOS e Android:

1. Renderizar os slots **na ordem em que chegam** em `skeleton.slots`. Nunca reordenar nem assumir posição fixa no
   código.
2. Renderizar cada slot com o `layout` recebido, dentro do enum fechado.
3. Número de colunas do `grid`, largura dos itens da `shelf` e snap do `pager` continuam sendo decisão do app
   (`WindowSizeClass` no Android, size classes no iOS).

A regra entra no contrato das histórias H14 (Android) e equivalente iOS, com teste de renderer usando duas ordens
diferentes.

### 5. O que continua fora

- **Form factor no servidor.** Não existe skeleton "para tablet". Dois skeletons diferem por produto (ordem, ênfase),
  nunca por tamanho de tela.
- **Slot criado por spec.** O vocabulário muda por ADR, não por publicação.
- **Aninhamento de slots.** A surface continua sendo uma lista ordenada de slots; sem slot dentro de slot.

### 6. Governança

- Um skeleton novo passa pelo mesmo maker-checker da spec, com diff que mostre a mudança de ordem e de layout.
- A verificação de slot portante do ADR-009 continua rodando por spec, contra o skeleton que ela referencia.

## Consequências

### Pontos positivos

- O serviço passa a atender montagens diferentes da mesma surface sem release, que é o objetivo declarado.
- Com o ADR-017, um braço de experimento pode testar montagem, não só conteúdo.
- Nenhum campo novo no fio. A mudança é de validação no servidor e de regra explícita nos apps.
- O furo de layout (`accounts` em `pager`) fecha.

### Pontos negativos / trade-offs aceitos

- A combinação skeleton × spec × plataforma aumenta o que o checker precisa revisar. O diff precisa deixar a ordem
  visível.
- Os dois apps precisam de teste de renderer com ordens diferentes. Um app que tenha ordem fixa no código quebra o
  modelo em silêncio.

### Riscos e mitigações

- **Explosão de skeletons.** Mitigação: skeleton novo só quando a ordem ou os layouts mudam; variação de conteúdo
  continua na spec. Métrica de skeletons ativos por surface.
- **App ignorando a ordem recebida.** Mitigação: fixture de contrato com uma segunda ordem, usada nos testes de
  renderer de iOS e Android.
- **Cache.** Sem impacto: a árvore é chaveada por `specRevisionId`, e a spec já fixa o skeleton que usa.

## Alternativas Consideradas

- **Manter a ordem fixa.** Descartada: impede o objetivo de montagens diferentes e contradiz a imagem de referência
  principal.
- **Ordem na spec (`slotOrder` como permutação), com skeleton único.** Viável, mas espalha a decisão de montagem por
  todas as specs e duplica a mesma ordem em cada uma. Skeleton versionado concentra a montagem num documento
  revisável.
- **`placement` + `order` por section (proposta `docs/05`).** Descartada: a ordem vira propriedade de cada section, o
  que impede validar a tela como um todo, e a proposta mistura ordem com form factor.
- **Skeleton por form factor.** Descartada (seção 5).

## Critérios de Validação

- `SkeletonValidator`: aceita `home` com `cards` antes de `shortcuts`; recusa skeleton sem `header` primeiro, sem slot
  portante, com slot fora do vocabulário, com id repetido ou com layout fora de `allowedLayouts`.
- `SpecValidator`: a verificação de slot portante roda contra o skeleton referenciado pela spec.
- Fixture de contrato adicional com a Home em segunda ordem, usada pelos testes de renderer iOS e Android.
- Teste de compose: duas specs com skeletons diferentes produzem `skeleton.slots` em ordens diferentes, com os mesmos
  types e o mesmo envelope.
