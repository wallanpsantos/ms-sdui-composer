---
name: sdui-commit-message
description: Use somente quando o operador pedir uma mensagem de commit para as alterações atuais do ms-sdui-composer. Gera o texto e não executa git add, commit ou push.
---

# Mensagem de commit

Fonte única: `.agents/rules/git-commit-standards.md`. Governança: `AGENTS.md` › Modo operacional.

## Passos

1. Ler as alterações: `git status` e `git diff HEAD` (staged e não staged). Se o operador pedir só o que está staged,
   `git diff --cached`.
2. Identificar a mudança principal. Com mudanças independentes, escolher a mais relevante e abrangente.
3. Escolher tipo e emoji da lista permitida; escopo só se for claramente identificável.
4. Escrever o cabeçalho `<emoji> <tipo>(<escopo>): <descrição no imperativo>`, com até 72 caracteres e sem ponto
   final.
5. Listar até 10 tópicos curtos, só com o que está no diff.
6. Responder somente com a mensagem, em pt-BR, sem explicação, aspas, Markdown ou bloco de código.

## Nunca

- Executar `git add`, `git commit` ou `git push`.
- Adicionar `Co-authored-by`, menção à IA ou crédito a modelo ou ferramenta.
- Inventar alteração, escopo ou ticket que não esteja no diff.
