# H17 — Governança, publish e rollback Android

## Objetivo

Estender o fluxo maker-checker, diff, auditoria, invalidação e rollback para revisões Android, mantendo independência
completa em relação aos pointers iOS. O mesmo código administrativo atende as duas plataformas, mas toda decisão
operacional é escopada por plataforma e channel.

## Critérios de aceite testáveis

- Um maker consegue abrir pedido de publicação para spec Android e um checker diferente consegue aprovar ou rejeitar;
  maker não aprova o próprio pedido.
- O checker visualiza diff estrutural entre revisões Android N-1 e N antes da decisão.
- Approve Android publica a revisão, move somente o pointer Android do channel-alvo, executa write-through da spec e
  invalida seletivamente cache Android afetado.
- Approve Android não move, invalida, altera nem substitui pointer, spec, árvore de cache ou revisão iOS.
- O rollback Android exige checker e `Idempotency-Key`, move somente `home + android + channel` para a revisão
  anterior/autorizada e preserva specs imutáveis.
- Approve, reject e rollback gravam audit append-only com ator, decisão, plataforma, channel, spec/revisão e contexto de
  pointer.
- Repetição idempotente de approve ou rollback não duplica efeitos nem eventos de auditoria.
- Teste ponta a ponta comprova publish Android, compose Android apontando a nova revisão, rollback Android e compose
  Android retornando a revisão restaurada; em todas as etapas, o compose iOS mantém a revisão original.

## Fora de escopo

- Rollback acoplado obrigatório entre plataformas.
- Kafka de auditoria, salvo decisão posterior; o plano o define como opcional.
- Console visual de authoring.

## Dependências

- H08 — Admin: rascunho, validação e diff.
- H09 — Maker-checker, publish e auditoria.
- H10 — Rollback por pointer.
- H15 — Spec, pointer e targeting Android.
- H16 — Matriz de compatibilidade e compose Android.

## Ordem sugerida / estimativa

- Fase 3 Android; obrigatório antes de canary Android.
- Estimativa: 3 dias.

## Referências

- Plano: §§ 4.3, 7.2, 7.5, 8, 9 e 15 (Fase 3).
- Contrato JSON: `envelope.specRevisionId`, `envelope.platform`, `envelope.channel`,
  `envelope.analytics.specRevisionId`.
- Skill: `skills/sdui-backend/`, maker-checker, revisão imutável, pointer, auditoria e rollback.
