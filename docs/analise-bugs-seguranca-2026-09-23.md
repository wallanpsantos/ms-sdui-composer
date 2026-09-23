# Revisão estática de bugs e segurança — 2026-09-23

Escopo: composição HTTP, negociação, validação, governança, concorrência, adapters em memória,
MongoDB e Redis, confrontados com os testes existentes e as restrições do AGENTS.md.

Não foram executados Gradle, builds, testes, cargas, ataques ou chamadas à aplicação. Os cenários
abaixo são deduzidos dos fluxos do código; não são reproduções executadas. Nenhum código produtivo
foi alterado. P1 significa correção prioritária por indisponibilidade, segurança ou integridade;
P2 significa erro relevante com condição mais restrita. Não há evidência de uma JVM efetivamente
encerrada: há caminhos para HTTP 500 e riscos de esgotamento de recursos.

## R01 — P1: corpo JSON administrativo sem limite antes da desserialização

**Evidência:** `sdui-app/src/main/kotlin/br/com/empresa/sdui/api/admin/AdminController.kt:130–135`;
`sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/configuration/SduiConfiguration.kt:94–98`;
`sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/mongo/MongoSupport.kt:145–149`.

O corpo de `POST /admin/v1/specs` é materializado como `Spec` antes de executar `actor(headers)`.
Não há limite de bytes do corpo, tokens ou tamanho total das props na borda. O teto do adapter
Mongo chega depois da desserialização, validação e serialização completa com `DomainJson.write`;
no modo em memória esse teto nem se aplica. A limitação de profundidade não limita largura ou
quantidade de strings de um JSON.

**Cenário:** se o endpoint for alcançável sem um limite externo de corpo, requisições grandes ou
concorrentes podem consumir o heap antes da recusa de autorização/validação. Um corpo com muitos
valores pequenos também contorna limites individuais de string. O efeito possível é GC intenso,
`OutOfMemoryError` e indisponibilidade do processo. Não foi feito ensaio de exaustão.

**Correção:** impor teto na leitura do corpo antes do binding, inclusive para transferência
chunked; limitar tokens e coleções; admitir um número limitado de requisições administrativas.
Autorização anterior à leitura também reduz a exposição.

