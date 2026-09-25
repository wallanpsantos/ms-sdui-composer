# Plano — linguagem de UI do backend: temas por segmento, blocos de composição e campanhas

Status: **PROPOSTO em 2026-09-24 e revisado com o operador ao longo do dia. Nada implementado.** As fases 2 a 7
dependem da aprovação dos ADR-023 a ADR-026 (T00); a fase 1 não depende. Plano separado de [plan.md](plan.md) e
[todo.md](todo.md), que registram outra entrega. A execução segue o AGENTS.md: sem Gradle por tarefa, suíte uma única
vez no checkpoint final e só se o operador autorizar.

## Contexto confirmado com o operador

- **O backend dita as regras de UI.** As equipes de iOS e Android consomem o contrato e implementam os renderizadores.
- **Fase de desenvolvimento.** Não há produção nem app publicado, então o contrato pode mudar agora sem custo de
  compatibilidade, inclusive o ADR-010.
- **Objetivo: montar qualquer tela de conteúdo e navegação** pelo backend, como as de `docs/images` e as montagens de
  `docs/examples`, sem release de app a cada tela nova.

### Decisões do operador (2026-09-24)

1. **Telas com digitação ficam nativas:** login, cadastro, checkout, PIN, formulários e mapa (ADR-015 mantido).
2. **Segmento é uma área do mesmo app:** investimentos, empréstimos, cartões, loja. Cada segmento tem telas e visual
   próprios, às vezes até outras cores, como a loja e os cartões no Inter e no Nubank.
3. **Fonte vai como identificador de fonte instalada**, carregada por uma biblioteca interna dos apps. Fonte nova
   exige versão nova do app.
4. **Campanhas:** várias ao mesmo tempo, em telas diferentes, e uma tela pode ter N campanhas. Data de início e de fim
   é opcional.
5. **Cancelar campanha:** quem aprovou, quem criou, o time responsável ou quem tiver acesso.
6. **Feature toggle:** a empresa usa, mas a ferramenta não está definida (ConfigMap via GitHub, LaunchDarkly, Unleash,
   Split.io, Statsig ou GrowthBook).

## Resposta curta

Hoje o serviço não envia estilo (ADR-010, ADR-019 e `VisualGuard`) e só compõe um catálogo fechado de dez componentes
de produto. A proposta:

1. **Temas por segmento** (ADR-023). Um tema global, um por segmento e, durante uma campanha, uma sobreposição. A tela
   informa o seu segmento e o app aplica o tema dele.
2. **Blocos de composição** (ADR-024). O contrato `block@1` traz uma árvore de primitivas cujo estilo só aceita nome
   de token. Valor literal continua proibido.
3. **Campanhas como entidade própria** (ADR-025). Uma campanha reúne peças em uma ou mais telas e, se quiser, o tema de
   campanha de um segmento. Tem data opcional, prioridade e toggle opcional. Várias campanhas convivem numa tela pela
   prioridade e pela capacidade de cada slot, e cada uma é aprovada e cancelada sozinha, sem mexer na tela base.
4. **Identidade autenticada no plano administrativo** (ADR-026). A regra de quem cancela depende de saber quem é a
   pessoa e de que time ela é, e hoje o plano administrativo aceita qualquer `Actor-Id` sem autenticação.

Tema e tela são independentes por construção: todo tema preenche o vocabulário inteiro e passa nos mesmos pares de
contraste que os blocos respeitam. Qualquer bloco válido fica legível sob qualquer tema válido.

Continuam fora: telas com digitação; actions além do conjunto fechado (ADR-011); colunas por breakpoint decididas no
servidor (AGENTS.md §7); lógica ou expressões no JSON; campanha escolhida por usuário, que exigiria composição
personalizada fora do cache compartilhado.

## O que mudou nesta revisão

- **Campanha deixou de ser uma revisão agendada no ponteiro.** Aquele desenho trocava a tela inteira e só permitia uma
  campanha por vez em cada tela. Com N campanhas simultâneas, cada combinação exigiria uma revisão própria. A campanha
  agora é uma entidade com peças que entram nos slots da tela base.
- **O tema de campanha passa a morar na campanha**, com as mesmas datas das peças. Tela e tema da campanha continuam
  trocando no mesmo instante.
- **Entram a política de cancelamento, a identidade autenticada e o toggle pelo servidor**, independente de ferramenta.

Histórico das versões anteriores: só intenção semântica com valores no app; depois tema único do backend com blocos;
depois tema por marca via `Client-Brand`, trocado por segmento da tela; depois janela de ativação no ponteiro, trocada
pela entidade campanha.

## Diagnóstico (verificado no código em 2026-09-24)

| Tema                 | Hoje                                                | Evidência                                                                                                                                     |
|----------------------|-----------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| Cor, padding, fonte  | Recusados na publicação                             | `MvpCatalog.VISUAL_KEYS` (`sdui-core/.../model/Capability.kt:95`); `VisualGuard` (`validate/Guards.kt:13`) chamado por `SpecValidator.kt:140` |
| Ícone                | Nome livre (`icon.pix`), sem registro               | Fixture canônica; `ComponentPropsValidator.kt:53` só exige texto não vazio                                                                    |
| Primitivas genéricas | Proibidas                                           | `MvpCatalog.GENERIC_TYPE_NAMES`; `CatalogValidator`; ADR-010                                                                                  |
| Montagem             | Ordem de slots, layout por slot, skeleton por spec  | ADR-018; `SlotLayout` (`model/Enums.kt:121`)                                                                                                  |
| Segmento e campanha  | Não existem                                         | Seleção por surface, plataforma, canal e targeting (`select/Select.kt`)                                                                       |
| Identidade do ator   | Headers `Actor-Id` e `Actor-Role`, sem autenticação | KDoc de `AdminController` (`api/admin/AdminController.kt:48`); `Actor(id, role)` sem time nem permissão                                       |

### Por que cabe no modelo atual

- **Bloco** é mais um contrato de componente: entra em `ComponentContracts.APPROVED`, no catálogo e nos `types` da
  surface. App que não declara `block@1` tem a section omitida pelo `Filter`, e chave de cache e ETag já variam pelas
  capabilities. A árvore vai em `props`, que já trafega como `JsonNode`; `PiiGuard`, `ActionGuard` e
  `referencesForeignSection` já percorrem `props` em profundidade.
