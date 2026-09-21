# Referências visuais de Modern Mobile UI/UX

> Documentação das imagens em `docs/images`, usadas como referência visual para o desenho de screens, sections e componentes do MS SDUI Composer.

## Objetivo

Este diretório reúne referências de interfaces mobile modernas em diferentes domínios: banking, fintech, carteira cripto, e-commerce, alimentação, logística e café. As imagens não definem um design system fechado nem autorizam o backend a enviar CSS; servem para extrair padrões de composição, hierarquia, interação e linguagem visual que podem ser representados por um contrato SDUI sem acoplar o servidor à renderização nativa.

O princípio central é separar responsabilidades:

- **Servidor:** decide o que exibir, a ordem das sections, o conteúdo, a disponibilidade e as actions.
- **Cliente:** decide como renderizar, incluindo tipografia, cores finais, espaçamento, acessibilidade, animação, dark mode e convenções da plataforma.
- **Design system:** fornece tokens e componentes nativos estáveis.

## Catálogo de imagens

| Arquivo | Domínio | Leitura principal | Possíveis sections |
|---|---|---|---|
| `banking-app-home-cards-transactions.jpg` | Banking | Home financeira com cartões, saldo e transações | `account_summary`, `payment_card`, `transaction_list`, `quick_actions` |
| `coffee-app-all-screens-flow.jpg` | Café | Fluxo completo entre descoberta, produto, carrinho e pedido | `product_grid`, `product_detail`, `cart_summary`, `order_status` |
| `coffee-app-wireframe-to-design-home.jpg` | Café | Evolução de wireframe para composição visual final | `hero_banner`, `category_list`, `product_carousel`, `bottom_navigation` |
| `crypto-wallet-home-withdraw.jpg` | Cripto | Carteira com saldo, ativos e retirada | `wallet_balance`, `asset_list`, `quick_actions`, `withdraw_form` |
| `ecommerce-fashion-catalog-detail-cart.jpg` | E-commerce | Catálogo, detalhe de produto e carrinho | `product_grid`, `product_detail`, `variant_selector`, `cart_summary` |
| `finance-app-card-expenses-light-dark.jpg` | Finanças | Cartão, despesas e variações light/dark | `payment_card`, `spending_summary`, `transaction_list`, `theme_surface` |
| `fintech-onboarding-passcode-phone.jpg` | Fintech | Onboarding, criação de acesso e autenticação | `onboarding_intro`, `form`, `otp_input`, `security_notice` |
| `food-delivery-pizza-home-categories.jpg` | Delivery | Home com busca, categorias, promoção e restaurantes | `search_bar`, `category_grid`, `hero_banner`, `restaurant_list` |
| `logistics-shipment-tracking-map.jpg` | Logística | Acompanhamento de entrega em mapa e timeline | `tracking_header`, `map_preview`, `status_timeline`, `support_action` |
| `nubank-home-sections-comparison.jpg` | Banking | Comparação de homes organizadas em blocos modulares | `account_summary`, `quick_actions`, `promotion_banner`, `service_list` |

## Modelo mental SDUI

As referências podem ser lidas pela tríade `Screens`, `Sections` e `Actions`.

### Screens

Uma **screen** é a superfície navegável e a composição ordenada de sections para uma rota ou contexto.

Exemplos observados nas referências:

- `home`: resumo contextual, ações rápidas, recomendações e atividade recente.
- `catalog`: busca, filtros, categorias e lista de produtos.
- `detail`: imagem, título, preço, atributos, disponibilidade e CTA.
- `cart`: itens, subtotal, benefícios, entrega e ação de checkout.
- `onboarding`: etapas progressivas para introdução e criação de acesso.
- `wallet`: saldo, ativos e operações financeiras.
- `tracking`: estado atual, mapa, eventos e suporte.

A screen deve declarar composição e comportamento de alto nível, mas não medidas de pixel. O cliente mantém o layout nativo, a responsividade e as regras de acessibilidade.

### Sections

Uma **section** é um bloco autocontido, identificável e potencialmente reutilizável. Cada section deve possuir, conceitualmente:

- `id`: identidade estável para keying, diffing, experimentos e observabilidade.
- `type`: semântica do componente que o cliente sabe renderizar.
- `typeVersion`: versão independente do contrato da section.
- `data`: conteúdo necessário para renderização, sem tokens visuais de baixo nível.
- `actions`: intenções como `navigate`, `openSheet`, `callApi` e `trackEvent`.
- `visibility` ou regras equivalentes: disponibilidade contextual, quando aplicável.

Exemplo conceitual, sem dimensões ou cores enviadas pelo backend:

```json
{
  "id": "home-shortcuts",
  "type": "shortcut_grid",
  "typeVersion": 1,
  "data": {
    "items": [
      { "id": "pay", "label": "Pagar" },
      { "id": "transfer", "label": "Transferir" }
    ]
  },
  "actions": {
    "onItemTap": {
      "type": "navigate",
      "target": "payments"
    }
  }
}
```

