# H12 — Validação integrada e gates de release

## Objetivo

Consolidar a validação automatizada do MS antes de qualquer canary iOS. Provar o contrato de fio, a seleção
determinística, a degradação controlada, a governança e os limites de performance do compose, sem consultar domínios de
negócio. Esta história converte a matriz de testes do plano em suíte executável e transforma o build em porta de entrada
do rollout.

## Critérios de aceite testáveis

### Contrato de fio

- Há teste de contrato que compara a resposta do compose para os headers iOS canônicos com a fixture de
  `contrato-sdui-home-definitivo.json`, desconsiderando apenas `envelope.generatedAt`; qualquer outra divergência de
  campo, tipo ou estrutura é falha.
- O teste de contrato serializa com o mesmo `JsonMapper` que o Boot autoconfigura, não com um mapper construído no
  teste. Um teste que monta o próprio mapper valida algo que não é a resposta HTTP e não conta como cobertura deste
  critério.
- A suíte falha se a resposta contiver qualquer atributo de aparência ou geometria: cor, tipografia, margem, padding,
  gap, largura, altura, raio, orientação, shimmer, ripple, haptic, `columns` ou `itemWidth`.
- A suíte falha se a resposta contiver payload de domínio, dado regulado ou PII.
- A suíte falha se aparecer `type@typeVersion` fora do catálogo MVP ou `action.type` fora de `navigate`,
  `open_bottom_sheet`, `track` e `noop`.
- A suíte falha se alguma section da resposta contiver referência cruzada a outra section (por `id`, por slot ou por
  posição). Há teste que compõe a Home com uma section arbitrária omitida e verifica que as demais permanecem íntegras e
  coerentes — é a prova executável de que a omissão da escada de fallback não produz uma tela com buraco semântico.

### Negociação e headers

- Header obrigatório ausente ou malformado retorna `400`, com corpo de erro estável.
- `API-Version` e `UI-Schema-Version` são validados como eixos independentes; um não substitui o outro.
- Há teste que falha se qualquer header do contrato for emitido ou aceito com prefixo `X-`.

### Compatibilidade e seleção

- Há testes de faixa para schema, capability, app version e OS version cobrindo mínima, intermediária e máxima, com
  limites inclusivos e teto nulo tratado como rolling.
- Há teste que prova que a comparação de versão é semver por ordinal e não comparação de string, com caso explícito de
  `8.10.0` contra `8.9.99`.
- Há teste que prova que os sete types `@1` do catálogo só são retornados quando suportados pelas capabilities efetivas,
  sendo estas a união da matriz do servidor com o delta do header.
- Dois requests idênticos no mesmo instante retornam o mesmo `specRevisionId`.
- App fora de qualquer faixa de targeting recebe `200` com `lastgood` da mesma plataforma ou skeleton mínimo, nunca
  `404`.

### Degradação e escada de fallback

- Section desconhecida é omitida e registrada em `envelope.omitted` com razão fechada, sem `4xx`.
- Falha ou timeout de uma projeção mantém a resposta `200` com as demais sections válidas.
- Redis indisponível, miss concorrente, timeout de dependência e ausência de spec elegível devolvem `lastgood` com
  `fallback: true` e `fallbackReason` fechado.
- **Sem árvore compatível e sem `lastgood` para `surface + platform + channel`, a resposta é `503` com `Retry-After` e
  corpo estável — nunca `500`, nunca `404`.** Este é o último degrau da escada e precisa de teste próprio, com Redis
  vazio.
- Miss concorrente elege um único líder de singleflight; os demais aguardam com timeout e não disparam composição
  redundante.

### Governança

- Há teste ponta a ponta de publish, troca de pointer, `ETag`/`304`, invalidação seletiva e rollback para a revisão
  anterior, comprovando o `specRevisionId` restaurado no envelope.
- Maker não aprova o próprio pedido: o teste usa dois atores distintos, independentemente de quem opera o sistema em
  produção.
- Approve concorrente do mesmo rascunho: um vence, o outro recebe `409`.
- Rollback sem checker autorizado recebe `403`.
- Repetição de approve ou rollback com a mesma `Idempotency-Key` não duplica efeito nem entrada de auditoria.
- Diff entre revisões N-1 e N lista `added`, `removed` e `changed` fiel ao snapshot.
- Spec e skeleton `PUBLISHED` não podem ser reescritos pelo fluxo admin.
- Spec que deixe um slot `required: true` possivelmente vazio para qualquer combinação de
  `platform × faixa de appVersion × capabilities` alcançada pelo próprio targeting é **recusada no publish**. Há teste
  com uma spec que posiciona só `account_card@2` no slot portante e targeting que alcança faixa declarando apenas `@1`:
  o publish falha. Recusa em runtime não conta como cobertura — o compose não decide se a Home ficou aceitável.
- Há teste que prova que `required` não aparece no payload de fio: é campo do skeleton persistido, e a resposta do
  compose não o serializa.

### Arquitetura e resiliência

- **ArchUnit roda no build e o build falha em violação**, não emite aviso. Cobre no mínimo: `core` sem framework,
  `@Transactional` ausente de controllers, filters e adapters salvo a exceção nominal do ADR-003,
  `@SpringBootApplication` só no bootstrap, e nenhuma dependência de `org.springframework.transaction` em core ou
  aplicação.
- Há teste que prova que o caminho de compose não abre transação.
- Nenhuma cadeia assíncrona do compose fica sem tratamento terminal.

### Performance

- Há cenário de carga versionado no repositório, com perfil fixo de headers, proporção hit/miss e duração, para que
  execuções sejam comparáveis entre si.
- O gate falha se o compose em hit não cumprir a meta interna de P99 até 400 ms nesse cenário.
- O gate registra `payload.bytes` como orçamento observado, para servir de linha de base na promoção do canary.

## Fora de escopo

- Teste de renderer iOS/Android, testes de UI nativa e homologação de rotas no registry do aplicativo.
- Testes de integração com contrato, cliente, apólice, sinistro ou qualquer serviço de domínio.
- Fase Android: H16 reusa esta suíte com headers e specs Android próprios.

## Dependências

- H00, H04, H05, H06, H07, H09, H10, H11.

## Ordem sugerida / estimativa

- Fase 5; obrigatório antes do canary. É pré-requisito declarado de H13, H16 e H18.
- Estimativa: 4 dias.

## Referências

- Plano: §§ 0, 3, 4.1, 4.2, 5.2, 5.4, 6.3, 7.5, 8, 9, 11 e 16 (matriz de testes que o plano exige).
- ADRs (`pre-arquitetura-sdui-home.md` §16): ADR-003 (exceção nominal do `@Transactional`), ADR-005 (mapper do teste de
  contrato), ADR-007 (`503` + `Retry-After`), ADR-008 (dois atores no teste de maker-checker).
- Contrato JSON: documento completo; principalmente `envelope`, `skeleton`, `sections`, `sections[].actions` e
  `sections[].analytics`.
- Skill: `skills/sdui-backend/`, compatibilidade, fallback, observabilidade, validação e rollout seguro.
