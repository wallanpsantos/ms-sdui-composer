# H02 — Modelo Mongo e índices da Home

## Objetivo

Criar a fonte de verdade documental da configuração SDUI e os índices mínimos que sustentam seleção, governança e
rollback. O MVP adota specs monoplataforma, com iOS como primeiro documento publicado.

## Critérios de aceite testáveis

- Existem coleções para catálogo, skeletons, specs, pointers, publish requests, diffs, audit log e idempotência, com as
  chaves e imutabilidade previstas no plano.
- `specs` e `skeletons` publicados não podem ser alterados; somente pointer, publish request pendente e idempotência são
  mutáveis nos respectivos ciclos.
- Há índice único para pointer por `surface + platform + channel`, para revisão de spec e para diff entre revisões.
- Faixas de versão persistem ordinais mínimo e máximo; seleção não compara semver como string e confirma a comparação na camada de domínio, sem comparar semver como string.
- O modelo suporta `stable`, `canary` e `internal`, sem confundir channel com feature flag.
- O seed iOS criado a partir da fixture é um spec monoplataforma; não há overlay ou motor JSON Patch no MVP.
- Não há árvore hidratada por usuário no Mongo nem payload regulado/Pii armazenado como cache de compose.

## Fora de escopo

- DocumentDB como decisão definitiva, overlay base+patch e sincronização com domínio.
- Endpoints admin, publish ou compose.

## Dependências

- H00.

## Ordem sugerida / estimativa

- Fase 1; em paralelo com H01.
- Estimativa: 3 dias.

## Referências

- Plano: §§ 5.1, 7.2, 7.3, 7.4 e 7.5.
- Contrato JSON: `envelope.specRevisionId`, `envelope.targeting`, `skeleton`, `sections`.
- Skill: `skills/sdui-backend/`, fonte de verdade, revisões imutáveis e pointers.