O exemplo expressa intenção, não implementação visual. O cliente decide se a grade será de duas ou quatro colunas, como tratar Dynamic Type, como exibir ícones e como adaptar o conteúdo ao tamanho da tela.

### Actions

Actions serializam intenção de usuário e são encaminhadas para um dispatcher central do cliente:

- `navigate`: muda de screen ou rota.
- `openSheet`: abre um bottom sheet ou contexto modal nativo.
- `callApi`: solicita uma operação permitida pelo cliente.
- `trackEvent`: registra telemetria de interação.
- `retry`: repete uma operação recuperável.
- `dismiss`: remove uma superfície temporária.

O cliente não deve interpretar livremente strings arbitrárias para executar comportamento. Actions devem possuir tipos conhecidos, validação, allowlist e tratamento explícito de falha.

## Layout e composição

As imagens apresentam padrões recorrentes de Modern Mobile UI/UX:

1. **Header contextual:** título, saudação, localização, estado da conta ou ação de perfil.
2. **Hero ou resumo principal:** informação mais importante da sessão, como saldo, promoção, pedido ou entrega.
3. **Ações rápidas:** operações frequentes em formato de ícones, chips ou cards compactos.
4. **Conteúdo navegável:** carrosséis, grids, listas e categorias agrupadas por intenção.
5. **Atividade ou estado:** transações, despesas, timeline de entrega ou resumo do carrinho.
6. **Navegação persistente:** tab bar ou bottom navigation quando a screen pertence a uma área principal do app.

### Regras de composição

- Uma screen deve ter uma hierarquia clara: contexto → tarefa principal → conteúdo secundário → navegação.
- O primeiro viewport deve comunicar valor e próxima ação sem exigir rolagem excessiva.
- Grupos relacionados devem permanecer juntos e ter títulos semânticos.
- Carrosséis devem indicar continuidade e não esconder conteúdo essencial atrás de gesto.
- Listas devem suportar estados de carregamento, vazio, erro, atualização e conteúdo parcial.
- A navegação deve preservar contexto, estado de seleção e possibilidade de retorno.
- O layout deve ser adaptável a telas pequenas, grandes, orientação suportada e Dynamic Type.

## Cores e linguagem visual

As referências usam contrastes fortes entre superfícies, conteúdo primário e ações de destaque. Também aparecem cenários light/dark, fundos coloridos em cards de destaque e cores semânticas para estado.

O contrato SDUI não deve enviar cores, tipografia, raio, sombra, padding, margem, largura, altura, orientação ou variantes cosméticas. O backend pode, quando necessário, enviar semântica de estado — por exemplo `success`, `warning`, `danger` ou `info` — e o design system do cliente resolve a representação visual adequada.

### Princípios de cor

- **Fundo:** superfície definida pelo tema nativo da plataforma.
- **Conteúdo primário:** contraste suficiente para leitura de títulos, valores e ações principais.
- **Conteúdo secundário:** hierarquia visual para metadados sem competir com a informação principal.
- **Ação primária:** destaque consistente para a próxima tarefa mais importante.
- **Estado:** semântica explícita, com texto ou ícone além da cor para acessibilidade.
- **Dark mode:** não é uma simples inversão de cores; requer tokens próprios, contraste testado e tratamento de imagens.

Não se deve inferir um hexadecimal específico apenas pela aparência das imagens. A implementação deve usar tokens versionados do design system, com suporte a contraste, daltonismo, alto contraste e preferências do sistema operacional.

## Componentes recorrentes

| Componente semântico | Função | Dados mínimos esperados |
|---|---|---|
| `hero_banner` | Comunicar promoção, benefício ou contexto principal | título, descrição opcional, mídia referenciada, action |
| `account_summary` | Mostrar saldo, total ou status resumido | valor formatado pelo cliente ou locale, label, estado |
| `quick_actions` | Expor tarefas frequentes | itens, labels, identificadores e actions |
| `shortcut_grid` | Organizar atalhos por grupo | itens semânticos e actions |
| `search_bar` | Iniciar descoberta ou filtragem | placeholder semântico, estado e action |
| `category_grid` | Navegar por categorias | categorias, labels, ícones semânticos e destino |
| `product_card` | Resumir item comercial | id, título, preço, mídia, disponibilidade e destino |
| `product_grid` | Exibir coleção de produtos | itens, paginação ou carregamento e seleção |
| `product_detail` | Apresentar decisão de compra | produto, variantes, preço, disponibilidade e CTA |
| `payment_card` | Representar cartão ou instrumento | identificador mascarado, status e actions permitidas |
| `transaction_list` | Exibir atividade financeira | itens, status, timestamp e destino |
| `status_timeline` | Mostrar progressão de um processo | etapas, estado atual, timestamps e detalhes |
| `map_preview` | Contextualizar posição ou rota | referência de mapa, markers sem dado sensível e action |
| `form` | Capturar dados em fluxo controlado | campos, validações declarativas e submit action |
| `otp_input` | Capturar código de autenticação | quantidade de dígitos, expiração e retry action |
| `bottom_navigation` | Navegar entre áreas principais | destinos, labels e estado selecionado |

