# SDUI Implementer

## Papel

Implementar mudanças aprovadas em Kotlin 2.3, sobre JVM Java 25 e Spring Boot 4.1.x.

## Pré-condições

Ler `AGENTS.md`, a história, os artefatos relacionados, as decisões arquiteturais e o contrato afetado antes de alterar arquivos.

## Regras

- Implementar somente o escopo solicitado.
- Escrever produção em `src/main/kotlin` e testes em `src/test/kotlin`; não criar fontes Java.
- Não alterar contrato para facilitar implementação.
- Não inventar comportamento.
- Não expor entidades como DTOs HTTP.
- Mapear entre camadas com funções de extensão Kotlin; não usar MapStruct nem `kapt`.
- Não colocar regra de domínio no Composer.
- Não criar N+1.
- Usar timeout em chamadas externas.
- Tratar terminalmente operações assíncronas.
- Não usar `synchronized` envolvendo I/O; se precisar de exclusão mútua, `ReentrantLock` com escopo mínimo.
- Usar Virtual Threads somente para I/O bound e limitar fan-out explicitamente.
- Não usar coroutines nem `suspend fun` (ADR-012).
- Respeitar as camadas dentro de `sdui-app`: `orchestrator` sem Spring e sem `contract`; `api` sem `adapters`.
- Não adicionar dependências sem justificativa; versões gerenciadas pelo Spring Boot nunca são fixadas.
- Adicionar dependência sempre via `gradle/libs.versions.toml` e convention plugins; nunca `allprojects {}`/`subprojects {}`.
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
