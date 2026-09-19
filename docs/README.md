# Documentação Técnica — ms-sdui-composer

Este diretório contém a documentação técnica oficial, especificações, arquitetura, fluxos de integração e registros operacionais do serviço `ms-sdui-composer`.

## 📚 Sequência Canônica de Leitura e Execução

Os documentos raiz estão numerados sequencialmente refletindo o ciclo de vida do projeto:

| Ordem | Arquivo | Descrição |
|:---:|---|---|
| **01** | [`01-iniciar-prompt.md`](01-iniciar-prompt.md) | **Prompt de Inicialização:** Ponto de partida do projeto com regras de bootstrap, precedência documental, papéis de IA/agentes e setup do serviço. |
| **02** | [`02-pre-arquitetura-ms-sdui-composer.md`](02-pre-arquitetura-ms-sdui-composer.md) | **Pré-Arquitetura Canônica:** Desenho modular (Clean Architecture), 4 módulos de produção + teste, convenções Kotlin/Java 25 e ADRs 001 a 013. |
| **03** | [`03-memoria-projeto-ms-sdui-composer.md`](03-memoria-projeto-ms-sdui-composer.md) | **Memória Operacional e Arquitetural:** Estado consolidado pós-MVP, decisões fixadas, contratos, 7 regras inegociáveis e metas de performance (SLOs). |

---

## 📂 Subdiretórios Estruturados

- [`adr/`](adr/README.md) — Registros de Decisões Arquiteturais formais (ADRs).
- [`artifacts/`](artifacts/README.md) — Fixtures canônicas de contrato (JSON) para validação de round-trip.
- [`historias/`](historias/README.md) — Backlog e especificações de histórias de desenvolvimento (`H00` a `H18`).
- [`runbooks/`](runbooks/) — Procedimentos operacionais para incidentes e rollback (canary iOS e Android).
