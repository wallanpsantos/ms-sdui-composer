# ms-sdui-composer

Serviço orquestrador e compositor Server-Driven UI (SDUI) para aplicações móveis (iOS e Android). O `ms-sdui-composer`
atua como **Presentation + Application Controller + BFF de UI**, compondo árvores de UI hidratadas e compatíveis a
partir de especificações versionadas, contexto do cliente e capabilities homologadas.

## Modo atual

O bootstrap e a H00 (contrato + fixture) estão concluídos. O foco é a **implementação direta e completa** do código
produtivo de `H01`–`H18`. Agentes e contribuidores escrevem produção e testes como fontes, sem ciclos repetitivos de
`gradlew` e sem esperar a suíte para continuar. A memória operacional está em `AGENTS.md`.

## Stack Tecnológica

- **Linguagem:** Kotlin 2.4.20
- **Plataforma:** JVM com Java 25 LTS via Gradle Toolchain
- **Framework:** Spring Boot 4.1.1 (Spring Framework 7.0.x via BOM)
- **Build:** Gradle 9.7.1 (Kotlin DSL, convention plugins em `build-logic/`, version catalog)
- **JSON:** Jackson 3 (`tools.jackson`)
- **Arquitetura & Testes:** JUnit Jupiter, AssertJ, ArchUnit 1.5.0
- **Persistência:** MongoDB 8.3+ (fonte da verdade de specs)
- **Cache:** Redis (armazenamento e fallback de árvores de UI)
- **Concorrência:** Spring MVC + Virtual Threads

## Estrutura Multi-Módulo

- `sdui-contract`: DTOs de contrato público, envelopes de resposta e modelos do catálogo SDUI.
- `sdui-core`: Regras puras de domínio, targeting ordinal, invariantes e políticas de composição (zero dependências de
  frameworks).
- `sdui-app`: Orquestrador de composição (`orchestrator`), adaptadores (`adapters`) e controllers HTTP (`api`).
- `sdui-bootstrap`: Módulo executável Spring Boot contendo a inicialização e configuração de runtime.
- `sdui-integration-test`: Testes arquiteturais (ArchUnit) e suíte de testes de integração end-to-end.

## Comandos de Build e Execução

Para o operador humano, quando quiser verificar o working tree. **Não** fazem parte do ciclo de implementação dos
agentes; se forem pedidos, correm uma única vez no final.

### Verificação de Versões e Ferramental

```powershell
java -version
.\gradlew.bat --version
```

### Compilação e Validação Completa

```powershell
.\gradlew.bat clean build --warning-mode=fail
```

## Governança e Memória Operacional

- `AGENTS.md`: Memória operacional com regras de negócio, grafo de módulos e convenções.
- `.agents/agents/`: Instruções operacionais para papéis especializados.
- `docs/`: Documentação arquitetural, fluxos de integração e especificações de histórias.
- `docs/adr/`: Registros de Decisões Arquiteturais (ADRs).
