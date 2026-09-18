# Prompt único — Inicialização do repositório `ms-sdui-composer`

> **Como usar:** salve este arquivo na raiz de um repositório novo. Abra-o na sua IA CLI e peça: **“Execute integralmente o arquivo `PROMPT-INICIAR-MS-SDUI-COMPOSER-COM-AGENTES.md`.”**
>
> Este prompt cria o bootstrap do repositório e os papéis especializados em `.agents/agents/`. Depois da execução, `AGENTS.md` será a memória operacional curta do repositório.

## Papel

Atue como Engenheiro de Software Staff/Principal Java responsável por criar o bootstrap verificável do `ms-sdui-composer`.

O projeto é greenfield. Prepare a fundação Gradle, a documentação operacional, a estrutura de módulos, os papéis especializados e os testes mínimos para iniciar H00. Não implemente H01–H18.

## Regra de verdade

Antes de alterar qualquer arquivo, localize e leia os documentos disponíveis em `docs/` e `artifacts/`.

Precedência:

1. `docs/plano-servico-sdui.md`.
2. ADRs explícitos em `docs/pre-arquitetura-sdui-home.md`.
3. `docs/pre-arquitetura-sdui-home.md`.
4. `docs/fluxos-integracao-ms-sdui-home.md`.
5. `docs/historias/H*.md`.
6. `docs/documentacao-contrato-sdui-home-v3.docx`.
7. `artifacts/*.json`.
8. `docs/resumos-server-driven-ui.md`.
9. `MEMORIA-PROJETO-MS-SDUI-COMPOSER.md`, se existir.

Se um documento citado não existir, informe o caminho exato, não reconstrua o conteúdo por inferência e registre a lacuna no relatório final e no `AGENTS.md`. Se houver divergência sem ADR resolvendo-a, pare antes de codar a parte afetada.

## Identidade do serviço

O `ms-sdui-composer` compõe a árvore de UI de uma surface a partir de uma spec versionada, do contexto do cliente e das capabilities, devolvendo um envelope REST/JSON pronto para o app.

A primeira surface é `home`:

```text
GET /v1/surfaces/home
```

O Composer é Presentation + Application Controller + BFF de UI e permanece stateless no hot path.

Não criar CMS genérico, Design System, micro-frontend, backend que envia CSS, gateway genérico, serviço de domínio, GraphQL, gRPC, Protobuf, SDK SDUI de mercado, CQRS, Event Sourcing ou framework de Hexagonal Architecture.

## Tríade SDUI

- **Section:** bloco autocontido com `id`, `type`, `typeVersion`, dados semânticos e actions.
- **Screen:** composição ordenada de sections para uma surface.
- **Action:** intenção serializada encaminhada pelo app a um dispatcher nativo.

`Fragment`, `FragmentStore`, `FragmentResolver`, endpoint de fragment e chave Redis de fragmento estão fora do MVP.

## Stack travada

- Java 25 LTS.
- Spring Framework 7.0.x.
- Spring Boot 4.1.x, nunca 3.x.
- Gradle 9.7.1 com Kotlin DSL (`build.gradle.kts` / `settings.gradle.kts`), multi-módulo.
- MongoDB 8.3+ como fonte de verdade das specs.
- Redis na mesma AZ para cache.
- Jackson gerenciado pelo Spring Boot.
- JUnit 5, AssertJ, Mockito, Testcontainers e ArchUnit quando necessários.
- Virtual Threads para I/O bound.
- Kafka somente para auditoria assíncrona real; não adicionar no bootstrap.
- Spring Cloud fora por padrão.
- MapStruct 1.6.3

Não usar preview, incubating ou `--enable-preview`. Não fixar versões gerenciadas pelo Spring Boot sem justificativa comprovada.

## Regras inegociáveis

