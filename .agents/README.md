# `.agents/` — instruções do time de agentes

Os arquivos deste diretório são prompts operacionais de desenvolvimento. Não são componentes do runtime: o
`ms-sdui-composer` não ganha camada para "orquestrar agentes", e o pipeline
`Negotiate → Select → Filter → Hydrate → Guard/Fallback → Compose` continua sendo do serviço.

As regras vivem no [`AGENTS.md`](../AGENTS.md). Papéis e skills citam a seção (`§19.10`) em vez de copiar o texto, e
listas com fonte no código (chaves visuais, PII, surfaces, contratos, métricas) são apontadas, nunca repetidas.

## Estrutura

```text
.agents/
├── README.md                      este índice
├── agents/                        papéis (prompts carregados por instrução)
│   ├── sdui-implementer.md        papel padrão
│   ├── sdui-architect.md
│   ├── sdui-tester.md
│   ├── sdui-contract-guard.md
│   └── sdui-reviewer.md
├── rules/
│   └── git-commit-standards.md    formato da mensagem de commit
└── skills/                        procedimentos no formato Agent Skills (SKILL.md)
    ├── sdui-backend/README.md     marcador; a skill canônica não existe e não é inventada
    ├── sdui-component/            criar e depreciar componente
    ├── sdui-surface/              surface nova
    ├── sdui-adr/                  registrar ADR na §11 da arquitetura
    ├── sdui-perf/                 medir antes de otimizar
    └── sdui-commit-message/       gerar mensagem de commit a pedido
```

## Papéis

| Papel                 | Quando entra                                   | Escreve arquivos?  | Saída                                             |
|-----------------------|------------------------------------------------|--------------------|---------------------------------------------------|
| `sdui-implementer`    | Sempre (papel padrão)                          | Produção e testes  | Arquivos, comportamento, testes, riscos           |
| `sdui-architect`      | Só se o operador pedir decisão estrutural nova | ADR e documentação | Decisão implementável e ADR na §11                |
| `sdui-tester`         | Só se o operador pedir autoria extra de testes | Fontes de teste    | Cenários escritos por regra                       |
| `sdui-contract-guard` | Só se o operador pedir inspeção de contrato    | Não (leitura)      | `PASS`, `PASS_WITH_WARNINGS` ou `BLOCK`           |
| `sdui-reviewer`       | Só se o operador pedir revisão                 | Não (leitura)      | `PASS`, `PASS_WITH_WARNINGS` ou `REQUEST_CHANGES` |

O fluxo padrão é um só: o implementer. Não encadear `architect → implementer → tester → contract-guard → reviewer` como
rotina; os demais papéis só entram quando o operador os nomeia. A execução de Gradle segue a política única do
`AGENTS.md` › Modo operacional.

## Como cada ferramenta carrega

| Ferramenta  | Papéis                                                                                                    | Skills                                                   |
|-------------|-----------------------------------------------------------------------------------------------------------|----------------------------------------------------------|
| Claude Code | Implementer importado pelo `AGENTS.md`; os outros por `.claude/agents/<papel>.md`, que só apontam para cá | `.claude/skills/<nome>/SKILL.md`, que só apontam para cá |
| Codex       | Por instrução ("leia `.agents/agents/<papel>.md`")                                                        | Lê `.agents/skills/<nome>/SKILL.md` diretamente          |
| Outras      | Por instrução                                                                                             | Por instrução                                            |

Git fica com o operador humano: nenhum agente executa `git add`, `git commit` ou `git push` (`AGENTS.md` › Modo
operacional). No Claude Code, `.claude/settings.json` nega esses comandos.

## Manutenção

- Papel ou skill novo entra neste índice e, se tiver adaptador, em `.claude/`.
- Adaptador não repete conteúdo: frontmatter mais a instrução de ler o arquivo canônico.
- Quem renomeia ou move um símbolo citado no `AGENTS.md` atualiza o `AGENTS.md` no mesmo diff.
- `AgentInstructionsIntegrityTest` (`sdui-integration-test`) falha quando uma instrução aponta caminho ou arquivo
  inexistente, quando uma skill não tem frontmatter válido ou quando um adaptador não aponta para o canônico.
