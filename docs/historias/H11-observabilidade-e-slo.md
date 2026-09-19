# H11 — Observabilidade e SLO

## Objetivo

Instrumentar o compose para operar a Home por revisão, plataforma e componente, verificando os objetivos de latência e a
saúde da degradação. Métricas não devem carregar PII nem conteúdo regulado.

## Critérios de aceite testáveis

- São emitidas as métricas `compose.hit`, `compose.miss`, `compose.fallback`, `compose.singleflight.wait`,
  `payload.bytes`, `serialize.ms` e `section.<tipo>.ms`.
- As métricas de compose carregam as tags `schemaVersion`, `appVersion`, `surface` e `platform`; a telemetria permite
  correlacionar revisão, channel e fallback do envelope.
- Há medição do tempo de cada section e da serialização, distinguindo hit, miss, espera de singleflight e fallback.
- Teste de carga e dashboard/alerta acordados pelo time demonstram a meta interna de compose P99 até 400 ms em hit.
- A operação verifica o SLO fim a fim de P99 abaixo de 1200 ms para rede + compose + first paint, com a parcela do MS
  explicitamente observável.
- Logs e métricas não incluem props que possam conter dado regulado, valores individuais, identificadores pessoais ou
  payload integral.
- O envelope e o `analytics` de cada section expõem, juntos, o conjunto completo de chaves de junção para correlacionar
  render do cliente com compose do servidor: `specRevisionId`, `schemaVersion`, `appVersion`, `build`, `platform`,
  `channel`, `component`, `componentVersion`, `slot`, `sectionId`. Há teste que falha se alguma dessas chaves sumir do
  envelope ou do `analytics` da section.
- É emitida a métrica `section.omitted` (contador) com tags `type`, `typeVersion`, `appVersion`, `platform`, `channel` e
  motivo fechado, espelhando o conteúdo de `envelope.omitted`. Omissão precisa ser série temporal com alerta por limiar,
  não só um array dentro de uma resposta que ninguém agrega.
- É emitida a métrica `select.no_candidate` quando o targeting não encontra spec elegível, segmentada por `platform`,
  `appVersion` e `schemaVersion`, antes de a escada de fallback assumir. Fallback silencioso e bem-sucedido é o modo de
  falha mais caro: a tela funciona, o dashboard fica verde e a revisão nova nunca chegou a ninguém.

## Fora de escopo

- Pipeline analítico de produto e consulta a domínios de negócio.
- A **coleta** do evento de render, que é do app (plano §14 item 6). O que não está fora de escopo é a **chave de
  junção**: se o MS não emitir as dimensões, a coleta do app não serve para nada e o gate de H13/H18 fica inexequível.

## Dependências

- H05, H06, H07.

## Ordem sugerida / estimativa

- Fase 5; instrumentar antes do canary.
- Estimativa: 3 dias.

## Referências

- Plano: §§ 0, 4.2 e 8.
- Contrato JSON: `envelope.analytics`, `sections[].analytics`, `envelope.fallback`, `envelope.specRevisionId`.
- Skill: `skills/sdui-backend/`, observabilidade por compose/section e SLO.
