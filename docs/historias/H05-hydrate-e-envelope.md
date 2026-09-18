# H05 — Hydrate e Envelope do compose

## Objetivo

Completar o pipeline `Negotiate → Select → Filter → Hydrate → Envelope` e entregar a árvore renderizável da Home. A
hidratação opera apenas projeções de apresentação e não consulta domínio de negócio.

## Critérios de aceite testáveis

- Para o seed iOS e headers equivalentes aos da fixture, `GET /v1/surfaces/home` retorna 200 e corpo semanticamente
  idêntico à fixture canônica, exceto valores operacionais variáveis permitidos, como `generatedAt`.
- A resposta sempre contém `envelope`, `skeleton` e `sections`; a ordem de slots e sections é preservada após o filtro.
- O envelope preenche surface, platform, schema, revisão, skeleton e hash, locale, channel, client, targeting,
  analytics, fallback, fallbackReason e omitted conforme contrato.
- Hidratação usa fan-out controlado, timeout por section e não realiza N+1 GET por widget; section que falhar é omitida
  e a Home permanece respondível.
- Valores monetários enviados são strings já formatadas, quando existirem; não são expostos valores brutos de domínio ou
  dados regulados.
- A resposta não inclui atributos de UI pertencentes ao Design System nativo, tais como dimensões, cores, margens,
  tipografia, raio, animação ou haptics.
- Não há 404 para a surface `home` por ausência de targeting vigente; esse cenário segue o fluxo de fallback da H07.

## Fora de escopo

- Cache Redis, ETag/304, rate limit, fallback efetivo e endpoints de governança.

## Dependências

- H01, H03, H04.

## Ordem sugerida / estimativa

- Fase 2; após Select/Filter.
- Estimativa: 3 dias.

## Referências

- Plano: §§ 1, 3, 4.2, 6.3 e 6.4.
- Contrato JSON: documento completo; em especial `envelope`, `skeleton` e `sections`.
- Dicionário: `documentacao-contrato-sdui-home-v3.docx`, definição campo a campo do payload v3.
- Skill: `skills/sdui-backend/`, compose, degradação por section e envelope fechado.
