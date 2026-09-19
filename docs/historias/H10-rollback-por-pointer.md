# H10 — Rollback por pointer

## Objetivo

Disponibilizar rollback operacional seguro da Home movendo somente o pointer para a revisão anterior ou alvo autorizado.
Rollback não edita JSON publicado nem reescreve histórico.

## Critérios de aceite testáveis

- O endpoint administrativo de rollback recebe `surface`, `platform` e `channel` resolvidos no path e requer
  `Idempotency-Key`.
- Apenas checker autorizado executa rollback; a tentativa por maker não autorizado é recusada.
- O pointer guarda revisão atual, anterior e versão para controle de concorrência; o rollback move o pointer de maneira
  atômica e mantém specs imutáveis.
- Repetição da mesma solicitação idempotente não move o pointer duas vezes nem duplica auditoria.
- O rollback registra no audit log ator, motivo, pointer anterior e pointer resultante.
- Após rollback, cache de spec/árvore do escopo afetado é invalidado seletivamente e o compose passa a responder a
  revisão restaurada.
- Teste ponta a ponta prova publish para uma revisão, rollback para a anterior e retorno do `specRevisionId` restaurado
  no envelope.

## Fora de escopo

- Edição corretiva da revisão publicada, flush global Redis e rollback conjunto obrigatório de iOS/Android.

## Dependências

- H07, H09.

## Ordem sugerida / estimativa

- Fase 4; logo após publish e antes de canary.
- Estimativa: 2 dias.

## Referências

- Plano: §§ 4.3, 7.5, 8 e 9.
- Contrato JSON: `envelope.specRevisionId`, `envelope.channel`, `envelope.fallback`,
  `envelope.analytics.specRevisionId`.
- Skill: `skills/sdui-backend/`, pointer, rollback, idempotência e última árvore boa.
