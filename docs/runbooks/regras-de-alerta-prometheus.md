# Regras de Alerta Prometheus — ms-sdui-composer

Este documento especifica os alertas formais orientados a sintomas (*symptom-based alerts*) do serviço
`ms-sdui-composer`, derivados diretamente dos SLOs da arquitetura e conectados aos procedimentos operacionais padrão
(Runbooks).

---

## 1. Filosofia de Alertas

Seguindo as diretrizes da disciplina de observabilidade:

1. **Alertas focam em sintomas que afetam o usuário**, e não em causas pontuais (ex.: CPU alta por si só não gera page
   se a latência e o throughput estiverem saudáveis).
2. **Todo alerta com severidade `page` possui ação imediata obrigatória e link direto para runbook.**
3. **Severidades padronizadas:**
    - `page`: Chamado emergencial imediato para o engenheiro de plantão (*on-call*).
    - `ticket`: Degradação não crítica a ser tratada pelo squad no expediente.

---

## 2. Catálogo de Alertas Operacionais

| Alerta                       | Severidade | Condição (PromQL)                               | Janela | Runbook Vinculado                                                                                               |
|------------------------------|:----------:|-------------------------------------------------|:------:|-----------------------------------------------------------------------------------------------------------------|
| `SDUIHomeHighLatencyP99`     |   `page`   | P99 de `compose.duration` (hit) > 400ms         |   5m   | [`ios-canary-rollback.md`](ios-canary-rollback.md)                                                              |
| `SDUIHomeHighUnavailability` |   `page`   | Taxa de HTTP 503 (`compose.unavailable`) > 0.5% |   3m   | [`contrato-de-retry-clientes-moveis.md`](contrato-de-retry-clientes-moveis.md)                                  |
| `SDUIReadBulkheadShedding`   |  `ticket`  | Rejeições no bulkhead de leitura > 0            |   2m   | [`contrato-de-retry-clientes-moveis.md`](contrato-de-retry-clientes-moveis.md)                                  |
| `SDUIRollbackTriggered`      |   `page`   | Execução de rollback administrativo > 0         |   1m   | [`ios-canary-rollback.md`](ios-canary-rollback.md) / [`android-canary-rollback.md`](android-canary-rollback.md) |
| `SDUIRateLimitSpike`         |  `ticket`  | Taxa de HTTP 429 (`compose.rate_limited`) > 5%  |   5m   | [`contrato-de-retry-clientes-moveis.md`](contrato-de-retry-clientes-moveis.md)                                  |
| `SDUIStoreFailures`          |  `ticket`  | Falhas de dependência de dados (`store.failure`) > 0 | 5m | [`persistencia-mongodb-redis.md`](persistencia-mongodb-redis.md)                                             |
| `SDUICacheInvalidationPending` | `ticket` | Invalidações pendentes no outbox (`cache.invalidation.pending`) > 0 | 10m | [`persistencia-mongodb-redis.md`](persistencia-mongodb-redis.md)                          |

As regras de latência, indisponibilidade e 429 filtram `surface="home"`: todas as métricas do
pipeline carregam a tag `surface` (valores `home` e `catalog`), e o SLO desta tabela é o da Home.
Replique com `surface="catalog"` quando o catálogo tiver SLO próprio acordado.

---

## 3. Especificação YAML para Prometheus / Alertmanager

```yaml
groups:
  - name: ms-sdui-composer-alerts
    rules:
      - alert: SDUIHomeHighLatencyP99
        expr: histogram_quantile(0.99, sum(rate(compose_duration_seconds_bucket{surface="home",outcome="hit"}[5m])) by (le)) > 0.4
        for: 5m
        labels:
          severity: page
          service: ms-sdui-composer
        annotations:
          summary: "Violação de SLO de latência P99 no hot path da Home (cache hit > 400ms)"
          description: "O percentil P99 de composição da Home em cache hit atingiu {{ $value }}s (SLO: <= 0.4s / 400ms) durante 5 minutos."
          runbook_url: "docs/runbooks/ios-canary-rollback.md"

      - alert: SDUIHomeHighUnavailability
        expr: (sum(rate(compose_unavailable_total{surface="home"}[5m])) / sum(rate(compose_duration_seconds_count{surface="home"}[5m]))) > 0.005
        for: 3m
        labels:
          severity: page
          service: ms-sdui-composer
        annotations:
          summary: "Taxa de HTTP 503 Service Unavailable acima de 0.5%"
          description: "A composição da Home está falhando e devolvendo 503 para mais de 0.5% das requisições (taxa atual: {{ $value | humanizePercentage }})."
          runbook_url: "docs/runbooks/contrato-de-retry-clientes-moveis.md"

      - alert: SDUIReadBulkheadShedding
        expr: sum(rate(compose_bulkhead_rejected_total[2m])) > 0
        for: 2m
        labels:
          severity: ticket
          service: ms-sdui-composer
        annotations:
          summary: "Bulkhead do plano de leitura rejeitando requisições"
          description: "O semáforo de concorrência do plano de leitura saturou e rejeitou chamadas na etapa de seleção ou composição."
          runbook_url: "docs/runbooks/contrato-de-retry-clientes-moveis.md"

      - alert: SDUIRollbackTriggered
        expr: sum(rate(admin_rollback_total[5m])) > 0
        for: 1m
        labels:
          severity: page
          service: ms-sdui-composer
        annotations:
          summary: "Rollback operacional de especificação acionado no plano de administração"
          description: "Um operador disparou rollback de ponteiro de spec (canal/plataforma afetados disponíveis nas tags da métrica)."
          runbook_url: "docs/runbooks/ios-canary-rollback.md"

      - alert: SDUIRateLimitSpike
        expr: (sum(rate(compose_rate_limited_total{surface="home"}[5m])) / sum(rate(compose_duration_seconds_count{surface="home"}[5m]))) > 0.05
        for: 5m
        labels:
          severity: ticket
          service: ms-sdui-composer
        annotations:
          summary: "Spike de HTTP 429 Rate Limited na Home (> 5%)"
          description: "Mais de 5% das requisições da Home estão sendo limitadas pelo Token Bucket."
          runbook_url: "docs/runbooks/contrato-de-retry-clientes-moveis.md"

      - alert: SDUIStoreFailures
        expr: sum(rate(store_failure_total[5m])) by (stage) > 0
        for: 5m
        labels:
          severity: ticket
          service: ms-sdui-composer
        annotations:
          summary: "Falhas de dependência de dados na etapa {{ $labels.stage }}"
          description: "O pipeline está degradando por falha de store ou cache. Ver health sduiStore/sduiCache."
          runbook_url: "docs/runbooks/persistencia-mongodb-redis.md"

      - alert: SDUICacheInvalidationPending
        expr: max(cache_invalidation_pending) > 0
        for: 10m
        labels:
          severity: ticket
          service: ms-sdui-composer
        annotations:
          summary: "Invalidações de cache pendentes no outbox há mais de 10 minutos"
          description: "O relay não consegue aplicar invalidações pós-commit; last good pode estar desatualizado."
          runbook_url: "docs/runbooks/persistencia-mongodb-redis.md"
```
