# H03 — Catálogo, skeleton e seed iOS

## Objetivo

Persistir e validar o catálogo semântico da Home, o skeleton ordenado de slots e a primeira spec iOS baseada na fixture.
Esta história fecha a estrutura que o runtime pode selecionar, sem levar decisões visuais ao JSON.

## Critérios de aceite testáveis

- O catálogo ativo contém exatamente os sete types MVP em `@1`: `top_bar`, `shortcut_shelf`, `account_card`,
  `card_product`, `credit_offer`, `coverage_card` e `decision_card`.
- O skeleton `home.default` contém, nesta ordem, os slots `header`, `shortcuts`, `accounts`, `cards`, `offers`,
  `coverage` e `foryou`, com layouts e títulos do contrato.
- Cada slot declara `allowedTypes` e máximo de instâncias coerente com o plano; placement com slot inexistente, type não
  permitido ou excedente é inválido.
- O seed iOS associa as oito sections da fixture aos slots corretos e preserva `type`, `typeVersion`, props, actions e
  analytics do contrato.
- Apenas os layouts semânticos do slot são aceitos; campos de CSS/pixel e tokens de aparência são recusados na
  validação.
- Cada `actionId` referenciado por item aponta para action existente na própria section; actions obedecem ao conjunto
  fechado e `navigate` usa rota `app://`.
- Cada slot do skeleton persistido declara `required: true|false`, ao lado de `allowedTypes` e do máximo de instâncias.
  `required` é campo de validação e **não** é serializado no payload de fio: o cliente não precisa dele e o orçamento de
  `payload.bytes` não paga por ele.
- Nenhum type genérico entra no catálogo. Nomes que descrevem layout ou primitiva — `row`, `column`, `container`,
  `generic_card`, `list_item` — são recusados na validação do catálogo; type nomeia conceito de produto (`account_card`,
  `credit_offer`), como o catálogo MVP já faz.
- Regra de três para generalização: um type só pode ser substituído por uma forma mais genérica depois que a mesma forma
  tiver sido publicada em `stable` em **três** sections distintas. Antes disso, duplicar é mais barato que abstrair.
  Esta é a lição direta do HubFramework do Spotify: generalizar cedo produz um catálogo em que ninguém consegue rastrear
  por que uma tela renderizou daquele jeito.

## Fora de escopo

- Materialização de overlays, types `@2`, renderer nativo e conteúdo vindo de domínio.

## Dependências

- H00, H02.

## Ordem sugerida / estimativa

- Fase 1; após a base Mongo.
- Estimativa: 3 dias.

## Referências

- Plano: §§ 2, 6.1, 6.2, 6.3 e 6.4.
- Contrato JSON: `skeleton.slots`, `sections[].slot`, `sections[].type`, `sections[].typeVersion`, `sections[].props`,
  `sections[].actions`.
- Dicionário: `documentacao-contrato-sdui-home-v3.docx`, definição de skeleton, section, props, actions e analytics.
- Skill: `skills/sdui-backend/`, catálogo semântico e validação de spec.
