# Documentação Técnica — ms-sdui-composer

Este diretório contém a documentação técnica oficial, especificações, arquitetura, fluxos de integração e registros
operacionais do serviço `ms-sdui-composer`.

## 📚 Documentos Canônicos de Arquitetura e Referência

Os documentos canônicos refletem o design do sistema, sua operação em produção e seu ciclo de vida contínuo:

| Documento                                                                        | Descrição                                                                                                                                                                                                   |
|----------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [`arquitetura-de-referencia.md`](arquitetura-de-referencia.md)                   | **Arquitetura Canônica de Referência:** Desenho modular (Clean Architecture), 4 módulos de produção + teste, convenções Kotlin/Java 25, pipeline de composição e decisões fundacionais (ADR-001 a ADR-013). |
| [`memoria-operacional-e-arquitetural.md`](memoria-operacional-e-arquitetural.md) | **Memória Operacional e Arquitetural:** Estado consolidado do serviço, decisões fixadas, contratos, 21 otimizações de performance aplicadas, metas de SLO e consolidação arquitetural dos ADRs 014 a 019.   |
| [`guia-depreciacao-e-migracao.md`](guia-depreciacao-e-migracao.md)               | **Guia de Depreciação e Migração:** Padrão Strangler para componentes, sunset de faixas de aplicativo, Expand/Contract no MongoDB e governança contínua de contratos.                                       |

---

## 📂 Subdiretórios Estruturados

- [`adr/`](adr/README.md) — Matriz consolidada de decisões arquiteturais do serviço (ADR-001 a ADR-019).
- [`artifacts/`](artifacts/README.md) — Fixtures canônicas de contrato (JSON) para validação de round-trip.
- [`historias/`](historias/README.md) — Registro consolidado e critérios de entrega das histórias do MVP (`H00` a
  `H18`).
- [`images/`](images/README.md) — Mapeamento e compatibilidade de layouts móveis reais (banking, finance, coffee, etc.).
- [`runbooks/`](runbooks/README.md) — Procedimentos operacionais para incidentes e rollback (canary iOS e Android) e
  contrato de retry móvel.
