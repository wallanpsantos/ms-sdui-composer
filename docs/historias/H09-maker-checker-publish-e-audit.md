# H09 — Maker-checker, publish e auditoria

## Objetivo

Implementar a governança de publicação: maker abre pedido, checker independente aprova ou rejeita, e somente a aprovação
move o pointer. A trilha é append-only e publish é transacional no Mongo replica set.

## Critérios de aceite testáveis

- Maker cria `publish_request`; checker aprova ou rejeita; o maker é impedido de aprovar o próprio pedido.
- A aprovação exige spec e skeleton válidos, diff disponível e revisão ainda publicável; ao aprovar, a revisão torna-se
  publicada e o pointer do channel/plataforma é movido.
- A operação grava ator, instante, revisão, decisão e contexto no `audit_log` append-only.
- Publish usa transação multi-documento apenas na camada de serviço para pointer, pedido, auditoria e idempotência;
  controller não abre transação e compose não usa transação.
- Repetição de approve com a mesma `Idempotency-Key` não cria efeitos duplicados.
- Após `PUBLISHED`, spec, catálogo/revisão aplicável e skeleton não podem ser reescritos pelo fluxo admin.
- A aprovação executa write-through da spec no Redis e invalida as árvores afetadas de forma seletiva.

## Fora de escopo

- Kafka para auditoria assíncrona, caso a organização o adote; o plano o trata como opcional.
- Rollback manual e rollout canary.

## Dependências

- H02, H07, H08.

## Ordem sugerida / estimativa

- Fase 4; após validação administrativa e cache.
- Estimativa: 4 dias.

## Referências

- Plano: §§ 0, 4.3, 7.2, 7.5, 8 e 9.
- Contrato JSON: `envelope.specRevisionId`, `envelope.channel`, `envelope.analytics.specRevisionId`.
- Dicionário: `documentacao-contrato-sdui-home-v3.docx`, campos de rastreabilidade expostos no contrato.
- Skill: `skills/sdui-backend/`, publish imutável, maker-checker, auditoria e invalidação.
