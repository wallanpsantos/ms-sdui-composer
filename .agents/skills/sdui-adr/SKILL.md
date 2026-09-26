---
name: sdui-adr
description: Use para registrar uma decisão arquitetural (ADR) do ms-sdui-composer na Seção 11 de docs/arquitetura-de-referencia.md. Não use para anotações operacionais ou tarefas, que vão para docs/tasks/.
---

# Registrar ADR

Fonte: `AGENTS.md` §14 e Seção 11 de `docs/arquitetura-de-referencia.md` (matriz de decisões e detalhamento). Não
existe diretório `docs/adr/`; os ADRs vivem na Seção 11.

## Passos

1. **Número:** o próximo livre depois do maior da matriz e das reservas. ADR-023 a ADR-026 estão reservados em
   `docs/tasks/plano-tokens-semanticos.md`.
2. **Matriz:** acrescentar a linha em "Matriz de Decisões", colunas `| ADR | Título | Status | Escopo Principal |`, com
   o id em negrito (`**ADR-027**`) e o status entre crases.
3. **Detalhamento:** acrescentar, em ordem numérica, sob "Detalhamento dos Registros de Decisão Arquitetural":

   ```markdown
   #### ADR-027 — <Título>

   - **Status:** `PROPOSTO`
   - **Contexto:** <problema e restrições, com evidência>
   - **Decisão:** <o que fica decidido>
   - **Consequências:** <ganhos, custos e o que passa a ser proibido>
   - **Alternativas descartadas:** <quando houver>
   - **Verificação:** <teste, métrica ou ensaio que confirma; quando houver>
   ```

   Molde: ADR-001 e ADR-022 na mesma seção.
4. **Status:** `PROPOSTO` até a verificação ou homologação; depois `ACEITO`. Substituição: `SUPERSEDED` pelo ADR novo,
   que registra `ACEITO (supersede ADR-NNN)`, como ADR-003 e ADR-013.
5. **Regra inegociável:** se a decisão criar regra para o dia a dia, propor a seção correspondente no `AGENTS.md`.
6. **Título da Seção 11:** atualizar o intervalo no título ("ADR-001 a ADR-NNN") quando o ADR for aceito.
