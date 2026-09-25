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
  (`docker compose up -d` com replica set `rs0` e Redis) conforme roteiro em [
  `docs/runbooks/persistencia-mongodb-redis.md`](../runbooks/persistencia-mongodb-redis.md):
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
