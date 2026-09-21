# ADR-019: Remoção de `variant` do `shortcut_shelf@1` Antes da Primeira Release

**Status:** `PROPOSTO`

**Data:** 2026-09-21

**Reafirma:** ADR-010. **Originado por:** ADR-016 (ação decorrente 2).

**Premissa que sustenta esta decisão:** nenhum app iOS ou Android, nem em build interno, renderiza a partir da fixture
atual. Se essa premissa for falsa, ver "Alternativa B".

## Contexto

O ADR-010 manda recusar qualquer campo de section que selecione variação de renderização, e cita `variant` pelo nome,
junto com `componentType`, `style`, `appearance` e `presentation`.

A fixture canônica `contrato-sdui-home-definitivo.json`, porém, tem `"variant": "compact"` nas props do
`shortcut_shelf`. E o `NoVisualAttributesTest` autoriza `variant` (`compact` | `regular`) **somente** nesse type,
citando o próprio ADR-010 na mensagem da asserção. Há, portanto, uma exceção ao ADR-010 que existe apenas num teste.

`compact` e `regular` descrevem densidade: quão apertados os atalhos aparecem. Densidade é aparência, e o ADR-010 é
claro que aparência é decisão do app.

O serviço está em desenvolvimento e o contrato ainda não tem consumidores em produção. Corrigir agora custa uma
alteração na fixture e em dois testes. Corrigir depois custaria um `shortcut_shelf@2` e um período de convivência entre
versões nos dois apps.

## Decisão (proposta)

1. Remover `variant` da fixture canônica e de qualquer seed.
2. Substituir o teste de exceção no `NoVisualAttributesTest` por um teste que recuse `variant` em qualquer type.
3. Levar `variant` e os demais seletores do ADR-010 (`componentType`, `style`, `appearance`, `presentation`) para a
   verificação de publish. Conferir antes que `style` não colide com chave de conteúdo legítima.
4. Densidade de atalhos passa a ser decisão do renderer, a partir do número de itens, do layout do slot (`shelf` ou
   `grid`, ADR-018) e da classe de tamanho de tela.
5. Se produto precisar de diferença **semântica** entre prateleiras (ex.: atalhos primários vs secundários), ela entra
   como estado de conteúdo nomeado pelo significado, conforme o item 2 do ADR-010, e não como `compact`/`regular`.

## Consequências

### Pontos positivos

- O contrato volta a obedecer ao ADR-010 sem exceção escondida.
- Menos um campo para iOS e Android interpretarem da mesma forma.

### Pontos negativos / trade-offs aceitos

- Se o design precisar de duas densidades para a mesma quantidade de itens no mesmo layout, o servidor não terá como
  pedir. A resposta aceita é: ou há diferença semântica (item 5), ou é decisão do Design System.

### Riscos e mitigações

- **Algum renderer já lê `variant`.** Mitigação: confirmar com os times iOS e Android antes de aplicar; se lerem,
  seguir a Alternativa B.

## Alternativas Consideradas

- **A. Emendar o ADR-010 para registrar a exceção.** Descartada: legitima um seletor de aparência no fio e abre
  precedente para outros types pedirem o mesmo.
- **B. Manter no `@1` e remover no `@2`.** É o caminho correto **apenas** se algum binário já consumir `variant`. Com
  o serviço em desenvolvimento, é custo sem necessidade.

## Critérios de Validação

- A fixture canônica não contém `variant`.
- `NoVisualAttributesTest` recusa `variant` em qualquer section.
- `SpecValidator` recusa spec com `variant`, `componentType`, `style`, `appearance` ou `presentation` em `props`.
