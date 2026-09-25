# Runbook — rollback operacional iOS (`home + ios + canary|stable`)

Janela de observação (definir **antes** de subir o canary): duração, linha de base `stable` no mesmo período, limiar de
`section.omitted`, `compose.fallback`, `select.no_candidate` e taxa de falha de render por célula
`type@typeVersion × appVersion × platform`. Ausência de telemetria de render por célula **reprova** promoção.

## Pointer vs tela

- **Tempo de pointer** (SLO do MS): da decisão de reverter até `GET /v1/surfaces/home` devolver o `specRevisionId`
  restaurado. Meta: abaixo de 30 s.
- **Tempo até a tela**: tempo de pointer + TTL residual da árvore + gatilho de refresh do cliente. Sem política de
  refresh nas lojas, registrar como **desconhecido**.

## Rollback (checker)

1. Checker distinto do maker. Header `Idempotency-Key` obrigatório.
2. `POST /admin/v1/pointers/home/ios/{channel}:rollback` com `Actor-Id`, `Actor-Role: CHECKER`.
3. O pointer `home + ios + {channel}` move para `previousSpecRevisionId` (ou alvo `PUBLISHED` autorizado). Spec
   publicada permanece imutável.
4. Invalidação seletiva `surface + platform + channel`. Sem flush global.
5. Confirmar compose: envelope `specRevisionId` restaurado, `platform: ios`, `channel` inalterado.
6. Medir os dois tempos e registrar no pós-incidente.

O atalho `internal` nunca se aplica a `canary` ou `stable`.
