# Documentação Técnica — ms-sdui-composer

Este diretório contém a documentação técnica oficial, especificações, arquitetura, fluxos de integração e registros
operacionais do serviço `ms-sdui-composer`.

## 📚 Sequência Canônica de Arquitetura e Referência

Os documentos canônicos refletem o design do sistema e seu ciclo operacional:

| Ordem  | Arquivo | Descrição |
|:------:|---|---|
| **02** | [`02-pre-arquitetura-ms-sdui-composer.md`](02-pre-arquitetura-ms-sdui-composer.md) | **Pré-Arquitetura Canônica:** Desenho modular (Clean Architecture), 4 módulos de produção + teste, convenções Kotlin/Java 25 e catálogo formal de ADRs 001 a 013. |
| **03** | [`03-memoria-projeto-ms-sdui-composer.md`](03-memoria-projeto-ms-sdui-composer.md) | **Memória Operacional e Arquitetural:** Estado consolidado pós-MVP, decisões fixadas, contratos, regras inegociáveis e metas de performance (SLOs). |
| **04** | [`04-guia-depreciacao-e-migracao-sdui.md`](04-guia-depreciacao-e-migracao-sdui.md) | **Guia de Depreciação e Migração:** Padrão Strangler para componentes, sunset de faixas de aplicativo, Expand/Contract no MongoDB e eliminação de código zumbi. |

*(Nota de histórico: O arquivo inicial `01-iniciar-prompt.md` continha o prompt de bootstrap do Dia 0 e foi aposentado após a conclusão e validação integral do MVP).*

---

## 📂 Subdiretórios Estruturados

- [`adr/`](adr/README.md) — Registros de Decisões Arquiteturais formais (ADRs 014 a 019).
- [`artifacts/`](artifacts/README.md) — Fixtures canônicas de contrato (JSON) para validação de round-trip.
- [`historias/`](historias/README.md) — Registro consolidado e critérios de entrega das histórias do MVP (`H00` a `H18`).
- [`images/`](images/README.md) — Mapeamento e compatibilidade de layouts móveis reais (banking, finance, coffee, etc.).
- [`runbooks/`](runbooks/README.md) — Procedimentos operacionais para incidentes e rollback (canary iOS e Android) e contrato de retry móvel.
