# Sumo de Repulsão

Estado em 2026-08-24 (endurecimento do código-fonte reconciliado):

- `SOURCE-IMPLEMENTED`: `SumoSession`, `SumoPaperController`, `SumoModule`,
  o resolver, o assembler, o encaminhador partilhado de eventos e o registo
  de resultados estão presentes nesta cópia de trabalho.
- `RUNTIME-UNVERIFIED`: não foi executada qualquer instalação, teste de
  aceitação Paper em funcionamento ou teste Paper descartável. Os testes Maven
  completos e Paper descartável ficam adiados.

## Experiência do jogador e comandos

A rota em português é `/sumo`:

```text
/sumo estado
/sumo ajuda
/sumo entrar
/sumo sair
```

O encaminhador partilhado também expõe `/sumo pronto`, mas este módulo devolve
`READY_UNSUPPORTED`, porque o Sumo começa quando entra o segundo jogador
admitido. Os comandos genéricos de estado e estatísticas são:

```text
/minijogos estado
/minijogos top knockback-sumo
/minijogos estatisticas knockback-sumo
```

A rota utiliza mensagens de falha/retorno em português, com conteúdo limitado,
como `A arena está cheia.`, `Não podes sair do ringue de Sumo.` e
`O golpe não foi aceite; o ringue permanece protegido.`

## Mecânicas e ciclo de vida

O resolver fixa intencionalmente a lista em dois jogadores (`minimum-players` e
`maximum-players` são ambos exatamente `2`). O módulo mantém um único ID de
partida para a fila e para a partida resultante. O controlador admite o primeiro
jogador, teletransporta-o para `side-a`, marca o item de repulsão configurado e
admite o segundo jogador em `side-b`. A sessão pura passa então pelos estados de
espera, execução, conclusão/cancelamento e encerrado. `best-of-rounds` controla
o número de vitórias de rondas necessário. Após uma saída do ringue que não
termine a partida, o controlador limpa ambas as velocidades, repõe a distância
de queda e devolve cada jogador ao lado configurado. Uma reposição de ronda que
falhe encerra a partida através da recuperação, em vez de continuar a partir de
uma posição ambígua.

A saída do ringue é um resultado controlado. O controlador trata
`fall-threshold-y` configurado ou a saída da fronteira registada como uma saída
do ringue. O encaminhador central de eventos cancela o dano de combate normal,
encaminha apenas um impulso de repulsão limitado e calculado pelo servidor
entre os dois jogadores ativos e converte eventos de queda/vazio e de morte
controlada em eventos de saída do ringue. Os IDs de evento do domínio rejeitam
operações de saída do ringue atrasadas ou repetidas.

O ciclo do controlador aplica agora `round-timeout-seconds` através da sessão
pura. No prazo limite, a ronda atual termina como empate determinístico
`ROUND_TIMEOUT` (a ordem da lista é mantida apenas por compatibilidade), a
partida regista dois jogadores em primeiro lugar com métricas limitadas de
empates/rondas, repõe ambas as fotografias de estado e encerra. Uma operação de
tempo limite repetida não pode produzir um segundo resultado. A desconexão/saída
continua a ser um caminho de recuperação sem competição.

## Isolamento, posicionamento e recuperação

`GameKey.KNOCKBACK_SUMO` tem a política de localização `SPAWN_SAFEZONE`. O
mundo e as localizações resolvidos têm, portanto, de corresponder à colocação
protegida da safezone de spawn; o assembler atribui à região dos participantes
o nome `knockback-sumo.boundary` e às duas localizações de spawn configuradas os
nomes `side-a` e `side-b`. O controlador falha de forma segura se a região não
estiver registada para este jogo e exigir admissão. A geometria sob
responsabilidade do operador deve permanecer imutável e separada dos
espectadores públicos.

A admissão utiliza `SessionCoordinator`, um `RegionAdmissionToken` por jogador
e o equipamento temporário marcado. A saída, desconexão, falha de preparação,
ronda terminal e encerramento do módulo utilizam a recuperação/reposição da
sessão e revogam a admissão. O módulo expõe o ID atual da fila/partida para
correlação de eventos. Nenhum inventário de sobrevivência, item largado ou
resultado de morte vanilla é a fonte autoritativa deste jogo.

O tratamento de saída/expulsão executa a desconexão do controlador seguida da
limpeza do wrapper, para que a lista de participantes e o marcador da partida
não permaneçam obsoletos após uma desconexão terminal normal. Cada passo é
isolado e as falhas da rota são registadas sem expor detalhes internos aos
jogadores; o monitor de isolamento persistente continua a ser a fronteira de
recuperação alternativa.

## Configuração e preparação do operador

As chaves de Sumo resolvidas são:

```text
minimum-players: 2
maximum-players: 2
best-of-rounds: 1..99
round-timeout-seconds: 1..3600
fall-threshold-y: -64..320
knockback-item-material: material de item válido
knockback-level: 1..10
```

O resolver também fornece uma revisão do conjunto de regras e isolamento
estrito contra falta de progresso. A região/mundo e as localizações
`side-a`/`side-b` são exigidas pela configuração resolvida e pelo assembler; não
existem coordenadas reais neste documento.

## Resultados e classificações

Os resultados concluídos são registados no modo `1v1`, com a revisão do conjunto
de regras, motivo, UUIDs do vencedor/perdedor e métricas limitadas `wins`,
`draws` e `rounds`. A recuperação, a desconexão e uma reposição falhada são
registadas como sem competição quando o destino da sessão aceita o resultado.
O predefinido público é `wins`, SUM, sendo melhor o valor mais alto.
`/minijogos top knockback-sumo` consulta atualmente todos os conjuntos de
regras e modos de Sumo; uma classificação limitada a um conjunto de regras
exige uma consulta explícita de estatísticas. O destino das estatísticas e a
ligação à persistência não têm verificação em execução.

## Limites de eventos e ecrãs

`MinigameEventRouter` é responsável pelo encaminhamento de movimento,
teletransporte, dano, morte, saída/expulsão e combate independente de
projéteis. Os comandos e os ecrãs nativos consomem o estado do módulo; nem um
ecrã nem o DiscordSRV são a fonte de verdade para admissões ou resultados. A
entrega em execução não está verificada.
