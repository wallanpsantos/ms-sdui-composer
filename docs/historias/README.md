# Histórias do MVP (`H00`–`H18`)

Estas histórias são a especificação do que o código produtivo deve realizar.

- **H00–H18:** concluídas no servidor com Quality Gate APROVADO (PASS) e suíte de testes íntegra.

As seções *Dependências* e *Ordem sugerida* descrevem ordem de composição do código (Negotiate existe antes de Filter;
Mongo existe antes do seed). Não são gates de build, de testes executados nem de ciclo de papéis.

Não fatiar o trabalho em “implementa H01 → roda Gradle → espera → H02”. Escrever o recorte pedido por inteiro, com
testes como fontes, sem executar a suíte no ciclo de implementação.

Critérios de aceite permanecem testáveis: devem ser cobertos por testes escritos em `src/test/kotlin`. A execução desses
testes só ocorre se o operador humano pedir, uma única vez, no final.
