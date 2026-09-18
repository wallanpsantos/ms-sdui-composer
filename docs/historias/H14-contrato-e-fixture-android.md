# H14 — Contrato e fixture canônicos Android

## Objetivo

Definir e congelar o fio canônico Android da Home antes de criar a spec Android. O Android compartilha o catálogo
semântico, skeleton e regras do SDUI com iOS, mas possui documento final, versões e faixa de targeting próprios quando
houver divergência de campos.

Não existe contrato Android nos artefatos fornecidos; portanto, esta história não presume nem cria campos Android a
partir do JSON iOS. Ela produz a fixture Android somente após validação conjunta com o time mobile Android e revisão do
dicionário v3.

## Critérios de aceite testáveis

- É criada uma fixture Android versionada para `surface: home`, `platform: android` e schema aceito pelo contrato
  acordado com Android.
- A fixture usa exclusivamente o catálogo Home MVP: `top_bar@1`, `shortcut_shelf@1`, `account_card@1`, `card_product@1`,
  `credit_offer@1`, `coverage_card@1` e `decision_card@1`.
- A fixture contém o skeleton semântico compartilhado: `header`, `shortcuts`, `accounts`, `cards`, `offers`, `coverage`
  e `foryou`, na ordem definida pelo plano.
- Todo campo Android é mapeado e aprovado contra o dicionário `documentacao-contrato-sdui-home-v3.docx`; campo não
  documentado bloqueia a publicação da fixture.
- A fixture não contém cor, tipografia, dimensões, margin, padding, gap, raio, animação, haptic, ripple, shimmer,
  orientação, variantes de forma ou qualquer CSS/pixel no JSON.
- Actions usam somente `navigate`, `open_bottom_sheet`, `track` ou `noop`; CTA visível possui `label`; `navigate` usa
  rota `app://` registrada pelo binário Android.
- O app Android possui renderers e dispatcher para os `type@version` e actions presentes na fixture; section
  desconhecida é omitida sem erro de tela.
- A revisão registra divergências reais de campos entre iOS e Android, se houver; não se cria type `Android*` para
  diferença apenas visual.

## Fora de escopo

- Reusar automaticamente valores, rotas, campos ou versões do fixture iOS.
- Enviar decisões de Design System Android pelo backend.
- Publicar spec, criar pointer ou executar canary.

## Dependências

- H00 — Contrato e fixture canônicos iOS.
- H03 — Catálogo, skeleton e seed iOS.

## Ordem sugerida / estimativa

- Fase 0 Android; iniciar em paralelo com a estabilização do caminho iOS, antes da spec Android.
- Estimativa: 3 a 5 dias, incluindo alinhamento e validação com mobile Android.

## Referências

- Plano: §§ 0, 1, 2, 4.1, 4.2, 5.1, 5.4, 6.1, 6.2, 6.4 e 15 (Fase 0 e Fase 1).
- Contrato JSON: `contrato-sdui-home-definitivo.json` é fio canônico somente para iOS; usar como referência estrutural,
  não como contrato Android implícito.
- Dicionário: `documentacao-contrato-sdui-home-v3.docx`, dicionário campo a campo que deve validar a fixture Android.
- Skill: `skills/sdui-backend/`, envelope fechado, compatibilidade, catálogo semântico e omit-unknown.
