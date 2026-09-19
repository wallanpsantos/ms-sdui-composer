# Documentação Técnica — ms-sdui-composer

Este diretório contém a documentação técnica oficial, especificações, arquitetura, fluxos de integração e registros operacionais do serviço `ms-sdui-composer`.

## 📚 Sequência Canônica de Leitura e Execução

Os documentos raiz estão numerados sequencialmente refletindo o ciclo de vida do projeto:

| Ordem | Arquivo | Descrição |
|:---:|---|---|
| **01** | [`01-iniciar-prompt.md`](01-iniciar-prompt.md) | **Prompt de Inicialização:** Instruções de bootstrap, precedência documental, papéis de IA/agentes e setup do serviço. |
| **02** | [`02-resumos-server-driven-ui.md`](02-resumos-server-driven-ui.md) | **Fundamentação Conceitual:** Referências teóricas de mercado (Joud Awad, Airbnb Ghost Platform, Martin Fowler). |
| **03** | [`03-pre-arquitetura-ms-sdui-composer.md`](03-pre-arquitetura-ms-sdui-composer.md) | **Pré-Arquitetura Canônica:** Desenho modular (Clean Architecture), convenções Kotlin/Java 25 e ADRs 001 a 013. |
| **04** | [`04-plano-servico-sdui.md`](04-plano-servico-sdui.md) | **Plano de Construção do Serviço:** Especificação detalhada de engenharia para as histórias `H00` a `H18`. |
| **05** | [`05-fluxos-integracao-ms-sdui-composer.md`](05-fluxos-integracao-ms-sdui-composer.md) | **Fluxos de Integração:** Diagramas de sequência e interfaces entre Mobile, BFF de UI e persistência/cache. |
| **06** | [`06-memoria-projeto-ms-sdui-composer.md`](06-memoria-projeto-ms-sdui-composer.md) | **Memória Operacional e Arquitetural:** Estado consolidado pós-MVP, decisões fixadas, contratos e regras inegociáveis. |
| **07** | [`07-relatorio-revisao-e-otimizacao-performance.md`](07-relatorio-revisao-e-otimizacao-performance.md) | **Relatório Técnico & Performance:** Auditoria dos 5 eixos de qualidade, as 7 correções de resiliência e análise do hot path. |

---

## 📂 Subdiretórios Estruturados

- [`adr/`](adr/README.md) — Registros de Decisões Arquiteturais formais (ADRs).
- [`artifacts/`](artifacts/README.md) — Fixtures canônicas de contrato (JSON) para validação de round-trip.
- [`historias/`](historias/README.md) — Backlog e especificações de histórias de desenvolvimento (`H00` a `H18`).
- [`runbooks/`](runbooks/) — Procedimentos operacionais para incidentes e rollback (canary iOS e Android).