- **Segmento** é fixo por surface. O envelope ganha um campo, e a chave de árvore e o ETag não mudam.
- **Tema** fica fora do envelope. Tema de campanha não invalida árvore em cache.
- **Campanha** é resolvida a cada requisição, antes do cache, como a seleção de spec (AGENTS.md §19.10). O conjunto de
  campanhas ativas entra na chave de árvore e no ETag, então ativar, encerrar ou cancelar uma campanha muda a chave
  sozinho, sem invalidação. As peças de campanha são sections comuns e passam pelos mesmos validadores.
- **Last good** já re-filtra as sections pelas capabilities de quem pede (`FallbackCoordinator.kt:113`). O mesmo ponto
  passa a descartar as peças de campanhas que não estão mais ativas.
- Exceção necessária: o `VisualGuard` recusaria `color` e `padding` dentro do bloco. No `block@1` ele é substituído
  pela validação de tipo de cada primitiva, que só aceita token.

### Achados laterais

1. **`VisualGuard` compara a chave inteira** (`Guards.kt:19`). `backgroundColor`, `fontSize`, `paddingTop` e o valor
   `"#820AD1"` passam hoje nos componentes de produto.
2. **URL de mídia sem política.** `imageUrl` e `avatarUrl` aceitam qualquer texto, sem `https` obrigatório nem host
   permitido.
3. **Plano administrativo sem autenticação.** Qualquer chamador na rede escolhe o próprio `Actor-Id` e o próprio papel.
   O maker-checker atual e a regra de cancelamento só têm efeito real com identidade autenticada (ADR-026).
4. **Documentação divergente do código.** O guia visual dá como concluído o bloqueio de `theme` e `isDarkMode`, que não
   estão no guard. A arquitetura (§5, passo 5) descreve guards em runtime, mas eles só rodam na publicação.

## Decisões propostas

### ADR-023 — Temas por segmento

- **Segmento** é uma lista fechada no core (`Segments`). Candidatos: `principal`, `investimentos`, `emprestimos`,
  `cartoes`, `loja`. Cada surface declara o seu (`home` → `principal`, `catalog` → `loja`), e o envelope informa
  `segment`. O app aplica o tema desse segmento à tela e, nas telas nativas de cada área, o tema correspondente.
- **Três níveis.** O global tem valor para todos os nomes do vocabulário. O de segmento sobrescreve o que muda. A
  sobreposição de campanha (ADR-025) sobrescreve o segmento enquanto a campanha está ativa. O validador confere sempre o
  tema efetivo; publicar um tema revalida todos os que herdam dele, inclusive as sobreposições de campanhas aprovadas,
  e recusa se algum ficar inválido.
- **Ativação** dos temas global e de segmento por ponteiro por escopo, plataforma e canal, com compare-and-set,
  maker-checker, auditoria, idempotência e rollback, nos moldes do ponteiro de spec.
- **Entrega:** `GET /v1/themes/active` devolve o tema efetivo de todos os segmentos de uma vez, com ETag, 304, `Vary`
  pelos headers que decidem a resposta e cache curto, que nunca passa da próxima borda de campanha conhecida. O app
  embarca um conjunto padrão para o primeiro uso sem rede.
- **Só a cor varia por modo** (claro e escuro, ambos obrigatórios). O resto tem valor único, em unidade lógica: 1
  equivale a 1 pt no iOS e 1 dp no Android.
- **Fonte por identificador** de fonte instalada, com `fallback` de sistema (`system.sans`, `system.serif`,
  `system.mono`). O registro espelha as fontes instaladas nas bibliotecas dos apps; app sem a fonte usa a reserva. O
  carregamento nunca bloqueia a tela, e o tamanho é base: o app aplica a escala do usuário.
- **Ícone** é nome do registro, com desenho embarcado no app.
- **Pares de contraste** fazem parte do vocabulário: abaixo de 4,5:1 para texto ou 3:1 para ícone, em algum modo, o
  tema é recusado (WCAG 2.2, critérios 1.4.3 e 1.4.11).

Vocabulário v1 (nomes candidatos):

| Família      | Nomes                                                                                                                                                                                                                                      |
|--------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `color`      | `background`, `surface`, `surface_variant`, `content.primary`, `content.secondary`, `content.inverse`, `brand.primary`, `brand.on_primary`, `feedback.positive`, `feedback.negative`, `feedback.warning`, `feedback.info`, `border.subtle` |
| `typography` | `display`, `headline`, `title`, `body`, `label`, `caption`; cada um com `fontFamily`, `fallback`, `weight`, `size`, `lineHeight`                                                                                                           |
| `space`      | `none`, `xxs`, `xs`, `sm`, `md`, `lg`, `xl`, `xxl`                                                                                                                                                                                         |
| `radius`     | `none`, `sm`, `md`, `lg`, `full`                                                                                                                                                                                                           |
| `elevation`  | `none`, `low`, `medium`, `high`                                                                                                                                                                                                            |
| `size`       | `sm`, `md`, `lg`, `xl`                                                                                                                                                                                                                     |
| `icon`       | os 15 em uso (`icon.pix`, `icon.barcode`...) e ícones de interface (`icon.chevron_right`, `icon.search`, `icon.close`...)                                                                                                                  |

### ADR-024 — Blocos de composição (`block@1`)

- Substitui a parte do ADR-010 que proíbe primitivas genéricas, **só dentro de `block@N`**. Os componentes de produto
  continuam sob o `VisualGuard` e o ADR-019.
- `block@N` é a versão do kit (primitivas e vocabulário). Um único item no `Component-Capabilities` evita suporte
  parcial nos apps e a fragmentação do cache.
- Prop de estilo aceita só token da família certa (`background: "color.brand.primary"`, `padding: "space.lg"`). Não
  existe `margin` por nó: o espaço entre irmãos é o `spacing` do pai ou um `spacer`.
- Limites: até seis níveis de nó, até 100 nós por bloco, até 24 filhos por contêiner, texto de até 280 caracteres.
- Acessibilidade validada na publicação: `image` exige `alt` ou `decorative: true`; `icon` exige `label` ou
  `decorative: true`; `box` com `actionId` exige `accessibilityLabel`; cor de texto e de ícone forma par declarado com o
  fundo efetivo (o `background` do `box` ancestral mais próximo, ou `color.surface`).
- Regras para os apps, no contrato: ordem de leitura é a da árvore; `typography.display`, `headline` e `title` são
  anunciados como título; área de toque mínima de 44 pt ou 48 dp; `text` com `sensitive: true` é mascarado quando o
  usuário oculta valores.

