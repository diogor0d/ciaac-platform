# Documentação de funcionalidades da CIAACPlatform

- Atualizado: 2026-10-08 (`Europe/Lisbon`).
- Estado comum: código-fonte presente e módulos desativados ou fechados até
  validação explícita. Há verificações locais limitadas de runtime; a aceitação
  completa e integrações permanecem `UNVERIFIED`. Arena/AuthMe estão ativados
  no alvo; o 1v1 fixo e a recuperação após desconexão do oponente sintético passaram aceitação nativa
  em 2026-10-07; outros modos e equipas maiores continuam por verificar.

Este índice é o ponto de entrada para a documentação da CIAACPlatform.
`SOURCE-VERIFIED` descreve o checkout; os estados `RUNTIME-VERIFIED` remetem
para a evidência datada indicada e não ampliam o seu âmbito.

Ver [verificação funcional e critérios de aceitação](functional-verification.md)
para evidência automatizada, estado observado e ensaios Paper ainda pendentes.

A [validação dos nove minijogos](all-minigames-validation.md) regista o trabalho
local em curso, os critérios comuns e as lacunas antes da preparação final.

A [preparação de Sumo e Batata Quente](sumo-hot-potato-deployment.md) descreve
as instalações que o operador terá de construir antes da ativação.

A [navegação por menus de inventário](minigame-menus.md) documenta as opções
do jogador, confirmações e autorização administrativa.

## Modos de minijogo

| Documento | Âmbito | Estado presente |
| --- | --- | --- |
| [Coliseu](arena.md) | Arena, filas, equipamento protegido/apostado, isolamento e recuperação | `RUNTIME-VERIFIED` local em 2026-10-08: R5, 1v1 light, combate letal, saída, AuthMe e crash; restantes formatos/kits delimitados na [matriz](all-minigames-validation.md) |
| [Build Battle](build-battle.md) | Ciclo de vida, votação, parcelas e reset por template | `RUNTIME-VERIFIED` local: construção/votos, vitória, saída, AuthMe e crash; 18 campos e 200 células próprios restaurados |
| [Batata Quente](hot-potato.md) | Passe, temporizador, eliminações e recuperação | `RUNTIME-VERIFIED` local: três peers, passe/cooldown/pavio, vitória e recuperação fria |
| [Sumo de Repulsão](knockback-sumo.md) | Rondas, repulsão, fronteira da plataforma e resultados | `RUNTIME-VERIFIED` local: ring-out, timeout, saída/AuthMe e crash; física do peer é uma aproximação |
| [Parkour de Checkpoints](checkpoint-parkour.md) | Checkpoints, timeout, isolamento e resultados | `RUNTIME-VERIFIED` local: checkpoints ordenados, vitória, saída, AuthMe e crash; restantes condições na matriz |
| [Campo de Tiro com Arco](archery-range.md) | Lanes, alvos etiquetados, projéteis e resultados | `RUNTIME-VERIFIED` local: disparos reais, recusas, vitória e crash com seta confirmada; limpeza por UUID |
| [Fuga às Bigornas](anvil-dodge.md) | Ondas, perigos etiquetados, esquivas e recuperação | `RUNTIME-VERIFIED` local: duas ondas, recusa tardia, saída/AuthMe/crash e marcador removido |
| [Piso das Cores](color-floor.md) | Rondas, células, template e restauração | `RUNTIME-VERIFIED` local: conclusão, saída/AuthMe e crash; 4096 células restauradas por comparação nativa |
| [Anéis de Elytra](elytra-rings.md) | Percurso, anéis, chunks, voo e resultados | `RUNTIME-VERIFIED` local: cliente normal, impulso/dois anéis, saída, timeout, AuthMe e crash com foguete vivo |


## Módulos transversais

| Documento | Âmbito | Estado presente |
| --- | --- | --- |
| [Carrinhos](minecarts.md) | Limites terminais por mundo, comandos de teste e auditoria local | Configuração começa desativada; alterações temporárias não persistem |
| [Autenticação](authentication.md) | Conclusão de restauro, perfis e capacidades de ligação | nLogin fechado/preservado; AuthMe instalado no alvo, 25 contas convertidas e login normal verificado |
| [Passaporte CIAAC](passport.md) | Qualificação autenticada, épocas, recompensas, apresentação e recuperação | Desativado por omissão; elegibilidade depende da sessão atual |
| [Eventos de segurança](security-events.md) | Emissão local limitada e fronteira de privacidade do consumidor externo | Conversa pública desativada por omissão; execução do consumidor `UNVERIFIED` |
| [Autorização administrativa](admin-permissions.md) | Nós por ação, sugestões, defaults negados e herança LuckPerms observada | Grupos remotos consultados em 2026-10-04; execução por jogadores no backend `UNVERIFIED` |

Os seis modos reunidos no ficheiro de nome histórico
[catálogo agregado dos modos](future-modes.md) já estão implementados no
código-fonte; não são trabalho futuro. O documento conserva a visão conjunta de
Sumo, Parkour, Arco, Bigornas, Piso das Cores e Anéis de Elytra. Os seis modos têm percursos Paper locais aceites em 2026-10-08. A
[matriz datada](all-minigames-validation.md) identifica candidatos, cenários
e lacunas; nenhuma aceitação local substitui o ensaio de uma instalação real.

## Contratos partilhados

| Documento | Âmbito | Estado presente |
| --- | --- | --- |
| [Experiência do jogador](player-experience.md) | Entrada, mensagens pt-PT, apresentações, isolamento e recuperação comuns | Contratos presentes no código-fonte; listeners, displays e integrações `UNVERIFIED` |
| [Estatísticas e apresentações](statistics-and-displays.md) | Resultados idempotentes, persistência, classificações e projeções | Implementação de origem presente; entrega Paper/TAB/DiscordSRV `UNVERIFIED` |
| [Artefactos de templates nativos](template-artifacts.md) | Formato, checksum e aplicação limitada para Build Battle e Piso das Cores | `RUNTIME-VERIFIED` local para captura e reset de Build Battle/Chão de Cores; limites e instalações reais continuam delimitados nos guias |
| [Atualizador de versões](release-updater.md) | Manifesto assinado, preparação, reinício opcional e cache | `SOURCE-VERIFIED`, desativado por omissão; publicação, consumo Paper e reinício `UNVERIFIED` |
| [Adaptadores de estado externo](external-state-adapters.md) | Pré-validação de snapshots, isolamento e pré-requisitos dos fornecedores | Pré-validação `SOURCE-VERIFIED`; restauro local integrado dos nove modos verificado nos cenários da matriz; produção tem aceitação separada |

O [probe de física local](../tools/paper-runtime-probes/README.md) documenta um
ensaio reproduzível de pistões num mundo descartável. O seu resultado não prova
jogabilidade nem integração dos fornecedores.

## Operação externa

| Documento | Âmbito | Estado presente |
| --- | --- | --- |
| [Acesso local ao Crafty Controller por MCP](crafty-mcp.md) | Disponibilidade do cliente MCP no posto de trabalho e limites de verificação | `RUNTIME-VERIFIED` em 2026-10-03: HTTPS, autenticação, enumeração e estatísticas; mutações `UNVERIFIED` |

Para regressar ao README do componente, usar o título
[CIAACPlatform](../README.md#ciaacplatform). As decisões, operação e evidência
datada de componentes externos permanecem fora deste repositório.

A aceitação nativa do Coliseu, o JAR instalado e os limites de escopo estão em
[verificação funcional](functional-verification.md) e no [registo de ativação](arena-activation.md). Os checkpoints anteriores mantêm o seu valor histórico.
