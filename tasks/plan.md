# Plano — quatro specs SDUI a partir das referências visuais

Status: **implementado no servidor em 2026-09-23** (branch `feature/melhorias`). Decisões em
[ADR-020](../docs/memoria-operacional-e-arquitetural.md#adr-020--múltiplas-surfaces-e-contratos-de-componente)
(`PROPOSTO`); exemplos em
[`docs/examples/screens`](../docs/examples/screens/README.md); checklist com evidências em [todo.md](todo.md).
Pendente de terceiros: homologação dos contratos novos pelos apps iOS/Android e fixture Android canônica.
O texto abaixo é o planejamento original, mantido como registro.
Escopo confirmado: specs/JSON do backend. MongoDB e Redis constituem entrega separada,
planejada em [plano-persistencia-mongo-redis.md](plano-persistencia-mongo-redis.md).
Execução detalhada em [todo.md](todo.md).

## Diagnóstico que orienta o plano

O runtime atual atende `GET /v1/surfaces/home`. `Select`, `ComposeScreenService`, fallback,
catálogo e validação de skeleton ainda incorporam regras específicas da Home financeira.
Há sete types, sete slots e slots portantes `header` e `accounts`. `CatalogValidator` exige
exatamente o conjunto ACTIVE do MVP: adicionar um JSON ao catálogo não habilita novos types.
Montagem variável já existe, inclusive `home.cards_first`; não precisa ser reinventada.

Todos os stores/cache cabeados são in-memory. Os exemplos desta entrega serão executáveis
nesse modo e não prometerão persistência, compartilhamento entre pods nem dados reais.
O seed usa pass-through; não há integração produtiva de transações ou produtos para inferir.

Existem fixture Home iOS, seed e guia visual. Falta um tutorial completo, e `docs/images/README.md`
ainda trata partes do ADR-018 como propostas. A entrega inclui corrigir essa divergência.

## Quatro composições propostas

Os identificadores abaixo são nomes de trabalho. “Template” significa um conjunto de skeleton,
spec versionada e exemplo de resposta; não uma nova entidade, CMS por nós ou motor de templates.

| ID de exemplo             | Referência                                                                      | Composição                                                            | Evolução necessária                                                        |
|---------------------------|---------------------------------------------------------------------------------|-----------------------------------------------------------------------|----------------------------------------------------------------------------|
| `fashion.catalog`         | [Moda, tela esquerda](../docs/images/ecommerce-fashion-catalog-detail-cart.jpg) | Saudação, entrada para busca/filtro, categorias e vitrine de produtos | Surface `catalog` e catálogo semântico de comércio                         |
| `banking.shortcuts_first` | [Nubank, montagem esquerda](../docs/images/nubank-home-sections-comparison.jpg) | Header, conta, atalhos, cartões, ofertas e seguros                    | Reutilizar tipos financeiros; mapear blocos sem equivalente explicitamente |
| `banking.cards_first`     | [Nubank, montagem direita](../docs/images/nubank-home-sections-comparison.jpg)  | Header, conta, cartões, atalhos em grid, ofertas e seguros            | Reutilizar skeleton cards-first e capacidades existentes                   |
| `banking.transactions`    | [Home bancária](../docs/images/banking-app-home-cards-transactions.jpg)         | Header, conta, cartões em pager, atalhos e transações recentes        | Novo slot e type semântico de resumo de transações                         |

As duas montagens Nubank são duas specs da mesma surface `home`, não dois endpoints.
A Home com transações também usa `home`; não fundir saldo e cartão num type genérico para
copiar o desenho. `catalog` será a segunda surface. A seleção de cada exemplo no ambiente
de demonstração será feita publicando/movendo o pointer pelo fluxo administrativo existente,
sem inventar header `template`, usar canal como tenant ou implementar experimentação adiada.

## Fronteira funcional

- A imagem de moda contém três telas. Nesta entrega, apenas o catálogo vira spec. Detalhe,
  carrinho e checkout permanecem destinos nativos. Busca, filtro, favorito, quantidade, tamanho
  e compra não ganham execução de negócio no composer. Mapear somente intenções aprovadas (`navigate` ou
  `open_bottom_sheet`); não introduzir `callApi` nem campos de formulário.
- Cor, fonte, espaçamento, dimensões, tema, desenho de cartão e barra de navegação nativa
  não entram no JSON. Layout usa apenas tokens semânticos já aceitos.
- Produtos não serão representados por `card_product` (cartão financeiro), nem categorias
  por tipos financeiros com props improvisadas. Novos types precisam de contrato explícito.
- A vitrine terá quantidade limitada de itens. Busca/paginação comercial completa fica nos
  destinos nativos; não entregar catálogo inteiro num envelope.
- “Acompanhe também”, botão “Meus cartões”, banners e demais blocos das imagens serão
  classificados na matriz de rastreabilidade: reutilizável, novo contrato ou responsabilidade
  nativa. Nenhum bloco será silenciosamente fingido com outro tipo.
- Dados são sintéticos. Não copiar nome/avatar/saldo/transações pessoais das imagens.
  Não introduzir dados regulados nem cache de árvore por usuário. Transações reais ficam
  condicionadas a um desenho separado de projeções seguras e revisão da política de cache.
- Não implementar renderer iOS/Android. A imagem Nubank orienta composição; não constitui
  contrato Android aprovado. A fixture canônica Android ausente não será inventada.

## Decisões propostas para ADR e contrato

1. Generalizar composição para uma allowlist de surfaces, cada uma com regras de slots,
   obrigatoriedade e types. O catálogo de comércio não pode exigir `accounts`. Uma surface
   desconhecida deve ser recusada antes de criar chaves ou tags; manter tags finitas.
2. Preservar o endpoint e a resposta Home v3 existentes. Definir leitura da nova surface
   sem colisão de mappings HTTP e propagar surface por request, seleção, fallback, cache,
   singleflight, métricas e administração. Isolar também plataforma e canal.
3. Propor contratos de catálogo/vitrine e resumo de transações: nomes e versões, props
   obrigatórias/opcionais, limites, ações, erros, omissão e capabilities. `product_collection@1`
   e `transaction_summary@1` são candidatos, não componentes já aprovados.
4. Generalizar a validação de catálogo sem torná-la aberta a qualquer type. Atualizar matriz
   e negociação para que clientes antigos não recebam automaticamente os novos componentes.
   Componentes novos só aparecem com suporte declarado/aprovado; exigências de slots devem
   produzir degradação previsível, sem quebrar a Home legada.
5. Definir política de locale: o contexto de hidratação recebe locale, mas a chave atual não
   o inclui. Nesta entrega, conteúdo sintético de locale fixo deve ser explícito; se houver
   localização de props, incluir a dimensão no desenho de cache antes de ativá-la.

Registrar ADR (s) novos com o próximo número livre conferido na execução (hoje após ADR-019).
Não alterar o estado de ADRs antigos para simular aprovação dessas propostas.

## Entregáveis

- Quatro conjuntos propostos em `docs/examples/screens/<id>/`: `skeleton.json`, `spec.json`
  (entrada administrativa) e `response.json` (envelope esperado). Separar esses formatos evita
  tentar publicar diretamente uma resposta HTTP como spec.
- README por exemplo com imagem de origem, mapeamento, headers, plataforma, capabilities,
  ações/destinos nativos e passos de criar rascunho, abrir pedido, aprovar e consultar.
- Fixtures e fontes de testes de contrato, seleção, isolamento e compatibilidade.
- Guia `docs/guia-criacao-telas-componentes.md`: exemplo completo, evolução de componente,
  distinção skeleton/spec/envelope, maker-checker, fallback, rollback e diagnóstico de erros.
- Atualização dos índices de documentação e do guia de imagens.
- Configuração explícita de demonstração com dados sintéticos; sem substituir automaticamente
  o seed canônico nem ativar exemplos em produção.

## Ordem de entrega

1. Contrato e rastreabilidade das quatro composições; propostas de ADR.
2. Primeira composição financeira com tipos existentes e fluxo administrativo documentado.
3. Segunda montagem financeira e prova de ordem/layout variável.
4. Fundação mínima de múltiplas surfaces, preservando Home e isolando cache/fallback.
5. Slice de catálogo de moda, do contrato ao exemplo de resposta.
6. Slice de resumo de transações, do contrato ao exemplo sintético.
7. Tutorial, atualização documental e revisão cruzada dos quatro exemplos.

Persistência não bloqueia essas slices no modo de demonstração in-memory. Implantação com
durabilidade ou múltiplas réplicas depende da entrega separada.

## Aceite global e verificação

- Quatro specs rastreáveis às três referências, com limitações explícitas, são publicáveis
  no modo demo e produzem envelopes coerentes com capabilities e slots.
- A Home canônica permanece compatível; nova surface não recebe fallback/árvore da Home.
  Novos types não vazam para clientes sem suporte. Nenhum atributo visual ou dado regulado
  é acrescentado.
- O tutorial permite reproduzir publicação, GET e rollback e diferencia exemplos propostos
  de contratos móveis aprovados. Nenhuma promessa de fidelidade visual sem renderer.

Durante implementação, escrever fontes de testes junto ao código e revisar fixtures/contratos
sem interromper para compilar. Gradle e suíte só poderão rodar por solicitação humana explícita,
uma única vez no final. Não declarar PASS com base em testes não executados. Homologação visual
depende dos apps, fora desta entrega de backend.

## Riscos e decisões pendentes da implementação

| Risco                                               | Tratamento                                                                                              |
|-----------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| Modelo atual muito acoplado a Home                  | Generalizar por surface com regressões dirigidas, sem catálogo irrestrito                               |
| Contrato Android ainda ausente                      | Manter exemplos como propostas e solicitar validação mobile antes de produção                           |
| Reutilização de cache serve dados de outro contexto | Preservar seleção anterior ao cache e reidratar requisitante também no waiter; fixar política de locale |
| Transações interpretadas como integração real       | Fixtures sintéticas e ação para fluxo nativo; nenhuma consulta a domínio regulado                       |
| Novos tipos entregues a versões antigas             | Capability explícita e testes negativos de compatibilidade                                              |
| Imagens confundidas com especificação de negócio    | Matriz por bloco, limites de conteúdo e destinos aprovados antes de implementação                       |

Nomes finais de types, routes/sheets nativos e matriz móvel são decisões de contrato a registrar
na tarefa T01. Não impedem este planejamento, mas não devem ser inferidos como já aprovados.
