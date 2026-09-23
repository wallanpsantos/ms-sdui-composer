# Plano separado — persistência MongoDB e cache Redis

Status: **P01–P12 implementados em 2026-09-23; P13 (ensaio operacional) pendente.** Decisão em
[ADR-021](../docs/memoria-operacional-e-arquitetural.md#adr-021--persistência-mongodb-83-e-cache-redis) (`PROPOSTO`, não homologado). O modo padrão
continua em memória e a restrição de instância única do AGENTS.md §17 continua valendo até o roteiro de homologação de
[`persistencia-mongodb-redis.md`](../docs/runbooks/persistencia-mongodb-redis.md) ser executado e registrado. Testes com
infraestrutura real só rodam com `SDUI_IT_MONGO_URI`/`SDUI_IT_REDIS_URL`; na execução desta entrega não havia Docker
nem banco disponível, então eles **não foram executados** (ver tabela de evidências). O texto original do plano vem a
seguir, mantido como registro.

## Estado verificado

`SduiConfiguration` registra stores/cache in-memory. `SduiApplication` exclui autoconfigurações
Mongo/Redis. Os starters existem, mas não comprovam integração. `compose.yaml` oferece infra
opcional: Mongo standalone e Redis sem persistência configurada. Não há unidade de trabalho
Mongo implementada. A unidade em memória usa lock e não demonstra rollback transacional.

A documentação oficial confirma que deployments standalone não suportam transações
multi-documento: [considerações de produção do MongoDB](https://www.mongodb.com/docs/manual/core/transactions-production-consideration/).
Por isso o plano exige validar a topologia antes de implementar a unidade transacional.

## Resultado esperado

Specs, skeletons, catálogo, pointers, pedidos, diffs, auditoria e idempotência sobrevivem a
restart e são compartilhados entre instâncias. Redis acelera leituras, sem ser a fonte de
verdade das publicações. Não persistir árvores personalizadas nem dados regulados.

Decisões propostas, a formalizar em ADR próprio com próximo número livre na execução:

- Mongo como autoridade para governança e idempotência. Definir documentos, índices únicos,
  tamanho máximo, retenção, migração, backup e restauração antes de escrever adapters.
- Transação programática em adapters via porta TransactionalUnitOfWork. Escolher e comprovar
  topologia com suporte transacional; o compose standalone atual não é evidência suficiente.
  Se o destino for DocumentDB, validar capacidades e decidir alternativa em ADR antes de codificar.
- Redis para spec cache, árvore não personalizada, projeções seguras quando houver consumidor
  e last good. Definir TTL, limite de bytes/entradas e tratamento de indisponibilidade por porta.
- Invalidação depois do commit, com mecanismo recuperável para falha entre commit Mongo e
  invalidação Redis. Propor geração/versionamento ou outbox com consumidor idempotente; decidir
  um protocolo explícito, incluindo prevenção de refill antigo após publicação/rollback.
- Reserva de idempotência não pode ser expulsa por pressão de cache; chave vinculada à operação
  e parâmetros, resultado reproduzível e expiração de reserva em andamento tratada explicitamente.
- Timeouts reais de drivers, pools limitados, sem retry de dependência no servidor e sem APIs
  reativas. Verificar também retry automático dos drivers na configuração escolhida.
- Singleflight permanece local inicialmente; documentar que pode haver um líder por pod.
  Não prometer deduplicação distribuída sem necessidade medida e protocolo próprio.
- Rate limit permanece por processo se mantido o adapter atual. Documentar efeito do número
  de réplicas; limite global é decisão separada, não consequência automática de adotar Redis.

## Tarefas e aceite

Todas pendentes. Cada lote abaixo deve ficar em até cerca de cinco arquivos; dividir antes
da implementação se necessário. Testes são escritos com o código, sem execução intermediária.

| ID | Trabalho / arquivos prováveis | Dependência | Aceite e verificação planejada |
|---|---|---|---|
| P01 | ADR de persistência, modelo de documentos, matriz de portas e índices, índice ADR | Nenhuma | Definir autoridade de cada porta, atomicidade, TTL, limites, invalidação e topologia; revisão contra regras do projeto |
| P02 | Configuração Mongo, propriedades, application.yaml, compose local, teste de conectividade | P01 | Ambiente transacional explícito, timeouts/pools reais, segredo fora do repositório; health diferencia configuração e conexão |
| P03 | Adapters Mongo SpecStore/SkeletonStore, mapeamento de documentos, testes | P02 | Specs publicadas imutáveis, índices revisionId e surface/platform/status, consultas direcionadas; round-trip e conflitos |
| P04 | Adapters CatalogStore/DiffStore/PublishRequestStore, mapeamento, teste | P03 | Catálogo e pedidos persistem, CAS de status atômico, diff recuperável; teste concorrente de decisão |
| P05 | PointerStore, AuditLogStore, documentos/índices, teste | P04 | Versão do pointer protege updates concorrentes; auditoria correlacionada e paginada; teste de conflito |
| P06 | IdempotencyStore, documento/índices, ajustes de serviço e teste | P05 | Uma reserva por operação/chave, payload incompatível rejeitado, replay estável após restart; não perder reserva ativa por poda |
| P07 | Unidade transacional e integração Publish/Rollback, teste | P06 | Efeito, auditoria e idempotência commitam juntos; falha injetada não deixa estado parcial |
| P08 | Configuração Redis, codec de cache, propriedades, teste | P01, P02 | Timeout, limite de payload, versionamento de formato, TTL e ausência de conteúdo regulado; round-trip e expiração |
| P09 | SpecCache/HydratedScreenCache/LastGoodScreenStore e teste | P07, P08 | Chaves isoladas por surface/platform/channel/revisão/capabilities e locale se aplicável; fallback respeita idade e contexto |
| P10 | Protocolo pós-commit e prevenção de refill antigo, teste de recuperação | P09 | Publicação/rollback não ressuscitam revisão retirada; simular queda entre commit e invalidação e escritor atrasado |
| P11 | Wiring final, seed idempotente, health, teste de restart | P10 | Segundo boot não sobrescreve publicações; modo persistente sem substituição silenciosa por in-memory; falhas explícitas |
| P12 | Métricas de adapters/pools, testes de falha, runbook | P11 | Instrumentação finita, sem PII; observar latência, saturação, falhas, cache e invalidação pendente |
| P13 | Migração/backup/rollback operacional, ensaio multi-instância, índice docs | P12 | Publicar em A e ler em B, reiniciar, limpar Redis e recuperar autoridade Mongo; restauração e retorno de versão documentados |

### Evidências (2026-09-23)

| ID  | Situação | Evidência |
|-----|----------|-----------|
| P01 | Feito | ADR-021 (autoridade por porta, documentos, índices, TTL, tetos, invalidação, topologia); índice em `docs/adr/README.md` |
| P02 | Feito, sem ensaio | `SduiProperties.persistence`, `MongoStoreConfiguration` (CSOT, pool, `retryWrites/Reads=false`), `application.yaml`, `compose.yaml` com replica set `rs0`, health `sduiStore` que distingue inalcançável × standalone |
| P03 | Feito, IT não executado | `MongoSpecStore`, `MongoSkeletonStore` (PUBLISHED imutável, índice único de revisão); `DomainJsonRoundTripTest`; `MongoPersistenceIT` |
| P04 | Feito, IT não executado | `MongoCatalogStore`, `MongoDiffStore`, `MongoPublishRequestStore` (`findOneAndReplace` condicionado); teste concorrente em `MongoPersistenceIT` |
| P05 | Feito | `PointerStore.compareAndSet` (memória e Mongo), `MongoAuditLogStore.recent`; `InMemoryStoresBehaviorTest`, `MongoPersistenceIT` |
| P06 | Feito | Reserva por `insertOne`/`putIfAbsent` sob lock, fingerprint (422 em reuso), prazo de reserva, sem expulsão por pressão; `AdminIdempotencyTest`, medição M2, `MongoPersistenceIT` |
| P07 | Feito, IT não executado | `MongoTransactionalUnitOfWork` sem `withTransaction`; falha injetada em `MongoPersistenceIT` |
| P08 | Feito, IT não executado | `RedisCacheConfiguration` (prazo, `REJECT_COMMANDS`), `RedisCacheCodec` versionado, teto de bytes; `DomainJsonRoundTripTest`, `RedisCachesIT` |
| P09 | Feito, IT não executado | `RedisSpecCache`, `RedisHydratedScreenCache` (índice por escopo), `RedisLastGoodScreenStore` (Lua); `RedisCachesIT` |
| P10 | Feito | Outbox + lápide versionada + relay; `CacheInvalidatorTest`, `InMemoryStoresBehaviorTest`, `RedisCachesIT` |
| P11 | Feito, IT não executado | Wiring condicional sem fallback silencioso, seed e demo idempotentes, health; `DurableModeBootIT` (restart) |
| P12 | Feito | Métricas `cache.operation.ms`, `cache.write.skipped`, `cache.invalidation.*`, listeners Micrometer do driver; runbook |
| P13 | **Pendente** | Roteiro escrito (runbook §6); ensaio multi-instância, restauração e `explain` em base representativa não realizados. Entram junto os itens deferidos da revisão (`todo.md`, Verificação final): falha do Mongo no admin como 503/409 e custo da leitura de pointer por requisição |

ProjectionStore só será conectado se houver consumidor definido; não criar adapter morto como
preparação. Retenção de audit/diffs/revisões precisa preservar seleção e rollback, não copiar
a poda de cache para dados de governança. Listagens administrativas devem receber paginação.

## Checkpoints e cenários obrigatórios

- Após P01–P02: ADR/topologia/configuração revisados; fontes de testes presentes.
- Após P03–P07: casos de transação, CAS, idempotência e índices cobertos por fontes de testes.
- Após P08–P10: desenho Redis revisado com escrita concorrente, isolamento e falha pós-commit.
- Após P11–P13: roteiro de homologação cobre restart de aplicação, perda de Redis, indisponibilidade
  Mongo, publicação concorrente e consistência entre duas instâncias.

A validação de índices deve medir consultas reais com explain/plano de execução em base
representativa. Estabelecer baseline de hit/miss, latência, taxa de erro e consumo antes de
afirmar ganho. Não aumentar pool nem introduzir cache adicional sem evidência.

Gradle/suíte somente se o operador pedir, uma vez no final. Ensaios de infraestrutura/carga são
uma etapa operacional explícita em ambiente de homologação e não foram realizados neste plano.
Não marcar persistência concluída apenas com mocks, containers saudáveis ou build verde.

## Operação e limites

O plano não inclui publicação em produção. O admin atual aceita identidade/papel por headers;
exposição fora do ambiente local exige fronteira de autenticação/autorização confiável definida
antes do rollout. A entrega de persistência deve documentar essa dependência existente.
Credenciais ficam em configuração externa. Não assumir que Redis é durável ou que a retomada
de um binário antigo é segura após mudanças de documentos: usar migração compatível e ensaio
de restauração. Atualizar AGENTS.md §17 apenas depois de comprovar a integração real.
