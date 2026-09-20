# GUIA CANÔNICO DE DEPRECIAÇÃO E MIGRAÇÃO — MS-SDUI-COMPOSER

**Última Atualização:** 2026-09-19  
**Status:** Oficial / Normativo  
**Aplicabilidade:** Equipes de Backend (BFF SDUI), Mobile (iOS e Android), Governança e Produto

---

## 1. Fundamentos e Filosofia: Código como Passivo

No ecossistema de **Server-Driven UI (SDUI)**, a disciplina de depreciação e migração não é um evento excepcional; é o
**modo de operação contínuo da plataforma**.

Em aplicações móveis distribuídas:

1. **Código é um Passivo (*Liability*), não um Ativo:** Toda funcionalidade, componente visual, chave de propriedade ou
   versão de contrato tem um custo contínuo de sustentação: testes de regressão, homologação em novas versões de SO,
   patches de segurança e overhead cognitivo da equipe.
2. **A Lei de Hyrum no SDUI:** Com milhões de dispositivos ativos, *todo comportamento observável do payload JSON vira
   dependência invisível de renderização no cliente nativo*. Isso inclui a presença de campos vazios em `props`, a ordem
   de chaves, os nomes de ações e a tolerância a nulos. Portanto, nenhuma remoção pode ser feita "em silêncio" assumindo
   que "ninguém usava".
3. **Planejamento no Design Time:** Nenhuma nova feature, componente (`type@ver`), faixa de versão ou documento de banco
   entra em produção sem responder previamente: *"Como este artefato será depreciado e removido em 12 a 24 meses?"*.
4. **Regra de Responsabilidade (*The Churn Rule*):** Quem introduz ou altera a infraestrutura de UI no BFF é responsável
   por orquestrar a migração dos consumidores ou garantir compatibilidade retroativa integral. O cliente móvel nunca
   deve ser deixado para "quebrar sozinho".

---

## 2. As Três Dimensões de Versionamento e Ciclo de Vida no ms-sdui-composer

A arquitetura do `ms-sdui-composer` divide a compatibilidade em três eixos ortogonais e independentes:

```
                  ┌──────────────────────────────────────────────┐
                  │ Eixo A: Protocolo & Envelope                 │
                  │ (API-Version HTTP + UI-Schema-Version SDUI)   │
                  └──────────────────────┬───────────────────────┘
                                         │
                  ┌──────────────────────▼───────────────────────┐
                  │ Eixo C: Faixa de Aplicativo & Plataforma     │
                  │ (Client-Platform, Client-Version, Targeting)  │
                  └──────────────────────┬───────────────────────┘
                                         │
                  ┌──────────────────────▼───────────────────────┐
                  │ Eixo B: Renderer & Capabilities              │
                  │ (Catálogo: type@typeVersion suportado)       │
                  └──────────────────────────────────────────────┘
```

---

## 3. Eixo B: Ciclo de Vida de Componentes do Catálogo (`type@typeVersion`)

### 3.1 Estados Normativos de um Componente

Cada componente registrado no `Catalog` (`ComponentType`) transita por quatro estados bem definidos:

| Estado       | Significado                                                                                       | Permite uso em novos Drafts? | Renderizado no App Móvel? |
|--------------|---------------------------------------------------------------------------------------------------|:----------------------------:|:-------------------------:|
| `DRAFT`      | Em especificação pela equipe de design/mobile; ainda não aprovado no catálogo oficial.            |             Não              |            Não            |
| `ACTIVE`     | Versão canônica estável em produção.                                                              |           **Sim**            |          **Sim**          |
| `DEPRECATED` | Substituto disponível (`@N+1`); em fase de migração. Telemetria ativa monitorando frota legada.   |    Apenas para correções     |    **Sim** (com aviso)    |
| `RETIRED`    | Fora de circulação em specs vigentes. Omissão graciosa automática caso solicitado por app fóssil. |           **Não**            |    Omitido via Filter     |

---

### 3.2 O Padrão Strangler para Componentes SDUI

A migração de uma versão antiga (`account_card@1`) para uma nova (`account_card@2`) segue 4 fases incrementais sem
parada de serviço:

