# Tarefas — specs das quatro composições

Plano: [plan.md](plan.md). Todas as tarefas estão pendentes. Nenhuma implementação autorizada
por este checklist foi executada neste planejamento. Os caminhos de arquivos novos são propostos.
Cada tarefa tem escopo de até cerca de cinco arquivos; se a execução exceder esse limite,
subdividir preservando os critérios de aceite.

## T01 — Matriz de contrato e ADR de múltiplas surfaces

- [ ] Mapear todos os blocos das três imagens para as quatro specs, classificando os que ficam nativos.
- [ ] Definir tipos, props, limites, capabilities e destinos; registrar propostas de ADR sem considerá-las aprovadas.
- [ ] Distinguir referências Android de contrato mobile formal.

Dependências: nenhuma. Porte M. Arquivos: novo ADR, índice ADR, matriz em docs/examples/screens/README.md,
contrato de comércio e contrato de transações (até cinco).
Verificação: revisão dos quatro mapeamentos contra imagens, validadores atuais e ADR-015/016;
nenhuma prop visual, primitiva genérica ou operação transacional de negócio.

## T02 — Primeira Home financeira de demonstração

- [ ] Criar skeleton/spec/response de `banking.shortcuts_first` com dados sintéticos.
- [ ] Documentar publicação via maker-checker e headers de consulta.
- [ ] Preservar fixture canônica; exemplo Android permanece proposta.

Dependências: T01. Porte M. Arquivos: três JSON, README do exemplo, fonte de teste de contrato.
Verificação: conferir actions, ids, slots e exemplos de resposta; escrever teste de publicação/composição.

## T03 — Home com cartões antes dos atalhos

- [ ] Criar skeleton/spec/response de `banking.cards_first`, aproveitando montagem já suportada.
- [ ] Demonstrar alteração de ordem e grid sem campos visuais nem release de renderer que já suporte os tokens.
- [ ] Documentar troca de revisão por pointer, sem roteamento experimental novo.

Dependências: T02. Porte M. Arquivos: três JSON, README do exemplo, teste comparativo.
Verificação: comparar ordens finais e capacidades exigidas entre as duas montagens.

## Checkpoint A — Composições financeiras

- [ ] Exemplos coerentes com o catálogo e governança existentes; diferenças visuais ficam nativas.
- [ ] Fontes de teste escritas; nenhuma execução Gradle presumida.

## T04 — Regras de domínio por surface

- [ ] Introduzir definição finita de surfaces/slots/types preservando regras atuais da Home.
- [ ] Permitir regras comerciais sem slot financeiro obrigatório; recusar surface desconhecida.
- [ ] Parametrizar Select e validações sem relaxar guardas visuais/PII.

Dependências: T01, checkpoint A. Porte M. Arquivos: definição de surface, Select, SkeletonValidator,
SpecValidator, teste de regras por surface.
Verificação: fontes de teste para Home legada, catálogo e rejeição de combinações cruzadas.

## T05 — Propagar surface no pipeline

- [ ] Levar surface do pedido até seleção, hidratação, cache, singleflight e fallback.
- [ ] Garantir isolamento e atualização de campos do requisitante em hit e waiter.
- [ ] Preservar orçamento, métricas de vocabulário finito e fallback por surface.

Dependências: T04. Porte M. Arquivos: ComposeModels, ComposeScreenService, FallbackCoordinator,
SduiConfiguration, teste de isolamento.
Verificação: revisar todas as ocorrências de Home fixa e escrever casos de ausência de candidato,
last good cruzado, cache hit e singleflight concorrente.

## T06 — Expor leitura e governança da nova surface

- [ ] Expor catálogo por surface sem ambiguidade com GET Home existente.
- [ ] Preservar negociação, ETag, Vary e contrato legado; decidir parâmetros finitos de entrada.
- [ ] Validar surface ao publicar/rollback e manter mapeamento correto de analytics.

