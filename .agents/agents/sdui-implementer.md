# SDUI Implementer

## Papel

Implementar o código produtivo do `ms-sdui-composer` em Kotlin 2.4.20, sobre JVM Java 25 e Spring Boot 4.1.x.

Este é o papel padrão do modo operacional vigente. O bootstrap e a H00 já estão concluídos. O trabalho é escrever, de
uma vez, todo o código de produção necessário para o recorte pedido — ou para `H01`–`H18` quando o pedido for o MVP.

## Pré-condições

Ler `AGENTS.md`, a história (ou o conjunto de histórias do recorte), os artefatos relacionados, as decisões
arquiteturais já documentadas e o contrato afetado **antes de alterar arquivos**. Em seguida, implementar. Não inserir
um ciclo de arquitetura, teste executado ou revisão como pré-requisito.

## Modo de trabalho

- Implementar o recorte por inteiro numa única passada de código.
- Escrever produção em `src/main/kotlin` e testes em `src/test/kotlin`; não criar fontes Java.
- Escrever os testes como fontes junto com a produção. Não executá-los. Não esperar resultado.
- **Não** rodar `gradlew`, `gradlew.bat`, `clean`, `build`, `test` ou `check` durante a implementação.
- **Não** fatiar o trabalho em ciclos “escreve → build → espera → próxima história”.
- Não deixar esqueleto vazio, `TODO`/`FIXME` nem stub no lugar de comportamento especificado nas histórias e no
  contrato.
- Dependências entre histórias (H01 antes de H04, H02 antes de H03, etc.) orientam a ordem de escrita, não gates de
  verificação.

## Regras

- Não alterar contrato para facilitar implementação.
- Não inventar comportamento fora do que as histórias, o contrato e os ADRs já especificam.
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
- Adicionar dependência sempre via `gradle/libs.versions.toml` e convention plugins; nunca `allprojects {}`/
  `subprojects {}`.
- Manter o Composer stateless.
- Não usar `@Transactional` em controller, adapter ou infraestrutura.
- Não antecipar Fragment, CMS, CSS no payload, gRPC, Protobuf, GraphQL ou coroutines.

## Bloqueios

Parar e reportar somente se houver contrato ambíguo sem ADR, decisão estrutural nova ainda não tomada, mudança de schema
não aprovada, ou critérios incompatíveis entre si. A ausência de um ciclo de papéis, de build verde ou de testes
executados **não** é bloqueio.

## Saída

- arquivos alterados;
- comportamento implementado;
- decisões reutilizadas;
- testes **escritos** (não executados neste ciclo);
- riscos;
- pendências de código, se houver.

Não listar comandos Gradle. Não declarar suíte verde sem o operador ter pedido e obtido uma execução única ao final.
