# ADR-021 — Persistência da governança no MongoDB e caches no Redis

- **Status:** `PROPOSTO` — adapters implementados e cobertos por testes em 2026-09-23; **não
  homologado**. O ensaio operacional (restart, perda de Redis, indisponibilidade do MongoDB,
  publicação concorrente e duas instâncias) e a medição com `explain` em base representativa
  ainda não foram feitos. Até lá o modo padrão continua em memória (AGENTS.md §17).
- **Data:** 2026-09-23
- **Relacionados:** ADR-006, ADR-007, ADR-013, ADR-014, ADR-020.
- **Planejamento:** [`tasks/plano-persistencia-mongo-redis.md`](../../tasks/plano-persistencia-mongo-redis.md) (P01–P13).

## Contexto

Todos os stores eram em memória: o estado não sobrevivia a restart, não era compartilhado entre
pods e a unidade de trabalho só dava exclusão mútua, sem rollback. Os starters de MongoDB e Redis
estavam no classpath, mas as autoconfigurações eram excluídas e não havia adapter.

## Decisão

### Modos e autoridade

| Propriedade                | Valores            | Padrão   | Efeito                                                                  |
|----------------------------|--------------------|----------|-------------------------------------------------------------------------|
| `sdui.persistence.store`   | `memory` \| `mongo` | `memory` | Specs, skeletons, catálogo, pointers, pedidos, diffs, auditoria, idempotência, outbox e unidade de trabalho |
| `sdui.persistence.cache`   | `memory` \| `redis` | `memory` | Cache de spec, cache de árvore e last good                               |

Valor fora da lista falha a subida (binding de enum). URI ausente com modo persistente falha a
subida. Nenhum modo persistente cai para memória em silêncio. `ProjectionStore`, singleflight e
limitador de taxa continuam locais ao processo.

| Porta                        | Autoridade      | Observação                                                                 |
|------------------------------|-----------------|----------------------------------------------------------------------------|
| SpecStore, SkeletonStore      | MongoDB          | PUBLISHED imutável: gravação condicionada a `status != PUBLISHED`           |
| CatalogStore                  | MongoDB          | Documento único; validação do conjunto antes da gravação; última gravação vence |
| PointerStore                  | MongoDB          | `compareAndSet` pela versão gravada no documento; conflito vira 409        |
| PublishRequestStore           | MongoDB          | Transição por `findOneAndReplace` condicionado ao status                    |
| DiffStore, AuditLogStore      | MongoDB          | Auditoria só com inserção; leitura paginada pelos mais recentes            |
| IdempotencyStore              | MongoDB          | Reserva por `insertOne` no `_id`; TTL só remove reserva abandonada ou resultado fora da janela |
| CacheInvalidationOutbox       | MongoDB          | Gravado na mesma transação que move o pointer                               |
| SpecCache, HydratedScreenCache, LastGoodScreenStore | Redis | Nunca fonte de verdade; perder o Redis custa latência e o degrau de last good |

### Documentos e índices

Cada documento guarda o objeto de domínio serializado em `json` (formato de `DomainJson`,
independente do mapper HTTP) e, ao lado, só os campos que alguma consulta filtra ou ordena, com
`_v` = versão do formato. Chaves livres das props nunca viram nome de campo no banco. Índices,
criados de forma idempotente na subida (`MongoSchema.ensureIndexes`):

- `specs`: único em `specRevisionId`; `(surface, platform, status)`; `(specId, revision)`;
- `skeletons`: `(skeletonId, revision desc)`;
- `audit_events`: `tsMillis desc`;
- `idempotency`: TTL em `expiresAt`;
- `cache_invalidations`: `createdAtMillis`.

Teto de documento serializado: `sdui.persistence.mongo.max-document-bytes` (1 MiB por padrão;
o limite do MongoDB é 16 MiB). Acima dele a gravação é recusada com 400.

### Transação e topologia

`MongoTransactionalUnitOfWork` abre sessão com `readConcern: snapshot` e `writeConcern: majority`
e vincula a sessão à thread; os adapters a usam enquanto ela existir. Efeito, auditoria, fecho da
chave de idempotência e registro no outbox commitam juntos. **Exige replica set ou cluster
shardeado**: standalone não tem transação multi-documento. O health indicator `sduiStore`
responde `DOWN` com `transactions: unsupported: standalone`. DocumentDB e outras implementações
compatíveis ficam fora até validação própria.

Não se usa `ClientSession.withTransaction` (repete a transação por até 2 minutos). Erro
transitório de transação vira `StoreConflict` (409); o operador reenvia com a mesma
`Idempotency-Key`. Se o commit acontecer e a resposta se perder, o registro de idempotência já
está fechado no banco e o reenvio recebe o replay.

