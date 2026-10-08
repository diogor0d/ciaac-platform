# Build Battle

Última atualização: 2026-10-08.

- `SOURCE-VERIFIED`: controlador Paper, domínio, isolamento de sessões e
  reset nativo de modelos estão presentes.
- `RUNTIME-VERIFIED` no fixture sintético, candidato `6792e19…`: votos de tema,
  construção por pacotes normais, revisão/votação, vitória, saída, desconexão,
  timeout e crash frio; 18 campos originais e 200 células de parcelas exatos.
- Os percursos atuais de Build Battle estão concluídos para esses cenários.
- `UNVERIFIED`: instalações reais, outras capacidades/geometrias e aceitação
  no Paper de produção. Ver a [evidência datada](all-minigames-validation.md).

As melhorias de preparação repetível de fixtures, bandas adicionais de
pontuação de Archery e testes de fronteira de Parkour estão em implementação
e ainda não têm aceitação em runtime.

## Ciclo de vida

O controlador segue este ciclo:

```text
IDLE -> WAITING -> THEME_VOTING -> COUNTDOWN -> BUILDING
BUILDING -> REVIEWING -> VOTING -> RESULTS -> RESETTING -> CLOSED
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
/buildbattle votar <parcela> <pontuação 1..5>
/buildbattle estado
```

Durante `BUILDING`, os jogadores recebem plots pela ordem estável dos UUIDs e
da configuração e mudam para Creative. Em revisão/votação ficam em Adventure.
Cada jogador recebe um token de admissão limitado ao seu plot `BUILD_BATTLE`.
Toda alteração de blocos ou entidades deve passar por `canBuild(player,
location)`; movimento e teleporte passam por `allowMove`/`allowTeleport`. O
item temporário de construção só é entregue depois de o snapshot durável e a
admissão estarem preparados.

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
plugins/CIAACPlatform/templates/<worldTemplateMarker>.template
```

O marcador e a revisão das regras têm de coincidir com o artefacto. O artefacto
captura o cuboide delimitador dos plots, incluindo espaços entre eles, até
100 000 blocos. As células pertencentes aos plots, em conjunto, estão limitadas
a 65 536. O reset altera somente essas células de plot, nunca o lobby nem os
espaços entre parcelas, e aplica no máximo 256 mutações por tick, sem física,
drops, carregamento forçado de chunks ou eliminação de mundos. A captura recusa
blocos fora da política segura, tile entities, chunks descarregados e qualquer
artefacto já existente.

Se não existir um fallback válido nem um serviço registado, a montagem falha
com `BUILD_BATTLE_TEMPLATE_UNAVAILABLE` e não inicia o encontro. O reset é
assíncrono através de `beginReset`/`pollReset` e só começa depois dos
resultados.

## Preparação do operador

1. Confirmar o UUID e o nome exatos do mundo dedicado no servidor Paper alvo.
2. Validar lobby, regiões dos plots e spawns no mesmo mundo.
3. Configurar `world-template-marker` com um ID seguro e único; confirmar que
   o volume delimitador não excede 100 000 blocos e a soma dos plots não excede
   65 536 células.
4. Carregar os chunks do volume e, com todas as sessões terminadas (incluindo
   recuperação/quarentena), executar `/ciaac instalações capturar-construcao`
   na consola local. O comando cria `<worldTemplateMarker>.template` uma vez e
   valida a leitura/checksum; não substitui ficheiros existentes.
5. Rever o artefacto segundo [template-artifacts.md](template-artifacts.md) e
   reiniciar normalmente para carregar o template.
6. Configurar o mundo dedicado para começar em Adventure, mantendo ativa a
   proteção global do Multiverse-Core. A sintaxe nativa é
   `/mv modify <mundo> set gamemode adventure`; conferir a ajuda da versão e
   o nome exato do mundo. O controlador usa Creative durante `BUILDING` e
   Adventure durante a revisão/votação.
7. Executar `validar` e completar a aceitação nativa antes de qualquer decisão
   de abertura operacional.

O repositório não fornece coordenadas reais. A captura local está disponível,
mas não confirma revisão/aceitação do artefacto nem substitui ensaios de
entrada, construção, revisão, saída e recuperação no Paper alvo. A aceitação
nativa delimitada de Build Battle está registada na
[matriz de validação](all-minigames-validation.md); a instalação de produção
continua `UNVERIFIED`.

## Fronteira dos lotes no Paper — 2026-10-08

`build-battle-isolation` passou no candidato `b34b6a033728e6d8bd44fb90e8c373c0c56be95520f2f7f721e568c35c4d4e23`.
Os dois clientes sintéticos caminharam em direção ao lote alheio e receberam
correção nativa junto da fronteira exata do próprio lote; a posição Paper
confirmou que continuaram dentro dele e com sessões ativas. A saída normal
restaurou os 18 campos, as 200 células próprias e as sentinelas de lobby/gap.

O primeiro driver confundia a colisão com um bloco de teste com recusa de
fronteira. Esse resultado não foi aceite como prova. Outras tentativas revelaram
teleportes ainda em trânsito e pausa do modelo antes do comando walk; os
registos foram preservados e a recuperação normal comparada antes de repetir.
O driver atual exige caminho sem obstáculos de teste, teleporte estabilizado e
correção a menos de 0,7 blocos do limite exato. Não desativa anti-cheat.

Os lotes deste fixture estão separados por um gap maior que o alcance de edição
normal; edição direta do lote alheio não foi ensaiada nativamente. As recusas de
blocos/ownership têm testes de origem, e uma instalação real com outras
distâncias precisa do seu próprio ensaio. Não atribuir um clique fora de
alcance à proteção do plugin.
