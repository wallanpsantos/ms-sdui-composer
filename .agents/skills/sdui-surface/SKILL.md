---
name: sdui-surface
description: Use ao adicionar uma surface nova (tela de primeiro nível servida em /v1/surfaces/<nome>) ao ms-sdui-composer. Não use para tela nova numa surface existente nem para componente novo (skill sdui-component).
---

# Surface nova

Procedimento condensado de `docs/guia-criacao-telas-componentes.md` (§3 e §6), do `AGENTS.md` §23.1 e do ADR-020 (Seção
11 de `docs/arquitetura-de-referencia.md`). Em dúvida, a fonte prevalece.

## Regra

Surface é allowlist. Toda surface vem de `Surfaces` (`sdui-core`, `model/Surface.kt`) e tem mapeamento HTTP literal
em `SurfaceController`. Proibidos `/v1/surfaces/{surface}` genérico, surface como texto livre em chave, tag ou
documento, e fallback ou árvore de uma surface servidos a outra (§23.1).

## Passos

1. **Decisão:** surface nova é decisão estrutural; registrar ADR (skill `sdui-adr`).
2. **Definição:** um `SurfaceDefinition` em `Surfaces` com slots (layouts permitidos e portantes), types, primeiro
   slot, evento de analytics e locale do conteúdo; incluir em `Surfaces.ALL` (guia §6).
3. **Rota:** um `@GetMapping` literal em `SurfaceController`, no molde de `/v1/surfaces/home` e
   `/v1/surfaces/catalog` (mesmo `version`, resposta 200 pré-serializada, §18.3).
4. **Separação automática:** pointer, cache, singleflight, last good e métricas passam a separar a surface nova (guia
   §6). Conferir o `entryPoint` no MDC (`CorrelationIdFilter`, via `Surfaces.find`) e que a tag de surface
   continua com valores do universo conhecido (§23.7).
5. **Componentes:** type novo segue a skill `sdui-component`.
6. **Conteúdo:** skeleton e spec pelo fluxo maker-checker (guia §3): rascunhos com ator `MAKER`, pedido de
   publicação com `Idempotency-Key`, aprovação por outro `CHECKER`.
7. **Testes:** rota literal, isolamento de cache e fallback entre surfaces e surface desconhecida sem rota, nas
   convenções do `sdui-tester`.
