# H18 — Canary e promoção Android

## Objetivo
Expor a Home SDUI Android inicialmente pelo channel `canary`, com seleção controlada por build allowlist ou mecanismo interno previsto no plano. Promover para `stable` somente após comprovar compatibilidade, SLO e reversão operacional, sem afetar a experiência iOS.

## Critérios de aceite testáveis
- Existe pointer independente para `home + android + canary`; somente builds Android autorizados recebem esse channel e os demais recebem `stable`.
- O compose canary retorna `envelope.platform: android`, `envelope.channel: canary` e `specRevisionId` Android rastreável em métricas, logs e auditoria.
- Durante a janela de canary, são monitoradas as métricas de hit, miss, fallback, espera de singleflight, payload, serialização e tempo por section, segmentadas por revisão, schema, app version, platform e channel.
- A janela observa a meta de P99 interno de compose em hit até 400 ms e o SLO fim a fim de P99 abaixo de 1200 ms, com a parcela do MS explicitamente medida.
- A promoção Android para stable exige os gates definidos na H12 e ausência de degradação operacional material acordada pelo time.
- O runbook de rollback Android é executável: checker usa `Idempotency-Key`, move somente o pointer Android do channel afetado, invalida o cache Android seletivamente e confirma a revisão restaurada via compose.
- Um rollback Android durante canary não altera a resposta, a revisão, o pointer ou o cache do iOS.
- Após promoção stable, o canary permanece como channel independente para revisões futuras; não é convertido em flag que multiplica specs por plataforma/schema/componente.
- A telemetria de render Android é cruzada por célula `type@typeVersion × appVersion × platform` na mesma janela e contra a mesma linha de base de `stable` Android. Célula iOS não serve de linha de base para Android: binários, frota e distribuição de versão são diferentes, e o semver das lojas não é comparável.
- Ausência de telemetria de render por célula reprova a promoção Android, pelas mesmas razões da H13.
- São medidos "tempo de pointer" e "tempo até a tela" para o rollback Android, separadamente, conforme a H13.

## Fora de escopo
- Canary compartilhado entre iOS e Android.
- Mudança em renderer, Design System, gestos, haptics, animações ou campos de aparência.
- Personalização por usuário ou integração com domínios de negócio.

## Dependências
- H11 — Observabilidade e SLO.
- H12 — Validação integrada e gates de release.
- H16 — Matriz de compatibilidade e compose Android.
- H17 — Governança, publish e rollback Android.
- Plano §14 itens 6 e 9 implementados pelo time mobile. Não é dependência de código deste MS, é pré-requisito de gate: sem as dimensões de telemetria e sem a política de refresh, dois critérios de promoção não podem ser avaliados.

## Ordem sugerida / estimativa
- Fase 5 Android; última história do rollout Android.
- Estimativa: 3 dias para preparação e execução inicial, excluída a duração da janela de observação acordada.

## Referências
- Plano: §§ 0, 2, 4.2, 5.2, 5.3, 8, 9 e 15 (Fase 5).
- Contrato JSON: `envelope.platform`, `envelope.channel`, `envelope.specRevisionId`, `envelope.fallback` e `envelope.analytics` como estrutura de referência.
- Dicionário: `documentacao-contrato-sdui-home-v3.docx`, campos Android aprovados no contrato v3.
- Skill: `skills/sdui-backend/`, channels, compatibilidade, observabilidade, pointer e rollback.