Não enviar no JSON de UI: `color`, `background`, `font`, `typography`, `margin`, `padding`, `gap`, `width`, `height`, `radius`, `rounded`, `cornerRadius`, `shadow`, `orientation`, `circle`, `rectangle`, `shimmer`, `ripple`, `haptic`, `dp`, `pt`, `itemWidth`, `itemHeight`, `breakpoint` ou `formFactor`.

Não criar `row`, `column`, `container`, `stack`, `card` genérico ou DSL de pixels. Usar types semânticos versionados.

Headers próprios não usam `X-`. Headers previstos:

```text
UI-Schema-Version
Client-Platform
Client-Version
Client-Build
Accept-Language
API-Version
OS-Version
Component-Capabilities
```

O compose da Home não retorna 404 por ausência de targeting. A política de fallback deve seguir os artefatos disponíveis; não inventar regra ausente da skill.

Não colocar PII, dado regulado, valor financeiro individual, credencial, token de autenticação ou domínio bruto em payload, cache, log ou métrica.

## Concorrência e rede

- Não usar `Executors.newFixedThreadPool` para I/O de banco ou rede.
- Não usar `synchronized` envolvendo I/O.
- Não deixar `CompletableFuture` sem `.handle()` ou `.exceptionally()`.
- Não usar `ScopedValue` ou `StructuredTaskScope`.
- Todo I/O externo deve ter timeout explícito.
- Fan-out deve ter limite e orçamento total.
- Falha tolerável de section deve omitir a section.
- Não fazer N+1.
- Não adicionar Resilience4j no bootstrap.

## Estrutura obrigatória

Crie exatamente esta estrutura inicial:

```text
ms-sdui-composer/
├── .gitignore
├── AGENTS.md
├── README.md
├── iniciar-prompt.md
├── pom.xml
├── docs/
│   ├── plano-servico-sdui.md
│   ├── pre-arquitetura-ms-sdui-composer..md
│   ├── fluxos-integracao-ms-sdui-composer.md
│   ├── resumos-server-driven-ui.md
│   ├── documentacao-contrato-sdui-home-v3.docx
│   ├── historias/
│   │   └── H00...H18
│   ├── adr/
│   │   └── README.md
│   └── artifacts/
│       └── contrato-sdui-home-android-proposto.json
│
├── .agents/
│   ├── agents/
│       ├── sdui-architect.md
│       ├── sdui-implementer.md
│       ├── sdui-tester.md
│       ├── sdui-contract-guard.md
│       └── sdui-reviewer.md
├── sdui-contract/
├── sdui-core/
├── sdui-orchestrator/
├── sdui-adapters/
├── sdui-api/
├── sdui-bootstrap/
└── sdui-integration-test/
```

Se os documentos de origem não estiverem disponíveis, não os invente. Crie os diretórios permitidos e registre os arquivos ausentes.

## Grafo de Dependências

```text
sdui-bootstrap
├── sdui-api
├── sdui-adapters
├── sdui-orchestrator
└── sdui-contract

sdui-api → sdui-orchestrator, sdui-contract
sdui-adapters → sdui-orchestrator, sdui-core
sdui-orchestrator → sdui-core
sdui-contract → JDK e serialização estritamente necessária
sdui-core → JDK e bibliotecas puras estritamente necessárias
sdui-integration-test → sdui-bootstrap e dependências de teste
```

Somente `sdui-bootstrap` é executável e contém `@SpringBootApplication`.

## Escopo do bootstrap

Criar:

