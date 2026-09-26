# Padrão de Mensagem de Commit

A governança de Git (nenhum agente executa `git add`, `git commit` ou `git push`) está no `AGENTS.md` › Modo
operacional. Este arquivo é a fonte única do formato da mensagem, usado somente quando o operador pedir para commitar
ou gerar mensagem de commit. Procedimento: skill `sdui-commit-message`.

## Proibição de Auto-Atribuição

- **Você não deve adicionar você**: proibido adicionar a si mesmo, co-autoria (`Co-authored-by`), menção à IA,
  créditos ao modelo/ferramenta, ou executar `git add`/`commit`.
- **Idioma**: responda sempre em pt-BR.
- **Origem dos dados**: analise exclusivamente as alterações atuais do `git diff` e gere uma única mensagem de commit
  pronta para uso.

## Formato Obrigatório

```text
<emoji> <tipo>(<escopo opcional>): <descrição no imperativo>
- <Descrições curtas em listas até 10 tópicos>
```

## Regras

- Retorne somente a mensagem do commit, sem explicações, aspas, Markdown ou bloco de código.
- Use uma única linha no cabeçalho/primeira linha, com no máximo 72 caracteres quando possível.
- A descrição deve ser objetiva, iniciar com verbo no imperativo e explicar a mudança principal.
- Não invente alterações, escopos, tickets ou detalhes que não estejam no diff.
- Use escopo apenas se estiver claramente identificável, por exemplo: `auth`, `api`, `checkout`, `database`, `docker` ou
  `docs`. Sem escopo, os dois-pontos vêm logo depois do tipo (`📚 docs: …`); com escopo, entre parênteses e sem espaço
  antes deles (`🗑️ remove(docs): …`).
- Prefira commits atômicos. Se o diff contiver mudanças independentes, escolha a alteração mais relevante e abrangente.
- Não use ponto final.

## Tipos e emojis permitidos

- ✨ `feat`: adiciona uma nova funcionalidade
- 🐛 `fix`: corrige um bug
- 📚 `docs`: altera documentação
- 🧪 `test`: adiciona, altera ou remove testes
- 📦 `build`: altera dependências, build ou empacotamento
- ⚡ `perf`: melhora desempenho
- 🎨 `style`: altera formatação, lint ou aparência sem mudar comportamento
- ♻️ `refactor`: reorganiza código sem alterar comportamento funcional
- 🔧 `chore`: altera configurações, manutenção ou tarefas
- 👷 `ci`: altera pipelines ou integração contínua
- 🗃️ `raw`: altera dados, parâmetros ou arquivos de configuração
- 🧹 `cleanup`: remove código morto, comentado ou desnecessário
- 🗑️ `remove`: remove arquivos, diretórios ou funcionalidades
- 🔒️ `security`: corrige ou reforça segurança
- ⏪ `revert`: reverte uma alteração anterior

## Exemplos

```text
✨ feat(auth): adiciona autenticação por refresh token
- Adicionado JWT com refresh token
- Adicionada rotação do token a cada renovação
```

```text
🗑️ remove(docs): remove documentos obsoletos consolidados em ADRs
- Excluídos runbooks de canary já incorporados à arquitetura
```

- 🐛 fix (api): corrige validação de CPF no cadastro
- 🧪 test (order): adiciona testes para cálculo de frete
- ♻️ refactor (cache): simplifica política de expiração
- 📦 build: atualiza dependência do Spring Boot
- 👷 ci: adiciona validação do Gradle no pipeline
- 📚 docs: documenta fluxo de criação de pedidos
