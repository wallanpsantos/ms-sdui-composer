# Runbook — persistência MongoDB e cache Redis (ADR-021)

> **Estado:** adapters implementados e cobertos por testes; **ensaio de homologação não
> realizado**. Enquanto o roteiro da seção 6 não for executado e registrado, o modo em memória
> continua o padrão e vale a restrição de instância única (AGENTS.md §17).

## 1. Ligar o modo persistente

| Variável                          | Exemplo                                                          |
|-----------------------------------|------------------------------------------------------------------|
| `SDUI_PERSISTENCE_STORE`          | `mongo`                                                          |
| `SDUI_PERSISTENCE_MONGO_URI`      | `mongodb://usuario:senha@host1,host2,host3/?replicaSet=rs0`      |
| `SDUI_PERSISTENCE_MONGO_DATABASE` | `sdui`                                                           |
| `SDUI_PERSISTENCE_CACHE`          | `redis`                                                          |
| `SDUI_PERSISTENCE_REDIS_URL`      | `rediss://:senha@redis:6379/0`                                   |

Credenciais só em configuração externa (secret do orquestrador). A subida **falha** se o modo
for inválido, se a URI faltar ou se o MongoDB não responder à criação de índices — nunca cai
para memória. Os prazos e o pool estão em `sdui.persistence.mongo.*` e `sdui.persistence.redis.*`
(`application.yaml`); não aumente pool nem prazo sem medição.

Local: `docker compose --profile infra up -d` sobe MongoDB como replica set `rs0` (um membro) e
Redis com teto de memória. Use `mongodb://localhost:27017/?replicaSet=rs0&directConnection=true`.

## 2. Saúde

`/actuator/health` ganha dois componentes:

| Componente  | `UP`                                     | `DOWN` e detalhe                                                                 |
|-------------|------------------------------------------|----------------------------------------------------------------------------------|
| `sduiStore` | MongoDB alcançável com transação         | `reachable: false` (rede, credencial, seleção de servidor) ou `transactions: unsupported: standalone` |
| `sduiCache` | Redis responde `PING`                    | `reachable: false` e o tipo do erro                                               |

Cache fora não derruba a composição (degrada para miss e perde o degrau de last good); banco de
governança fora derruba seleção e publicação (a escada de fallback serve last good do Redis até
24 h). Decida no probe de readiness qual dos dois tira a instância do balanceamento.

## 3. Métricas

| Métrica                                         | Leitura                                                           |
|-------------------------------------------------|-------------------------------------------------------------------|
| `mongodb_driver_commands_seconds{command,status}` | Latência e erro por comando (listener do driver)                |
| `mongodb_driver_pool_*` (`size`, `checkedout`, `waitqueuesize`) | Saturação do pool                                  |
| `cache_operation_ms_seconds{cache,operation,outcome}` | Latência e erro do Redis por cache (`tree`, `spec`, `last_good`) |
| `cache_write_skipped_total{cache}`              | Entrada acima de `max-entry-bytes` ou teto do cache em memória     |
| `cache_invalidation_applied_total` / `failed_total` | Invalidação pós-commit aplicada ou deixada no outbox          |
| `cache_invalidation_pending`                    | Invalidações que falharam na última drenagem do relay              |
| `store_failure_total{stage}`                    | Falha de dependência no pipeline (`select`, `spec_cache`, `tree_cache`, `compose`, `last_good`, `invalidation_outbox`) |
| `admin_error_total{error="conflict"}`           | Compare-and-set perdido ou conflito transacional                   |

Todas com tags finitas; nada de identificador de usuário, versão exata de app ou texto livre.

## 4. Falhas comuns

| Situação                                    | Efeito                                                           | Ação                                                                 |
|---------------------------------------------|------------------------------------------------------------------|----------------------------------------------------------------------|
| Redis indisponível                          | Misses; `store_failure{stage=tree_cache}`; sem last good          | Restabelecer o Redis. Nada a reparar: o MongoDB é a autoridade       |
| Redis limpo (restart/flush)                 | Misses até o cache se refazer                                    | Nenhuma; observar `compose_miss_total` voltar ao patamar             |
| MongoDB sem primário                        | Seleção falha → last good (≤ 24 h) ou 503; publicação falha       | Restabelecer o primário; conferir `sduiStore`                        |
| `cache_invalidation_pending > 0` persistente | Outbox não drena: Redis fora ou erro de script                   | Ver logs `entryPoint=invalidation_relay`; o relay tenta a cada `invalidation-relay-interval-ms` |
| 409 em aprovação/rollback                   | Outra escrita moveu o pointer ou transação concorrente           | Reler o pointer (`GET` de specs/auditoria) e decidir; reenviar com a mesma chave só se a intenção continuar válida |
| 400 "teto de armazenamento"                  | Documento acima de `max-document-bytes`                          | Reduzir a spec; não subir o teto sem revisão                         |