Kit v1 (13 primitivas):

| Primitiva | Tipo      | Props                                                                                                                        |
|-----------|-----------|------------------------------------------------------------------------------------------------------------------------------|
| `stack`   | contêiner | `axis` (`vertical`/`horizontal`), `spacing` (space), `align` (`start`/`center`/`end`/`stretch`)                              |
| `grid`    | contêiner | `itemSize` (size), `spacing` (space). O app calcula as colunas pela largura                                                  |
| `shelf`   | contêiner | rolagem horizontal; `itemSize` (size), `spacing` (space)                                                                     |
| `pager`   | contêiner | carrossel paginado; `spacing` (space), `indicator` (booleano)                                                                |
| `box`     | contêiner | um filho; `background` (color), `padding` (space), `radius`, `elevation`, `border` (color), `actionId`, `accessibilityLabel` |
| `text`    | conteúdo  | `text`, `style` (typography), `color` (color), `maxLines` (1 a 10), `align`, `sensitive`                                     |
| `image`   | conteúdo  | `url` (https e host permitido), `aspectRatio` (`1:1`, `4:3`, `3:2`, `16:9`, `3:4`), `fit`, `radius`, `alt`/`decorative`      |
| `icon`    | conteúdo  | `name` (icon), `color` (color), `size` (size), `label`/`decorative`                                                          |
| `avatar`  | conteúdo  | `url` ou `initials`, `size` (size), `alt`                                                                                    |
| `button`  | conteúdo  | `label`, `emphasis` (`primary`/`secondary`/`tertiary`), `icon` (icon), `actionId` (obrigatório)                              |
| `badge`   | conteúdo  | `text` (até 24), `tone` (`neutral`/`brand`/`positive`/`negative`/`warning`/`info`)                                           |
| `divider` | conteúdo  | `color` (color.border)                                                                                                       |
| `spacer`  | conteúdo  | `size` (space)                                                                                                               |

### ADR-025 — Campanhas

- **Entidade própria**, com id, nome, time dono, prioridade, janela opcional, toggle opcional, peças e sobreposições
  de tema. A tela base (spec) não muda quando uma campanha começa, termina ou é cancelada.
- **Peças.** Cada peça mira uma surface, um slot, plataformas e canais (padrão `stable`) e traz sections comuns: blocos
  ou componentes de produto, com as próprias actions. As sections passam pelos mesmos validadores das specs. Os ids
  começam com `cmp_`, prefixo proibido nas specs, para nunca colidir com a tela base.
- **Várias campanhas numa tela.** As sections da tela base têm prioridade 0. As de campanha têm a prioridade da
  campanha: positiva entra antes da base, negativa entra depois. Em cada slot, primeiro o `Filter` remove o que o app
  não
  sabe renderizar; depois ficam as de maior prioridade até a capacidade do slot (`maxInstances`). Assim uma campanha
  pode somar peças a um slot ou deslocar o conteúdo base, e quem tem o app antigo continua vendo a tela base. Empate de
  prioridade é decidido pela aprovação mais antiga.
- **Slots portantes** (`header`, `accounts`, `products`) não recebem peça de campanha.
- **Data opcional.** `activeFrom` (inclusivo) e `activeUntil` (exclusivo), em UTC na API. Sem data, a campanha vale da
  aprovação até ser encerrada. A data é avaliada a cada leitura, com o relógio do servidor, sem processo agendado.
- **Tema de campanha** é uma sobreposição por segmento, válida nas mesmas datas das peças. Duas campanhas com tema no
  mesmo segmento e datas sobrepostas são recusadas na aprovação; se toggles ligarem as duas, vence a de maior
  prioridade.
- **Aprovação e cancelamento** (decisão 5). Criar é do maker; aprovar é de um checker diferente de quem criou. Cancelar
  é imediato, sem segunda aprovação, porque parar é menos arriscado que começar: pode quem criou, quem aprovou, quem é
  do time dono ou quem tem a permissão `campaign:cancel`. O motivo é obrigatório e vai para a auditoria.
- **Toggle opcional, pelo servidor** (decisão 6). Hoje já dá para integrar qualquer ferramenta chamando a API de
  campanha (ativar, cancelar), inclusive um pipeline que aplica definições versionadas no GitHub. Quando a ferramenta
  for definida, a campanha pode declarar `flag` e o composer avalia a flag no servidor pelo OpenFeature, padrão da CNCF
  com providers para as ferramentas citadas (a confirmar no T21). O contexto da avaliação nunca inclui dado do usuário.
  O app nunca decide conteúdo por toggle: o estado teria de ir num header, viraria dimensão de cache e deixaria o
  cliente escolher o que vê.
- **Mudança na tela base.** Publicar uma spec que remove um slot ou type usado por campanha aprovada é recusado com a
  lista das campanhas. Se mesmo assim uma peça não couber na tela selecionada, ela é descartada na composição, com
  métrica e log.
- **Caches.** O hash das campanhas ativas entra na chave de árvore e no ETag. O índice de campanhas é recarregado em
  poucos segundos em cada instância, o que limita o atraso de um cancelamento entre instâncias. O last good descarta
  peças de campanhas que deixaram de estar ativas.
- **Analytics.** Section de campanha leva `campaignId` no analytics da section, para medir a campanha no app. O id não
  vai para tag de métrica, que tem cardinalidade limitada (AGENTS.md §23.7).

Exemplo de campanha (trecho, valores ilustrativos):

```json
{
  "id": "cmp_black_friday_2026",
  "name": "Black Friday 2026",
  "ownerTeam": "loja",
  "priority": 10,
  "window": {
    "activeFrom": "2026-11-27T03:00:00Z",
    "activeUntil": "2026-12-01T03:00:00Z"
  },
  "placements": [
    {
      "surface": "home",
      "slot": "offers",
      "platforms": [
        "ios",
        "android"
      ],
      "sections": [
        {
          "id": "cmp_bf_home_banner",
          "type": "block",
          "typeVersion": 1,
          "props": {
            "root": "…"
          }
        }
      ]
    },
    {
      "surface": "catalog",
      "slot": "featured",
      "platforms": [
        "ios",
        "android"
      ],
      "sections": [
        {
          "id": "cmp_bf_loja_hero",
          "type": "block",
          "typeVersion": 1,
          "props": {
            "root": "…"
          }
        }
      ]
    }
  ],
  "themes": {
    "loja": {
      "color": {
        "light": {
          "brand.primary": "#111111",
          "brand.on_primary": "#FFD23F"
        },
        "dark": {
          "brand.primary": "#111111",
          "brand.on_primary": "#FFD23F"
        }
      }
    }
  }
}
```

