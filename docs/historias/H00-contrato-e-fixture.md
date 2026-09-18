# H00 — Contrato e fixture canônicos

## Objetivo

Congelar o contrato de fio da Home iOS como fonte de teste do runtime. Transformar o JSON definitivo em fixture
versionada e estabelecer a revisão humana obrigatória pelo dicionário do contrato antes de qualquer implementação.

## Critérios de aceite testáveis

- A fixture é semanticamente idêntica a `contrato-sdui-home-definitivo.json`: envelope, skeleton e oito sections
  preservam nomes, tipos e estrutura.
- A fixture contém somente `surface: home`, `platform: ios` e `schemaVersion: 3`.
- O catálogo exercitado pela fixture é exatamente `top_bar@1`, `shortcut_shelf@1`, `account_card@1`, `card_product@1`,
  `credit_offer@1`, `coverage_card@1` e `decision_card@1`.
- Todas as actions da fixture usam somente `navigate`, `open_bottom_sheet`, `track` ou `noop`; CTAs visíveis têm
  `label`.
- Revisão de contrato registra que não existem campos de geometria ou aparência no fio: cor, tipografia, margem,
  padding, tamanho, raio, orientação, shimmer ou variante visual.
- Revisão pelo dicionário em `documentacao-contrato-sdui-home-v3.docx` confirma a semântica de cada campo usado na
  fixture; divergência bloqueia a história seguinte.

## Fora de escopo

- Implementar endpoint, persistência, renderizador iOS ou integração com domínio.
- Criar contrato Android; ele terá documento e pointer próprios depois do iOS.

## Dependências

- Nenhuma.

## Ordem sugerida / estimativa

- Fase 0; executar primeiro.
- Estimativa: 1 dia.

## Referências

- Plano: §§ 0, 2, 4.2, 6.1, 6.2 e 6.4.
- Contrato JSON: raízes `envelope`, `skeleton`, `sections`; paths `sections[].type`, `typeVersion`, `props`, `actions`,
  `analytics`.
- Dicionário: `documentacao-contrato-sdui-home-v3.docx`, dicionário campo a campo do contrato v3.
- Skill: `skills/sdui-backend/`, contrato fechado, compatibilidade e validação de payload.
