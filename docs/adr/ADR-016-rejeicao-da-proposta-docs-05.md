# ADR-016: Rejeição da Proposta `docs/05-importante-proposta-melhoria.md`

**Status:** `REJEITADO`

**Data:** 2026-09-21

**Reafirma:** ADR-004, ADR-007, ADR-009, ADR-010, ADR-011, ADR-012, ADR-014. **Origina:** ADR-015 (escopo), ADR-017
(experimentação), ADR-018 (montagem variável).

## Contexto

O `docs/05` propõe reorganizar o serviço em torno de um **template de screen** no MongoDB e de um **composer** que
caminha o template, chama repositórios de domínio e emite sections com fallback. O objetivo declarado é provar que o
backend monta "qualquer tela" dos domínios de `docs/images/`.

O serviço está em desenvolvimento, e isso importa para esta avaliação: mudar o desenho agora é barato. Por isso a
rejeição **não** se apoia em "já está implementado". Ela se apoia no mérito de cada item: segurança, cache, validação
e fronteira de domínio. Onde a proposta aponta uma necessidade real, a necessidade foi aproveitada e tratada em ADR
próprio.

Três observações sobre o documento:

1. **Os mapeamentos não descrevem as imagens.** Cada um começa com "estrutura típica observada em wireframes desse
   tipo". Na imagem do Nubank, por exemplo, o `05` fala de transações e insights, mas a imagem mostra a mesma Home em
   duas montagens: `cards` trocando de posição com `shortcuts` e `shortcuts` passando de prateleira para grade.
2. **A proposta contradiz a própria referência.** Cita as categorias hostis a SDUI e propõe cobrir exatamente elas.
3. **O texto tem marcas de export de pesquisa genérica:** notas de rodapé de ferramenta, um trecho em chinês e um
   esqueleto em Java num projeto Kotlin.

## Decisão

A proposta é **rejeitada como desenho**. O modelo de spec versionada, skeleton, capabilities e envelope continua sendo a
base. Cada item foi avaliado individualmente.

### Itens rejeitados

| Item                                                                | Motivo                                                                                                                                                                        |
|---------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Layouts `two_pane` "em tablets" e `scrollable_grid (2/3/4 colunas)` | Decisão de form factor no servidor. No mobile, isso é do `WindowSizeClass` (Android) e das size classes (iOS). `formFactor`, `breakpoint` e `columns` estão em `VISUAL_KEYS`. |
| `adaptLayout(context, formFactor)` e `layout: vertical/horizontal`  | Mesma violação; `orientation` também é chave visual.                                                                                                                          |
| `type` sem `typeVersion`                                            | Elimina o eixo B: o cliente deixa de declarar o que sabe renderizar.                                                                                                          |
| `fallback: { type: text }` por nó                                   | Um rótulo sem dado nem ação é pior que omissão, e obriga os dois apps a suportar `text` para sempre. O risco real, slot portante vazio, é pego no publish (ADR-009).          |
| Types nomeados pela forma (`category_grid`, `product_list`)         | Duplica o layout token no nome; o catálogo passa a crescer por aparência (ADR-010).                                                                                           |
| Composer chamando `ProductRepository`, `OrderRepository` etc.       | O composer não acessa domínio. A hidratação é da SPI `SectionHydrator`, de propriedade das squads.                                                                            |
| `source: { repo, segment, limit }` no template                      | Acopla a autoria da tela às fontes de dados e permite ao admin apontar qualquer repositório.                                                                                  |
| Laço sequencial, sem timeout, com `default -> throw`                | Latência somada e erro de digitação virando 500 na tela inteira, em vez de omissão (ADR-007, ADR-012, ADR-014).                                                               |
| Template mutável versionado pelo `_id`                              | Contradiz revisão imutável + pointer + rollback + maker-checker.                                                                                                              |
| `flagId` por nó                                                     | Quebra cache de árvore e singleflight e torna inviável validar slot portante por combinação. Necessidade tratada no ADR-017.                                                  |
| `userId` e saudação personalizada no composer                       | Estado por usuário num composer stateless; risco de PII em cache.                                                                                                             |
| `callApi`, `addToCart`, `completeOnboarding`, `onLongPress`         | `callApi` faz o servidor comandar chamadas arbitrárias por fora da camada de rede do app, da autenticação e do pinning. As demais são actions de domínio (ADR-011).           |
| Primitivos `stack`, `text`, `image`                                 | Porta para DSL de layout (`GENERIC_TYPE_NAMES`).                                                                                                                              |
| Onboarding, passcode, checkout, chat e mapa como SDUI               | Categorias hostis (ADR-015).                                                                                                                                                  |
| "Logar por componente renderizado"                                  | O servidor não sabe o que foi renderizado; é telemetria do app.                                                                                                               |

