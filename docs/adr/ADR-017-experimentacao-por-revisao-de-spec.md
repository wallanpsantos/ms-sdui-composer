# ADR-017: Experimentação por Revisão de Spec

**Status:** `PROPOSTO`

**Data:** 2026-09-21

**Relaciona-se com:** ADR-007, ADR-009, ADR-014, ADR-018, H13 (canary), `AGENTS.md` §19.10 (chave de cache por
`specRevisionId`). **Originado por:** ADR-016.

**Gatilho para promover:** primeiro pedido concreto de teste A/B, com hipótese, métrica e duração.

## Contexto

A proposta rejeitada pelo ADR-016 trazia uma necessidade real: testar variações de tela sem release. A forma proposta,
`flagId` por nó, não serve a este serviço:

1. **Cache.** A árvore é chaveada por `specRevisionId`. Variar sections por usuário dentro da mesma revisão exige pôr o
   bucket, ou o usuário, na chave.
2. **Validação.** O ADR-009 verifica slot portante no publish. Com N flags por nó, seriam 2^N combinações.
3. **Canal.** A H13 fixa que canal não é feature flag e que não existe spec por combinação flag × schema × plataforma.
4. **Rastreabilidade.** O `specRevisionId` deixaria de identificar o que o usuário viu.

Como o serviço está em desenvolvimento, é o momento certo de reservar espaço no modelo de pointer para isso, mesmo que a
implementação espere o primeiro caso real.

## Decisão (proposta)

**Um braço de experimento é uma revisão de spec inteira.** Nunca uma section ou nó. Com o ADR-018, um braço pode diferir
em conteúdo, em montagem (ordem e layout dos slots) ou nos dois.

### Modelo

O pointer de `surface + platform + channel` ganha um campo opcional:

```json
{
  "experiment": {
    "id": "exp_home_shortcuts_grid",
    "endsAt": "2026-11-30T00:00:00Z",
    "arms": [
      {
        "name": "control",
        "specRevisionId": "rev_home_a",
        "weight": 50
      },
      {
        "name": "grid",
        "specRevisionId": "rev_home_b",
        "weight": 50
      }
    ]
  }
}
```

- Cada braço é uma revisão `PUBLISHED` que passou por todas as validações de publish.
- Experimentos só existem em `stable`. Canary continua sendo liberação por build.
- iOS e Android têm pointers independentes; um experimento é sempre de uma plataforma.

### Atribuição

- Header opcional `Experiment-Bucket`: inteiro de 0 a 999, calculado **no dispositivo** a partir de um identificador de
  instalação. O servidor nunca recebe o identificador.
- `slot = (hash(experiment.id) + bucket) mod 1000`, mapeado para braço pelos pesos acumulados. O deslocamento evita que
  os mesmos usuários caiam sempre no primeiro braço.
- Header ausente ou inválido, ou experimento vencido (`endsAt`): braço `control`.
- A atribuição acontece no `SELECT`, antes da consulta ao cache.

### Limites

Um experimento ativo por `surface + platform + channel`; no máximo três braços; pesos inteiros somando 100; mudanças
de peso e encerramento por maker-checker; rollback do pointer encerra o experimento.

### Cache, fallback e analytics

- A chave de árvore continua sendo `specRevisionId`: árvores cacheadas = número de braços.
- Em degradação, o last good pode ser de outro braço; o envelope sai com `fallback=true` e a análise exclui essas
  respostas.
- O `envelope.specRevisionId` já identifica o braço. Nenhum campo novo no contrato de fio.
- Métrica de servidor com tag `experimentArm` (cardinalidade máxima 3).

## Consequências

### Pontos positivos

- Experimentos com as mesmas garantias de qualquer publicação: imutabilidade, validação, rollback e auditoria.
- Mudança de contrato mínima: um header opcional.

### Pontos negativos / trade-offs aceitos

- Variar uma section exige uma revisão inteira por braço. A duplicação é o que mantém validação e cache simples.
- Os apps precisam calcular e enviar o bucket, com a mesma regra em iOS e Android.

### Riscos e mitigações

- **Experimento esquecido.** `endsAt` obrigatório; vencido vira `control` com métrica própria.
- **Braços com targeting divergente.** O publish recusa braço cujo targeting difira do `control`.
- **Bucket manipulado.** Aceitável: ambos os braços passaram pelas mesmas validações.

## Alternativas Consideradas

- **`flagId` por nó.** Descartada pelos quatro motivos do Contexto.
- **Serviço externo de flags no compose.** Chamada remota no hot path, sem retry (ADR-014), e cache por usuário.
- **Canal como experimento.** Proibido pela H13.
- **Flags só no cliente.** Serve para comportamento do renderer, não para estrutura.

## Critérios de Validação

- Mesmo bucket e mesmo experimento retornam sempre o mesmo braço; distribuição respeita os pesos; header ausente,
  inválido ou experimento vencido retornam `control`.
- Publish recusa mais de três braços, pesos que não somam 100, braço não `PUBLISHED` ou targeting divergente.
- Cache de árvore para dois braços tem duas entradas; nenhuma chave contém bucket ou identificador de dispositivo.
- Rollback remove o experimento e invalida o last good (ADR-014, regra 6).
