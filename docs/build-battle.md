# Build Battle

Estado em 2026-08-24:

- `SOURCE-VERIFIED`: controlador Paper, domínio, isolamento de sessões e
  reset nativo de modelos estão presentes.
- `UNVERIFIED`: não existe validação Paper descartável nem instalação
  de mundo ou modelo em execução.

## Ciclo de vida

O controlador segue este ciclo:

```text
IDLE -> WAITING -> THEME_VOTING -> COUNTDOWN -> BUILDING
BUILDING -> REVIEWING -> RESULTS -> RESETTING -> CLOSED
Qualquer estado inseguro, desconexão, timeout ou falha de reset -> RECOVERING
```

A composição é alterável apenas em `WAITING`. Durante a votação de tema fica
congelada e não são aceites novas entradas ou saídas. Uma falha mantém o
encontro fechado até as sessões e a arena serem recuperadas; não retoma um
encontro parcialmente confiável.

Cada operação de entrada, saída, voto de tema, voto de plot e desconexão valida
primeiro o ID de operação, a sequência e o estado de repetição associados ao
encontro. Uma entrada rejeitada não consome o ID; uma repetição bem-sucedida é
rejeitada ou tratada de forma idempotente conforme o contrato.

## Temas e construção

A lista de temas sem duplicados é limitada a 256 entradas. Cada encontro recebe
exatamente três opções determinísticas, sem duplicados, derivadas do ID do
encontro. As ações apresentadas ao jogador são:

```text
/buildbattle entrar
/buildbattle sair
/buildbattle tema <id>
/buildbattle votar <id>
/buildbattle estado
```

Durante `BUILDING`, os jogadores recebem plots pela ordem estável dos UUIDs e
da configuração. Cada jogador recebe um token de admissão limitado ao seu plot
`BUILD_BATTLE`. Toda alteração de blocos ou entidades deve passar por
`canBuild(player, location)`; movimento e teleporte passam por
`allowMove`/`allowTeleport`. O item temporário de construção só é entregue
depois de o snapshot durável e a admissão estarem preparados.

## Avaliação

Em `REVIEWING`, os plots são visitados por ordem determinística. O controlador
só avança depois de o plot atual ser avaliado. As pontuações respeitam os
limites configurados; votos para o próprio plot ou para um plot fora da posição
atual são recusados. O resultado só começa depois de todos os votantes elegíveis
avaliarem todos os outros plots. Um timeout de votação conduz à recuperação
`NO_CONTEST`, sem inventar abstenções ou pontuações.

A ordenação é definida por média, total e ID estável do plot
(`AVERAGE_THEN_TOTAL_THEN_PLOT_ID`). O resultado imutável é guardado antes da
restauração e do reset.

## Isolamento e recuperação

Build Battle usa um mundo dedicado e a fronteira estrita de
`SessionCoordinator`. A admissão só ocorre depois de o snapshot ser durável;
o jogador recebe apenas um token do seu plot e é restaurado antes de o encontro
ser considerado concluído. Inventário, armadura, efeitos, localização, modo de
jogo, voo e restante estado temporário permanecem nessa fronteira. Não é dada
qualquer recompensa de itens ou moeda.

O controlador único rejeita um segundo encontro enquanto houver composição ou
recuperação ativa. Verificações exatas do nome e UUID do mundo protegem lobby,
plots e retorno. Teleportes externos, movimento entre plots, alterações de
blocos não autorizadas, desconexão, desativação do plugin e falhas de
restauração fecham a operação.

Os resultados classificados só são registados depois de todas as sessões serem
restauradas e o reset terminar. A recuperação emite `NO_CONTEST` sem
classificação apenas quando todas as sessões estiverem seguras. Uma sessão em
quarentena impede o resultado terminal.

## Reset de modelos

`ConfiguredModuleAssembler` cria um `PaperBuildBattleResetPort` nativo como
fallback. Um serviço `BuildBattleResetPort` registado pode substituí-lo, mas
tem de manter o mesmo contrato de falha segura. O fallback lê um artefacto
revisto pelo operador a partir de:

```text
plugins/CIAACPlatform/templates/<worldTemplateMarker>
```

O marcador e a revisão das regras têm de coincidir com o artefacto. O modelo
deve cobrir o cuboide completo que contém todos os plots, incluindo os espaços
entre eles. O reset aplica no máximo 4.096 blocos por tick, sem física, drops,
carregamento forçado de chunks ou eliminação de mundos.

Se não existir um fallback válido nem um serviço registado, a montagem falha
com `BUILD_BATTLE_TEMPLATE_UNAVAILABLE` e não inicia o encontro. O reset é
assíncrono através de `beginReset`/`pollReset` e só começa depois dos
resultados.

## Preparação do operador

1. Confirmar o UUID e o nome exatos do mundo dedicado no servidor Paper alvo.
2. Validar lobby, regiões dos plots e spawns no mesmo mundo.
3. Gerar e rever o artefacto segundo
   [template-artifacts.md](template-artifacts.md).
4. Confirmar que o volume completo respeita o limite do artefacto.
5. Iniciar ou reiniciar o servidor através do ciclo de vida controlado.

O código não fornece coordenadas reais, comandos de instalação ou uma ferramenta
de captura. Toda a instalação e aceitação Paper permanece `UNVERIFIED`.
