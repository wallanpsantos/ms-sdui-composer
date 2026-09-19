# ms-sdui-composer

Serviço orquestrador e compositor Server-Driven UI (SDUI) para aplicações móveis (iOS e Android). O `ms-sdui-composer`
atua como **Presentation + Application Controller + BFF de UI**, compondo árvores de UI hidratadas e compatíveis a
partir de especificações versionadas, contexto do cliente e capabilities homologadas.

## Status Atual

O escopo do **MVP (H00 a H18)** está integralmente implementado, testado e validado. A base passou por auditoria
técnica multidimensional (`agent-skills:code-review-and-quality`) com Quality Gate **APROVADO (PASS)** e otimização
de performance no hot path (`agent-skills:performance-optimization`).

As 7 correções de resiliência e concorrência (incluindo o reparo crítico do Singleflight, proteção contra overflow de
SemVer e eliminação da dupla serialização JSON) foram aplicadas e validadas. A memória operacional consolidada está
em `AGENTS.md` e em `docs/06-memoria-projeto-ms-sdui-composer.md`.

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

- `AGENTS.md`: Memória operacional viva com regras de negócio, grafo de módulos e convenções para agentes.
- `docs/README.md`: Índice sequencial completo da documentação do projeto.
- `docs/06-memoria-projeto-ms-sdui-composer.md`: Memória arquitetural e operacional consolidada do serviço.
- `docs/07-relatorio-revisao-e-otimizacao-performance.md`: Relatório detalhado da auditoria de qualidade e otimização de performance.
- `.agents/agents/`: Instruções operacionais para papéis especializados.
- `docs/`: Documentação arquitetural, fluxos de integração e especificações de histórias (`H00`–`H18`).
- `docs/adr/`: Registros de Decisões Arquiteturais (ADRs).
- `sdui-app/src/test/resources/load/compose-hit-p99.yaml`: Contrato versionado de carga e metas de SLO.
