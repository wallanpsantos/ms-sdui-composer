# H16 — Matriz de compatibilidade e compose Android

## Objetivo

Validar que o runtime compõe a Home Android com o mesmo pipeline de iOS —
`Negotiate → Select → Filter → Hydrate → Envelope` — preservando isolamento de plataforma. A finalidade é provar
compatibilidade de binário/schema/componentes e comportamento resiliente antes de expor usuários Android.

## Critérios de aceite testáveis

- `GET /v1/surfaces/home` com `Client-Platform: android` devolve 200 com envelope Android e fixture Android quando a
  combinação de headers é compatível.
- O compose Android preserva a ordem do skeleton e das sections após o filtro e não consulta qualquer domínio de
  negócio.
- A matriz de testes cobre `platform × appVersion × OS-Version × schema × capabilities × channel`, incluindo versões
  Android mínima, intermediária e máxima definidas na H15.
- Type ou `typeVersion` não suportado pelo binário Android é omitido e registrado em `envelope.omitted`; a Home continua
  respondendo sem 4xx.
- Props adicionais opcionais em type conhecido são toleradas pelo cliente conforme a regra de compatibilidade; mudança
  incompatível exige nova `typeVersion` e spec Android compatível.
- `ETag` e `If-None-Match` retornam 304 para a mesma representação Android e retornam 200 após mudança de pointer ou
  revisão Android.
- O cache da árvore Android usa chave com `platform: android`, schema, `appMajorMinor`, hash de capabilities ordenadas e
  channel; não colide com a árvore iOS nem contém userId.
- Falha de section, Redis indisponível, timeout de dependência ou ausência de spec Android elegível devolvem a última
  árvore boa Android com fallback explícito, sem usar árvore iOS e sem 404 para `home`.
- O compose Android em cache hit atende à meta interna de P99 até 400 ms no cenário de teste acordado.

## Fora de escopo

- Publicação de canary Android, promoção para stable e rollback operacional.
- Alterar o contrato iOS, o conjunto de types ou o Design System Android.

## Dependências

- H05 — Hydrate e Envelope do compose.
- H06 — ETag, 304 e rate limit do compose.
- H07 — Redis, singleflight e fallback.
- H12 — Validação integrada e gates de release.
- H15 — Spec, pointer e targeting Android.

## Ordem sugerida / estimativa

- Fase 2/4 Android; executar depois de existir spec Android publicável e antes de canary.
- Estimativa: 4 dias.

## Referências

- Plano: §§ 3, 4.2, 5.2, 5.4, 6.3, 8 e 15 (Fases 0, 2 e 4).
- Contrato JSON: `envelope`, `skeleton`, `sections`, `envelope.etag`, `envelope.fallback`, `envelope.fallbackReason` e
  `envelope.omitted` como estrutura de referência.
- Dicionário: `documentacao-contrato-sdui-home-v3.docx`, regras do payload Android aprovado.
- Skill: `skills/sdui-backend/`, compatibilidade, filtro, cache, singleflight, fallback e envelope.
