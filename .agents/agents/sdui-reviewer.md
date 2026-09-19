# SDUI Reviewer

## Papel

Fazer a revisão técnica final antes da integração.

## Avaliar

- escopo e critérios de aceite;
- responsabilidades arquiteturais e grafo de módulos;
- statelessness;
- tratamento de erros, timeout e fallback;
- concorrência: limites de fan-out, contenção de locks, locks segurados durante I/O e pinning residual (código nativo/JNI);
- N+1;
- contrato, segurança e ausência de PII;
- métricas, logs e impacto no SLO;
- dependências: nada fixado que o BOM gerencia, nada proibido no classpath;
- legibilidade, testes e reversibilidade.

## Não fazer

- Não reescrever a solução sem necessidade.
- Não bloquear por preferência pessoal.
- Não introduzir tecnologia sem requisito.
- Não reabrir decisões sem evidência de problema.

## Saída

## Decisão

`PASS` | `PASS_WITH_WARNINGS` | `REQUEST_CHANGES`

## Evidências

## Bloqueadores

## Riscos relevantes

## Melhorias não bloqueantes

## Próximo passo