### ADR-026 — Identidade autenticada no plano administrativo

- O serviço valida um JWT (OIDC) emitido pelo provedor de identidade da empresa, com emissor e chaves configuráveis,
  pelo starter de resource server do Spring Boot, cuja versão vem do BOM.
- As claims viram `Actor`: id, papéis, times e permissões. Maker-checker, aprovação de tema e cancelamento de campanha
  passam a decidir sobre essa identidade.
- Os headers `Actor-Id` e `Actor-Role` ficam só no perfil local, por propriedade explícita. Valor inválido falha a
  subida, como a persistência (AGENTS.md §23.12).

### Alternativas descartadas

- **Só intenção, com valores no app:** não deixa o backend ditar cor, espaço e fonte.
- **Valor literal por nó:** sem variante de modo escuro, sem validação de contraste possível no servidor.
- **Marca informada pelo app no header `Client-Brand`:** o segmento é da tela, e o servidor já sabe qual é pela
  surface.
- **Tema embutido no envelope de cada tela:** repetiria vários KB por resposta e invalidaria árvores a cada campanha.
- **Um tema completo por segmento, sem nível global:** repetiria escalas e tipografia em cada segmento.
- **Revisão de tela agendada no ponteiro:** troca a tela inteira e só aceita uma campanha por vez; N campanhas
  simultâneas exigiriam uma revisão para cada combinação.
- **Janela por section dentro da spec:** acopla todas as campanhas de uma tela à mesma spec; cancelar uma campanha
  exigiria publicar a tela de novo, e times diferentes disputariam o mesmo rascunho.
- **Processo agendado que move ponteiros na hora:** precisa de um líder entre instâncias e perde a borda se estiver
  fora do ar.
- **Toggle avaliado no app para trocar conteúdo:** fragmentaria o cache e daria ao cliente a escolha do conteúdo.
- **SDK de uma ferramenta de toggle específica:** prenderia o serviço a um fornecedor que a empresa ainda não definiu.
- **Confiar em header de identidade vindo do gateway:** só é seguro com rede isolada e mTLS; validar o token no próprio
  serviço não depende disso.
- **Primitivas como types de section e uma capability por primitiva:** misturariam slot e árvore visual e permitiriam
  suporte parcial nos apps.

## Regra de verificação (vale para todas as tarefas)

- Fontes de teste escritas junto com o código, sem parar para compilar.
- Compilação opcional com `kotlinc` 2.4.20 e `-Werror`, fora do Gradle (receita da sessão de 2026-09-24).
- Gradle só no checkpoint final, uma vez, com autorização: `.\gradlew.bat clean build --warning-mode=fail`.
- Nenhuma tarefa é declarada concluída com base em teste não executado; o que não rodou fica registrado como tal.

## Tarefas

### Fase 0 — Decisão

#### T00 — ADR-023 a ADR-026 e contratos

- [ ] **Descrição:** registrar as quatro decisões e os contratos para os apps: `docs/contratos/tema-v1.md`,
  `docs/contratos/block-v1.md` e `docs/contratos/campanha-v1.md` (peças, prioridade, capacidade, `campaignId` no
  analytics).
- **Aceite:**
    - Cada ADR com Status `PROPOSTO`, Contexto, Decisão, Alternativas descartadas, Consequências e Verificação.
    - ADR-024 declara a substituição parcial do ADR-010; ADR-019 continua `ACEITO` para os componentes de produto.
    - Matriz de ADRs e §11 da arquitetura atualizadas.
    - Segmentos, kit, vocabulário, fontes e contrato de campanha revisados com as equipes de iOS e Android.
- **Verificação:** revisão humana. Nenhuma execução.
- **Dependências:** nenhuma.
- **Arquivos:** `docs/adr/ADR-023-temas-por-segmento.md`, `docs/adr/ADR-024-blocos-de-composicao.md`,
  `docs/adr/ADR-025-campanhas.md`, `docs/adr/ADR-026-identidade-administrativa.md`, os três contratos,
  `docs/adr/README.md`, `docs/arquitetura-de-referencia.md`.
- **Escopo:** M (documentação).

### Fase 1 — Endurecimento (vale mesmo se os ADRs forem recusados)

#### T01 — `VisualGuard` por palavra e por valor literal

- [ ] **Descrição:** quebrar a chave em palavras (camelCase, snake_case, kebab-case) e recusar quando alguma palavra
  for chave visual de uma palavra só; as compostas (`formFactor`, `componentType`, `cornerRadius`, `itemWidth`,
  `itemHeight`, `isDarkMode`) continuam casando pela chave inteira. Recusar string que seja, inteira, literal de cor
  (`#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb(`, `hsl(`) ou de medida (`12px`, `16dp`, `14pt`, `14sp`, `1.5em`, `2rem`).
  Acrescentar `theme`, `prominent` e `isDarkMode`.
- **Aceite:**
    - Recusados: `backgroundColor`, `textColor`, `fontSize`, `paddingTop`, `iconColor`, `theme`, `"#820AD1"`, `"16dp"`.
      Aceitos: `acceptLabel`, `valueDisplay`, `"Pedido #123"`, `"10%"`.
    - Varredura do corpus JSON (fixtures, seed, demo e exemplos) sem violação, exceto `proposta-docs-05-hostil.json`,
      que continua recusada. Levantamento de 2026-09-24: só `columns` e `orientation` dessa fixture casam.
    - `VISUAL_KEYS` e `FORBIDDEN_VISUAL_KEYS` alinhados; §10 e backlog do guia visual corrigidos.
- **Testes:** `VisualGuardTest` (novo) e varredura do corpus em `VisualKeysAlignmentTest`.
- **Dependências:** nenhuma.
- **Arquivos:** `validate/Guards.kt`, `model/Capability.kt`, `VisualGuardTest.kt` (novo), `NoVisualAttributesTest.kt`,
  `VisualKeysAlignmentTest.kt`, `docs/images/README.md`.
- **Escopo:** M.

#### T02 — Mídia só por `https` e host permitido

- [ ] **Descrição:** toda chave `url` ou terminada em `Url` exige `https://` e host na allowlist configurada
  (`sdui.media.allowed-hosts`); lista vazia significa só `https`. Vale para componentes de produto, blocos e peças de
  campanha.