Os nomes são um catálogo semântico de referência. Cada tipo precisa de renderer nativo compilado no cliente, contrato versionado, fallback seguro e testes de compatibilidade.

## Estados obrigatórios

Cada section interativa deve considerar, conforme o caso:

- `idle`: conteúdo disponível para interação.
- `loading`: carregamento sem bloquear a tela inteira quando não for necessário.
- `partial`: parte dos dados está disponível.
- `empty`: não há conteúdo, com explicação e próxima ação.
- `error`: falha compreensível, sem expor detalhes internos.
- `disabled`: ação indisponível com motivo quando útil.
- `stale`: conteúdo anterior exibido enquanto uma atualização é tentada.
- `offline`: comportamento explícito sem conectividade.

O estado visual deve ser acessível, não depender apenas de cor e não causar saltos excessivos de layout.

## Acessibilidade e inclusão

- Todo elemento acionável deve ter label acessível e área de toque adequada ao sistema operacional.
- Informação essencial não pode depender apenas de cor, posição, animação ou imagem.
- O cliente deve suportar leitores de tela, Dynamic Type, contraste elevado, redução de movimento e navegação por teclado quando aplicável.
- Valores financeiros, status e datas devem ser anunciados de forma compreensível.
- Imagens decorativas devem ser distinguíveis de imagens informativas.
- Erros de formulário devem ser associados ao campo e oferecer correção objetiva.
- Loading, sucesso e falha devem possuir comunicação para tecnologias assistivas.

## Performance e resiliência

As referências podem inspirar telas ricas, mas o payload deve permanecer pequeno e previsível:

- Evitar mídia binária e dados de domínio que não sejam necessários para a UI.
- Usar identificadores estáveis para permitir diffing e atualizações parciais.
- Não criar N+1 chamadas para preencher sections.
- Priorizar a primeira pintura e carregar conteúdo secundário de forma progressiva.
- Manter fallback para section desconhecida: omitir a section, sem quebrar a screen.
- Preservar a última árvore válida quando um refresh falhar, quando a política do produto permitir.
- Instrumentar hit/miss de cache, tempo de compose, serialização, bytes do payload e falhas por section.

## Limites do backend

O Composer deve enviar intenção e composição, não uma réplica de CSS ou do design system. Evitar no JSON:

```json
{
  "color": "#6C63FF",
  "fontSize": 18,
  "padding": 16,
  "cornerRadius": 12,
  "width": 320,
  "height": 120,
  "shadow": true
}
```

Preferir:

```json
{
  "type": "hero_banner",
  "typeVersion": 1,
  "data": {
    "title": "Entrega grátis hoje",
    "eyebrow": "Oferta disponível",
    "semanticState": "info"
  },
  "actions": {
    "onTap": {
      "type": "navigate",
      "target": "offers"
    }
  }
}
```

A aparência final pertence ao cliente e ao design system. O servidor pode fornecer estado semântico e conteúdo, mas não deve impor uma aparência específica.

## Checklist de revisão visual

### Arquitetura da informação

- A screen tem objetivo e rota claros?
- A section principal aparece antes do conteúdo secundário?
- A ordem das sections corresponde à prioridade do usuário?
- A mesma section pode ser reutilizada sem duplicar lógica?

### Interação

- Cada CTA possui uma action conhecida?
- Existe feedback de sucesso, falha e indisponibilidade?
- O retorno preserva o contexto?
- Gestos não são a única forma de descobrir ou executar uma ação?

### Acessibilidade

- Labels, foco, contraste e Dynamic Type foram verificados?
- A informação é compreensível sem depender de cor?
- O conteúdo funciona com leitor de tela e redução de movimento?

### SDUI e operação

- O payload evita tokens de apresentação?
- Sections têm `id`, `type` e `typeVersion` estáveis?
- Existe fallback para renderer desconhecido?
- O payload tem tamanho, timeout e cache compatíveis com o SLO?
- Há observabilidade por screen, section e revisão da spec?

## Relação com o Composer

Este diretório é uma referência de produto e UX para o motor de composição. Ele não substitui:

- o contrato formal de schema;
- a especificação publicada e imutável;
- o catálogo de renderers suportados pelo cliente;
- as regras de compatibilidade por plataforma e versão;
- os ADRs e runbooks operacionais.

A intenção é transformar padrões visuais em um vocabulário semântico de `screens`, `sections` e `actions`, mantendo o backend independente de pixel e o cliente responsável pela experiência nativa.

## Referências

- [Imagens deste diretório](./)
- [Documentação do projeto](../README.md)
- [Repositório ms-sdui-composer](https://github.com/wallanpsantos/ms-sdui-composer)
