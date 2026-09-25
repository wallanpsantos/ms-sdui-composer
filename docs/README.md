# Documentação Técnica — ms-sdui-composer

Este diretório contém a documentação técnica oficial, especificações, arquitetura, fluxos de integração e registros
operacionais do serviço `ms-sdui-composer`.

## 📚 Documentos Canônicos de Arquitetura e Referência

Os documentos canônicos refletem o design do sistema, sua operação em produção e seu ciclo de vida contínuo:

| Documento                                                                | Descrição                                                                                                                                                                                                                                                                                                                            |
|--------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [`arquitetura-de-referencia.md`](arquitetura-de-referencia.md)           | **Arquitetura Canônica de Referência:** Desenho modular (Clean Architecture), 4 módulos de produção + teste, convenções Kotlin/Java 25, pipeline de composição, regras de concorrência e resiliência, observabilidade, governança, catálogo completo de decisões (ADR-001 a ADR-022) e histórico consolidado do MVP (`H00` a `H18`). |
| [`guia-depreciacao-e-migracao.md`](guia-depreciacao-e-migracao.md)       | **Guia de Depreciação e Migração:** Padrão Strangler para componentes, sunset de faixas de aplicativo, Expand/Contract no MongoDB e governança contínua de contratos.                                                                                                                                                                |
| [`guia-criacao-telas-componentes.md`](guia-criacao-telas-componentes.md) | **Guia de Criação de Telas e Componentes:** skeleton × spec × envelope, maker-checker, nova surface, novo componente com capability, fallback, rollback, locale e diagnóstico.                                                                                                                                                       |

---

## 📂 Subdiretórios Estruturados

- [`contratos/`](contratos/transaction-summary-v1.md) — Contratos **propostos** de componentes novos
  (`transaction_summary@1`, `catalog_navigation@1`, `product_collection@1`), não homologados pelos apps.
- [`examples/screens/`](examples/screens/README.md) — Quatro composições de exemplo (skeleton, spec e resposta), matriz
  de rastreabilidade das imagens e roteiro de publicação no modo demo.
- [`images/`](images/README.md) — Mapeamento e compatibilidade de layouts móveis reais (banking, finance, coffee, etc.).
- [`tasks/`](tasks/README.md) — Backlog operacional pendente ([`todo.md`](tasks/todo.md)) e planejamento da linguagem de
  UI do backend ([`plano-tokens-semanticos.md`](tasks/plano-tokens-semanticos.md)).

---

> [!NOTE]
> Os catálogos de ADRs (ADR-001 a ADR-022), as diretrizes de rollback Canary (iOS/Android) e o contrato de
resiliência/retry de clientes móveis foram consolidados diretamente no documento canônico [
`arquitetura-de-referencia.md`](arquitetura-de-referencia.md) (Seções 6, 10 e 11). As regras de métricas e alertas
Prometheus estão especificadas em [`regras-de-alerta-prometheus.md`](regras-de-alerta-prometheus.md).
