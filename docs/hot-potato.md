# Batata Quente

Última atualização: 2026-10-08.

- `RUNTIME-VERIFIED` num Paper descartável com três clientes autenticados:
  fila/countdown, teleports para mundo dedicado, passe nativo e passe de
  regresso, rejeição durante cooldown, eliminação pelo pavio, vitória,
  desconexão/reauth e restauro exato dos 18 campos observados.
- No candidato `6792e19…`, o crash frio, reinício e autenticação normal AuthMe
  restauraram exatamente os 18 campos Paper no fixture descartável. As
  instalações de produção, física completa de cliente vanilla e integrações
  externas ainda exigem aceitação.
  Ver [evidência funcional](functional-verification.md) e
  [preparação das instalações](sumo-hot-potato-deployment.md).

## Ciclo de vida

```text
DISABLED -> IDLE -> WAITING -> COUNTDOWN -> ENTRY_LOCKED -> RUNNING
RUNNING -> SUDDEN_DEATH -> FINISHING -> RESTORING -> CLOSED
Qualquer falha insegura -> RECOVERING -> RESTORING -> CLOSED
```

O módulo permanece fechado até o mundo, a fronteira da arena e todos os spawns
serem validados. Cada spawn tem de ficar dentro da região `arena`. A composição
é alterável durante a fila; durante a contagem e o jogo não são aceites novas
entradas.

## Mecânicas

No início de `RUNNING`, um jogador recebe a batata. O passe é uma operação
idempotente ligada ao encontro, ao portador atual e ao jogador recetor. O
controlador verifica a distância, a sessão e o estado de ambos os jogadores;
não aceita eventos antigos, passes duplicados ou jogadores fora da arena.

O tempo restante é limitado e a ronda termina quando a batata explode, quando
o jogador é eliminado ou quando o período de morte súbita termina. A eliminação
é controlada: não cria drops de morte nem altera o inventário de sobrevivência.
A apresentação pode usar partículas e sons, mas nunca é a fonte de verdade.

## Isolamento e recuperação

`GameKey.HOT_POTATO` usa `DEDICATED_WORLD`. O token de admissão mantém cada
jogador na região do encontro e o router trata movimento, teleporte, dano,
passagem, morte, saída, expulsão e desconexão.

Antes de alterar o estado, `SessionCoordinator` guarda o snapshot do jogador.
Conclusão, cancelamento, timeout, encerramento e falha de restauração revogam
tokens e seguem o percurso de recuperação. O encontro seguinte só pode abrir
depois de todas as sessões estarem restauradas.

## Configuração

```text
minimum-players: 2..64
maximum-players: minimum..64
initial-fuse-seconds: 1..3600
minimum-fuse-seconds: 1..initial-fuse-seconds
fuse-reduction-per-round-seconds: 0..initial-fuse-seconds
pass-cooldown-milliseconds: 0..60000
pass-range-blocks: 0.1..32
match-timeout-seconds: 1..86400
spawns.<id>: {x, y, z, yaw, pitch}
```

O número de entradas `spawns.*` tem de ser pelo menos `maximum-players`; cada
uma tem de estar dentro da região `arena`. A contagem decrescente é fixa em 20
segundos; os tokens cobrem pelo menos 10 minutos e todo o timeout configurado
mais um segundo.

O operador deve confirmar a identidade exata do mundo, a região da arena, os
spawns, a área de espectadores e a política de reposição num ambiente
descartável.

## Resultados e classificações

Os resultados usam o modo `ffa` e métricas limitadas `wins`, `survival_ms`,
`passes`, `eliminated`, `forfeits` e `carrier_ms`. Desconexão, saída,
timeout ou restauração falhada produzem `NO_CONTEST` sem classificação. O
resultado só é registado depois de a restauração ser confirmada.

Se um participante sair ou desconectar durante a partida, toda a ronda termina
como `NO_CONTEST`. Quando alguém está offline, a recuperação fica pendente e a
arena recusa novas entradas até a sessão ser restaurada após autenticação.

## Limites de execução

A faceta de mundo confirma a preservação de uma instalação imutável; não
reconstrói terreno. Displays externos e DiscordSRV continuam `UNVERIFIED`.
A linha de visão e a fronteira têm validação no controlador; obstáculos,
entrada pública de espectadores e geometria real têm de ser ensaiados antes
da ativação de produção.
