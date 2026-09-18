# H07 — Redis, singleflight e fallback

## Objetivo

Proteger o P99 do compose com cache Redis na mesma AZ, impedir tempestade de misses e degradar para a última árvore boa.
O cache não personaliza a Home nem transporta PII.

## Critérios de aceite testáveis

- O runtime usa chaves separadas para spec, árvore, projeção de section, última árvore boa e singleflight, conforme o
  plano.
- A chave da árvore inclui surface, platform, schema, app major.minor, hash SHA-256 das capabilities ordenadas e
  channel; não inclui userId.
- A árvore tem TTL entre 30 e 90 segundos; `lastgood` só é gravada após compose 200 e tem retenção longa; mídias
  binárias não são cacheadas.
- Em miss concorrente, uma única execução popula a chave; requisições concorrentes aguardam com timeout definido e
  registram métrica de espera.
- Em Redis indisponível, timeout de dependência ou ausência de spec selecionável, o endpoint retorna 200 com última
  árvore boa, `fallback: true` e motivo de fallback fechado; não retorna 404 para `home` nem 500 por timeout de section.
- Approve e rollback invalidam somente chaves da revisão e da combinação `surface + platform + channel` afetadas; não
  existe flush global.
- O compose em hit atende à meta interna P99 de até 400 ms, aferida em teste de desempenho acordado pelo time.

## Fora de escopo

- Cache por usuário, personalização por domínio, CDN de mídia e pool fixo para I/O.

## Dependências

- H04, H05, H06.

## Ordem sugerida / estimativa

- Fase 3; após o compose funcional.
- Estimativa: 4 dias.

## Referências

- Plano: §§ 4.2 e 8.
- Contrato JSON: `envelope.fallback`, `envelope.fallbackReason`, `envelope.omitted`, `envelope.channel`.
- Dicionário: `documentacao-contrato-sdui-home-v3.docx`, campos de fallback e envelope.
- Skill: `skills/sdui-backend/references/fallback-e-versao.md` e demais diretrizes de cache, versão e degradação.
