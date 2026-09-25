# Padrão de Commit e Governança Git

## 1. Governança Git (Operador Humano)

- **Não executar nenhum comando de git commit ou git push**, nem diretamente nem através de subagentes.
- Todas as alterações, refatorações, documentações e modificações de código devem ser realizadas diretamente no
  workspace, deixando os arquivos prontos para o operador humano inspecionar via `git diff` / `git status`, revisar
  tecnicamente e realizar commits e pushes manualmente conforme sua preferência.

## 2. Padrão de Commit e Proibição de Auto-Atribuição

Quando o operador pedir para commitar ou gerar mensagem de commit:

- **Você não deve adicionar você**: Proibido adicionar a si mesmo, co-autoria (`Co-authored-by`), menção à IA, créditos
  ao modelo/ferramenta, ou executar `git add`/`commit`.
- **Idioma**: Responda sempre em pt-BR.
- **Origem dos dados**: Analise exclusivamente as alterações atuais do `git diff` e gere uma única mensagem de commit
  pronta para uso.

### Formato Obrigatório

```text
<emoji> <tipo>(<escopo opcional>): <descrição no imperativo>
- <Descrições curtas em listas até 10 tópicos>
```

### Regras

- Retorne somente a mensagem do commit, sem explicações, aspas, Markdown ou bloco de código.
- Use uma única linha no cabeçalho/primeira linha, com no máximo 72 caracteres quando possível.
- A descrição deve ser objetiva, iniciar com verbo no imperativo e explicar a mudança principal.
- Não invente alterações, escopos, tickets ou detalhes que não estejam no diff.
- Use escopo apenas se estiver claramente identificável, por exemplo: `auth`, `api`, `checkout`, `database`, `docker` ou
  `docs`.
- Prefira commits atômicos. Se o diff contiver mudanças independentes, escolha a alteração mais relevante e abrangente.
- Não use ponto final.

### Tipos e emojis permitidos:

- ✨ `feat`: adiciona uma nova funcionalidade
- 🐛 `fix`: corrige um bug
- 📚 `docs`: altera documentação
- 🧪 `test`: adiciona, altera ou remove testes
- 📦 `build`: altera dependências, build ou empacotament
- ⚡ `perf`: melhora desempenho
- 🎨 `style`: altera formatação, lint ou aparência sem m
- ♻️ `refactor`: reorganiza código sem alterar comportamento funcional
- 🔧 `chore`: altera configurações, manutenção ou tarefa
- 👷 `ci`: altera pipelines ou integração contínua
- 🗃️ `raw`: altera dados, parâmetros ou arquivos de conf
- 🧹 `cleanup`: remove código morto, comentado ou desnecessário
- 🗑️ `remove`: remove arquivos, diretórios ou funcionali
- 🔒️ `security`: corrige ou reforça segurança
- ⏪ `revert`: reverte uma alteração anterior

### Exemplos:

✨ feat (auth): adiciona autenticação por refresh token

- Adicionado JWT com refresh token

🐛 fix (api): corrige validação de CPF no cadastro

🧪 test (order): adiciona testes para cálculo de fret

♻️ refactor (cache): simplifica política de expiração

📦 build: atualiza dependência do Spring Boot

👷 ci: adiciona validação do Maven no pipeline

📚 docs: documenta fluxo de criação de pedidos
