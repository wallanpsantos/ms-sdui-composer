# Diretório de Tarefas e Planejamento Operacional

Este diretório concentra o backlog de pendências operacionais, roteiros de homologação e planejamentos futuros de
evolução técnica do `ms-sdui-composer`.

---

## 📋 Conteúdo do Diretório

| Arquivo                                                    | Descrição                                                                                                                                                                                           |   Status   |
|:-----------------------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:----------:|
| [`todo.md`](todo.md)                                       | **Backlog Operacional Pendente:** Acompanhamento de validações finais (execução Gradle, ensaio operacional de banco real P13, baseline HTTP em máquina dedicada e homologação de contratos móveis). |  `ATIVO`   |
| [`plano-tokens-semanticos.md`](plano-tokens-semanticos.md) | **Linguagem de UI do Backend:** Especificação arquitetural para temas por segmento, blocos primitivos (`block@1`), campanhas dinâmicas e autenticação JWT de operadores (ADR-023 a ADR-026).        | `PROPOSTO` |

---

## 🏛️ Centralização Canônica e Histórico

As entregas anteriores de desenvolvimento (**H00 a H18** do MVP e as evoluções pós-MVP **ADR-020 a ADR-022**) foram
**100% concluídas, testadas e consolidadas** no documento canônico de arquitetura do projeto:

- Consulte [`docs/arquitetura-de-referencia.md`](../arquitetura-de-referencia.md) (§11 e §12) para a especificação
  técnica completa, catálogo de ADRs com status `ACEITO` e matriz consolidada de entregas.
- Planos operacionais anteriores de implementação de telas (`plan.md`) e de persistência
  (`plano-persistencia-mongo-redis.md`) foram descontinuados por já estarem totalmente incorporados ao código de
  produção e à arquitetura de referência.