- **Aceite:**
    - `http://`, `javascript:`, `data:` e host fora da lista são recusados com o caminho da prop; exemplos atuais
      continuam aceitos.
    - A política chega ao validador puro por parâmetro, com padrão só-`https`; os testes existentes não mudam.
- **Testes:** `MediaGuard` no core e caso de governança com host recusado.
- **Dependências:** nenhuma.
- **Arquivos:** `validate/Guards.kt`, `validate/SpecValidator.kt`, `orchestrator/admin/AdminServices.kt`,
  `adapters/configuration/SduiProperties.kt` e `SduiConfiguration.kt`, teste novo.
- **Escopo:** M.

#### Checkpoint A — fronteira endurecida

- [ ] T01 e T02 com fontes de teste escritas; compilação `kotlinc -Werror` limpa, se feita.
- [ ] Nenhuma spec de seed, demo ou exemplo passou a ser recusada.
- [ ] ADR-023 a ADR-026 aprovados antes da fase 2.

### Fase 2 — Segmentos e temas

#### T03 — Vocabulário, segmentos, fontes e tema efetivo no core

- [ ] **Descrição:** `DesignTokens` com o vocabulário v1 e os pares de contraste; `Segments`; `FontRegistry` com as
  fontes instaladas e a classe de reserva de cada uma; `Theme` (global ou segmento, pai e valores), `ThemeOverlay` (a
  sobreposição que a campanha usa), `ThemePointer` e `ThemeValidator`, que monta o tema efetivo e exige todo nome em
  todos os modos, faixas, escalas crescentes, fonte do registro e contraste WCAG dos pares em cada modo.
- **Aceite:**
    - Contraste pela fórmula de luminância relativa do WCAG, em Kotlin puro; o teste de referência dá 21:1 para preto
      sobre branco.
    - Ciclo, tema sem pai ou mais de três níveis são recusados.
    - Validar um pai com a lista dos herdeiros e das sobreposições devolve quais ficariam inválidos.
- **Testes:** `ThemeValidatorTest` e `DesignTokensTest` (novos).
- **Dependências:** T00 e T01.
- **Arquivos:** `model/DesignTokens.kt`, `model/Theme.kt`, `validate/ThemeValidator.kt` (novos) e os dois testes.
- **Escopo:** M.

#### T04 — Segmento da tela no envelope

- [ ] **Descrição:** `SurfaceDefinition` ganha `segment` (`home` → `principal`, `catalog` → `loja`) e
  `ScreenEnvelope` ganha `segment`, preenchido no mapper a partir da surface.
- **Aceite:** toda surface tem segmento da lista; chave de árvore e ETag não mudam; fixtures canônicas e respostas dos
  exemplos atualizadas.
- **Testes:** `ScreenResponseMapperTest`, `SurfaceRulesTest` e os testes de contrato que comparam fixtures.
- **Dependências:** T03.
- **Arquivos:** `model/Surface.kt`, `contract/screen/ScreenEnvelope.kt`, `api/mapping/ScreenResponseMapper.kt`, as
  fixtures e as respostas dos exemplos.
- **Escopo:** S (a maior parte é JSON).

#### T05 — Registro e ponteiro de tema em memória, com seed

- [ ] **Descrição:** portas `ThemeStore` e `ThemePointerStore` (compare-and-set por escopo, plataforma e canal),
  adapters em memória e seed com o tema global e os cinco segmentos, validados na subida. No modo durável, até o T12, o
  tema só vem do seed e nada o altera em runtime.
- **Aceite:** tema inválido no seed impede a subida com a lista de erros (AGENTS.md §23.12); compare-and-set com versão
  desatualizada é recusado.
- **Testes:** carga válida e inválida; conflito de compare-and-set.
- **Dependências:** T03.
- **Arquivos:** `port/outbound/Stores.kt`, `adapters/memory/InMemoryThemeStores.kt` (novo),
  `adapters/seed/ThemeSeed.kt` (novo), `resources/seed/themes/*.json` (novos),
  `adapters/configuration/SduiConfiguration.kt`, teste novo.
- **Escopo:** M.

#### T06 — `GET /v1/themes/active`

- [ ] **Descrição:** caso de uso de leitura, controller e `ThemeResponse` em `sdui-contract`. Devolve o tema efetivo
  de todos os segmentos, com o canal resolvido pela mesma `CanaryPolicy` das telas, serializado uma vez em bytes
  (AGENTS.md §18.3).
- **Aceite:** 200 com ETag derivado das revisões efetivas; 304 com `If-None-Match`; `Vary` e cache curto; segmento sem
  tema ativo fica fora da resposta; sem header `X-`; métrica com tags finitas.
- **Testes:** `ThemeWebTest` (novo).
- **Dependências:** T05.
- **Arquivos:** `port/inbound/UseCases.kt`, `orchestrator/theme/ThemeQueryService.kt` (novo),
  `api/http/ThemeController.kt` (novo), `contract/theme/ThemeResponse.kt` (novo), `ThemeWebTest.kt` (novo).
- **Escopo:** M.

#### Checkpoint B — temas servidos

- [ ] T03 a T06 escritos; compilação `kotlinc -Werror` limpa, se feita; ArchUnit sem ciclo novo.
- [ ] Revisão com as equipes móveis: segmentos, identificadores de fonte e valores dos temas.

### Fase 3 — Blocos de composição

#### T07 — Kit de primitivas no core

- [ ] **Descrição:** `Primitives` com as 13 primitivas e a especificação de cada prop (família de token, enum, texto
  com limite, URL, booleano, inteiro com faixa ou `actionId`), quais são contêineres e quantos filhos aceitam.
  `BlockNode` é a árvore lida de `props.root`.
- **Aceite:** árvore malformada devolve erro com caminho (`root.children[1].props.style`), nunca exceção; toda prop de
  estilo aponta para uma família do vocabulário.
- **Testes:** `PrimitivesTest` (novo).
- **Dependências:** T03.
- **Arquivos:** `model/Primitives.kt`, `model/BlockNode.kt` (novos) e o teste.
- **Escopo:** S.

#### T08 — Validação do `block@1` e entrada no catálogo

- [ ] **Descrição:** `BlockValidator` aplica o kit, os limites, a política de mídia, a acessibilidade e os pares de
  contraste. `ComponentContracts` ganha `BLOCK`; `ComponentPropsValidator` delega a ele; `SpecValidator` troca o
  `VisualGuard` pelo `BlockValidator` só para `block@N`, com o motivo comentado; `Surfaces` aceita `block` em `home` e
  `catalog`.
