# Runbook — rollback operacional Android (`home + android + canary|stable`)

Espelha o runbook iOS, com isolamento de plataforma: rollback Android **não** move pointer, cache, last-good ou revisão
iOS.

A fixture Android (`sdui-contract/src/test/resources/fixtures/contrato-sdui-home-android-proposto.json`) ainda **não** existe. Sem contrato Android
aprovado não há seed de conteúdo nem promoção. O runtime já isola `Client-Platform: android`.

## Rollback (checker)

1. `POST /admin/v1/pointers/home/android/{channel}:rollback` com `Idempotency-Key`.
2. Confirmar compose Android com o `specRevisionId` restaurado.
3. Confirmar compose iOS inalterado.
4. Registrar **tempo de pointer** e **tempo até a tela** (ou desconhecido).