```
[FASE 1: Expand]
Catálogo recebe account_card@2 (ACTIVE). account_card@1 permanece ACTIVE.
BFF suporta ambas as versões de hidratação.

[FASE 2: Dual-Run / Targeting]
Spec para apps novos (ex.: iOS >= 8.20) referencia account_card@2.
Spec para apps legados (ex.: iOS < 8.20) continua apontando para account_card@1.
O Select e o Filter garantem entrega correta baseada em capabilities declaradas.

[FASE 3: Advisory Deprecation]
account_card@1 passa para status DEPRECATED no catálogo.
Telemetria micrometer monitora tráfego residual: section.account_card.ms e section.omitted.
Equipe mobile é notificada do cronograma de corte.

[FASE 4: Compulsory Retirement / Contract]
Volume em account_card@1 atinge limiar de corte (< 0.05% do tráfego ou data limite).
account_card@1 é marcado como RETIRED.
Se um cliente fóssil requisitar, o Filter executa omissão graciosa (UNSUPPORTED_TYPE).
Como 'accounts' é slot portante (required: true), a escada de fallback (ADR-007) entrega LastGood.
```

---

## 4. Eixo C: Ciclo de Vida e Sunset de Faixas de Aplicativo (Bands)

### 4.1 Fatiamento em Faixas de Targeting (*Bands*)

O servidor divide o ecossistema móvel em três faixas de maturidade:

- **`band: legacy` (ex.: iOS 8.4.0 a 8.9.99):** Frotas desatualizadas com suporte a um subconjunto de tipos (ex.: apenas
  3 componentes essenciais: `top_bar`, `shortcut_shelf`, `account_card`).
- **`band: current` (ex.: iOS 8.10.0 a 8.19.99):** Versão principal da base de usuários, suportando todos os 7
  componentes do catálogo MVP.
- **`band: next` (ex.: iOS 8.20.0+):** Versões de vanguarda contendo novos skeletons, layouts dinâmicos e novos
  componentes `@2`.

### 4.2 Procedimento Normativo de Sunset de Versão de App

Quando a organização decide encerrar o suporte a uma faixa legada (ex.: descontinuar apps `< 8.10.0`):

1. **Validação de Volume:** O time consulta as métricas de compose:
    - `compose.hit{appVersion="..."}`
    - `select.no_candidate{appVersion="..."}`
2. **Atualização do Targeting:** O `SpecValidator` e o `DraftService` criam nova revisão de Spec onde `appVersion.min` é
   elevado.
3. **Escada de Fallback (ADR-007):**
    - Caso um cliente descontinuado faça requisição, o `Select` não encontra candidato direto.
    - O orquestrador busca `LastGoodScreenStore`.
    - Se não houver `lastgood`, responde deterministicamente **HTTP 503 Service Unavailable** com cabeçalho
      `Retry-After: 5` e corpo de erro estável `COMPOSE_UNAVAILABLE` (`reason: no_compatible_spec`).
    - A Home **nunca retorna HTTP 404** nem lança exceções não tratadas (HTTP 500).

---

## 5. Eixo A: Versionamento de Protocolo e Envelope

### 5.1 Desacoplamento HTTP vs. SDUI

| Identificador         | O que versiona                                                               | Exemplo | Cabeçalho HTTP         |
|-----------------------|------------------------------------------------------------------------------|---------|------------------------|
| **API-Version**       | O contrato da rota REST/HTTP (endpoints, códigos HTTP, segurança).           | `1`     | `API-Version: 1`       |
| **UI-Schema-Version** | A estrutura semântica do envelope SDUI (`envelope`, `skeleton`, `sections`). | `3`     | `UI-Schema-Version: 3` |

### 5.2 Regras de Evolução de Schema

- **Evolução Compatível (dentro de UI-Schema 3):**
    - Adicionar campos opcionais em `props` ou novos nós no envelope que clientes antigos ignoram.
    - Não exige elevação de `UI-Schema-Version`.
- **Evolução Incompatível (migração para UI-Schema 4):**
    - Reestruturação de campos de raiz (`envelope`, `sections`), mudança no formato de ações ou remoção de campos de
      analytics.
    - Exige suporte dual no `HomeController` e `Negotiate`:
      ```kotlin
      when (context.schemaVersion) {
          "3" -> mapperV3.toResponse(screen)
          "4" -> mapperV4.toResponse(screen)
          else -> ComposeResult.InvalidHeaders(...)
      }
      ```

---

## 6. Persistência e Cache: O Padrão Expand / Migrate / Contract

Mudanças de esquema de banco são as mais arriscadas porque **dados não podem ser revertidos com um simples rollback de
deploy**. No MongoDB e no Redis, o `ms-sdui-composer` aplica estritamente o padrão Expand/Contract.

```
┌─────────────────┐       ┌─────────────────┐       ┌─────────────────┐
│   1. EXPAND     │ ───►  │   2. MIGRATE    │ ───►  │   3. CONTRACT   │
│ Adiciona campos │       │ Dual-write no   │       │ Remove campos e │
│ como opcionais  │       │ app + Backfill  │       │ código legado   │
│ no Document     │       │ em background   │       │ em deploy novo  │
└─────────────────┘       └─────────────────┘       └─────────────────┘
```