- **Aceite:** recusados, com caminho: literal, token da família errada, nome fora do vocabulário, primitiva
  desconhecida, árvore acima dos limites, `image` sem `alt`, texto sem par de contraste, `actionId` inexistente, PII no
  texto. Os dez componentes de produto seguem sob o `VisualGuard`.
- **Testes:** `BlockValidatorTest` (novo) e casos em `SpecValidatorTest`, `SurfaceRulesTest` e
  `ComponentContractsTest`.
- **Dependências:** T02 e T07.
- **Arquivos:** `validate/BlockValidator.kt` (novo), `validate/ComponentPropsValidator.kt`, `validate/SpecValidator.kt`,
  `model/Surface.kt`, `BlockValidatorTest.kt` (novo).
- **Escopo:** M.

#### T09 — Piloto: vitrine da loja com destaque em bloco

- [ ] **Descrição:** exemplo novo `fashion.catalog_blocks`, com o `featured` da surface `catalog` montado por
  `block@1` a partir do destaque da imagem 5. O exemplo existente não muda.
- **Aceite:** publicável no modo demo; `response.json` para quem declara `block@1` e `response-without-block.json` com
  o destaque omitido e 200; `ExampleResponsesContractTest` aceita chave visual dentro de `block` quando o valor é token.
- **Testes:** `ScreenExamplesTest` e `ExampleResponsesContractTest`.
- **Dependências:** T06 e T08.
- **Arquivos:** `resources/demo/screens/fashion.catalog_blocks/*`, `docs/examples/screens/fashion.catalog_blocks/*`,
  `adapters/seed/DemoScreensLoader.kt`, `ScreenExamplesTest.kt`, `ExampleResponsesContractTest.kt`.
- **Escopo:** M (a maior parte é JSON).

#### Checkpoint C — piloto

- [ ] T07 a T09 escritos; tamanho do envelope do piloto medido e registrado; revisão humana antes da fase 4.

### Fase 4 — Governança de tema

#### T10 — Serviços de governança de tema

- [ ] **Descrição:** rascunho de tema (maker), pedido de publicação, aprovação e rejeição (checker diferente de quem
  abriu) e rollback do ponteiro de tema. Na aprovação, revalida o tema efetivo e os herdeiros, move o ponteiro por
  compare-and-set e grava auditoria na mesma unidade de trabalho. Reusa `IdempotencyStore`, `AuditLogStore` e
  `TransactionalUnitOfWork`.
- **Aceite:** quem abriu não aprova (403); conflito de ponteiro dá 409; a mesma `Idempotency-Key` com outros
  parâmetros dá 422; publicar um pai que invalida um herdeiro é recusado com a lista.
- **Testes:** `ThemeGovernanceTest` (novo).
- **Dependências:** T06.
- **Arquivos:** `orchestrator/admin/ThemeAdminServices.kt` (novo), `port/inbound/UseCases.kt`,
  `port/outbound/Stores.kt`, `adapters/memory/InMemoryThemeStores.kt`, `ThemeGovernanceTest.kt` (novo).
- **Escopo:** M.

#### T11 — Endpoints administrativos de tema

- [ ] **Descrição:** rascunho, pedidos de publicação, aprovação, rejeição e rollback de tema em `/admin/v1/`, com os
  filtros, limites e erros do plano administrativo atual.
- **Aceite:** escopo, plataforma ou canal desconhecidos no path dão 404, no corpo 400; métricas sem id de tema em tag.
- **Testes:** `ThemeAdminWebTest` (novo).
- **Dependências:** T10.
- **Arquivos:** `api/admin/ThemeAdminController.kt` (novo), `api/http/ApiExceptionHandler.kt`,
  `ThemeAdminWebTest.kt` (novo).
- **Escopo:** M.

#### T12 — Temas no MongoDB

- [ ] **Descrição:** adapters Mongo para temas, ponteiros e pedidos, com documento versionado e os cuidados do ADR-021.
- **Aceite:** paridade com os adapters em memória; teste de integração condicionado a `SDUI_IT_MONGO_URI`.
- **Testes:** casos novos em `MongoPersistenceIT`.
- **Dependências:** T10.
- **Arquivos:** `adapters/mongo/MongoThemeStores.kt` (novo),`adapters/configuration/DurablePersistenceConfiguration.kt`,
  `MongoPersistenceIT.kt`.
- **Escopo:** M.

#### Checkpoint D — governança de tema

- [ ] T10 a T12 escritos; ITs de Mongo registrados como não executados enquanto não houver infraestrutura.

### Fase 5 — Identidade no plano administrativo

#### T13 — Ator com times e permissões, de fonte configurável

- [ ] **Descrição:** `Actor` ganha papéis, times e permissões, e a borda administrativa passa a obtê-lo de uma porta
  de identidade. A fonte por headers fica disponível só com `sdui.admin.identity=headers`.
- **Aceite:** valor desconhecido na propriedade falha a subida; o maker-checker atual continua com o mesmo
  comportamento sobre o novo `Actor`.
- **Testes:** `AdminGovernanceWebTest` e teste novo da configuração.
- **Dependências:** T00.
- **Arquivos:** `model/ClientContext.kt` (`Actor`), `api/admin/AdminController.kt`, `api/admin/ThemeAdminController.kt`,
  `adapters/configuration/SduiProperties.kt`, teste novo.
- **Escopo:** M.

#### T14 — Identidade por JWT validado no serviço

- [ ] **Descrição:** fonte `jwt` com o starter de resource server do Spring Boot: emissor e chaves por configuração,
  claims mapeadas para papéis, times e permissões.
- **Aceite:** token ausente, expirado, de outro emissor ou com assinatura inválida dá 401; papel insuficiente dá 403;
  nenhum dado do token vai para log além do id do ator.
- **Testes:** `AdminJwtIdentityTest` (novo), com chaves geradas no teste.
- **Dependências:** T13.
- **Arquivos:** `api/configuration/AdminSecurityConfiguration.kt` (novo), `api/admin/JwtActorResolver.kt` (novo),
  `sdui-app/build.gradle.kts`, teste novo.
- **Escopo:** M.

#### Checkpoint E — identidade

- [ ] T13 e T14 escritos; ArchUnit revisado para a dependência nova; produção configurada com `jwt`.

### Fase 6 — Campanhas

#### T15 — Modelo e validação de campanha no core

