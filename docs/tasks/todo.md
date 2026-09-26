# Backlog e Tarefas Operacionais Pendentes

> [!NOTE]
> Todas as tarefas e histórias de desenvolvimento anteriores (**H00 a H18** e **T01 a T11 / R01 a R12**) foram
concluídas, validadas e **centralizadas no documento canônico de arquitetura**:
> - Consulte [`docs/arquitetura-de-referencia.md`](../arquitetura-de-referencia.md) (§11 e §12) para a especificação
    canônica, catálogo de ADRs (`ACEITO`) e matriz consolidada de entregas.
> - As quatro composições de telas desenvolvidas encontram-se documentadas em [
    `docs/examples/screens/`](../examples/screens/README.md).
> - Os contratos de novos componentes estão em [`docs/contratos/`](../contratos/).

---

## 📌 Tarefas Operacionais e Homologações Pendentes

As tarefas ativas e itens em aberto restantes no projeto estão agrupados a seguir:

### 1. Validação de Build e Automação

- [ ] **Reexecução da suíte Gradle completa:** Executar `gradlew clean build --warning-mode=fail` uma única vez ao
  final, quando expressamente autorizado pelo operador humano, para validação do pipeline com as últimas otimizações
  (teto de cache, segredo Redis, MDC).

### 2. Infraestrutura e Persistência Operacional (ADR-021)

