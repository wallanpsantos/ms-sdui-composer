---
name: sdui-perf
description: Use antes de qualquer mudança de performance no ms-sdui-composer, para medir antes e depois com o perfHarness ou o loadTest. Não use para correção funcional sem objetivo de desempenho.
---

# Medir antes de otimizar

Fonte: `AGENTS.md` §23.14, `sdui-app/build.gradle.kts` (tasks `perfHarness` e `loadTest`), `PerfHarness.kt` e
`HttpLoadGenerator.kt` em `sdui-app/src/test/kotlin/br/com/empresa/sdui/`. As duas tasks executam Gradle: só rodam a
pedido do operador (`AGENTS.md` › Modo operacional).

## Ferramentas

- **Harness in-process:** `.\gradlew.bat :sdui-app:perfHarness -Pscenarios=m3,m6`. Sem `-Pscenarios`, roda todos.
  Cenários: `m1` cardinalidade de métricas, `m2` idempotência sob saturação, `m3` custo de seleção com 0, 1k e 10k
  revisões residentes, `m4` singleflight, `m5` hidratação pass-through, `m6` poda do cache de árvore em 10.000
  entradas, `m7` listagem administrativa de specs, `m8` mapeamento versus serialização no acerto, `audit` append de
  auditoria no teto. Imprime a linha de ambiente (JVM, CPUs, heap) e, por cenário, ns/op (mediana de 5 execuções
  depois do aquecimento, com mínimo e máximo) e bytes alocados por operação.
- **Carga HTTP:** `.\gradlew.bat :sdui-app:loadTest -PbaseUrl=http://localhost:8080` contra uma instância no ar.
  Laço fechado com 32 workers em virtual threads, 10 s de aquecimento e 60 s de medição; ajustes pelas propriedades de
  sistema `load.concurrency`, `load.warmupSeconds` e `load.durationSeconds`. Cenário e metas em
  `sdui-app/src/test/resources/load/compose-hit-p99.yaml`.

## Passos

1. Escolher o cenário que exercita o caminho a mudar; se nenhum exercita, escrever o cenário antes da otimização.
2. Medir **antes**, no mesmo ambiente em que se medirá depois; guardar a linha de ambiente.
3. Aplicar a mudança.
4. Medir **depois**, mesmo cenário e mesmo ambiente.
5. Comparar medianas considerando mínimo e máximo. Ganho dentro do ruído é recusado e registrado como tal.
6. Registrar antes, depois, ambiente e conclusão na seção "Medições" do documento da tarefa em `docs/tasks/`.