- [ ] **Descrição:** `Campaign`, `Placement`, `ActivationWindow` e `CampaignValidator`: sections das peças validadas
  como as de spec contra as regras da surface; prefixo `cmp_`; nada em slot portante; peças por slot dentro da
  capacidade; datas coerentes; sobreposição de tema no mesmo segmento com datas sobrepostas recusada; sobreposição de
  tema validada como tema efetivo.
- **Aceite:** cada regra tem caso negativo com mensagem e caminho; a janela respeita as bordas (um instante antes, no
  início, no fim e depois).
- **Testes:** `CampaignValidatorTest` e `ActivationWindowTest` (novos).
- **Dependências:** T03 e T08.
- **Arquivos:** `model/Campaign.kt`, `validate/CampaignValidator.kt` (novos) e os dois testes.
- **Escopo:** M.

#### T16 — Composição com campanhas

- [ ] **Descrição:** a composição resolve as campanhas ativas da surface, plataforma e canal antes do cache; junta as
  peças às sections da tela base; aplica o `Filter`; ordena cada slot por prioridade e corta na capacidade; põe o hash
  das campanhas ativas na chave de árvore e no ETag; leva `campaignId` no analytics da section. O last good descarta
  peças de campanhas inativas.
- **Aceite:**
    - Sem campanha ativa, payload, chave de árvore e ETag idênticos aos de hoje.
    - App sem `block@1` vê a tela base, e não um slot vazio, quando a peça de campanha é bloco.
    - Peça que não cabe na tela selecionada é descartada com métrica de tags finitas.
- **Testes:** `CampaignCompositionTest` (novo) e casos em `DefaultFallbackCoordinatorTest`.
- **Dependências:** T15.
- **Arquivos:** `orchestrator/compose/ComposeScreenService.kt`, `orchestrator/compose/FallbackCoordinator.kt`,
  `filter/CampaignMerge.kt` (novo), `model/Screen.kt` (`RedisKeys.tree` e ETag),
  `contract/analytics/AnalyticsResponse.kt`, testes.
- **Escopo:** M.

#### T17 — Tema com campanha

- [ ] **Descrição:** o endpoint de tema aplica a sobreposição da campanha ativa de cada segmento e limita o cache à
  próxima borda de campanha conhecida.
- **Aceite:** com relógio controlado, o tema da loja muda no início da Black Friday e volta no fim; os outros segmentos
  não mudam.
- **Testes:** casos novos em `ThemeWebTest`.
- **Dependências:** T06 e T15.
- **Arquivos:** `orchestrator/theme/ThemeQueryService.kt`, `api/http/ThemeController.kt`, `ThemeWebTest.kt`.
- **Escopo:** S.

#### T18 — Governança de campanha

- [ ] **Descrição:** rascunho (maker), aprovação por checker diferente de quem criou, cancelamento imediato pela
  política da decisão 5 com motivo obrigatório, auditoria e idempotência. Publicar uma spec que quebra uma peça
  aprovada é recusado com a lista das campanhas.
- **Aceite:** criador aprovando dá 403; cancelamento por quem não criou, não aprovou, não é do time dono e não tem
  `campaign:cancel` dá 403; o cancelamento vale na próxima leitura da mesma instância.
- **Testes:** `CampaignGovernanceTest` (novo).
- **Dependências:** T10, T13 e T15.
- **Arquivos:** `orchestrator/admin/CampaignAdminServices.kt` (novo), `orchestrator/admin/AdminServices.kt`,
  `port/inbound/UseCases.kt`, `port/outbound/Stores.kt`, `CampaignGovernanceTest.kt` (novo).
- **Escopo:** M.

#### T19 — Endpoints e persistência de campanha

- [ ] **Descrição:** endpoints `/admin/v1/campaigns` (criar, editar rascunho, pedir aprovação, aprovar, cancelar,
  listar ativas e agendadas por surface), adapters em memória e Mongo, e índice de campanhas recarregado em poucos
  segundos em cada instância.
- **Aceite:** listagem paginada (AGENTS.md §23.13); métricas sem id de campanha em tag; paridade entre memória e Mongo.
- **Testes:** `CampaignAdminWebTest` (novo) e `MongoPersistenceIT`.
- **Dependências:** T12 e T18.
- **Arquivos:** `api/admin/CampaignAdminController.kt`, `adapters/memory/InMemoryCampaignStore.kt`,
  `adapters/mongo/MongoCampaignStore.kt` (novos), `DurablePersistenceConfiguration.kt`, testes.
- **Escopo:** M.

#### T20 — Duas campanhas simultâneas de ponta a ponta

- [ ] **Descrição:** Semana do Cartão (prioridade 5, de 20/11 a 30/11) e Black Friday (prioridade 10, de 27/11 a 30/11,
  com tema da loja), publicadas pelo maker-checker, com o slot `offers` da Home com capacidade 2.
- **Aceite:**
    - Com relógio controlado: até 19/11, só a oferta base; de 20/11 a 26/11, Semana do Cartão e oferta base; de 27/11
      a 30/11, Black Friday e Semana do Cartão, com a oferta base deslocada e o tema da loja trocado; a partir de 01/12,
      tudo volta.
    - Cancelar a Black Friday no dia 28 devolve a Semana do Cartão e a oferta base na Home e o tema normal na loja, na
      próxima leitura.
- **Testes:** `CampaignFlowTest` (novo).
- **Dependências:** T16, T17 e T19.
- **Arquivos:** `docs/examples/campaigns/README.md` (novo), `CampaignFlowTest.kt` (novo).
- **Escopo:** S.

#### T21 — Toggle pelo servidor com OpenFeature (opcional)

- [ ] **Descrição:** quando a ferramenta de toggle for definida, a campanha pode declarar `flag` com valor padrão, e o
  composer avalia a flag no servidor pelo OpenFeature, com o provider da ferramenta escolhida (ou `flagd` para flags
  versionadas no GitHub).
- **Aceite:**
    - Contexto da avaliação só com surface, plataforma, canal e segmento, nunca dado de usuário.
    - Ferramenta fora do ar usa o valor padrão da campanha e emite métrica.
    - Campanha com `flag` sem provider configurado é recusada na aprovação.
    - Compatibilidade do SDK e dos providers com Java 25 e Spring Boot 4.1 confirmada na documentação oficial antes da
      adoção.
