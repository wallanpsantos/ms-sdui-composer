# ADR-022 — Integridade da governança e limites de entrada

- **Status:** `PROPOSTO` — implementação e fontes de regressão escritas em 2026-09-23;
  compilação, testes e homologação ainda não executados neste ciclo.
- **Data:** 2026-09-23.
- **Relacionados:** ADR-007, ADR-008, ADR-013, ADR-014, ADR-021.
- **Origem:** [revisão estática R01–R12](../analise-bugs-seguranca-2026-09-23.md).

## Contexto

A aprovação guardava a identidade de um rascunho editável, sem vincular o conteúdo revisado.
Leitura seguida de gravação permitia sobrescrita concorrente. A unidade de trabalho em memória
não desfazia alterações em falha, e reservas de idempotência vencidas podiam afetar um novo dono.
Na borda, JSON administrativo e o índice de árvores Redis tinham caminhos de crescimento sem teto.

## Decisão

### Conteúdo revisado e transações

`PublishRequest.reviewedContentHash` guarda SHA-256 da serialização determinística de spec e
skeleton exato, com mapas ordenados. Só o status do skeleton é normalizado: sua publicação por
outro pedido não muda o conteúdo. A aprovação relê e valida dentro da transação, exige spec DRAFT
e hash idêntico, e troca spec e skeleton por compare-and-set do conteúdo observado. Alteração
posterior à abertura ou pedido antigo sem hash resulta em 409 e exige nova abertura e revisão.

Rascunhos também usam compare-and-set: criação exige ausência, edição exige o estado lido. Uma
colisão na alocação de revisão resulta em 409, sem retry ou sobrescrita. `specRevisionId` é único
entre todas as identidades nos dois adapters. Revisões de skeleton precisam existir exatamente;
`current` não substitui referência inexistente. IDs de revisão usam caracteres seguros, até 128
caracteres, sem `userId`; dados antigos inválidos acionam degradação controlada no compose.

Em memória, um coordenador compartilhado mantém snapshots de todos os stores administrativos.
Somente escritores administrativos tomam o lock; as leituras do hot path continuam sem lock.
O commit publica um snapshot e a falha descarta o estado privado da transação. Isso inclui índices,
pedidos, pointer, auditoria, idempotência e outbox. Cache continua fora da transação. Outbox cheia
recusa a operação em vez de expulsar invalidações ainda pendentes. O custo aceito é copiar os
mapas/listas alterados em escritas administrativas; esta mudança não declara ganho de performance.
Nada disso dá durabilidade ou coordenação entre pods ao modo em memória.

### Dono da reserva de idempotência

`reserve` devolve um token aleatório. `complete` exige token, operação e fingerprint iguais, estado
em voo e prazo vigente; participa do mesmo commit do efeito. `release` só remove a reserva em voo
com aquele token. Um trabalhador atrasado não fecha nem apaga a reserva do sucessor. Mongo grava
`owner` e `_v: 2` no documento de idempotência e condiciona a substituição/remoção atomicamente.
Nenhum retry de dependência foi acrescentado.

### Limites antes da desserialização

`AdminRequestLimitFilter` verifica os headers de ator antes do corpo, limita sua leitura a
`sdui.admin-max-body-bytes` (1 MiB) e mantém um semáforo até o fim do processamento administrativo
(`sdui.admin-max-concurrent-requests`, padrão 8). Corpo excedente retorna 413, inclusive chunked;
saturação retorna 503 com `Retry-After` variável. Os headers continuam sendo declarações sem
autenticação: a barreira de rede do plano administrativo segue necessária.

O mapper HTTP também limita o documento a 50 mil tokens e profundidade 64 antes de materializar
props. O limite de domínio das props continua 32. JSON acima dos limites do parser é recusado
pelo binding HTTP. A API de limites é a de
[StreamReadConstraints do Jackson](https://github.com/FasterXML/jackson-core/blob/3.x/src/main/java/tools/jackson/core/StreamReadConstraints.java).

### Schema, fallback e Redis

Negociação só aceita o schema canônico da allowlist (`3`); overflow, outros schemas e aliases
como `03` retornam 400. O contexto não substitui parsing inválido por schema 3. Last good só serve
schema, surface, plataforma e canal compatíveis. A validação de targeting usa limites e transições
reais da matriz de capabilities, sem fabricar `minor + 50`.

O índice Redis passa a `sdui:treeidx:v2:<surface>:<platform>:<channel>`, um ZSET com vencimento de
cada árvore como score. Lua poda membros vencidos, verifica teto, grava árvore e índice
atomicamente. O teto é `sdui.tree-cache-max-entries` **por escopo** no Redis (global no cache em
memória). No teto, descarta a nova escrita e emite `cache.write.skipped`; renovação de uma entrada
existente continua permitida. A invalidação acontece em Lua e não transfere todos os membros à JVM.
Scripts podem percorrer até o teto configurado; Redis continua sendo standalone, conforme ADR-021.

## Alternativas descartadas

- Conferir somente status/identidade permite publicar conteúdo diferente do revisado.
- Só reordenar escritas reduz um cenário de estado parcial, mas não oferece rollback em falhas posteriores.
- Remover idempotência apenas pela chave deixa o dono antigo remover o sucessor.
- TTL do SET inteiro não remove membros históricos enquanto chegam novas gravações.
- Limitar JSON somente depois do binding não limita as alocações anteriores à validação.

## Consequências e atualização operacional

1. Antes de atualizar, impedir novas mutações administrativas, drenar operações em andamento e
   retirar todos os escritores antigos. Não misturar escritores antigos e novos: os antigos não
   conferem o token nem o hash. Downgrade restaura essas falhas e não é compatível com as garantias.
2. Pedidos OPEN sem `reviewedContentHash` precisam ser reabertos com nova chave de idempotência e
   revisados novamente. Pedidos concluídos continuam legíveis. Reservas antigas em voo ficam
   indisponíveis até expirar; resultados concluídos preservam a janela de replay.
3. O índice Redis antigo, do tipo SET, deixa de receber escritas e expira pelo TTL já existente
   após retirar os escritores antigos. As árvores antigas também expiram; não há flush de dados.
4. Revisões publicadas com referência de skeleton inexistente ou ID inválido precisam de uma
   revisão válida/pointer corrigido pela governança. O fallback não deve corrigir silenciosamente
   a identidade persistida.
5. Novos limites HTTP podem recusar documentos administrativos que antes eram aceitos. As novas
   configurações e os códigos 400/413/503 precisam ser considerados pelo cliente administrativo.

## Verificação

Fontes escritas: `AdminRequestLimitFilterTest`, `HttpJsonLimitsTest`, `SemVerAndNegotiateTest`,
`SpecValidatorTest`, `AdminIdempotencyTest`, `ComposeResilienceTest`,
`DefaultFallbackCoordinatorTest`, `InMemoryStoresBehaviorTest`, `MongoPersistenceIT` e
`RedisCachesIT`. Incluem limite chunked, saturação/liberação de permissão, conteúdo alterado,
CAS perdido, duplicidade de ID, rollback em memória e trabalhador com reserva vencida.

Neste ciclo, somente revisão estática de código, assinaturas/chamadores e diff. **Nenhuma
compilação ou suíte foi executada**, conforme AGENTS.md. Mongo/Redis exigem infraestrutura real
e ensaio do [runbook](../runbooks/persistencia-mongodb-redis.md). Não há PASS novo.
