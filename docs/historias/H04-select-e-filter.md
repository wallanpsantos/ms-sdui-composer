# H04 — Select e Filter determinísticos

## Objetivo
Implementar a seleção determinística da spec publicada e o filtro defensivo de sections por capability. A entrada é o contexto negociado; a saída ainda não contém hidratação nem envelope final.

## Critérios de aceite testáveis
- A seleção lê o pointer por `home + platform + channel`; `stable` é default e canary só é elegível por build allowlist ou mecanismo interno previsto no plano.
- Candidatas são `PUBLISHED`, da mesma plataforma e schema compatível; são filtradas por app version, OS version e capabilities efetivas.
- Capabilities efetivas são a união da matriz servidor por plataforma/versão e do delta do header; o header isolado não decide compatibilidade.
- A ordenação é `priority` decrescente e, no empate, `publishedAt` decrescente; requests iguais no mesmo instante retornam o mesmo `specRevisionId`.
- Os limites mínimo e máximo das faixas são inclusivos; teto nulo é rolling; semver não é comparado lexicograficamente.
- Section cujo `type@version` não seja capability efetiva é removida e entra em `envelope.omitted` com motivo fechado quando o envelope for montado; não gera 4xx.
- Casos de teste cobrem iOS legacy/current/next e comprovam que faixas iOS não são reutilizadas para Android.

## Fora de escopo
- Cache Redis, hidratação, fallback, ETag e persistência de publish.

## Dependências
- H01, H02, H03.

## Ordem sugerida / estimativa
- Fase 2; iniciar quando o seed publicado estiver disponível.
- Estimativa: 3 dias.

## Referências
- Plano: §§ 5.1, 5.2, 5.3 e 5.4.
- Contrato JSON: `envelope.client`, `envelope.targeting`, `envelope.omitted`, `sections[].type`, `sections[].typeVersion`.
- Dicionário: `documentacao-contrato-sdui-home-v3.docx`, compatibilidade de schema, section e omitted.
- Skill: `skills/sdui-backend/`, matriz de compatibilidade e omissão de section desconhecida.
