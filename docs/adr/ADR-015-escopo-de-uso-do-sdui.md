# ADR-015: Escopo de Uso do SDUI — Surfaces Elegíveis e Surfaces Hostis

**Status:** `PROPOSTO`

**Data:** 2026-09-21

**Complementa:** ADR-004 (Screen sem Fragment), ADR-010 (sem seletor de renderização), ADR-011 (actions fechadas).
**Relaciona-se com:** ADR-016 (rejeição da proposta `docs/05`), ADR-018 (montagem variável da surface).

## Contexto

O `ms-sdui-composer` está em desenvolvimento. Hoje ele compõe uma surface, `home`, para iOS e Android, a partir de spec
versionada, contexto do cliente e capabilities declaradas. O objetivo declarado é atender montagens diferentes de tela
nos dois apps, e novas surfaces vão chegar.

O ADR-004 já prevê reabrir o desenho quando houver uma segunda surface, mas nenhum documento diz **quais** telas são
boas
candidatas a SDUI. A lacuna ficou visível com a proposta `docs/05`, que tentou provar que o backend monta "qualquer
tela" e modelou onboarding, passcode, carrinho, checkout e rastreamento com mapa. A própria referência citada ali (Joud
Wawad) classifica essas categorias como hostis a SDUI: alta performance (mapa, câmera, vídeo), baixa mudança
(autenticação, settings, onboarding) e offline-crítico (checkout, recibos).

Decidir o escopo agora, antes da segunda surface, é barato. Decidir depois de uma surface hostil já estar em construção
custa retrabalho nos três lados: servidor, iOS e Android.

## Decisão

### 1. Critérios de elegibilidade

Uma tela pode ser server-driven quando atende a **todos** os critérios:

1. **Muda mais rápido que o ciclo de release das lojas.** Se muda uma vez por trimestre, uma release resolve.
2. **É composição de blocos de leitura e navegação.** A interação se limita às actions do ADR-011.
3. **Degradação por omissão é aceitável.** O que é indispensável cabe no modelo de slot portante (ADR-009).
4. **É online-first.** O skeleton local mínimo do binário é uma experiência aceitável quando o servidor não responde
   (ADR-007).
5. **Não coleta dado sensível.** Nada de senha, PIN, OTP, dado de cartão, documento ou biometria.

### 2. Categorias hostis

| Categoria                | Exemplos                                      | Critério violado                                                                               |
|--------------------------|-----------------------------------------------|------------------------------------------------------------------------------------------------|
| Autenticação e segurança | login, passcode, PIN, OTP, biometria          | 5. Entrada segura exige `isSecureTextEntry` (iOS), `FLAG_SECURE` (Android) e teclado dedicado. |
| Onboarding e KYC         | abertura de conta, documento, selfie          | 1, 5                                                                                           |
| Transação e pagamento    | checkout, Pix, transferência, recibo          | 2, 4. Mutação com idempotência que precisa funcionar com rede instável.                        |
| Alta performance         | mapa, câmera, vídeo, AR                       | 2. O servidor não envia coordenada nem telemetria.                                             |
| Configurações            | settings, privacidade                         | 1                                                                                              |
| Formulários              | tela cuja função principal é capturar entrada | 2                                                                                              |

### 3. SDUI é porta de entrada, não o fluxo

Uma surface elegível pode **levar** a um fluxo hostil, nunca **contê-lo**. O atalho "Pix" é SDUI; a Área Pix é nativa.
O card de uma entrega é SDUI; o mapa é nativo. A ligação é sempre `navigate` para rota `app://`. Nenhuma action executa
mutação.

### 4. Como uma nova surface entra

Um ADR curto por surface, que mostre o atendimento aos critérios da seção 1 e declare skeleton, slots portantes e os
types que reutiliza ou introduz. O ADR é o registro; ele não precisa esperar a surface estar pronta para ser escrito.

## Consequências

### Pontos positivos

- A pergunta "isso pode ser SDUI?" ganha resposta verificável antes de alguém começar a construir.
- Os times iOS e Android sabem desde já o que o renderer SDUI precisa suportar e o que continua no código de cada
  feature.
- Fluxos regulados ficam fora de um serviço que não foi desenhado nem revisado para eles.

### Pontos negativos / trade-offs aceitos

- Mudanças em fluxos hostis continuam dependendo de release.
- Uma surface limítrofe precisa de argumento escrito para entrar. É intencional.

### Riscos e mitigações

- **Fluxo hostil disfarçado de section** (um card com campo de PIN). Mitigação: `PiiGuard` já recusa `password` e
  `senha`. Avaliar `pin`, `otp` e `passcode` em `PII_KEYS`, conferindo antes que o `PropWalk` compara chave exata.
- **Critérios rígidos demais para uma surface legítima.** Mitigação: o status é `PROPOSTO`. Os critérios devem ser
  testados contra a segunda surface real antes de virar `ACEITO`.

## Alternativas Consideradas

- **"Qualquer tela" (proposta `docs/05`).** Descartada; ver ADR-016.
- **Decidir caso a caso.** Descartada. Funciona com uma pessoa decidindo e deixa de funcionar quando o time cresce.

## Critérios de Validação

- `SpecValidator` recusa action fora de `ALLOWED_ACTIONS`; teste de regressão com `callApi`, `addToCart` e
  `completeOnboarding`.
- O ADR de cada nova surface cita esta decisão, critério por critério.
- Promoção a `ACEITO` depois de aplicado à segunda surface.
