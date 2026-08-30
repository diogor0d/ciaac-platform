# Documentação de funcionalidades da CIAACPlatform

- Documentado: 2026-08-28 (`Europe/Lisbon`).
- Estado comum: código-fonte presente e módulos desativados ou fechados até
  validação explícita; execução Paper, jogabilidade, integrações e implantação
  permanecem `UNVERIFIED`.

Este índice é o ponto de entrada para a documentação de implementação da
CIAACPlatform. Os estados abaixo descrevem o checkout; não provam instalação,
ativação ou comportamento num servidor real.

## Modos de minijogo

| Documento | Âmbito | Estado presente |
| --- | --- | --- |
| [Coliseu](arena.md) | Arena, filas, equipamento protegido/apostado, isolamento e recuperação | Domínio `SOURCE-VERIFIED` e adaptador presente mas fechado; execução/implantação `UNVERIFIED` |
| [Build Battle](build-battle.md) | Ciclo de vida, votação, parcelas e reset por template | `SOURCE-IMPLEMENTED`; Paper, mundo e template `RUNTIME-UNVERIFIED` |
| [Batata Quente](hot-potato.md) | Passe, temporizador, eliminações e recuperação | `SOURCE-IMPLEMENTED`; arena Paper `RUNTIME-UNVERIFIED` |
| [Sumo de Repulsão](knockback-sumo.md) | Rondas, repulsão, fronteira da plataforma e resultados | `SOURCE-IMPLEMENTED`; aceitação Paper `RUNTIME-UNVERIFIED` |
| [Parkour de Checkpoints](checkpoint-parkour.md) | Checkpoints, timeout, isolamento e resultados | `SOURCE-IMPLEMENTED`; percurso Paper `RUNTIME-UNVERIFIED` |
| [Campo de Tiro com Arco](archery-range.md) | Lanes, alvos etiquetados, projéteis e resultados | `SOURCE-IMPLEMENTED`; entidades e ligação Paper `RUNTIME-UNVERIFIED` |
| [Fuga às Bigornas](anvil-dodge.md) | Ondas, perigos etiquetados, esquivas e recuperação | `SOURCE-IMPLEMENTED`; aceitação Paper `RUNTIME-UNVERIFIED` |
| [Piso das Cores](color-floor.md) | Rondas, células, template e restauração | `SOURCE-IMPLEMENTED`; mutação/restauração Paper `RUNTIME-UNVERIFIED` |
| [Anéis de Elytra](elytra-rings.md) | Percurso, anéis, chunks, voo e resultados | `SOURCE-IMPLEMENTED`; chunks, voo e aceitação Paper `RUNTIME-UNVERIFIED` |

Os seis modos reunidos no ficheiro de nome histórico
[catálogo agregado dos modos](future-modes.md) já estão implementados no
código-fonte; não são trabalho futuro. O documento conserva a visão conjunta de
Sumo, Parkour, Arco, Bigornas, Piso das Cores e Anéis de Elytra. A admissão e a
execução Paper continuam por validar.

## Contratos partilhados

| Documento | Âmbito | Estado presente |
| --- | --- | --- |
| [Experiência do jogador](player-experience.md) | Entrada, mensagens pt-PT, apresentações, isolamento e recuperação comuns | Contratos presentes no código-fonte; listeners, displays e integrações `RUNTIME-UNVERIFIED` |
| [Estatísticas e apresentações](statistics-and-displays.md) | Resultados idempotentes, persistência, classificações e projeções | Implementação de origem presente; entrega Paper/TAB/DiscordSRV `RUNTIME-UNVERIFIED` |
| [Artefactos de templates nativos](template-artifacts.md) | Formato, checksum e aplicação limitada para Build Battle e Piso das Cores | `SOURCE-IMPLEMENTED`; artefactos, chunks e mutações Paper `RUNTIME-UNVERIFIED` |
| [Atualizador de versões](release-updater.md) | Manifesto assinado, preparação, reinício opcional e cache | `SOURCE-IMPLEMENTED`, desativado por omissão; publicação, consumo Paper e reinício `RUNTIME-UNVERIFIED` |

Para decisões, operação e evidência datada, regressar à matriz
[Documentação relacionada](../README.md#documentação-relacionada) do componente.