- **Testes:** `CampaignFlagTest` (novo), com provider em memória do próprio SDK.
- **Dependências:** T16 e T17.
- **Arquivos:** `port/outbound/Stores.kt` (`FeatureFlagGate`), `adapters/flags/OpenFeatureFlagGate.kt` (novo),
  `gradle/libs.versions.toml`, `sdui-app/build.gradle.kts`, teste novo.
- **Escopo:** M.

#### Checkpoint F — campanhas

- [ ] T15 a T21 escritos (T21 só se a ferramenta estiver definida); compilação `kotlinc -Werror` limpa, se feita.
- [ ] Relógio sincronizado (NTP) registrado como requisito de implantação; perf do caminho de hit medido antes e depois
  da resolução de campanhas (AGENTS.md §23.14), se autorizado.

### Fase 7 — Contrato publicado e documentação

#### T22 — Contrato legível por máquina para iOS e Android

- [ ] **Descrição:** JSON Schema (draft 2020-12) do `block@1`, do tema e do analytics de campanha, gerado a partir de
  `Primitives`, `DesignTokens`, `Segments` e `FontRegistry`, versionado em `sdui-contract/src/main/resources/schema/`.
- **Aceite:** um teste gera o schema e compara com o arquivo versionado; mudar kit, vocabulário, segmentos ou fontes sem
  atualizar o schema quebra o teste.
- **Testes:** `ContractSchemaAlignmentTest` (novo, em `sdui-integration-test`).
- **Dependências:** T09 e T20.
- **Arquivos:** `schema/block-v1.schema.json`, `schema/theme-v1.schema.json` (novos) e o teste.
- **Escopo:** M.

#### T23 — Guias, arquitetura e memória operacional

- [ ] **Descrição:** guia de criação (blocos, temas de segmento, campanhas, cancelamento, integração de toggle pelo
  servidor), guia visual (três dicionários e reclassificação dos candidatos observados), arquitetura (temas, blocos,
  campanhas, identidade e correção do passo 5 da §5) e AGENTS.md (regras novas e seção do ciclo).
- **Aceite:** nenhum documento afirma estado que o código não tem; ADRs seguem `PROPOSTO` até a homologação.
- **Dependências:** T22.
- **Arquivos:** `docs/guia-criacao-telas-componentes.md`, `docs/images/README.md`, `docs/arquitetura-de-referencia.md`,
  `AGENTS.md`, `README.md`.
- **Escopo:** M (documentação).

#### Checkpoint final

- [ ] Execução única, se autorizada: `.\gradlew.bat clean build --warning-mode=fail`.
- [ ] Homologação: iOS e Android renderizam o kit, aplicam o tema de cada segmento, mostram duas campanhas numa tela e
  trocam o tema da loja nas bordas da Black Friday. Só então os ADRs passam a `ACEITO`.

## Roadmap para "qualquer tela" (planejar depois do checkpoint C)

1. **Surfaces dos segmentos.** Hoje só existem `home` (principal) e `catalog` (loja). Investimentos, empréstimos e
   cartões precisam de telas próprias.
2. **Tela nova sem release de app.** Rota genérica nos apps (`app://sdui/<surface>`) e uma surface de conteúdo livre,
   com header e um slot que aceita vários blocos.
3. **Tela nova sem deploy do backend.** Surfaces como dado governado, com teto de quantidade, id validado e
   maker-checker.
4. **Bloco com tema de outro segmento**, como a vitrine da loja dentro da Home com as cores da loja.
5. **Prévia de campanha:** o envelope e os temas como estarão numa data futura, antes de aprovar.
6. **Componentes de produto como modelos de blocos** no servidor, para os apps implementarem só o kit.
7. **Experimentação por revisão (ADR-017)**, para comparar montagens, campanhas e temas com dados.
8. **Ícone remoto**, com formato vetorial definido e a política de mídia do T02.

## Riscos e mitigações

| Risco                                                                      | Impacto | Mitigação                                                                                                             |
|----------------------------------------------------------------------------|---------|-----------------------------------------------------------------------------------------------------------------------|
| Qualquer um na rede aprova ou cancela campanha, porque não há autenticação | Alto    | ADR-026 (T13, T14) antes de produção; headers só no perfil local                                                      |
| Liberdade demais gera telas inconsistentes ou inacessíveis                 | Alto    | Estilo só por token; pares de contraste; acessibilidade e limites validados na publicação; maker-checker              |
| iOS e Android renderizam a mesma primitiva de formas diferentes            | Alto    | JSON Schema (T22); regras de renderização no contrato; exemplos como referência visual para testes nos apps           |
| Muitas campanhas disputam o mesmo slot                                     | Médio   | Prioridade e capacidade dão resultado determinístico; peças excedentes medidas; limite de peças por slot na aprovação |
| Mudança na tela base quebra peça de campanha aprovada                      | Médio   | Publicação da spec recusada com a lista das campanhas; na composição, peça que não cabe é descartada com métrica      |
| Cancelamento demora a chegar às outras instâncias                          | Médio   | Índice de campanhas recarregado em poucos segundos; last good descarta peças de campanhas inativas                    |
| Ferramenta de toggle fora do ar                                            | Médio   | Valor padrão declarado na campanha e métrica de erro (T21)                                                            |
| Toggle usado para personalizar por usuário                                 | Médio   | Contexto de avaliação sem dado de usuário; campanha por usuário fora do escopo                                        |
| Relógio do servidor errado adianta ou atrasa a campanha                    | Médio   | NTP como requisito de implantação; troca registrada em log; prévia de campanha no roadmap                             |
| Campanha com contraste ruim ou fonte que o app não tem                     | Médio   | Sobreposição validada como tema efetivo; registro espelha as fontes instaladas; fonte de reserva                      |
| O kit cresce sem critério                                                  | Médio   | Primitiva nova só com ADR, nova versão do kit e implementação nos dois apps                                           |

## Perguntas em aberto

1. **Quais segmentos existem hoje e com que nomes?** Os candidatos são `principal`, `investimentos`, `emprestimos`,
   `cartoes` e `loja`.
2. **Qual provedor de identidade a empresa usa** (Keycloak, Microsoft Entra ID, Okta, Cognito ou outro), e os times já
   vêm como claim no token? Define o T14.
3. **Qual ferramenta de feature toggle será usada?** Define o provider do T21; até lá, a integração é pela API de
   campanha.
4. O kit v1 (13 primitivas) cabe no esforço das equipes móveis? Alguma deve sair ou entrar?
5. Quais hosts de mídia valem em cada ambiente (T02)?
