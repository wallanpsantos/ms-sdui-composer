# Resumos — referências canônicas do SDUI

Somente estes três artigos valem como referência permanente do projeto. Os demais textos lidos no levantamento inicial saíram do conjunto canônico.

Decisões de transporte e implementação (10/09/2026):

- Contrato HTTP: **REST + JSON**. Sem GraphQL, sem gRPC, sem Protobuf no desenho vigente.
- Sem framework de SDUI de terceiros (Beagle, DivKit, Stac, Adaptive Cards, LiquidUI, Ghost como produto, etc.). Composer, schema, registry e renderer são nossos.
- Conceitos dos artigos entram; a stack de origem (GraphQL, NestJS, React Native, TanStack, header `x-ui-schema-version`) não.

Fontes relidas em 09/09/2026; recorte atualizado em 10/09/2026.

---

## Índice

1. [Joud W. Awad — Airbnb, Netflix e Lyft sem App Store](#1-joud-w-awad--airbnb-netflix-e-lyft-sem-app-store)
2. [Airbnb — Ghost Platform](#2-airbnb--ghost-platform)
3. [Martin Fowler — Feature Toggles](#3-martin-fowler--feature-toggles)
4. [Síntese para este projeto](#síntese-para-este-projeto)

---

## 1. Joud W. Awad — Airbnb, Netflix e Lyft sem App Store

- **Título:** Server-Driven UI: Ship mobile UI without app-store reviews
- **Fonte:** [https://joudwawad.medium.com/how-airbnb-netflix-and-lyft-ship-ui-without-touching-the-app-store-49c9f64f5e2b](https://joudwawad.medium.com/how-airbnb-netflix-and-lyft-ship-ui-without-touching-the-app-store-49c9f64f5e2b)
- **Autor:** Joud W. Awad

O backend devolve *o que mostrar* (seções, telas, ações, ordem, conteúdo). O cliente decide *como renderizar* (fonte, gesto, animação, tamanho, cor, raio, dark mode, Dynamic Type, densidade, convenção de plataforma). Não é CSS remoto, micro-frontend nem JS avaliado no device.

Frase do artigo: *“SDUI is not ‘the backend sends CSS.’”* O cliente continua dono de rendering, gestures, animations, accessibility labels, haptics, dark mode e convenções de plataforma. Qualquer campo de aparência no payload (`width`, `height`, `rounded`, `orientation`, `circle`, `rectangle`, `shimmer`) atravessa essa linha e vira CSS remoto. Sem isso o time mobile não ajusta tema, acessibilidade e loja sem novo deploy de backend.

O artigo descreve três sistemas de mercado e uma implementação de exemplo:

- **Airbnb / Ghost:** seções independentes da tela (ex.: `PriceBreakdownSection` reaparece em fluxos diferentes). No artigo o transporte é GraphQL — aqui o modelo de seção permanece; o transporte é REST/JSON.
- **Netflix:** home de discovery com observabilidade por componente, versão de schema e versão de cliente.
- **Lyft / Canvas:** nomes semânticos (`RideOptionCard`). No artigo o fio é Protobuf/gRPC — aqui o equivalente é tipo JSON semântico no Design System, não primitiva de pixel.

Peças que copiamos como ideia, não como biblioteca: contrato compartilhado, Composer (template + dados → JSON), header de schema (no projeto: `UI-Schema-Version`, sem prefixo `X-`), renderer com registry, actions despachadas no centro, cache e fallback obrigatório no envelope.

**Riscos que o artigo marca e o projeto herda:** teste fica mais difícil; over-abstraction mata o sistema (HubFramework do Spotify foi descontinuado); não usar SDUI em vídeo, mapa ou checkout offline-crítico.

**Frase-chave:** *“SDUI is a spectrum, not a binary.”* Schema first. Versionar schema e componente em separado. Observabilidade antes de velocidade.

**Releitura integral em 17/09/2026.** Itens que não estavam no recorte anterior e que geraram delta no projeto:

- Netflix — a telemetria que o artigo cobra é de **render**, no cliente, cruzada por componente × versão de schema × versão do binário, com taxa de falha por célula e alerta por limiar. Métrica de compose no servidor não substitui. Entrou como plano §14 item 6, H11 e gate de H13/H18.
- Shopify — fallback como campo de primeira classe do envelope, para que seja estruturalmente impossível publicar type novo sem caminho de degradação. Aqui a política permanece omissão pura, com slot portante declarado; decisão registrada no ADR-009.
- Spotify / HubFramework — regra de três: só generalizar um componente depois que a mesma forma apareceu três vezes em produção. Entrou na governança do catálogo (H03).
- DoorDash — a ferramenta de autoria veio mais de um ano depois dos contratos endurecidos. O adiamento do console ganhou gatilho de reentrada objetivo (§13.3).
- Cache do cliente — chave de invalidação por schema (`buster`), para que bump de schema derrube a árvore persistida no device. Entrou no §14 item 4.
- Refresh do cliente — o app não recarrega sozinho no meio da interação; ele reduz o limiar de stale e refaz no próximo gatilho natural. Entrou no §14 item 9 e virou o denominador do tempo real de rollback (§9).
- Just Eat — o custo de teste do SDUI cresce com o produto `telas × types × versões de cliente × segmentos × flags`. Já refletido na H12; citado aqui como justificativa do tamanho daquela suíte.
- Yelp — `availableTypes` / `deprecatedTypes` no payload, para o cliente saber o que o servidor poderia ter mandado. **Não adotado**: `envelope.omitted` já responde "por que você não recebeu isto", e depreciação é assunto de catálogo e de admin, não de orçamento de bytes do fio. Registrado para não ser reproposto como novidade.

Reforço do que **não** se importa da implementação de referência do artigo: o resolver dele emite `direction`, `gap` e `layout: horizontal`, contrariando a própria tese do texto sobre "backend não manda CSS"; o header é `x-ui-schema-version`, com prefixo `X-`; e a negociação falha com `426`. Nenhum dos três entra — H12 já falha o build no primeiro, o §4.1 fecha o segundo e o ADR-007 fecha o terceiro.

---

## 2. Airbnb — Ghost Platform

- **Título:** A deep dive into Airbnb’s server-driven UI system
- **Fonte:** [https://medium.com/airbnb-engineering/a-deep-dive-into-airbnbs-server-driven-ui-system-842244c5f5](https://medium.com/airbnb-engineering/a-deep-dive-into-airbnbs-server-driven-ui-system-842244c5f5)
- **Autor:** Ryan Brooks

Ghost Platform entrega **UI + dados** para web, iOS e Android a partir de um schema compartilhado. No Airbnb o schema é GraphQL (TypeScript, Swift, Kotlin). Neste projeto o schema compartilhado é o JSON do envelope REST — mesmos conceitos, outro fio.

Conceitos que entram no desenho da Home:

- **Section** — bloco com dados já localizados/formatados; o tipo (`SectionComponentType`) escolhe a cara (`TITLE` vs `PLUS_TITLE`). Equivale aos componentes semânticos do Design System (`HeroBanner`, `ShortcutGrid`, `FeedCard`).
- **Screen** — organiza sections via layout por form factor (compact vs wide). Na Home: skeleton + slots.
- **Action** — contrato de evento (`IAction`); o servidor descreve a intenção, o cliente executa o handler nativo. Layout e ação permanecem desacoplados.

A aposta do artigo é composabilidade: a mesma section vive em telas diferentes. Cliente só registra componentes que já existem no binário. Modelar o bloco reutilizável ganha; modelar “a tela X do iOS” perde o padrão.

**Ganhos:** uma fonte de apresentação; paridade entre plataformas no *o quê*; mudança de composição sem binário novo.

**Riscos:** schema unificado é caro de evoluir; cliente perde ajuste fino por plataforma — mitigado no projeto com documento persistido por plataforma quando os campos divergem, sem duplicar o catálogo.

**Lições para o MS:** section > screen. Composer monta árvore REST/JSON. Seção que o cliente não declara em `Component-Capabilities` é omitida, não derruba a Home.

**Leitura integral em 17/09/2026.** Datação: byline de 29/06/2021, com o texto
dizendo que o Ghost tinha então cerca de um ano; o metadado da página traz
15/07/2026, sem que se possa confirmar qual corresponde ao conteúdo atual.
Ler a seção "What's next for GP?" (nested sections, WYSIWYG, descoberta via
Figma) como roadmap de 2021, **não** como estado atual — é a parte do artigo
que mais tende a ser citada para propor recurso novo aqui.

Mecanismos que foram lidos, entendidos e **recusados com registro**, para que
não voltem como novidade:

- `SectionComponentType` — um modelo de dados, várias renderizações escolhidas
  pelo servidor. Recusado no ADR-010: o exemplo do próprio artigo seleciona logo
  e estilo, que é aparência decidida no servidor. Alternativas por caso no ADR.
- `IAction` extensível por feature, com handler contendo lógica de negócio.
  Recusado no ADR-011: devolve regra ao binário sem registro nem versionamento.
- Nested sections e edição WYSIWYG — roadmap de 2021 da Airbnb, não é referência
  para o nosso MVP. Console já está adiado com gatilho objetivo (§13.3).

Mecanismos adotados como **regra**, não como código:

- Layout por form factor escolhido pelo **cliente**, com o servidor mandando
  as variantes. Vira a regra "form factor não é eixo de targeting" (§5.3): sem
  `Client-FormFactor`, sem quarto eixo, sem multiplicar a matriz de specs.
- `SectionContainer` com status por section: segunda fonte independente
  tratando degradação como estrutura, reforço de contexto do ADR-009.
- Section autocontida, independente da tela e das vizinhas: sai de princípio e
  vira critério verificável em H08 e H12 (D13).

Detalhe de desenho guardado para quando o ADR-004 reabrir: o Ghost aponta do
layout para o `sectionId` no array externo, e não da section para o slot, para
que a mesma section apareça em mais de um arranjo sem duplicar payload. Nossa
direção atual (`section → slot`) é mais enxuta com um arranjo só; a inversão é
o desenho a adotar se houver segunda surface ou variante de skeleton.

---

## 3. Martin Fowler — Feature Toggles

- **Título:** Feature Toggles (aka Feature Flags)
- **Fonte:** [https://martinfowler.com/articles/feature-toggles.html](https://martinfowler.com/articles/feature-toggles.html)
- **Autor / data:** Pete Hodgson · 09/10/2017

Não é um artigo de SDUI. É o mecanismo de exposição que o composer usa *antes* de montar a árvore: mudar comportamento sem mudar o binário.

Quatro categorias:

| Tipo | Função | Dinâmica | Vida típica |
| --- | --- | --- | --- |
| Release | código latente em produção (separar *deploy* de *release*) | estática, no deploy | dias / semanas |
| Experiment | A/B, coortes | runtime | horas / semanas |
| Ops | kill switch sob carga | runtime rápido | variável |
| Permissioning | premium, interno, allowlist | runtime por request | meses / anos |

Princípios: toggle é estoque com custo de carregamento; decidir longe do `if`; Strategy em vez de condicional espalhado; Release Toggle vive em config versionada; Experiment/Ops/Permissioning precisam de contexto da request; data de expiração; Knight Capital é o anti-padrão de toggle eterno.

**Relação com este SDUI:** a flag decide *se* a Home (ou um slot) existe para aquele cliente; o SDUI decide *como* a árvore é composta. Juntos: o servidor decide layout e exposição por coorte. Sem disciplina: explosão `schema × componente × flag × plataforma`.

No MS da Home a flag não consulta domínio de apólice/contrato. Ela opera sobre superfície, slot e coorte de app — não sobre dado regulado.

---

## Síntese para este projeto

### Fronteira backend × mobile (Joud + Ghost)

Servidor descreve composição funcional: quais sections, em qual ordem, com quais dados, quais actions, quais regras de exposição. Cliente realiza com renderer já compilado. Ghost chama isso Section / Screen / Action; neste MS: section / skeleton+slot / `actions[]`.

| Responsabilidade | Backend | Mobile |
| --- | --- | --- |
| Quais sections e em que ordem | sim | não |
| `type` + `typeVersion` | sim | registry local |
| Copy, badge, URL allowlist, estado de negócio | sim | não |
| Action (`navigate`, `open_bottom_sheet`, `track`, `noop`) | declara | despacha e valida |
| Pixel, cor, fonte, padding, radius, shadow, animation | não | sim |
| `circle` / `rectangle` / `shimmer` / `rounded` / `orientation` | não | sim |
| Dark mode, Dynamic Type, haptic, densidade de tela | não | sim |
| Capability e faixa de app | escolhe spec | declara quem é |
| Section desconhecida | omite | ignora campo extra |

Composer = `(spec + contexto de negociação) → DTO de view`. Não consulta produto, apólice ou conta.

Estados no JSON são semântica (`LOW_STOCK`, `REQUIRES_LOGIN`), nunca cor. Slot vazio: omitir, ou `items: []` se o empty state for produto. Não mandar dezena de section oca para o app decidir regra.

Skeleton da Home é o osso da surface (slots + token `pager|shelf|grid|list|fixed`). Não é `shimmer`, proporção nem `itemCount` em dp. Fallback é envelope (`fallback`, `fallbackReason`, `omitted[]`) + omissão de section — não 426, não 404 em `home`.

Não importar do rascunho externo: header `X-*`, `viewLayout.singleColumn`, `onTap` dentro de `data`, `ListingCard` / inventário neste MS, `theme`/`appearance`/`layout: horizontal`, JS remoto, Nativeblocks.

### O que os três artigos autorizam

1. Servidor devolve **view model / UI tree** em JSON, não entidade de domínio.
2. Cliente é **registry + renderer** de componentes já existentes no binário.
3. Valor = composição e correção sem loja, não eliminar o app nativo.
4. **Versionamento + fallback** no envelope. Seção desconhecida some; a tela não quebra.
5. Superfície inicial = **Home** (alta rotatividade). Fora: checkout, câmera, mapa, animação pesada.

### Transporte e implementação fechados

| Tema | Decisão |
| --- | --- |
| API | REST |
| Payload | JSON (DTO de view) |
| Framework SDUI de mercado | nenhum |
| Schema GraphQL / Protobuf / gRPC | fora do desenho vigente |
| Header de schema | `UI-Schema-Version` (sem `X-`) |
| Demais headers | `Client-Platform`, `Client-Version`, `Client-Build`, `OS-Version`, `Component-Capabilities` |
| Composer | Kotlin na JVM Java + Spring Boot >= 4.1.1, stateless |
| Flag | decide existência; JSON decide composição |

### Peças que implementamos nós mesmos

- Contrato JSON versionado, não pacote `@sdui/contracts` de terceiros
- Composer / Application Controller
- Registry no cliente nativo
- Actions no JSON, handlers no app
- Cache (Redis) + última árvore boa
- Observabilidade por seção, schema e versão de cliente (lição Netflix/Joud)
- Toggles com dono, tipo e data de expiração (Fowler)

### O que não resolver com SDUI

- Animação, gesto, câmera, mapa, vídeo, jogo
- Offline de pagamento/checkout
- Ausência de Design System — o servidor só orquestra blocos que o binário já desenha
- Substituir o registry nativo por SDK/framework externo