## 5. Backup, restauração, migração e retorno de versão

- **Backup:** `mongodump --uri=<uri> --db=sdui --oplog` a partir de um secundário, com retenção
  definida pela governança. O Redis não tem backup: é descartável.
- **Restauração:** `mongorestore --uri=<uri> --oplogReplay` num banco vazio; em seguida limpar o
  Redis (`FLUSHDB` do banco do serviço) para não servir árvore de estado que o banco não tem mais,
  e subir o serviço. O seed não sobrescreve o que foi restaurado.
- **Migração de formato:** documentos carregam `_v`. Mudanças são expand/contract: (1) binário novo
  lê `_v` antigo e novo e ainda grava o antigo; (2) depois de todo o parque atualizado, passa a
  gravar o novo; (3) migração em lote; (4) remoção da leitura antiga. O cache Redis versiona o
  envelope (`v`); versão desconhecida é miss.
- **Retorno de versão do binário:** só é seguro enquanto nenhum documento tiver sido gravado num
  formato que o binário anterior não lê. Se a etapa (2) já ocorreu, o retorno exige restauração.
- **Índices:** criados na subida por `MongoSchema.ensureIndexes`. Mudança de índice é migração
  como qualquer outra; valide com `explain("executionStats")` em base representativa antes.

## 6. Roteiro de homologação (P13) — pendente de execução

Registrar para cada passo: versão do commit, topologia, horário, resultado observado e métricas.

1. **Índices:** carregar base representativa (ex.: 10 mil revisões, 10% publicadas) e rodar
   `explain("executionStats")` das consultas: pointer por `_id`, spec por `specRevisionId`,
   publicadas por `(surface, platform, status)`, auditoria por `tsMillis`. Esperado: `IXSCAN`, sem
   `COLLSCAN`.
2. **Baseline:** carga HTTP (`gradlew :sdui-app:loadTest -PbaseUrl=...`) em memória e em modo
   persistente, mesma máquina e mesmo cenário; comparar P99 de hit, hit ratio, erros e pool.
3. **Restart:** publicar, reiniciar, conferir pointer, catálogo e auditoria preservados e seed sem
   sobrescrever (`DurableModeBootIT` cobre o caminho feliz).
4. **Perda de Redis:** derrubar o Redis durante carga; esperado 200 com misses, `sduiCache` DOWN,
   nenhum 5xx de composição. Restabelecer e ver o hit ratio voltar.
5. **Indisponibilidade do MongoDB:** derrubar o primário; esperado last good com `fallback: true`
   até a eleição; publicação recusada; nenhum estado parcial depois.
6. **Queda entre commit e invalidação:** publicar com o Redis fora; esperado
   `cache_invalidation_failed_total` e pendência no outbox; religar o Redis e ver a pendência zerar
   sem ação manual.
7. **Duas instâncias:** publicar em A e ler em B; rollback em B e ler em A; aprovações concorrentes
   do mesmo pedido em A e B (um 200, um 409); mesma `Idempotency-Key` em A e B (um efeito).
8. **Restauração:** restaurar backup num banco novo, apontar uma instância e conferir revisões e
   pointer.

Só depois deste roteiro registrado a restrição de instância única do AGENTS.md §17 pode ser
revista. Build verde, mocks ou containers saudáveis não substituem o ensaio.


## Atualização com as correções de integridade (ADR-022)

Consultar o [procedimento e as limitações do ADR-022](../memoria-operacional-e-arquitetural.md#consequências-e-atualização-operacional)
antes de trocar o binário: drenar as mutações administrativas, retirar escritores antigos e
reabrir pedidos OPEN sem hash para revisão. Documentos de idempotência novos têm `owner` e `_v: 2`;
Redis usa índice ZSET `sdui:treeidx:v2:*`, com teto por escopo. O índice SET antigo expira após o
último escritor antigo sair. Não executar flush global. Compilação e regressões deste ciclo
continuam pendentes de execução autorizada.
