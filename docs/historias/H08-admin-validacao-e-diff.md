# H08 — Admin: rascunho, validação e diff

## Objetivo

Oferecer o fluxo administrativo separado do runtime para criar e inspecionar rascunhos de catálogo, skeleton e spec. A
validação profunda ocorre antes de publish; o caminho quente não remonta a Home.

## Critérios de aceite testáveis

- Endpoints administrativos existem somente sob `/admin/v1/**` e exigem identidade corporativa e papel compatível.
- O admin permite consultar catálogo, skeleton e histórico de revisões; permite criar rascunho de skeleton/spec e listar
  specs por plataforma/channel.
- Rascunho falha se placement referencia slot inexistente, type fora de `allowedTypes`, props incompatíveis com o
  catálogo ou action fora do contrato fechado.
- Validação rejeita atributo de CSS/pixel e dados regulados no payload de UI.
- A validação impede edição de spec e skeleton `PUBLISHED`; uma mudança cria nova revisão.
- O sistema produz diff rastreável entre revisão N-1 e N antes de abertura de publish request.
- O MVP cria e valida spec monoplataforma iOS; não usa base compartilhada com overlay JSON Patch.
- A validação de rascunho recusa spec em que um slot `required: true` possa ficar vazio para qualquer combinação de
  `platform × faixa de appVersion × capabilities` alcançada pelo targeting daquela revisão. A verificação é em publish,
  nunca em runtime: o compose não decide se a Home ficou aceitável.
- A validação recusa type cujo nome denote layout ou primitiva genérica, conforme a regra do catálogo (H03).
- O diff N-1 → N marca explicitamente quando um slot `required` muda de ocupação, para que o checker veja a mudança sem
  reconstruir o targeting de cabeça.
- A validação recusa spec em que as `props` de uma section referenciem o `id` de outra section, o índice de um slot ou a
  posição da própria section na ordem. Uma section precisa ser renderizável a partir apenas das próprias `props` e
  `actions`.
- A validação recusa `props` cujo valor dependa de outra section ter sido renderizada — por exemplo, texto que mencione
  "o cartão acima" ou contadores que só fazem sentido com um vizinho presente. A verificação exequível é a estrutural
  (sem referência cruzada); a semântica fica na revisão do checker, e o diff precisa deixá-la visível.

## Fora de escopo

- Aprovação, movimentação de pointer, rollback e UI web de console.

## Dependências

- H02, H03.

## Ordem sugerida / estimativa

- Fase 4; pode evoluir em paralelo à H07 depois da modelagem estar estável.
- Estimativa: 4 dias.

## Referências

- Plano: §§ 4.3, 6.3, 7.2 e 7.3.
- Contrato JSON: `skeleton`, `sections[].slot`, `sections[].type`, `props`, `actions`, `analytics`.
- Dicionário: `documentacao-contrato-sdui-home-v3.docx`, dicionário dos campos de spec e resposta.
- Skill: `skills/sdui-backend/`, validação em publish e imutabilidade de revisão.