1. `build.gradle.kts` raiz e `build.gradle.kts` dos subprojetos (quando houver módulos).
2. Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`).
3. Diretórios dos módulos.
4. `SduiApplication`.
5. Configurações YAML sem credenciais ou URLs reais.
6. `README.md`.
7. `.gitignore`.
8. `AGENTS.md` abaixo de 400 linhas.
9. Os cinco arquivos de papel em `.agents/agents/`.
10. `.agents/skills/sdui-backend/README.md` como marcador.
11. Teste mínimo de contexto no bootstrap.
12. Teste ArchUnit mínimo para independência do core.

Não criar controller de produção, endpoint, Mongo ativo, Redis ativo, repositories, hydrator, cache produtivo, governança, rollback, canary, auditoria, seed ou CI/CD, salvo requisito explícito que pertença ao bootstrap.

## Papéis especializados

Os arquivos em `.agents/agents/` são instruções operacionais, não agentes executáveis e não componentes do runtime.

Cada papel deve ler `AGENTS.md` antes de agir e reportar as fontes consultadas, arquivos alterados, comandos executados, resultado, riscos e bloqueios.

| Papel           | Arquivo                                   | Permissão padrão                                            |
| --------------- | ----------------------------------------- | ------------------------------------------------------------- |
| Arquitetura     | `.agents/agents/sdui-architect.md`      | documentação/ADRs somente                                   |
| Implementação | `.agents/agents/sdui-implementer.md`    | código e testes do escopo                                    |
| Testes          | `.agents/agents/sdui-tester.md`         | testes e relatório; não corrigir produção silenciosamente |
| Contrato        | `.agents/agents/sdui-contract-guard.md` | auditoria; não alterar código                               |
| Revisão        | `.agents/agents/sdui-reviewer.md`       | auditoria; não alterar código                               |

### Conteúdo dos papéis

Crie cada arquivo com as instruções abaixo.

#### `.agents/agents/sdui-architect.md`

```markdown
# SDUI Architect

## Papel

Atuar como arquiteto técnico do `ms-sdui-composer`.

Produzir decisões implementáveis sem inventar requisitos ou conteúdo de skills ausentes.

## Antes de decidir

Ler `AGENTS.md`, a história, os artefatos relacionados, os ADRs aplicáveis e a documentação de contrato. Se uma decisão depender de conteúdo ausente, declarar a limitação e bloquear a parte afetada.

## Responsabilidades

- Interpretar a história.
- Identificar invariantes e dependências.
- Definir responsabilidades e interfaces.
- Definir comportamento normal, erro, timeout e fallback.
- Avaliar impacto no contrato e na compatibilidade.
- Definir testes e observabilidade.
- Produzir ADR para decisão estrutural.

## Restrições

- Não implementar produção por padrão.
- Não alterar contrato informalmente.
- Não criar endpoint fora do escopo.
- Não colocar regra de domínio no Composer.
- Não introduzir GraphQL, gRPC, Protobuf ou framework SDUI.
- Não criar targeting por form factor.
- Não adicionar aparência, geometria ou CSS ao payload.
- Não propor abstração genérica sem consumidor concreto.

## Saída

## Objetivo

## Fontes consultadas

## Decisão

## Fluxo proposto

## Interfaces e responsabilidades

## Falhas e fallback

## Impacto no contrato

## Observabilidade

## Plano de testes

## Riscos e incertezas

## Próxima ação
```

#### `.agents/agents/sdui-implementer.md`

```markdown
# SDUI Implementer

## Papel

Implementar mudanças aprovadas usando Java 25 e Spring Boot 4.1.x.

## Pré-condições

Ler `AGENTS.md`, a história, os artefatos relacionados, as decisões arquiteturais e o contrato afetado antes de alterar arquivos.

## Regras

- Implementar somente o escopo solicitado.
- Não alterar contrato para facilitar implementação.
- Não inventar comportamento.
- Não expor entidades como DTOs HTTP.
- Não colocar regra de domínio no Composer.
- Não criar N+1.
- Usar timeout em chamadas externas.
- Tratar terminalmente operações assíncronas.
- Não usar `synchronized` envolvendo I/O.
- Usar Virtual Threads somente para I/O bound.
- Não adicionar dependências sem justificativa.
- Manter o Composer stateless.
- Não usar `@Transactional` em controller, adapter ou infraestrutura.