### 6.1 Exemplo Prático no MongoDB (Evolução de SpecDocument)

Cenário: adicionar suporte a tags de segmentação `segmentTags: List<String>` em `SpecDocument`.

1. **Fase 1 (Expand):**
    - Declarar o campo com valor padrão no Kotlin: `val segmentTags: List<String> = emptyList()`.
    - Deploy em produção. O código novo lê documentos antigos sem erro (recebendo lista vazia). O código antigo ignora o
      campo se já existir.
2. **Fase 2 (Migrate / Dual-write & Backfill):**
    - Novos drafts de spec gravam `segmentTags`.
    - Um job batch assíncrono (fora do hot path) popula `segmentTags` nas specs publicadas existentes sem travar a
      coleção (sem locks exclusivos).
3. **Fase 3 (Contract):**
    - O código passa a considerar `segmentTags` como obrigatório na regra de negócio.
    - Deploy separado e isolado.

### 6.2 Higiene de Cache no Redis

- **Chaves com Namespaces Versionados:** O `RedisKeyspace` embute plataforma, schema, appMajorMinor e capsHash na chave
  de cache de árvore (`sdui:tree:...`).
- **Zero Invalidação Global:** Proibido executar `FLUSHALL` ou varreduras com `KEYS *`.
- **Invalidação Cirúrgica e TTL Curto:** A árvore de tela possui TTL de 60 segundos (`sdui.treeTtlSeconds: 60`).
  Alterações de spec invalidam apenas a chave específica via `treeCache.invalidate(...)` durante a aprovação do Publish
  Request ou Rollback.

---

## 7. Eliminação de Código Zumbi (*Zombie Code*)

Código zumbi é aquele que ninguém mantém, não possui testes ativos, mas permanece consumindo memória e atenção dos
engenheiros.

### 7.1 Indicadores no ms-sdui-composer

- Interfaces em `port.inbound` ou `port.outbound` com zero chamadas ou implementações reais (ex.: `HeaderNegotiation`,
  identificada e eliminada no ciclo de qualidade).
- Adaptadores de memória mantidos em paralelo a adaptadores reais sem testes de contrato correspondentes.
- Métodos utilitários de serialização não referenciados pelo pipeline ativo.

### 7.2 Protocolo de Eliminação

1. **Auditoria de Referências:** Executar busca em todo o repositório (`git grep <simbolo>`) e validar ausência em
   testes e documentação.
2. **Corte Direto:** Em caso de código interno sem consumidores externos, remover sumariamente sem necessidade de
   período advisory.
3. **Validação de Compilação:** Garantir que o projeto compila limpo com `allWarningsAsErrors = true`.

---

## 8. Débito Técnico e Migrações Arquiteturais Internas

### 8.1 Caso Canônico: Migração ADR-003 → ADR-013 (Transação de Publish Concluída)

- **Histórico (ADR-003):** `MongoTransactionalUnitOfWork` utilizava `@Transactional` do Spring, exigindo proxy
  dinâmico CGLIB, classe `open` e uma exceção nominal na suíte do ArchUnit (`ArchitectureTest.kt`).
- **Implementação Consolidada (ADR-013):** A porta `TransactionalUnitOfWork` foi migrada para execução programática com
  `TransactionTemplate`. O mecanismo dispensa proxies AOP, elimina classes abertas desnecessárias e permitiu fortalecer
  a
  regra do ArchUnit para "nenhuma classe de produção declara `@Transactional`" (sem exceções nominais).
- **Status da Migração:** `CONCLUÍDA / APROVADA`. ADR-013 promovido a `ACEITO` e ADR-003 marcado como `SUPERSEDIDO`.

---

## 9. Checklist Operacional de Depreciação e Sunset

Antes de desativar qualquer componente, versão de spec ou campo de persistência:

- [ ] **Substituto em Produção:** A versão substituta está homologada, testada e em operação no canal estável.
- [ ] **Zero Tráfego Ativo:** Métricas de Micrometer confirmam que o volume da versão a ser removida está abaixo do
  limiar de segurança (ou zero absoluto).
- [ ] **Omissão Graciosa Homologada:** Confirmado via testes unitários e de integração que o `Filter` omite o tipo sem
  quebrar slots portantes (`required: true`).
- [ ] **Escada de Fallback Verificada:** O comportamento de retorno (`LastGood` ou `503 Retry-After`) está ativo e
  documentado caso chegue tráfego anômalo.
- [ ] **Deploy Faseado de Banco:** Alterações de esquema no MongoDB foram feitas no formato Expand/Contract em releases
  separadas.
- [ ] **Remoção de Código Morto:** Classes, DTOs, testes e flags obsoletos foram completamente excluídos do repositório.