- [ ] **Ensaio Operacional P13:** Executar o ensaio de resiliência e failover com infraestrutura real
  (`docker compose up -d` com replica set `rs0` e Redis) conforme especificado em [
  `docs/arquitetura-de-referencia.md`](../arquitetura-de-referencia.md#82-modo-persistente-opt-in-mongodb-83-e-cache-redis-adr-021)
  e ADR-021:
  - Restart de pods sob carga.
  - Comportamento de degradação sob perda temporária do Redis.
  - Indisponibilidade e recuperação de nó primário do MongoDB.
  - Consistência de ponteiro e cache entre múltiplas instâncias concorrentes.
  - Execução dos testes de integração reais: `MongoPersistenceIT`, `RedisCachesIT` e `DurableModeBootIT`.

### 3. Performance e Medições Finais

- [ ] **Baseline HTTP em Ambiente Dedicado:** Medição formal de latência HTTP e throughput do cenário `compose-hit-p99`
  em hardware dedicado (gerador de carga e servidor em máquinas isoladas, superando medições de notebook de
  desenvolvimento).

### 4. Homologação com Clientes Móveis (ADR-020)

- [ ] **Homologação dos Contratos Móveis:** Validação formal dos novos componentes de comércio e transações
  (`product_collection@1`, `catalog_navigation@1`, `transaction_summary@1`) com as equipes nativas de iOS e Android.
- [ ] **Fixture Android Canônica:** Recebimento e incorporação da fixture definitiva acordada com a equipe móvel Android
  (substituindo proposta atual).

### 5. Backlog Futuro — Tokens Semânticos e Linguagem de UI (ADR-023 a ADR-026)

- [ ] **Execução das Fases 0 a 7:** Conforme detalhado em [
  `docs/tasks/plano-tokens-semanticos.md`](plano-tokens-semanticos.md) (temas por segmento, componente primitivo
  `block@1`, campanhas dinâmicas e autenticação JWT de operadores administrativos).

### 6. Achados da Revisão de Instruções (2026-09-25)

Levantados na execução de [`plano-evolucao-agents.md`](plano-evolucao-agents.md); ficaram fora daquele escopo, que
só tratou instruções. Caminhos abreviados: `CORE` = `sdui-core/src/main/kotlin/br/com/empresa/sdui/core`, `APP` =
`sdui-app/src/main/kotlin/br/com/empresa/sdui`.

- [ ] **Chaves visuais camelCase invisíveis nos testes de contrato:** `NoVisualAttributesTest.kt:41` e
  `RejectedProposalContractTest.kt:37` comparam `key.lowercase()` com `FORBIDDEN_VISUAL_KEYS`, que tem `cornerRadius`,
  `itemWidth`, `itemHeight`, `formFactor` e `componentType` em camelCase; essas cinco nunca casam. Tratar no T01 de
  `plano-tokens-semanticos.md`, que mexe nos mesmos arquivos.
- [ ] **Checksum de spec sem comprimento:** `CORE/validate/SpecValidator.kt:53` aceita `^sha256:[0-9a-f]+$`, mas o KDoc
  (L51) promete 64 dígitos.
- [ ] **Chave Redis fora de `RedisKeys`:** `APP/adapters/redis/RedisCaches.kt:281-282` monta `sdui:treeidx:v2:...` sem
  passar por `RedisKeys` nem por `containsUserId` (§18.6).
- [ ] **Catch sem métrica:** `RedisCaches.kt:552-553` (`decodeOrNull` transforma falha de decodificação em miss mudo) e
  `APP/orchestrator/admin/AdminServices.kt:908-910` (`SpecCache.warm`) (§20.3).
- [ ] **Alcance do teto estrito (§23.11):** `CORE/limit/TokenBucket.kt:118, 123` e `InMemoryStores.kt:475` decidem o
  teto por `size()`; o KDoc diz que a aproximação é intencional. Decidir se a regra vale só para caches.
- [ ] **Append de auditoria sem medição válida:** `InMemoryGovernance.kt:752-753` copia a lista a cada append sob o
  lock global; a medição de ~250 ns perdeu o registro (`584366a`). Medir com
  `:sdui-app:perfHarness -Pscenarios=audit` (§23.14).
- [ ] **Possível PII em log do admin:** `AdminController.kt:467-473, 535-543` registra `currentActor.id` e o `reason`
  livre; o KDoc de `Actor.id` cita e-mail (`CORE/model/ClientContext.kt:18`). Confirmar o formato do `Actor-Id` (§8,
  §22.4).
- [ ] **Referências ao documento de medição removido:** `sdui-app/build.gradle.kts:33`, `PerfHarness.kt:68, 72` e
  `HttpLoadGenerator.kt:27` citam `docs/performance/medicoes-2026-09-23.md`.
- [ ] **KDoc divergente do código:** `ComposeModels.kt:124` (`withJitter`; o nome é `jittered`);
  `AdminController.kt:124, 494, 558` e `ApiExceptionHandler.kt:254` (prefixo `sdui.` que as métricas não têm);
  `SduiConfiguration.kt:447` (`/v1/surfaces/{surface}`); `InMemoryStores.kt:38` (`RedisKeys.treeKey`; o nome é `tree`).
- [ ] **Seção 12 da arquitetura cita testes inexistentes:** `docs/arquitetura-de-referencia.md:651-669` (ex.:
  `NegotiateTest`, `SelectTest`, `HomeControllerWebTest`).
- [ ] **Links quebrados na documentação:** `README.md:719` (`docs/adr/README.md`), `README.md:725-727`
  (`docs/runbooks/`) e `plano-tokens-semanticos.md:331-332` (`docs/adr/...`; os ADRs vão para a Seção 11).
- [ ] **CI sem `--warning-mode=fail`:** `.github/workflows/gradle.yml:48-52` e `release.yml:41`, ao contrário do
  comando local documentado.
- [ ] **Perfil `infra` comentado:** o KDoc de `MongoPersistenceIT.kt:53-59` manda
  `docker compose --profile infra up -d`,
  mas `compose.yaml:56, 74` tem o perfil comentado (afeta o ensaio P13).
- [ ] **Aliases do catálogo sem uso:** `gradle/libs.versions.toml:31, 34-36` (`spring-boot-starter-restclient` e três de
  Testcontainers).
- [ ] **Valor inválido de `sdui.persistence.*` sem teste:** a recusa depende do binding do enum
  (`SduiProperties.kt:118-165`); falta o teste de subida com valor inválido (§23.12).
