---
name: sdui-component
description: Use ao criar um componente SDUI novo (type@typeVersion), publicar uma versão nova de componente existente ou depreciar e aposentar uma versão no ms-sdui-composer. Não use para tela nova com componentes já aprovados (guia §3) nem para surface nova (skill sdui-surface).
---

# Componente SDUI: criar e depreciar

Procedimento condensado de `docs/guia-criacao-telas-componentes.md` (§2, §5 e §9), de
`docs/guia-depreciacao-e-migracao.md` (§3 e §9) e do `AGENTS.md` (§8, §23.2 e §23.3). Em dúvida, a fonte prevalece.

## Antes de começar

- O conteúdo é SDUI? Autenticação, PIN, onboarding regulado, checkout, chat e mapas ficam nativos e são alcançados por
  `navigate` (guia §2, ADR-015).
- Nome pelo conceito de produto (`transaction_summary`, não `transaction_list`); nada de primitiva genérica (`row`,
  `card`, `container`) nem aparência nas props (`MvpCatalog.VISUAL_KEYS`, ADR-010).

## Criar

1. **Contrato primeiro** (guia §5.1). Escrever `docs/contratos/<type-em-kebab-case>-v<N>.md` no molde de
   `docs/contratos/transaction-summary-v1.md`: status, Conceito (surface, slot, capability, portante), Props (tabela
   Prop | Obrigatória | Tipo | Regra, com limites), Actions, Proibições, Erros e omissão, Exemplo.
2. **ADR** (skill `sdui-adr`), registrando o contrato e a decisão de compatibilidade.
3. **Servidor** (guia §5.2): `type@typeVersion` em `ComponentContracts.APPROVED`; o type nos `types` da surface e do
   slot em `Surfaces`; regras de props em `ComponentPropsValidator`. Testes negativos junto (campo ausente, limite,
   operação proibida), nas convenções do `sdui-tester`.
4. **Catálogo** (guia §5.3): `PUT /admin/v1/catalog/components/{type}/{v}` com `status: ACTIVE`. Sem contrato
   aprovado, o `CatalogValidator` recusa, inclusive inativo.
5. **Compatibilidade** (guia §5.4, §23.3): a matriz do servidor não concede o type a nenhuma faixa de app; ele só chega
   a quem declara `Component-Capabilities: <type>@<v>`.
    - slot opcional: sem a capability, a section é omitida (`unsupported_type`);
    - slot portante: a capability vai em `requiredCapabilities` do spec, e o cliente sem ela cai no fallback da mesma
      surface.
6. **Homologação móvel** (guia §5.5): o componente só aparece depois de o renderer existir nos apps. Conceder o type a
   uma faixa pela matriz do servidor é decisão registrada, não automática.

## Depreciar uma versão (Strangler)

Estados do catálogo: `DRAFT`, `ACTIVE`, `DEPRECATED`, `RETIRED` (guia de depreciação §3.1).

1. **Expand:** `@N+1` entra `ACTIVE` ao lado de `@N`, com contrato próprio.
2. **Dual-run:** specs para apps novos usam `@N+1`; apps legados continuam em `@N`. Select e Filter entregam pela
   capability declarada.
3. **Advisory:** `@N` passa a `DEPRECATED`; acompanhar `section.omitted` e a métrica do componente; avisar a equipe
   móvel do cronograma.
4. **Retirement:** abaixo do limiar de corte (ou na data limite), `@N` vira `RETIRED`; o Filter omite com
   `UNSUPPORTED_TYPE`, e slot portante segue a escada do ADR-007.

Os sete types da Home não saem do catálogo ativo enquanto a Home publicada depender deles (`CatalogValidator`).

Checklist antes de retirar (guia de depreciação §9): substituto em produção; tráfego residual abaixo do limiar;
omissão graciosa testada sem quebrar slot portante; escada de fallback verificada; mudança de banco em
Expand/Contract; código morto removido.
