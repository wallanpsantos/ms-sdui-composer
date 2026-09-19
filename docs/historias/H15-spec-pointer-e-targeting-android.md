# H15 — Spec, pointer e targeting Android

## Objetivo

Criar as specs monoplataforma Android e os pointers próprios da Home, permitindo que o mesmo runtime componha iOS e
Android sem cruzar versões, revisões ou canais. O servidor seleciona a spec a partir dos headers; o app apenas declara
sua identidade e capabilities.

## Critérios de aceite testáveis

- Existem specs Android irmãs das specs iOS, com `platformScope` e targeting Android; documentos iOS e Android não são
  reutilizados nem mutados entre si.
- Cada spec Android publicada referencia o skeleton e os sete types do catálogo compartilhado, mas pode conter os campos
  de conteúdo que o contrato Android realmente define.
- Existem pointers únicos e independentes para `home + android + stable`, `home + android + canary` e
  `home + android + internal` quando cada channel for habilitado.
- A matriz de capabilities do servidor é mantida por `(platform, Client-Version)` e contém a capacidade Android efetiva;
  `Component-Capabilities` é apenas delta.
- A seleção com `Client-Platform: android` considera somente candidatas Android `PUBLISHED`, schema compatível, faixa de
  versão Android, OS Android, build quando aplicável e capabilities efetivas.
- Testes cobrem versões Android mínima, intermediária e máxima; limites de faixa são inclusivos, teto nulo é rolling e
  semver não é comparado como string.
- A ordem de decisão é pointer por plataforma/channel, filtros de targeting e capabilities, `priority` decrescente e
  `publishedAt` decrescente em empate.
- Requests iguais para Android no mesmo instante retornam o mesmo `specRevisionId`; nenhum request Android seleciona
  revisão iOS.

## Fora de escopo

- Overlay base + JSON Patch no MVP.
- Canary, promoção stable, rollback e mudanças no renderer Android.
- Unificar semver, build ou faixa de lojas Android e iOS.

## Dependências

- H02 — Modelo Mongo e índices da Home.
- H04 — Select e Filter determinísticos.
- H14 — Contrato e fixture canônicos Android.

## Ordem sugerida / estimativa

- Fase 1/2 Android; após a fixture Android aprovada.
- Estimativa: 3 dias.

## Referências

- Plano: §§ 0, 1, 2, 4.1, 5.1, 5.2, 5.4, 7.2, 7.3, 7.4 e 15 (Fases 1 e 2).
- Contrato JSON: estrutura de `envelope.client`, `envelope.targeting`, `envelope.specRevisionId` e `envelope.channel`;
  valores iOS não definem valores Android.
- Skill: `skills/sdui-backend/`, seleção por plataforma, versão, capability e pointer.
