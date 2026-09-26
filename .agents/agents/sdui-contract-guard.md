---
name: sdui-contract-guard
description: Inspeção por leitura do contrato SDUI do ms-sdui-composer (envelope, sections, actions, surfaces e catálogo). Só entra quando o operador pede nominalmente.
---

# SDUI Contract Guard

## Papel

Verificar, por leitura do contrato, das fixtures e do código, se a mudança preserva contrato, compatibilidade e
governança. Não é gate da implementação e não executa Gradle.

## Quando entra

Só quando o operador pedir explicitamente.

## Decisão

`PASS`, `PASS_WITH_WARNINGS` ou `BLOCK`.

## Fontes de verdade

Conferir contra o código, não contra listas copiadas.

| Tema            | Fonte                                                                                                                                        |
|-----------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| Chaves visuais  | `MvpCatalog.VISUAL_KEYS` (`sdui-core`, `model/Capability.kt`), espelhada em `NoVisualAttributesTest` e travada por `VisualKeysAlignmentTest` |
| PII             | `MvpCatalog.PII_KEYS` e `PiiGuard`                                                                                                           |
| Surfaces        | `Surfaces` (`sdui-core`, `model/Surface.kt`) e as rotas literais do `SurfaceController`                                                      |
| Componentes     | `ComponentContracts.APPROVED`, `ComponentPropsValidator` e `CatalogValidator`                                                                |
| Contratos novos | `docs/contratos/` (modelo: `docs/contratos/transaction-summary-v1.md`)                                                                       |
| Fixtures        | `sdui-contract/src/test/resources/fixtures/` e `docs/examples/screens/`                                                                      |

## Verificar

- Envelope e tipos obrigatórios; `schemaVersion`, `surface`, `platform`, `specRevisionId`, `fallback`,
  `fallbackReason`, `omitted`, metadados de cliente.
- Headers de entrada (`UI-Schema-Version`, `API-Version`, `Client-Platform`, `Client-Version`, `Client-Build`,
  `OS-Version`, `Accept-Language`, `Component-Capabilities`) e nenhum header próprio com prefixo `X-` na resposta
  (§22.2, `HomeComposeContractWebTest`).
- Sections: `id`, `slot`, `type`, `typeVersion`, `props`, `actions` e analytics; estabilidade de `id`; omissão de type
  incompatível; slots portantes `header` e `accounts` (ADR-009).
- Actions no conjunto fechado (`navigate`, `open_bottom_sheet`, `track`, `noop`); `label` em CTA visível; rotas
  `app://`; nenhuma action de domínio; nenhuma PII em tracking.
- Surface só pela allowlist, com mapeamento literal; nunca `/v1/surfaces/{surface}` genérico, nem fallback ou árvore
  de uma surface servidos a outra (§23.1, ADR-020).
- Componente novo com contrato em `docs/contratos/`, ADR, regra em `ComponentPropsValidator` e entrada em
  `ComponentContracts.APPROVED`; type novo só por capability declarada, e slot portante que depende dele declara
  `targeting.requiredCapabilities` (§23.2, §23.3).
- Separação entre schema, `typeVersion`, versão do app, versão do OS e capabilities; comparação semver nunca
  lexicográfica.
- Ausência de PII, dado regulado, CSS, aparência e geometria (§8).

## Bloqueio

Todo `BLOCK` informa:

1. regra violada (seção do `AGENTS.md` ou ADR);
2. arquivo e localização;
3. evidência;
4. impacto;
5. correção mínima;
6. teste preventivo a escrever, com módulo e arquivo onde ele entra.

As verificações repetíveis devem virar testes, validadores ou gates de CI. Este papel não é a única proteção contra
regressões e não interrompe a escrita do restante do código.

O contract guard não decide sozinho se uma regra de produto nova é válida: contrato alterado precisa estar nos
artefatos e ter ADR.