### Prazos, pool e retry

- MongoDB: prazo por operação do driver (CSOT, `operation-timeout-ms`), `server-selection-timeout-ms`,
  `connect-timeout-ms`, pool com `max-pool-size` e `max-wait-time-ms`; `retryWrites=false` e
  `retryReads=false`.
- Redis (Lettuce): `command-timeout-ms` curto, `connect-timeout-ms`, comandos recusados na hora com
  a conexão caída (`REJECT_COMMANDS`) em vez de enfileirados.
- Nenhum retry de dependência no caminho da requisição (ADR-014).

### Redis: formato, tetos e chaves

Valores em envelope JSON versionado (`RedisCacheCodec`, `v = 1`); versão diferente é miss.
Entrada acima de `max-entry-bytes` não é gravada (`cache.write.skipped`). Chaves de `RedisKeys`,
nunca com identificador de usuário. TTL: árvore = `tree-ttl-seconds`; spec = `spec-ttl-seconds`;
last good = `last-good-ttl-seconds`. O índice de árvores por surface/plataforma/canal
(`sdui:treeidx:*`) evita `SCAN` na invalidação.

### Invalidação pós-commit e escrita atrasada

- Cache de árvore e de spec são chaveados por revisão: uma entrada antiga nunca é lida por quem
  selecionou outra revisão, e a revisão publicada é imutável. Invalidá-los é higiene.
- O last good é chaveado por surface/plataforma/canal e carrega a **versão do pointer** sob a qual
  a árvore foi composta. A invalidação grava uma lápide na versão nova; a gravação de versão menor
  é recusada atomicamente (script Lua no Redis, `compute` em memória). Assim uma composição que
  começou antes da publicação e terminou depois não ressuscita a revisão retirada.
- A invalidação é registrada no outbox dentro da transação e aplicada logo depois do commit. Se o
  processo cair no meio ou o Redis estiver fora, o registro fica pendente e o relay
  (`sdui.persistence.invalidation-relay-interval-ms`) o aplica em qualquer instância. Reaplicar é
  seguro: a lápide só avança de versão.

### Retenção

Governança não é podada automaticamente: revisões, diffs, pedidos e auditoria sustentam seleção,
rollback e auditoria. Retenção é decisão de governança a formalizar; listagens administrativas são
paginadas (`offset`, `limit` ≤ 500).

### Seed e restart

O seed canônico e a carga demo são idempotentes: cada item só é gravado se não existir, e uma
gravação que perde a corrida para outro pod é aceita quando o item passou a existir. O segundo boot
não devolve pointer nem catálogo ao estado do seed.

### Segurança operacional

URIs com credencial vêm de configuração externa (`SDUI_PERSISTENCE_MONGO_URI`,
`SDUI_PERSISTENCE_REDIS_URL`). O plano administrativo continua identificando o ator por header,
sem autenticação: exposição fora de rede controlada exige fronteira de autenticação antes do
rollout, independentemente da persistência.

## Alternativas descartadas

- **Spring Data repositories com mapeamento anotado:** exigiria anotar o domínio puro ou duplicar
  modelos; o documento com `json` + campos de consulta mantém o domínio intacto.
- **`@Transactional`:** proibido no projeto (ADR-013); a unidade de trabalho é programática.
- **Invalidar só depois do commit, sem outbox:** perde a invalidação se o processo cair no meio.
- **Singleflight e limitador distribuídos no Redis:** sem necessidade medida; com N réplicas pode
  haver um líder por pod e o limite efetivo é N vezes o configurado por processo.

## Consequências

- O serviço pode rodar com mais de uma instância **depois** do ensaio de homologação; até lá, o
  modo em memória continua o padrão e a restrição de instância única do AGENTS.md §17 vale.
- Consultas de seleção no modo `mongo`: leitura do pointer por `_id` e da revisão apontada por
  índice único (com cache de spec na frente); a listagem de publicadas só acontece quando a
  revisão apontada não serve ao cliente.

## Verificação

Sem infraestrutura: `DomainJsonRoundTripTest`, `InMemoryStoresBehaviorTest`,
`CacheInvalidatorTest`, `AdminIdempotencyTest`. Com MongoDB (replica set) e Redis reais, habilitados
por `SDUI_IT_MONGO_URI` / `SDUI_IT_REDIS_URL`: `MongoPersistenceIT`, `RedisCachesIT`,
`DurableModeBootIT`. Roteiro de homologação em
[`docs/runbooks/persistencia-mongodb-redis.md`](../runbooks/persistencia-mongodb-redis.md).
