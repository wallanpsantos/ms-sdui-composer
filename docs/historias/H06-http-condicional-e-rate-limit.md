# H06 — ETag, 304 e rate limit do compose

## Objetivo

Adicionar semântica HTTP de revalidação e proteção de capacidade ao endpoint de compose, sem alterar o contrato JSON

200. O first paint permanece responsabilidade do cache local do app.

## Critérios de aceite testáveis

- A resposta 200 emite ETag coerente com revisão, plataforma e schema da árvore retornada.
- Um `If-None-Match` igual à ETag da representação selecionada retorna 304 sem corpo de compose.
- Alteração de pointer ou revisão selecionada torna a representação revalidável como nova, não devolvendo 304 para a
  árvore anterior.
- O rate limit usa token bucket por identidade e plataforma, como determinado pelo plano, e não deriva chave de dados de
  domínio do payload.
- Excesso de requisições é limitado sem transformar falha de uma section em erro de rate limit e sem expor campos fora
  do contrato.
- Testes verificam 200 inicial, 304 subsequente e 200 após troca de revisão.

## Fora de escopo

- Cache de árvore Redis e fallback de última árvore boa.
- Controle de acesso administrativo.

## Dependências

- H01, H05.

## Ordem sugerida / estimativa

- Fase 2; após o endpoint devolver o envelope.
- Estimativa: 2 dias.

## Referências

- Plano: § 4.2, regras do envelope; § 8, proteção de runtime.
- Contrato JSON: `envelope.etag`, `envelope.specRevisionId`, `envelope.platform`, `envelope.schemaVersion`.
- Skill: `skills/sdui-backend/`, cache/revalidação HTTP e resiliência do runtime.
