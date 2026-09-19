# Architectural Decision Records (ADRs)

Este diretório contém os Registros de Decisões Arquiteturais (ADRs) do projeto `ms-sdui-composer`.

## Formato e Nomenclatura

Os ADRs devem seguir a numeração sequencial de três dígitos:
`ADR-XXX-<slug-descritivo>.md` (ex.: `ADR-001-divisao-multi-modulo.md`).

## Estrutura Padrão

Todo ADR deve conter:

1. **Título:** `ADR-XXX: <Título da Decisão>`
2. **Status:** `PROPOSTO` | `ACEITO` | `REJEITADO` | `DEPRECADO` | `SUPERSEDED`
3. **Contexto:** Motivação do problema, restrições técnicas, requisitos não funcionais.
4. **Decisão:** O que foi decidido, detalhamento técnico, contratos afetados.
5. **Consequências:**
    - Pontos positivos (o que ganhamos);
    - Pontos negativos / trade-offs aceitos;
    - Riscos operacionais e mitigações.
6. **Alternativas Consideradas:** Soluções descartadas e a justificativa técnica para o descarte.
7. **Critérios de Validação:** Como a decisão é verificada (testes unitários, ArchUnit, benchmarks, classpath).

## ADRs Canônicos Documentados na Pré-Arquitetura

Os ADRs fundamentais da pré-arquitetura (`docs/02-pre-arquitetura-ms-sdui-composer.md`) são:

- **ADR-001:** Divisão em 4 módulos de produção (`contract`, `core`, `app`, `bootstrap`) e 1 de teste
  (`integration-test`). (`ACEITO`)
- **ADR-002:** Isolamento estrito de `ComposeTraceContext` (fora de `core` e `orchestrator`). (`ACEITO`)
- **ADR-003:** Isolamento de `@Transactional` com `TransactionalUnitOfWork`. (`ACEITO`)
- **ADR-004:** Adoção de `Screen` e eliminação de `Fragment` no MVP. (`ACEITO`)
- **ADR-005:** Adoção de Jackson 3 (`tools.jackson`) gerenciado pelo Spring Boot 4.1. (`ACEITO`)
- **ADR-006:** Invalidação seletiva de cache Redis via scan desacoplado. (`ACEITO`)
- **ADR-007:** Escada determinística de Fallback (cache hit -> last good -> 503 Retry-After). (`ACEITO`)
- **ADR-008:** Maker-Checker com autorização formal (com premissa de engenheiro único em dev). (`PENDENTE`)
- **ADR-009:** Validação estrita de slots portantes (`header`, `accounts`) no publish. (`ACEITO`)
- **ADR-010:** Rejeição de `SectionComponentType` genérico e seletores de estilo no payload. (`ACEITO`)
- **ADR-011:** Conjunto fechado de Actions do MVP (`navigate`, `open_bottom_sheet`, `track`, `noop`). (`ACEITO`)
- **ADR-012:** Exclusão de coroutines e bibliotecas reativas em favor de Spring MVC + Virtual Threads. (`ACEITO`)
- **ADR-013:** Unificação de transações com `TransactionTemplate`. (`PROPOSTO`)
