# H13 — Canary iOS e rollback operacional

## Objetivo

Expor a Home SDUI inicialmente apenas para iOS pelo channel `canary`, com seleção controlada por build allowlist ou
mecanismo interno previsto no plano. Validar comportamento real, indicadores operacionais e a reversão imediata por
pointer antes de promover para `stable`. É a última história do recorte iOS.

## Critérios de aceite testáveis

### Isolamento do channel

- Existe pointer distinto para `home + ios + canary`, independente do pointer `home + ios + stable`; mover um não move o
  outro.
- Apenas builds iOS autorizados pela allowlist ou pelo mecanismo interno recebem a spec canary; os demais continuam
  recebendo `stable`.
- O compose canary retorna `envelope.platform: ios` e `envelope.channel: canary`, e a revisão é rastreável em métricas,
  logs e auditoria pelo `specRevisionId`.
- Channel não é feature flag e não é versão de schema: o canary usa o mesmo catálogo semântico e o mesmo contrato de
  envelope do `stable`, sem introduzir type, header ou campo visual novo.
- Não existe spec gerada por combinação de flag × schema × plataforma. Se a promoção exigir uma spec nova por
  combinação, a modelagem está errada e a história para.
- Cache, `lastgood` e singleflight do canary são chaveados por channel; tráfego canary não contamina a árvore de
  `stable`.

### Observação

- Durante a janela acordada são acompanhadas `compose.hit`, `compose.miss`, `compose.fallback`,
  `compose.singleflight.wait`, `payload.bytes`, `serialize.ms` e `section.<tipo>.ms`, segmentadas por `specRevisionId`,
  `schemaVersion`, `appVersion`, `platform` e `channel`.
- A janela de observação tem duração e critério de abandono definidos **antes** de o canary subir, registrados no
  runbook. Sem isso o canary vira exposição indefinida.
- A linha de base de comparação é a medição de `stable` no mesmo período, não a medição do canary contra si mesmo.
- A telemetria de render do app é acompanhada na mesma janela e com a mesma linha de base de `stable`, cruzada por
  célula `type@typeVersion × appVersion × platform`. A métrica que interessa é taxa de falha de render por célula, não
  volume total de erro: um defeito confinado a uma faixa de binário desaparece no agregado.
- `section.omitted` e `select.no_candidate` são acompanhadas junto com `compose.fallback`. Fallback bem-sucedido é
  degradação, não sucesso.

### Promoção

- A promoção de canary para `stable` só ocorre com todos os gates atendidos: sem aumento material de `compose.fallback`,
  `section.omitted` ou erro em relação ao `stable`; P99 do compose em hit dentro da meta de 400 ms; `payload.bytes`
  dentro do orçamento observado em H12; compatibilidade de binário, schema e capabilities já comprovada; e **taxa de
  falha de render por célula `type@typeVersion × appVersion × platform` dentro do limiar acordado antes da subida do
  canary**, medida na telemetria do app.
- Gate de servidor verde com célula de render vermelha **não promove**. As duas leituras são independentes e ambas são
  obrigatórias.
- **Ausência do dado de render por célula é reprovação, não neutralidade.** Se a telemetria do app não estiver
  publicando as dimensões do plano §14 item 6, o canary não é promovido. Observabilidade antes de velocidade: promover
  sem ela é aceitar um rollout cego em cima de uma frota heterogênea.
- Promover é mover o pointer de `stable` para a revisão já validada em canary. Não é republicar, não é editar JSON, não
  é criar revisão nova.
- A promoção passa pelo mesmo maker-checker do publish, com auditoria append-only registrando ator, papel, channel,
  revisão de origem e de destino.

### Rollback

- Existe runbook executável versionado no repositório, não apenas descrito: checker usa `Idempotency-Key`, move o
  pointer de `home + ios + canary` ou `home + ios + stable` para a revisão anterior, invalida seletivamente o cache do
  escopo afetado e confirma o `specRevisionId` restaurado via compose.
- O rollback é exercitado de verdade durante a janela de canary, em ambiente autorizado. Runbook não testado não fecha a
  história.
- O rollback não depende de editar nem republicar o JSON anterior; spec `PUBLISHED` permanece imutável.
- A invalidação é seletiva por `surface + platform + channel` e revisões afetadas; nunca flush global do Redis.
- São medidos e registrados **dois** tempos, com nomes distintos no runbook:
    - **Tempo de pointer** — da decisão de reverter até o compose devolver a revisão restaurada. Meta do plano: abaixo
      de 30 s. É o único que está sob controle do MS e o único que vira SLO.
    - **Tempo até a tela** — tempo de pointer + TTL residual da árvore + gatilho de refresh do cliente (plano §14 item
      9). É a duração real do incidente para o usuário. Não tem meta no MVP, mas é estimado a partir da política de
      refresh e registrado no runbook; sem ele, o pós-incidente não sabe dizer por quanto tempo a Home ruim ficou em
      campo.
- Se a política de refresh do §14 item 9 ainda não estiver implementada nas duas lojas, o "tempo até a tela" é
  registrado como desconhecido — explicitamente, no runbook. Não é arredondado para o tempo de pointer.

### Quem aprova

- Rollback e promoção de `stable` exigem checker autorizado, distinto do maker. Com um único engenheiro de backend, o
  checker é definido conforme ADR-008: par de produto como checker, ou uso de `internal` para o ciclo de
  desenvolvimento. A regra "maker ≠ checker" não é desligada no código para caber no tamanho do time.
- O atalho de publish permitido em `internal` nunca se aplica a `canary` ou `stable`.
- O `audit_log` registra o papel mesmo quando maker e checker forem a mesma identidade em `internal`, para que a trilha
  mostre o fato em vez de escondê-lo.

### Fronteira

- Android não é habilitado nesta história. H14 a H18 criam specs, faixas, pointer e rollout Android próprios, reusando
  catálogo e regras, nunca o documento iOS.

## Fora de escopo

- Lançamento Android.
- Experimentos que multipliquem specs por combinação de flag, schema e plataforma.
- A/B estatístico; channel canary basta no MVP.
- Mudança do Design System, renderer nativo, gestos, haptics, animações ou aparência do app.

## Dependências

- H07, H09, H10, H11, H12.
- Plano §14 itens 6 e 9 implementados pelo time mobile. Não é dependência de código deste MS, é pré-requisito de gate:
  sem as dimensões de telemetria e sem a política de refresh, dois critérios de promoção não podem ser avaliados.

## Ordem sugerida / estimativa

- Fase 6; última história do recorte iOS.
- Estimativa: 3 dias para preparação e execução inicial, excluída a duração da janela de observação.

## Referências

- Plano: §§ 2, 4.2, 4.3, 5.2, 5.3, 8, 9, 12 e 15 (Fase 6).
- Fluxos: `fluxos-integracao-ms-sdui-home.md` §7, gates de promoção e diagrama de canary.
- ADRs (`pre-arquitetura-sdui-home.md` §16): ADR-007 (escada de resposta), ADR-008 (quem é o checker).
- Contrato JSON: `envelope.platform`, `envelope.channel`, `envelope.specRevisionId`, `envelope.fallback`,
  `envelope.analytics`.
- Skill: `skills/sdui-backend/`, channels, compatibilidade, fallback, pointer, rollback e rollout.