O `maxPostSize` do Tomcat não é um limite geral para JSON, conforme a
[documentação oficial do conector](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html).
O Jackson também não limita por padrão comprimento total ou quantidade de tokens, conforme
[StreamReadConstraints](https://github.com/FasterXML/jackson-core/blob/3.x/src/main/java/tools/jackson/core/StreamReadConstraints.java).

## R02 — P1: overflow de schema é convertido silenciosamente em schema 3

**Evidência:** `sdui-core/src/main/kotlin/br/com/empresa/sdui/core/negotiate/Negotiate.kt:22,35–38`;
`sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/ClientContext.kt:34`;
`sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/compose/ComposeScreenService.kt:158–176`.

`UI-Schema-Version: 2147483648` passa no regex de até dez dígitos, mas excede `Int.MAX_VALUE`.
`SemVer.parse` devolve null e `ClientContext` substitui o valor por `SemVer(3, 0, 0)` para seleção.
O texto original continua sendo usado na chave de cache, ETag e envelope.

**Cenário:** com os demais headers válidos para a Home canônica, variar o schema entre números
de dez dígitos acima de `Int.MAX_VALUE` seleciona a mesma revisão como schema 3, mas cria uma
chave diferente por valor. Não exige acesso administrativo. Isso permite provocar misses e,
com Redis, alimentar o crescimento de R03. O envelope também anuncia um schema não suportado.

**Correção:** rejeitar parsing inválido e schemas fora da allowlist na negociação; carregar no
contexto um schema já validado, sem valor substituto silencioso.

## R03 — P1: índice de árvores no Redis retém membros de entradas expiradas

**Evidência:** `sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/redis/RedisCaches.kt:100–114`.

Cada escrita faz `SADD` no índice do escopo e renova o TTL do conjunto inteiro. A expiração da
chave de árvore não remove seu membro desse conjunto. Enquanto houver escritas antes do TTL
do índice, o conjunto retém o histórico de chaves até uma invalidação administrativa.

**Cenário:** com `cache=redis`, tráfego contínuo com chaves distintas, especialmente o de R02,
acumula membros mesmo depois de as árvores vencerem. Na invalidação, `members()` traz o conjunto
inteiro para o heap e cria outra lista de strings. Há risco de pressão de memória no Redis e na
JVM, além de invalidação lenta. `maxEntryBytes` limita cada árvore, não o índice.

**Correção:** usar índice com expiração por membro e poda limitada, ou invalidar incrementalmente
sem um conjunto histórico ilimitado. Limitar a cardinalidade admitida independentemente do TTL.

## R04 — P1: identificador aceito na publicação causa HTTP 500 persistente

**Evidência:** `sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/admin/AdminServices.kt:117–123`;
`sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/Screen.kt:70`;
`sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/compose/ComposeScreenService.kt:178`.

`specRevisionId` não é validado contra as restrições da chave. Um identificador como
`rev_userId_1` passa pelos validadores de publicação, mas `RedisKeys.containsUserId` procura
essa substring em toda a chave. O `check` da composição fica fora dos blocos que acionam fallback.

**Cenário:** publicar uma cópia válida da Home com esse identificador e apontar o pointer para
ela. GETs que selecionem a revisão, sem revalidação antecipada por ETag, lançam
`IllegalStateException` e recebem HTTP 500. A situação persiste até corrigir o pointer.

**Correção:** validar identificadores na governança antes de armazenar/publicar e garantir que
todo valor publicado possa formar uma chave válida. Manter uma saída controlada no pipeline
para dados legados que violem essa invariante.

## R05 — P1: revisão inexistente de skeleton permite alteração de tela publicada por rascunho

**Evidência:** `sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/port/outbound/Stores.kt:81–83`;
`sdui-core/src/main/kotlin/br/com/empresa/sdui/core/validate/SpecValidator.kt:44–49`;
`sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/admin/AdminServices.kt:107,265,287–288`.

`findFor` procura a revisão referenciada, mas usa `current(skeletonId)` se não a encontrar.
O validador confere ID e surface, sem exigir igualdade de revisão. A publicação não corrige a
referência do spec e `current` também pode devolver um skeleton DRAFT.

**Cenário:** publicar spec com `skeletonRevision=999` quando existe apenas a revisão 1. A
publicação usa a revisão 1. Depois criar um rascunho de skeleton revisão 2: composições sem
árvore cacheada passam a usar a revisão 2, sem nova aprovação do spec. Layout/ordem podem mudar
com o mesmo identificador e ETag; adicionar um slot obrigatório sem section pode levar ao
fallback e, na ausência de last good utilizável, a 503.

**Correção:** exigir resolução exata da revisão na autoria/publicação e no runtime, recusando
referências ausentes. Não usar o skeleton corrente como substituto de uma referência versionada.

## R06 — P1: pedido aberto não fixa o conteúdo que o checker aprova

**Evidência:** `sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/admin/AdminServices.kt:103–124,202–224,255–280`.

A abertura calcula o diff, mas deixa o spec editável. A aprovação relê o rascunho atual pelo par
`specId/revision`; não compara seu conteúdo com o revisado nem exige que `specRevisionId`
continue igual ao do pedido. Quando há pai, apenas verifica que algum diff daquele par existe.

**Cenário:** abrir pedido para conteúdo A; consultar seu diff; salvar conteúdo B na mesma revisão;
aprovar o pedido original. B é publicado usando um pedido/diff que descreve A. A edição pode
inclusive trocar `specRevisionId`, surface ou plataforma, se o novo conjunto passar na validação.
Isso quebra a integridade do maker-checker sem precisar de requisições simultâneas.

**Correção:** vincular o pedido a um snapshot ou digest calculado do conteúdo e impedir edição
da revisão em aprovação, ou recusar atomicamente a aprovação se conteúdo/identidade mudaram.

## R07 — P2: criação concorrente de rascunhos perde conteúdo silenciosamente

**Evidência:** `sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/admin/AdminServices.kt:103,116–124`;
`sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/memory/InMemoryStores.kt:69–84,103–104`;
`sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/mongo/MongoGovernanceStores.kt:64–70,122–124`.

A descoberta de `nextRevision` é separada da gravação. Os dois adapters permitem substituir
uma revisão DRAFT já existente; o lock da gravação em memória não cobre a escolha do número.

**Cenário:** duas chamadas para criar a próxima revisão do mesmo spec observam N e escolhem N+1.
Ambas podem retornar sucesso, mas a segunda substitui o conteúdo da primeira. No Mongo, isso
também pode ocorrer entre duas instâncias: o filtro permite substituir um documento não publicado.

**Correção:** separar criação de revisão de edição de rascunho; criação deve reservar número e
inserir sem sobrescrita. Edição precisa conferir uma versão esperada para detectar concorrência.

## R08 — P1: specRevisionId duplicado em memória quebra identidade de pointer e cache

**Evidência:** `sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/memory/InMemoryStores.kt:69–82`;
`sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/mongo/MongoSupport.kt:116`.

O store em memória protege somente o par `specId/revision`. O índice `byRevisionId` aceita
sobrescrever o mesmo `specRevisionId` com outro spec. O Mongo tem índice único para esse campo;
o modo padrão em memória não reproduz a garantia.

**Cenário:** criar e publicar outro spec na mesma surface/plataforma reutilizando um
`specRevisionId` existente. Pointer e caches identificam ambos pelo mesmo ID. A seleção pode
resolver o novo objeto enquanto a árvore cacheada ainda contém o anterior; o ETag não permite
ao cliente distinguir os conteúdos. Um rollback para esse ID também fica ambíguo.

**Correção:** exigir unicidade global de `specRevisionId` sob o mesmo lock da gravação e devolver
conflito para tentativa de reutilização por outro par de spec/revisão.

## R09 — P2: segunda aprovação deixa pedido falsamente aprovado no modo em memória

**Evidência:** `sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/admin/AdminServices.kt:189–224,275–285`;
`sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/memory/InMemoryStores.kt:72–73,580–588`.

É possível abrir dois pedidos distintos para a mesma revisão DRAFT. A aprovação muda primeiro o
status do pedido e depois grava o spec. A unidade de trabalho em memória não desfaz efeitos.

**Cenário:** abrir pedidos A e B; aprovar A; aprovar B. B ainda está OPEN, passa pelas verificações
e muda para APPROVED. `specStore.save` então lança `IllegalStateException` porque a revisão já
foi publicada por A. B permanece APPROVED, apesar do HTTP 500, sem a conclusão correspondente
de auditoria/idempotência. No Mongo o rollback protege o estado, mas o conflito ainda vira 500.

**Correção:** recusar publicação de revisão já publicada dentro da mesma região de consistência,
antes de alterar o pedido, e tratar o conflito de domínio. O modo em memória precisa garantir
atomicidade dos efeitos que apresenta como uma transação, ou evitar essa transição parcial.

## R10 — P2: reserva expirada de idempotência não tem identidade do proprietário

**Evidência:** `sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/memory/InMemoryStores.kt:280–303`;
`sdui-app/src/main/kotlin/br/com/empresa/sdui/adapters/mongo/MongoCoordinationStores.kt:45–74`;
`sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/admin/AdminServices.kt:546–551`.

A retomada de reserva vencida não produz um token de proprietário. `complete` substitui por
chave e `release` remove qualquer reserva em voo daquela chave, mesmo que outra chamada a
tenha adquirido depois do vencimento.

**Cenário:** A reserva a chave e fica atrasada além do prazo; B retoma a chave; A volta e falha.
O `release` de A remove a reserva de B, liberando C para executar em paralelo. Se A concluir,
pode substituir a reserva/resultado de B. A janela depende de atraso superior ao timeout
configurado; não foi observada em execução.

**Correção:** atribuir token/geração por reserva; exigir esse token em complete/release e no
commit do efeito, recusando conclusões de proprietários vencidos.

## R11 — P2: soma de SemVer no validador lança exceção por entrada aceita

**Evidência:** `sdui-core/src/main/kotlin/br/com/empresa/sdui/core/validate/SpecValidator.kt:138–140`;
`sdui-core/src/main/kotlin/br/com/empresa/sdui/core/model/SemVer.kt:10–12`.

Para faixa sem máximo, o validador cria `SemVer(min.major, min.minor + 50, 0)`. A soma de `Int`
transborda quando o minor é suficientemente próximo de `Int.MAX_VALUE`.

**Cenário:** enviar rascunho com mínimo `{major: 8, minor: 2147483647, patch: 0}` e máximo null.
O mínimo em si é aceito por `SemVer`, mas a amostra sintética tem minor negativo e o construtor
lança `IllegalArgumentException`. O resultado é HTTP 500 em vez de erro de validação.

**Correção:** evitar a soma sem verificação; gerar amostras válidas nas transições reais da matriz
ou estabelecer limites explícitos antes de construir a amostra.

## R12 — P2: last good não verifica compatibilidade do protocolo de envelope

**Evidência:** `sdui-app/src/main/kotlin/br/com/empresa/sdui/orchestrator/compose/FallbackCoordinator.kt:92–120`;
`sdui-core/src/main/kotlin/br/com/empresa/sdui/core/negotiate/Negotiate.kt:35–38`.

Depois da idade, o fallback verifica capabilities e ocupação dos slots obrigatórios, mas não o
schema solicitado. O last good é compartilhado por surface/plataforma/canal, sem schema na chave.

**Cenário:** uma Home schema 3 aquece o last good. Outro cliente com os mesmos headers válidos,
mas `UI-Schema-Version: 2`, não encontra spec compatível e recebe o envelope schema 3 como 200
fallback. A existência de cache decide se o protocolo incompatível é servido ou resulta em 503.

**Correção:** verificar compatibilidade de envelope antes de servir fallback e/ou rejeitar schema
não suportado já na negociação. Não basta filtrar componentes.

**Distinção:** o teste `HomeCompatibilityWebTest.kt:114–132` permite explicitamente fallback fora
da faixa de SO. Essa política não foi contada como bug; este achado é sobre o eixo de protocolo.

## Risco de segurança já documentado

`AdminController.kt:307–311` monta o ator diretamente de `Actor-Id` e `Actor-Role`. Quem alcançar
os endpoints pode reivindicar MAKER ou CHECKER e usar duas identidades diferentes. Isso já está
explicitado no `README.md:258` e no ADR-021; não foi tratado como descoberta nova. É condição
necessária respeitar a fronteira externa de acesso antes de expor o plano administrativo.

## Null pointers, hipóteses descartadas e limites

- Não foi confirmado um NPE diretamente alcançável por uma requisição normal nos fluxos revistos.
  Isso não é prova de ausência de NPE em toda a aplicação.
- Não foi registrado o suposto bug de `List<T>` receber null por padrão: o
  [KotlinModule 3.x habilita StrictNullChecks por padrão](https://raw.githubusercontent.com/FasterXML/jackson-module-kotlin/3.x/src/main/kotlin/tools/jackson/module/kotlin/KotlinFeature.kt).
- Não foi afirmado StackOverflowError explorável apenas por profundidade HTTP: o Jackson possui
  teto próprio, embora os walkers posteriores ao teste de profundidade não parem imediatamente.
- Não foi executada auditoria de CVEs das dependências, nem verificada a configuração do gateway
  ou a implantação real. Os adapters persistentes continuam sem homologação, conforme AGENTS.md.
- Os testes lidos cobrem caminhos relacionados, mas não fornecem evidência executada dos cenários
  novos. Antes de corrigir, transformar cada cenário escolhido em regressão; executar a suíte
  somente mediante pedido humano e uma única vez no final, conforme a regra operacional.