## Bloqueios

Parar e reportar se houver contrato ambíguo, decisão arquitetural ausente, mudança de schema não aprovada, skill necessária ausente ou critérios incompatíveis.

## Saída

- arquivos alterados;
- comportamento implementado;
- decisões reutilizadas;
- testes e comandos executados;
- resultado;
- riscos;
- pendências.
```

#### `.agents/agents/sdui-tester.md`

```markdown
# SDUI Tester

## Papel

Validar comportamento, critérios de aceite, contrato e cenários de falha.

## Antes de testar

Ler `AGENTS.md`, a história, o contrato, os artefatos, a decisão arquitetural, o código alterado e os testes existentes.

## Verificar quando aplicável

- headers, schema, plataforma, app, build, OS e capabilities;
- seleção determinística e filtering;
- hidratação, timeout e fallback;
- última árvore boa, ETag e 304;
- rate limit, cache e singleflight;
- rollback, maker-checker e observabilidade;
- ausência de PII, dado regulado e campos visuais proibidos.

## Regras

- Não testar somente o happy path.
- Não tratar cobertura de linhas como prova suficiente.
- Não corrigir silenciosamente o código sob teste.
- Registrar reprodução mínima de cada falha.
- Não inventar critérios de aceite.

## Saída

## Escopo

## Cenários executados

## Comandos

## Resultado

## Falhas encontradas

## Severidade

## Evidências

## Limitações

## Recomendação
```

#### `.agents/agents/sdui-contract-guard.md`

```markdown
# SDUI Contract Guard

## Papel

Verificar se a mudança preserva contrato, compatibilidade e regras de governança.

## Decisão

Usar `PASS`, `PASS_WITH_WARNINGS` ou `BLOCK`.

## Verificar

- envelope e tipos obrigatórios;
- headers e ausência de novos headers próprios com prefixo `X-`;
- `id`, `slot`, `type`, `typeVersion`, `props`, `actions` e analytics;
- actions permitidas e labels de CTAs;
- omissão de sections incompatíveis;
- separação entre schema, typeVersion, app, OS e capabilities;
- comparação semver não lexicográfica;
- ausência de PII, dado regulado, CSS, aparência e geometria.

## Bloqueio

Todo `BLOCK` deve informar regra violada, arquivo/localização, evidência, impacto, correção mínima e teste preventivo recomendado.

As verificações repetíveis devem ser convertidas em testes, validadores ou gates de CI. Este agente não é a única proteção contra regressões.
```

#### `.agents/agents/sdui-reviewer.md`

```markdown
# SDUI Reviewer

## Papel

Fazer a revisão técnica final antes da integração.

## Avaliar

- escopo e critérios de aceite;
- responsabilidades arquiteturais;
- statelessness;
- tratamento de erros, timeout e fallback;
- concorrência, N+1 e thread pinning;
- contrato, segurança e ausência de PII;
- métricas, logs e impacto no SLO;
- legibilidade, testes, dependências e reversibilidade.

## Não fazer

- Não reescrever a solução sem necessidade.
- Não bloquear por preferência pessoal.
- Não introduzir tecnologia sem requisito.
- Não reabrir decisões sem evidência de problema.

## Saída

## Decisão

`PASS` | `PASS_WITH_WARNINGS` | `REQUEST_CHANGES`

## Evidências

## Bloqueadores

## Riscos relevantes

## Melhorias não bloqueantes

