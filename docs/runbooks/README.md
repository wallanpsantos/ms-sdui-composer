# Runbooks Operacionais — ms-sdui-composer

Este diretório contém os procedimentos operacionais padrão (SOPs) para incidentes, rollbacks e homologação de rede do
serviço `ms-sdui-composer`.

## Catálogo de Runbooks

| Procedimento                 | Arquivo                                                                        | Descrição                                                                                                          |
|------------------------------|--------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------|
| **Rollback Canary iOS**      | [`ios-canary-rollback.md`](ios-canary-rollback.md)                             | Procedimento para reversão operacional de pointer e invalidação de cache para o canal Canary iOS.                  |
| **Rollback Canary Android**  | [`android-canary-rollback.md`](android-canary-rollback.md)                     | Procedimento isolado para reversão operacional no canal Canary Android (sem impactar iOS).                         |
| **Contrato de Retry Mobile** | [`contrato-de-retry-clientes-moveis.md`](contrato-de-retry-clientes-moveis.md) | Regras de repetição para apps móveis (jitter, backoff, revalidação ETag e desfechos de rede derivados do ADR-014). |
| **Alertas Prometheus**       | [`regras-de-alerta-prometheus.md`](regras-de-alerta-prometheus.md)             | Especificação de alertas Prometheus orientados a sintomas (SLO, indisponibilidade, bulkhead e rollback).           |
