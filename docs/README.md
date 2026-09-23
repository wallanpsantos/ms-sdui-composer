# Documentação Técnica — ms-sdui-composer

Este diretório contém a documentação técnica oficial, especificações, arquitetura, fluxos de integração e registros
operacionais do serviço `ms-sdui-composer`.

## 📚 Documentos Canônicos de Arquitetura e Referência

Os documentos canônicos refletem o design do sistema, sua operação em produção e seu ciclo de vida contínuo:

| Documento                                                                        | Descrição                                                                                                                                                                                                   |
|----------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [`arquitetura-de-referencia.md`](arquitetura-de-referencia.md)                   | **Arquitetura Canônica de Referência:** Desenho modular (Clean Architecture), 4 módulos de produção + teste, convenções Kotlin/Java 25, pipeline de composição e decisões fundacionais (ADR-001 a ADR-013). |
| [`memoria-operacional-e-arquitetural.md`](memoria-operacional-e-arquitetural.md) | **Memória Operacional e Arquitetural:** Estado consolidado do serviço, decisões fixadas, contratos, 21 otimizações de performance aplicadas, metas de SLO, consolidação arquitetural dos ADRs 014 a 022 e registro consolidado de histórias do MVP (`H00` a `H18`). |
| [`guia-depreciacao-e-migracao.md`](guia-depreciacao-e-migracao.md)               | **Guia de Depreciação e Migração:** Padrão Strangler para componentes, sunset de faixas de aplicativo, Expand/Contract no MongoDB e governança contínua de contratos.                                       |
| [`guia-criacao-telas-componentes.md`](guia-criacao-telas-componentes.md)         | **Guia de Criação de Telas e Componentes:** skeleton × spec × envelope, maker-checker, nova surface, novo componente com capability, fallback, rollback, locale e diagnóstico. |
| [`analise-performance-2026-09-23.md`](analise-performance-2026-09-23.md)         | **Análise de Performance (estática):** oito achados priorizados; status de tratamento no fim do documento.                                                                                                 |
| [`performance/medicoes-2026-09-23.md`](performance/medicoes-2026-09-23.md)       | **Medições:** antes e depois de cada achado, variância, decisões e experimentos rejeitados; carga HTTP.                                                                                                    |

---

## 📂 Subdiretórios Estruturados

- [`adr/`](adr/README.md) — Matriz consolidada de decisões arquiteturais (ADR-001 a ADR-022), unificando as decisões
  em `docs/arquitetura-de-referencia.md` (ADR-001 a 013) e `docs/memoria-operacional-e-arquitetural.md` (ADR-014 a 022).
- [`artifacts/`](artifacts/README.md) — Fixtures canônicas de contrato (JSON) para validação de round-trip.
- [`contratos/`](contratos/transaction-summary-v1.md) — Contratos **propostos** de componentes novos
  (`transaction_summary@1`, `catalog_navigation@1`, `product_collection@1`), não homologados pelos apps.
- [`examples/screens/`](examples/screens/README.md) — Quatro composições de exemplo (skeleton, spec e resposta),
  matriz de rastreabilidade das imagens e roteiro de publicação no modo demo.
- [`images/`](images/README.md) — Mapeamento e compatibilidade de layouts móveis reais (banking, finance, coffee, etc.).
- [`runbooks/`](runbooks/README.md) — Procedimentos operacionais para incidentes e rollback (canary iOS e Android) e
  contrato de retry móvel.
