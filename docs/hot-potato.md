# Batata Quente

Estado em 2026-08-24:

- `SOURCE-VERIFIED`: domínio, controlador Paper, regras de passe e
  recuperação de sessão estão presentes.
- `UNVERIFIED`: não existe validação Paper descartável nem execução
  real da arena.

## Ciclo de vida

```text
DISABLED -> IDLE -> WAITING -> COUNTDOWN -> ENTRY_LOCKED -> RUNNING
RUNNING -> SUDDEN_DEATH -> RESULTS -> RESETTING -> CLOSED
Qualquer falha insegura -> RECOVERING
```

O módulo permanece fechado até o mundo, a fronteira da arena, os spawns, a área
de espectadores e a reposição serem validados. A composição é alterável apenas
em `WAITING`; durante a contagem e o jogo não são aceites novas entradas.

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
countdown-seconds: 1..3600
fuse-seconds: 1..3600
pass-range: 0.1..64.0
sudden-death-seconds: 0..3600
```

O operador deve confirmar a identidade exata do mundo, a região da arena, os
spawns, a área de espectadores e a política de reposição num ambiente
descartável.

## Resultados e classificações

Os resultados usam o modo `ffa` e métricas limitadas `wins`, `survival_ms`,
`passes`, `eliminated`, `forfeits` e `carrier_ms`. Desconexão, saída,
timeout ou restauração falhada produzem `NO_CONTEST` sem classificação. O
resultado só é registado depois de a restauração ser confirmada.

## Limites de execução

A ligação de listeners Paper, a reposição real da arena, displays, DiscordSRV e
a execução da regra do pavio continuam `UNVERIFIED`.
