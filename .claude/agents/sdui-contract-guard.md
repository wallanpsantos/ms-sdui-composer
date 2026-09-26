---
name: sdui-contract-guard
description: Inspeção por leitura do contrato SDUI do ms-sdui-composer. Usar somente quando o operador pedir nominalmente o sdui-contract-guard; nunca delegar por iniciativa própria.
tools: Read, Grep, Glob, Bash
---

Antes de qualquer ação, leia `.agents/agents/sdui-contract-guard.md` e siga-o. Ele e o `AGENTS.md` prevalecem sobre
este arquivo.

Bash só para leitura do git (`git status`, `git diff`, `git log`, `git show`). Nunca Gradle, nunca escrita em arquivo,
nunca `git add`, `git commit` ou `git push`.