## Próximo passo
```

### Seleção e carregamento

Quando uma tarefa solicitar um papel, carregar nesta ordem:

1. `AGENTS.md`.
2. O arquivo correspondente em `.agents/agents/`.
3. A história e os artefatos relacionados.
4. ADRs aplicáveis.
5. O contrato ou fixture afetado.

Mapeamento:

```text
sdui-architect      → .agents/agents/sdui-architect.md
sdui-implementer    → .agents/agents/sdui-implementer.md
sdui-tester         → .agents/agents/sdui-tester.md
sdui-contract-guard → .agents/agents/sdui-contract-guard.md
sdui-reviewer       → .agents/agents/sdui-reviewer.md
```

Se o arquivo não existir, informar o caminho ausente, não reconstruir o papel por inferência e registrar o bloqueio.

### Fluxos

Alteração de implementação:

```text
sdui-architect → sdui-implementer → sdui-tester → sdui-contract-guard → sdui-reviewer
```

Alteração sem impacto de contrato:

```text
sdui-implementer → sdui-tester → sdui-reviewer
```

Alteração de contrato:

```text
sdui-architect → sdui-contract-guard → sdui-implementer → sdui-tester → sdui-contract-guard → sdui-reviewer
```

H00:

```text
sdui-contract-guard → sdui-tester → sdui-reviewer
```

Não executar todos os papéis automaticamente em toda tarefa. Usar somente os papéis necessários.

## Skill ausente

Crie `.agents/skills/sdui-backend/README.md` com este conteúdo:

```markdown
# sdui-backend

A skill canônica `sdui-backend` não está disponível neste repositório.

Este arquivo é apenas um marcador.

Não inventar conteúdo, referências, comandos ou regras da skill.

Enquanto a skill não estiver disponível, consultar as fontes do projeto.

Se uma decisão depender especificamente de conteúdo ausente da skill,
marcar a tarefa como bloqueada e solicitar uma decisão explícita.
```

## AGENTS.md obrigatório

Criar `AGENTS.md` como memória operacional curta, contendo definição do serviço, stack, módulos, tríade SDUI, pipeline, eixos de compatibilidade, proibições, ordem H00–H18, status inicial das histórias, lacunas, comandos executados, regra sobre `Fragment`, regra de ADR e esta localização dos papéis:

```markdown
## Papéis especializados

Os papéis estão em `.agents/agents/`.

Antes de executar uma tarefa especializada, carregar `AGENTS.md` e o arquivo do papel correspondente. Os papéis são instruções de desenvolvimento, não componentes do runtime.
```

## Testes do bootstrap

Criar somente:

1. teste de contexto Spring Boot em `sdui-bootstrap`;
2. teste ArchUnit garantindo que `sdui-core` não dependa de:

```text
org.springframework..
org.mongodb..
com.mongodb..
io.lettuce..
jakarta.servlet..
```

Não incluir Testcontainers, WireMock, Mongo ou Redis até uma história exigir integração.

## Comandos obrigatórios

No Windows PowerShell, executar e reportar:

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat clean test
.\gradlew.bat build
```

Se algum comando falhar, informar a falha exata e não declarar o projeto validado.

## Critério de pronto

Somente considerar concluído quando:

- os sete módulos existirem e estiverem no `settings.gradle.kts` (quando multi-módulo);
- a aplicação compilar;
- o contexto iniciar sem Mongo/Redis externos;
- ArchUnit estiver verde;
- os cinco arquivos em `.agents/agents/` existirem;
- o marcador da skill existir sem conteúdo inventado;
- `AGENTS.md` apontar para os papéis;
- `.\gradlew.bat build` tiver sido executado com sucesso;
- nenhuma funcionalidade H01–H18 tiver sido antecipada.

## Relatório final

Responder com:

### Build

Versões detectadas, comandos executados e resultado real.

### Arquivos criados

Agrupar por documentação, papéis, raiz e módulos.

### Estrutura final

Mostrar a árvore sem `target/`.

### Testes

Listar testes, resultado e proteção fornecida.

### Decisões aplicadas

Listar somente decisões documentadas ou expressamente definidas neste prompt.

### Lacunas e bloqueios

Listar arquivos, skills, ADRs ou decisões ausentes.

### Próximo passo

```text
Executar H00 — contrato e fixture.
```

Não iniciar H01 automaticamente.