Dependências: T05. Porte M. Arquivos: controller de leitura, HomeController, AdminServices,
ScreenResponseMapper, teste HTTP de surfaces.
Verificação: fontes de teste de endpoints e isolamento; revisão das dimensões que alteram resposta/cache.

## T07 — Habilitar novos contratos com capabilities explícitas

- [ ] Implementar catálogo aprovado para comércio e transações, sem types arbitrários.
- [ ] Atualizar CapabilityMatrix e validação de catálogo sem conceder novos types automaticamente aos apps antigos.
- [ ] Definir omissão/fallback por slot e versão, preservando os sete types legados.

Dependências: T01, T06. Porte M. Arquivos: catálogo de componentes, CapabilityMatrix,
CatalogValidator, validadores específicos de props, teste de compatibilidade.
Verificação: testar por fontes cliente antigo, suporte parcial e suporte completo; conferir alinhamento
entre tipos válidos na publicação e universo conhecido na negociação.

## Checkpoint B — Fundação de novas telas

- [ ] ADRs/contratos revisáveis, Home preservada e surface comercial isolada.
- [ ] Testes negativos escritos para novos tipos e campos proibidos.

## T08 — Catálogo de moda

- [ ] Criar skeleton/spec/response de `fashion.catalog` com vitrine limitada e dados fictícios.
- [ ] Mapear entrada para busca/filtro e navegação; detalhe/carrinho/checkout continuam nativos.
- [ ] Não reutilizar `card_product` para mercadoria nem incluir cores/tamanhos de apresentação.

Dependências: T07, checkpoint B. Porte M. Arquivos: três JSON, README, teste de contrato/composição.
Verificação: conferir limites de itens, ações resolvíveis, política de locale e ausência de operações de negócio.

## T09 — Home bancária com resumo de transações

- [ ] Criar skeleton/spec/response de `banking.transactions` com novo componente aprovado.
- [ ] Separar semanticamente conta e cartão; resumo de transações usa somente dados sintéticos.
- [ ] Demonstrar comportamento sem capability e ação para extrato/filtro nativo.

Dependências: T07, checkpoint B. Porte M. Arquivos: três JSON, README, teste de contrato/composição.
Verificação: revisão de dados, limites, ações e fallback; nenhuma dependência de dados pessoais no cache.

## T10 — Configuração de demonstração e reprodução

- [ ] Disponibilizar carga/publicação explícita dos quatro exemplos sem sobrescrever seed canônico por padrão.
- [ ] Documentar sequência de atores, ids, revisões e respostas para GET/rollback.
- [ ] Manter modo in-memory e advertência factual sobre restart e instância única.

Dependências: T02, T03, T08, T09. Porte M. Arquivos: loader ou script administrativo de demo,
configuração demo, README de execução, teste de fluxo (até quatro).
Verificação: revisão da sequência e fontes de teste; não executar mutações externas durante planejamento.

## T11 — Tutorial e sincronização documental

- [ ] Escrever guia de nova tela e novo componente do contrato à publicação e depreciação.
- [ ] Corrigir status e capacidade de ordenação em docs/images/README.md.
- [ ] Indexar exemplos e planos, distinguindo proposto, implementado e homologado.

Dependências: T10. Porte M. Arquivos: guia de criação, docs/README.md, docs/images/README.md,
docs/examples/screens/README.md (até quatro).
Verificação: seguir cada passo por leitura e confrontar payloads com os exemplos e endpoints finais.

## Checkpoint final

- [ ] Quatro composições documentadas e cobertas por fontes de testes; nenhuma tela foi omitida sem classificação.
- [ ] Verificar guards, isolamento ArchUnit e warnings na execução única final, somente se o humano a solicitar.
- [ ] Registrar testes executados ou não executados e limitações de homologação mobile.
- [ ] Persistência permanece entrega separada, sem declarar Redis/Mongo operacionais por existir compose.yaml.
