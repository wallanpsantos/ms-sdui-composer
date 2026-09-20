# Contrato de retry — clientes iOS e Android (`GET /v1/surfaces/home`)

Documento de homologação. Define o que o app deve fazer com cada desfecho de `GET /v1/surfaces/home`. Deriva do
ADR-014.

O servidor **não** faz retry de nenhuma dependência: a escada de fallback do ADR-007 é a política de degradação.
Repetir é responsabilidade do cliente, e é seguro porque `GET` não tem efeito colateral. O que este contrato
estabelece é **quando** repetir, **quantas vezes** e **com que espaçamento** — sem isso, uma coorte inteira volta em
bloco e repete o pico que causou a recusa.

## Tabela de desfechos

| Status                     | Repetir?  | Política                                                                              |
|----------------------------|-----------|---------------------------------------------------------------------------------------|
| `200 OK`                   | —         | Renderizar. Guardar o `ETag` para a próxima requisição.                               |
| `304 Not Modified`         | —         | Manter a árvore em cache local. Não há corpo.                                          |
| `400 INVALID_HEADERS`      | **Nunca** | Terminal. É bug de cliente: os headers de negociação estão errados. Reportar e parar.  |
| `429 RATE_LIMITED`         | Sim       | Honrar `Retry-After`. Somar jitter local de ±40%. Orçamento: **2 tentativas**.          |
| `503 COMPOSE_UNAVAILABLE`  | Sim       | Honrar `Retry-After`. Backoff exponencial com jitter. Orçamento: **3 tentativas**.      |
| `500 INTERNAL_ERROR`       | Não       | Terminal. Invariante quebrada no servidor; repetir não muda o desfecho.                 |

Esgotado o orçamento de tentativas, o app **falha rápido** e usa a última árvore que ele mesmo guardou, exibindo o
estado degradado ao usuário. Não existe tentativa indefinida: retries sem teto num app instalado em milhões de
aparelhos são um ataque de negação de serviço contra o próprio backend.

## Jitter

O servidor já envia `Retry-After` perturbado em ±40% (429 na faixa 1–3 s; 503 na faixa 3–7 s), porque a chave do
limitador é `Client-Platform:Client-Build` — uma **coorte**, não um aparelho. Quando um build popular estoura o
bucket, todos os aparelhos daquele build são recusados no mesmo segundo.

O cliente deve **somar o seu próprio jitter** por cima. São duas defesas contra o mesmo problema, e a do cliente
continua valendo se uma versão futura do servidor deixar de enviar jitter.

## Revalidação com `ETag`

Toda resposta `200` traz `ETag` e `Cache-Control: private, max-age=60`. O app deve reenviar o valor em
`If-None-Match`. Uma revalidação que devolve `304` termina no servidor antes de compor qualquer coisa — é o caminho
mais barato dos dois lados e o que mais reduz carga em pico.

O `Vary` declara de quais headers a resposta depende. Qualquer cache intermediário precisa respeitá-lo, sob pena de
entregar a árvore de um contexto a outro.

## O que o cliente não deve fazer

- **Não** repetir `400` nem `500`. São terminais.
- **Não** ignorar `Retry-After` e usar intervalo fixo próprio.
- **Não** repetir sem teto, nem reiniciar o orçamento a cada tela.
- **Não** empilhar retry de camada de rede (biblioteca HTTP) com retry de camada de aplicação. Retries em camadas
  se multiplicam: duas camadas de três tentativas são nove requisições por gesto do usuário.

## Verificação na homologação

1. Forçar `503` no ambiente de homologação e observar que os instantes de retorno dos aparelhos se espalham, em vez
   de se concentrarem num mesmo segundo.
2. Confirmar que, esgotado o orçamento, o app exibe o conteúdo local em vez de tela de erro.
3. Confirmar que um `400` não gera nenhuma repetição.
4. Confirmar que o `ETag` está sendo reenviado, medindo a proporção de `304` no servidor pela tag `outcome` da
   métrica `compose.duration`.
