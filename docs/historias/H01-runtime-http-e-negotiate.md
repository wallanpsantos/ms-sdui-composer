# H01 — Runtime HTTP e Negotiate

## Objetivo
Disponibilizar o único endpoint runtime do MVP, `GET /v1/surfaces/home`, e normalizar a identidade do cliente para o pipeline de compose. Separar explicitamente a versão HTTP da versão de schema de UI.

## Critérios de aceite testáveis
- O endpoint aceita `API-Version: 1` e aplica versionamento Spring MVC por esse header; `UI-Schema-Version` não é usado como versão HTTP.
- A negociação exige `UI-Schema-Version`, `Client-Platform`, `Client-Version`, `Client-Build`, `Accept-Language` e `API-Version`.
- `OS-Version` e `Component-Capabilities` são tratados como recomendados, sem substituir a matriz de capabilities do servidor.
- Valores de plataforma fora de `ios` e `android`, semver inválido, build inválido, locale inválido ou headers obrigatórios ausentes são rejeitados conforme o contrato HTTP definido pelo time; não há header com prefixo `X-` criado pelo MS.
- O contexto negociado preserva plataforma, versão, build, SO, schema solicitado, locale e capabilities normalizadas para as etapas seguintes.
- Para o primeiro recorte, a rota iOS chega ao pipeline; Android não reutiliza automaticamente documento ou faixa de versão do iOS.

## Fora de escopo
- Seleção de spec, montagem de envelope, rate limit, cache e endpoints administrativos.
- Qualquer consulta a contrato, cliente, apólice, sinistro ou outro domínio.

## Dependências
- H00.

## Ordem sugerida / estimativa
- Fase 1; após H00, pode seguir em paralelo com H02.
- Estimativa: 2 dias.

## Referências
- Plano: §§ 3, 4.1, 4.2 e 5.
- Contrato JSON: `envelope.client`, `envelope.platform`, `envelope.schemaVersion`, `envelope.locale`.
- Dicionário: `documentacao-contrato-sdui-home-v3.docx`, campos do envelope e regras de request.
- Skill: `skills/sdui-backend/`, negociação e versão de API/schema.