### Necessidades reais que a proposta apontou

| Necessidade                                                         | Por que o mecanismo do `05` não serve                      | Destino                                                                                          |
|---------------------------------------------------------------------|------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| **Montagens diferentes de tela** (`placement` + `order` + `layout`) | Mistura ordem, que é legítima, com form factor, que não é. | **ADR-018.** A ordem dos slots passa a ser dado do skeleton versionado, não constante do código. |
| Experimentação sem release                                          | Nível errado (nó em vez de revisão).                       | ADR-017.                                                                                         |
| Critério de onde não usar SDUI                                      | — (é a melhor parte do documento)                          | ADR-015.                                                                                         |
| Reuso do catálogo entre surfaces                                    | Template genérico em vez de skeleton por surface.          | Gatilho do ADR-004.                                                                              |
| Registry de renderers e dispatcher de actions no cliente            | Exemplo em React Native.                                   | Guia para os times mobile em Jetpack Compose e SwiftUI (backlog).                                |

## Consequências

### Pontos positivos

- A base do serviço continua coerente, e as regras que a proposta violaria ganham teste de regressão.
- As necessidades legítimas viram decisões rastreáveis em vez de ficarem num documento rejeitado.

### Pontos negativos / trade-offs aceitos

- A rejeição fecha uma linha de exploração enquanto ainda seria barato explorá-la. O risco é aceito porque os itens
  rejeitados falham por motivo estrutural (segurança, cache, fronteira de domínio), não por custo de implementação.
- A parte da proposta que estava certa, a montagem variável, só avança se o ADR-018 for levado adiante. Rejeitar o
  `05` sem tratar o ADR-018 deixaria a necessidade sem resposta.

### Ações decorrentes

1. Inserir no topo do `docs/05`, sem apagá-lo:

   ```markdown
   > **Status: REJEITADO (ADR-016, 2026-09-21).** Registro histórico, não especificação.
   > As necessidades aproveitadas estão no ADR-015, no ADR-017 e no ADR-018.
   ```

2. Tratar a divergência de `variant` entre o ADR-010 e a fixture: ADR-019.
3. Atualizar `docs/adr/README.md` com os ADRs 015 a 019.

## Alternativas Consideradas

- **Adotar a proposta como contrato v2.** Descartada pelo mérito dos itens da tabela, não pelo custo.
- **Adotar apenas o fallback por section.** Descartada. Nos apps nativos, ignorar type desconhecido é trivial, e o caso
  perigoso é pego no publish.
- **Adotar apenas `placement` + `order`.** Descartada nessa forma, mas a necessidade foi para o ADR-018, sem form
  factor.

## Critérios de Validação

- Fixture de regressão com spec no formato do `05` (`two_pane`, `columns`, `orientation`, `category_grid`, `callApi`,
  section sem `typeVersion`). O `SpecValidator` recusa e lista cada violação.
- ArchUnit: `orchestrator` não depende de repositório de domínio; hidratação só via `SectionHydrator`.
