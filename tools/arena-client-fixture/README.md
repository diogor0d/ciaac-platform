# Cliente sintético da Arena no servidor local

Ferramenta de aceitação, separada do JAR CIAACPlatform e das suas dependências.
Usa o cliente offline normal da [MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib),
com protocolo Minecraft **26.2 / 776**. O artefacto oficial está fixado a
`org.geysermc.mcprotocollib:protocol:26.2-20260907.143312-19` no
[OpenCollab](https://repo.opencollab.dev/maven-snapshots/org/geysermc/mcprotocollib/protocol/26.2-SNAPSHOT/).
SHA-256 confirmado contra o digest do repositório:
`5757ef29742d75b4af90fcdaf3d997e4b6dcf23928e601b4869487286854779a`.

O alvo é fixo: **127.0.0.1:25567**, servidor descartável em
`/private/tmp/ciaac-paper-local-test`, mundo `ciaac-synthetic-test`, com
`online-mode=false` para reproduzir a identidade offline da produção. O cliente
recusa configuração divergente e exige `--authorized-loopback-fixture`.
Não lê contas/tokens do launcher, não se liga ao backend real e não cria sessões
autenticadas por APIs. A identidade predefinida é `CiaacArenaPeer`; para testes
de equipa, `--fixture-peer 2`, `3`, `4` ou `5` seleciona respetivamente
`CiaacArenaPeer2`, `CiaacArenaPeer3`, `CiaacArenaPeer4` ou `CiaacArenaPeer5`. O primeiro argumento
`--authorized-loopback-fixture` continua obrigatório; argumentos extra ou
valores fora dessa lista são recusados. O fornecedor configurado (AuthMe ou nLogin) deve inicializar e autenticar
cada conta sintética pelo fluxo normal. A entrada no estado GAME e o ack de
teleport não provam autenticação: o driver espera a confirmação do login normal
e a consequência durável antes de continuar.

Compilar com JDK 25 e um executável Maven existente:

```sh
python3 tools/arena-client-fixture/build.py --maven /path/to/mvn
```

A compilação resolve apenas as dependências da ferramenta, valida o digest do
protocolo e escreve classes/classpath em `target/local-arena-client`. Não liga
a nenhum servidor. Para execução autorizada, usar esse diretório e classpath
com `LocalArenaPeer --authorized-loopback-fixture [--fixture-peer 2|3|4|5]` num stdin aberto. A leitura
de comandos aceita `register`, `login`, `coliseu`, `minijogos`, `passaporte`,
`sumo`, `batataquente`, `buildbattle`, `parkour`, `arco`, `bigornas`, `cores` e
`elytra`, além das ações limitadas descritas abaixo, `status` e `quit`; nunca
imprime os argumentos de autenticação. Estes são os nove comandos-raiz dos
minijogos (`coliseu`, `sumo`, `batataquente`, `buildbattle`, `parkour`, `arco`,
`bigornas`, `cores` e `elytra`); `minijogos` e `passaporte` continuam permitidos
como comandos de consulta. Não enviar credenciais num terminal com echo, nem
guardá-las em Git.

O self-test offline verifica a allowlist, as ações nativas limitadas e os
índices de identidade; a execução de 2026-10-07 passou **127 verificações**. Isto
verifica o cliente local, não a aceitação dos modos no servidor.

O driver `acceptance.py` inclui casos separados para Parkour, Arco e Chão de
Cores, além dos casos legados. `--case all` continua a executar apenas Sumo e
Batata Quente; não significa aceitação de todos os nove minijogos. A existência
de um caso ou de uma pasta privada não constitui prova de aceitação em runtime;
ver os resultados datados abaixo.

O peer reconhece teleports nativos, envia o ack e a posição resultante,
reconhece batches de chunks e emite fim de tick. A biblioteca trata keepalive e
a troca de estado de configuração. Por predefinição mantém-se estacionário;
as ações seguintes são explícitas e não implementam navegação ou combate autónomo.
As mensagens do servidor são anunciadas sem imprimir texto ou argumentos.
A conexão expira ao fim de 15 minutos e fecha ao sair do processo.

## Ações limitadas no fixture — 2026-10-07

O stdin aceita também os comandos normais `sumo` e `batataquente`. `attack N`
e `interact N` usam somente o índice de peer `1..5`: o alvo tem de ter sido
resolvido por um spawn nativo de jogador com o UUID offline exato de uma das
identidades sintéticas acima. Não são aceites IDs de entidade ou nomes
arbitrários. O cliente envia swing e ataque/interação nativos, com alcance
limitado a 3,2 blocos e intervalo mínimo de 250 ms. `move dx dz` envia movimento
horizontal relativo, finito, limitado a 0,3 blocos por passo e 50 ms entre
passos; não permite coordenadas absolutas nem alteração direta de Y.
`interact N` envia o clique na altura relativa de um bloco do alvo.
`walk <east|west|north|south> <ticks>` envia input nativo para a frente durante 1 a
100 ticks, apenas com `flat-floor-motion on`; uma caminhada ativa termina por
si ou é cancelada ao desativar o modelo.

`status` escreve JSON com `localPose`, `lastNativeTeleport`, `receivedVelocity`,
`velocityPackets`, `lastNonzeroVelocity`, `nonzeroVelocityPackets`,
`walkTicksRemaining`, `carrierTitle` e `carrierPeerIndex`. A posição local inclui
movimentos enviados pelo cliente; só `lastNativeTeleport` é uma posição
explicitamente recebida do servidor. O estado autoritativo continua a exigir
verificação no Paper. Os títulos de Batata Quente são classificados por texto
exato e identidades permitidas, sem imprimir texto arbitrário ou credenciais.

`flat-floor-motion on [floor-y]` ativa uma aproximação explícita de movimento
num piso sintético plano infinito. Sem valor, usa a altura Y local corrente;
com valor, o driver declara explicitamente a altura do piso, por exemplo
`flat-floor-motion on 80` depois de confirmar que Y=80 é o plano do piso.
O valor tem de ser finito, estar entre -64 e 320, não ficar acima do jogador e
estar no máximo quatro blocos abaixo da posição atual. Assim, após uma correção
nativa a Y=80.614, o driver pode rever e indicar o piso real conhecido em Y=80;
o peer continua suspenso até receber esse comando explícito. A aproximação aplica
os impulsos de velocidade recebidos do servidor, gravidade e arrasto a 20 Hz,
enviando posições normais. `walk` usa aceleração horizontal aproximada de 0,098
no chão e 0,0196 no ar, com fricção 0,546 no chão e 0,91 no ar; envia input
forward nativo e liberta-o ao concluir os ticks. `flat-floor-motion off` desativa
a aproximação e cancela uma caminhada ativa. Cada teleport nativo liberta o input,
cancela a caminhada e desativa a aproximação, exigindo nova confirmação do piso
pelo driver. O movimento e as posições continuam a ser uma aproximação explícita
de piso plano infinito; os resultados físicos e autoritativos exigem verificação
nativa no cliente e no servidor. Não simula blocos, colisões laterais, buracos,
escadas ou toda a física vanilla.
Não usar a aproximação como prova de comportamento de um cliente vanilla;
validar separadamente correções/rejeições do servidor e o resultado durável.

Verificação em **2026-10-04**: o binário fixado declara 26.2/776, a ferramenta
compilou e o peer chegou ao estado GAME real do Paper local, com ack de teleport
e conta UUID offline reconhecida por LuckPerms/SimpleClaimSystem. O wizard
inicial nLogin estava pendente; isto **não** prova autenticação, admissão ou uma
partida. Um comando de entrada não criou sessão/snapshot, mas não foi confirmado
que ultrapassou o wizard: essa observação não é prova de negação pelo router.
Guardar logs/config/database/evidência do ensaio fora de Git e remover o peer
antes de qualquer implantação; o seu JAR não pertence ao servidor.

## Atualização de aceitação — 2026-10-04

O cliente Modrinth Fabulously Optimized passou pelo login normal nLogin num
Paper descartável **26.2-84-26e81c4**. O peer continuou limitado a
`127.0.0.1:25567`. Para concluir o setup local, `nlogin.admin` foi temporário e
explicitamente revogado; uma verificação confirmou que estava falso antes do
gameplay. nLogin 2.0.24 não conseguiu carregar ARGON2ID em ARM; o servidor local
usou BCRYPT2A, configuração suportada. Não houve alteração das definições de
produção.

Com o peer e o cliente real, ambos os jogadores chegaram a `ACTIVE` com kits e
spawns. No primeiro ensaio, um disparo após 30 segundos expirou o token de
região e provocou um forfeit incorreto. A validade da admissão agora renova-se
até ao prazo do combate depois de `ACTIVE`, enquanto o token de entrada continua
limitado a 30 segundos. Apesar do forfeit antigo, a leitura NBT daquele ensaio
confirmou ambos os snapshots `RESTORED` e sessões `CLOSED`, incluindo espada
com dano 7, capacete dourado com dano 4, `bodyLeatherHorseArmor`, sela e XP
(nível 7, progresso `0.52380955`, total 0). Esses valores não são atribuídos à
partida posterior enquanto a leitura NBT final dela não estiver confirmada.

Esta execução usou uma sessão nova. Uma quarentena anterior continuou
`QUARANTINED` após tentativa de reinício autenticado; o caso novo não a recupera
nem prova recuperação positiva. Um ensaio separado falhou corretamente antes
da ativação por ter relógio regressivo anterior à preparação, fechando ambos os
jogadores e restaurando os snapshots. A Arena e cinco outros controladores
passaram a usar relógio de ativação novo.

O estado de login e admissão acima substitui a observação anterior de wizard
pendente para esta execução. Mantêm-se os limites: ensaio descartável, sem
implantação ou alteração de produção, sem aceitação de todas as funcionalidades
e sem vitória PvP confirmada.

No ensaio nativo seguinte, ambos autenticaram-se normalmente e permaneceram
`ACTIVE`. Aos 45 segundos da ativação, o cliente Modrinth usou o arco; o registo
durável confirmou `ARROW REMOVED` e a partida continuou ativa. Não se afirma
acerto de arco nem dano causado pelo projétil. Após dez ataques corpo a corpo
com arrefecimento, um golpe letal às `22:22:16` (Lisboa) terminou a partida. O
cliente mostrou o vencedor e `r0das` registou uma `VICTORY` com motivo
`LETHAL_DAMAGE` para esta partida.

A consulta à base confirmou seis sessões `CLOSED`, seis snapshots `RESTORED`,
seis leases `RESTORED` e uma arrow `REMOVED`, abrangendo os pares dos ensaios
anteriores e desta partida. O jogador regressou à posição de saída original,
com saúde 20 e controlos libertados. A leitura NBT final desta partida aguarda
verificação. Equipamento espelhado, desconexão, recuperação positiva após
reinício autenticado e acesso nativo a toda a altura continuam pendentes; não
houve ativação de produção.

## Recuperação autenticada após crash — 2026-10-05

Este ensaio usou um fixture sintético novo e preservou integralmente a
quarentena e os arquivos de jogador/plataforma anteriores. Só o fixture novo foi
reposto; a quarentena antiga não foi recuperada nem limpa.

Antes do crash, ambos os jogadores nLogin estavam `ACTIVE` e ambos os snapshots
`TEMPORARY_APPLIED`. A baseline tinha duas pérolas no slot 1 do inventário
normal e uma Elytra com dano 9 no slot de peito. O loadout temporário protegido
reteve estes itens proibidos; o jogador não os retirou durante o combate.
`save-all` guardou o estado enquanto o jogador ainda estava na Arena. O driver
protegido terminou apenas o processo Java local que lançou
(SIGKILL, exit -9). O mesmo JAR e a mesma base foram reabertos. Uma leitura
somente de consulta confirmou ambos ainda `ACTIVE`/`TEMPORARY_APPLIED`. O
encaminhamento nativo pré-login de 150 ms não alterou a posição; nLogin ficou
parado até os dois jogadores completarem o fluxo normal de autenticação.

Após esse login, a recuperação autenticada marcou ambos como `RECOVERED`; as
duas sessões ficaram `CLOSED`, os snapshots `RESTORED` e as leases `RESTORED`.
A leitura NBT confirmou espada com dano 7, duas pérolas, capacete com dano 4,
`bodyLeatherHorseArmor`, sela, Elytra com dano 9 e XP nível 7 / progresso
`0.52380955` / total 0. O cliente voltou à saída original com saúde 20 e sem
controlos pressionados. Não foi encontrado kit etiquetado por PDC após o
restauro. A evidência privada está em
`/private/tmp/ciaac-paper-local-test/verification-2026-10-05-authenticated-mirrored-crash-fixed`.

O gate de recuperação exige a capacidade de autenticação e
`ConnectionRegistry.isCurrent` para o objeto de ligação/época atuais. Oito casos
de serviço cobrem o gate e a auditoria adiada para violações não autenticadas;
estas mantêm estado pendente e a negação de transporte. O ensaio nativo não
exercitou essa violação/gate exato. `mvn verify`: **379 testes, 378 aprovados,
um ignorado, zero falhas/erros**. JAR CIAACPlatform:
`0099b09ed6258f0c278a072d8a6f6d7a3980b6f29266f0d3591612a746263f7a`.

Verificação de altura total no mesmo Paper local: teleports de consola para
`(20.5, 200, 10.5)`, `(20.5, -20, 10.5)` e `(20.5, 80, 10.5)` visaram o interior
horizontal da Arena (`X=0..40`, `Z=0..20`) em três alturas. A admissão negou os
teleports do jogador não admitido. A mensagem vanilla «Teleported» não prova
movimento; depois de cada tentativa, o estado nativo confirmou o cliente fora,
na saída `(-5.5, 80, 10.5)`. Numa caminhada normal, parou em `x=-0.03898` ao
cair para `y=73.32778` na lacuna do piso do laboratório. Não houve entrada na
Arena. A repetição corrente confirmou ainda saúde 20, ligação ativa e nenhum
controlo pressionado após cada tentativa. Evidência privada:
`/private/tmp/ciaac-paper-local-test/verification-2026-10-05-full-height-native-denial/evidence.json`.
A recuperação autenticada e a negação de teleport foram verificadas apenas no
Paper local; backend remoto e produção não foram alterados.

Este checkpoint prova recuperação após crash com login normal, não uma
desconexão normal. Esse ensaio continua pendente; não se declara aceitação
completa do jogo.

## Desconexão e duelo no JAR atual — 2026-10-05

Os dois ensaios seguintes usaram o JAR CIAACPlatform SHA-256
`0099b09ed6258f0c278a072d8a6f6d7a3980b6f29266f0d3591612a746263f7a` no Paper
local descartável.

No ensaio de desconexão normal, o resultado foi `VICTORY` / `DISCONNECT`; ambas
as sessões terminaram `CLOSED`, snapshots `RESTORED` e leases `RESTORED`. O peer
voltou a autenticar-se pelo nLogin normal e teve inventário vazio na saída. O
inventário/XP do cliente real foi consultado após restauro.

No duelo fixo do mesmo build, o cliente Minecraft real usou arco e ataques
corpo a corpo contra o peer sintético Paper, sem injeção de evento ou morte
forçada. Resultado `VICTORY` / `LETHAL_DAMAGE` em
`2026-10-05T12:23:18.487937Z`; o arrow ficou `REMOVED`. Ambas as sessões
fecharam e snapshots/leases foram restaurados. NBT confirmou espada com dano 7,
duas pérolas, capacete dourado com dano 4, `bodyLeatherHorseArmor`, sela, Elytra
com dano 9 e XP nível 7 / progresso `0.52380955` / total 0. Ambos voltaram às
posições de saída originais; o peer ficou vazio e o cliente real sem controlos
pressionados.

Isto atualiza a pendência anterior de desconexão e documenta um duelo de kit
fixo no build corrente. Continua a ser validação local de dois casos; não prova
aceitação de todos os modos ou ativação de produção.

A aceitação nativa de equipas 3v3 com seis atores (cliente real e quatro peers
sintéticos) continua pendente. As opções fornecem apenas identidades sintéticas
limitadas e não simulam autenticação.

## Fixture local histórico — recuperação após desconexão (`d6cf9c8…`)

O peer responde agora a `ClientboundPingPacket` com o `ServerboundPongPacket`
normal. A biblioteca já trata keepalive; o ping adicional é necessário para
os desafios de ligação do GrimAC. Não desativar o anti-cheat nem conceder bypass
para acomodar um cliente de teste. Os formulários AuthMe de configuração são
reconhecidos por IDs exatos; o registo/login só envia respostas após pedido
explícito, e nunca escreve os valores de palavra-passe nos logs.

O JAR de recuperação `d6cf9c8eca8d19352e3488f901a83eb8c1348e5471409b69bebadfc8040155cb`
passou num Paper local com GrimAC/PacketEvents, AuthMe e dois peers sintéticos.
Ambos chegaram aos spawns configurados com seis itens temporários etiquetados.
Após saída normal de um peer, o outro foi restaurado imediatamente, mantendo-se
a sessão ausente pendente e a Arena fechada. O login normal do mesmo peer
concluiu a recuperação: 15 campos NBT de cada jogador, incluindo `equipment`
(armadura/offhand em 26.2), inventários, XP, vitais, modo, capacidades, posição
e rotação, ficaram iguais às baselines. Não houve falha de recuperação nem
bypass de anti-cheat. Isto prova o ciclo de desconexão no fixture; não constitui
prova de combate físico ou de implantação deste JAR em produção.

## Resultado posterior em produção — 2026-10-07

O resultado local acima é histórico e não é a aceitação final. O JAR
`d6cf9c8eca8d19352e3488f901a83eb8c1348e5471409b69bebadfc8040155cb` foi
implantado e passou 449 testes (448 aprovados, um ignorado, zero falhas/erros).
A partida nativa pública 1v1 com kit fixo e a desconexão durante partida foram
verificadas; os 15 campos NBT de ambos os jogadores coincidiram exatamente após
restauro/reautenticação. O fixture local foi terminado normalmente, removido e
a porta 25567 ficou fechada. Ver [verificação funcional](../../docs/functional-verification.md)
para o âmbito verificado e [ativação da Arena](../../docs/arena-activation.md)
para recuperação, rollback e limites operacionais. Equipas maiores,
espectadores e outros minijogos permanecem por verificar.

## Driver de aceitação completo

[acceptance.py](acceptance.py) inicia apenas o fixture privado já preparado em
`/private/tmp/ciaac-paper-local-test`; recusa outro listener em 25567, JAR Paper
não reconhecido, digest CIAAC divergente ou diretório de evidência fora de
`/private/tmp`. Não cria mundos/facilidades nem aceita a EULA automaticamente.
Requer JDK 25, classes da ferramenta compiladas, AuthMe revisto e configuração
sintética descrita na evidência datada. A ausência deste fixture é um erro,
nunca um motivo para usar outro servidor.

```sh
python3 tools/arena-client-fixture/acceptance.py \
  --authorized-loopback-fixture --java /path/to/jdk25/bin/java \
  --private-output /private/tmp/ciaac-minigame-evidence \
  --expected-plugin-sha256 <digest-do-JAR-instalado> --case all
```

O que faz: `--case all` executa os dois casos legados (Sumo e Batata Quente)
com três contas sintéticas, autenticação normal, resultados e comparação de 18
campos NBT; fecha apenas os processos que criou. `--case crash-sumo` e
`--case crash-potato` gravam o estado nativo, terminam forçadamente só o
subprocesso Paper próprio e verificam restauro depois de reauth com a mesma
password. Os casos `parkour`, `archery` e `color-floor`, e os seus casos de
crash, são explícitos e separados. `--case crash-archery` termina antes do
lançamento de seta; `--case crash-archery-launched` confirma uma seta real e o
seu UUID antes do crash. `--case setup-color-floor` prepara a captura local,
`color-floor` exercita conclusão/saída/desconexão e `crash-color-floor` testa
recuperação após remoção nativa de blocos. Os nomes dos casos não alargam a
matriz automaticamente. `sumo` e `potato` permitem ensaios individuais;
`recover-only` autentica e drena sessões antigas sem iniciar nova partida.
Passwords, logs e NBT ficam em ficheiros privados 0600/0700 fora de Git.
O reset inicial da password sintética conclui antes de abrir a conexão; GAME
ou um teleport ack não substituem a confirmação AuthMe de login bem-sucedido.

Casos locais adicionais, executados individualmente com os mesmos argumentos
de fixture e digest acima:

```sh
python3 tools/arena-client-fixture/acceptance.py --authorized-loopback-fixture \
  --java /path/to/jdk25/bin/java --private-output /private/tmp/ciaac-minigame-evidence \
  --expected-plugin-sha256 <digest-do-JAR-instalado> --case parkour-edges

python3 tools/arena-client-fixture/acceptance.py --authorized-loopback-fixture \
  --java /path/to/jdk25/bin/java --private-output /private/tmp/ciaac-minigame-evidence \
  --expected-plugin-sha256 <digest-do-JAR-instalado> --case anvil-edges

python3 tools/arena-client-fixture/acceptance.py --authorized-loopback-fixture \
  --java /path/to/jdk25/bin/java --private-output /private/tmp/ciaac-minigame-evidence \
  --expected-plugin-sha256 <digest-do-JAR-instalado> --case archery-bands

python3 tools/arena-client-fixture/acceptance.py --authorized-loopback-fixture \
  --java /path/to/jdk25/bin/java --private-output /private/tmp/ciaac-minigame-evidence \
  --expected-plugin-sha256 <digest-do-JAR-instalado> --case setup-repeat
```

`parkour-edges` compara a capacidade configurada, testa dois percursos nativos
quando a capacidade permite, confirma por posição nativa do servidor o retorno
à gate após cruzar a fronteira e a recusa de cruzar a meta antes do primeiro
checkpoint, e termina essas corridas com saída/restauro. Depois verifica o
resultado `NO_CONTEST/TIME_LIMIT` com restauração exata. Não injeta teleports ou
eventos e não afirma validar quedas ou colisões.
`anvil-edges` testa a fronteira enquanto uma única sessão permanece em espera,
posiciona um jogador numa célula marcada e outro numa célula segura através de
caminhada nativa e confirma a eliminação/continuação por mensagem, estado,
resultado e métricas persistidas. Também verifica as duas ondas nativas, a
limpeza dos marcadores, o piso inalterado e o restauro exato dos dois jogadores.
O sobrevivente precisa concluir a segunda onda; o caso não injeta dano nem
afirma validar colisões físicas vanilla.
`archery-bands` usa quatro tentativas separadas de dois disparos nativos, uma
por alvo, e compara `score`, `shots` e `bullseyes` duráveis com os pesos
configurados em cada banda. `setup-repeat` exige os dois templates existentes,
confirma que novas capturas são recusadas sem alterar os
digests da configuração/templates, reinicia normalmente o servidor próprio e
compara identidade/alturas dos três mundos e configuração/disponibilidade dos
nove módulos em estado idle `WAITING`. Não edita UUIDs, checksums, configuração
ou artefactos. Estes casos só validam o fixture sintético e não aceitam
instalações reais.

Compilar com `build.py --maven /path/to/mvn --self-test` executa também as
56 verificações offline de limites, identidade, serialização de interação,
input, seleção dos pacotes de movimento e validação do piso explícito. O modelo de piso infinito continua
aproximado: esta ferramenta não prova colisões/física completas de vanilla.

## Aceitação local do Arco — 2026-10-07

No Paper descartável, com autenticação normal e estado sintético sem dados de
contas reais, a recusa por alvo em falta preservou os 18 campos do jogador. Uma
segunda conta não entrou na lane ocupada e o seu estado também ficou intacto.
Quatro bandas foram representadas por ArmorStands válidos; dois disparos de arco
nativos geraram eventos reais de projétil, resultado `VICTORY` / `COMPLETED` e
restauro exato dos 18 campos. Os percursos de saída normal, desconexão e quit
seguido de reautenticação também restauraram exatamente os 18 campos. Timeout
de 30 segundos resultou em `NO_CONTEST` / `TIMEOUT`, com 18 campos restaurados.
O caso `crash-archery` terminou o Paper descartável com sessão `ACTIVE` antes de
qualquer seta ser lançada e recuperou os 18 campos após reautenticação AuthMe
normal com a mesma password (sessão `CLOSED`, snapshot `RESTORED`, contagens
118). É uma evidência pré-lançamento e permanece distinta do caso seguinte.

No caso `crash-archery-launched`, input real de arco puxou e libertou uma seta
nativa. O UUID exato ficou `CONFIRMED` e a entidade foi confirmada viva antes do
`save-all` e SIGKILL apenas do subprocesso Paper próprio. Depois do reinício e
login AuthMe normal com a mesma password, os 18 campos foram restaurados; a
entidade própria ficou `REMOVED` e o UUID exato estava ausente. O ack positivo
do fixture dependia destas verificações. Esta evidência confirma apenas esse
caso delimitado de crash após lançamento; não representa toda a matriz do Arco.

O candidato atual tem SHA-256
`7ff23154714b105720bf1fd376deed69af4f0897267a880395f4733d331a6dd2`; a suite
reportou 563 testes (562 aprovados, um ignorado, zero falhas/erros).

O self-test offline da ferramenta passou 127 verificações, incluindo seleção de
arco e orientação. O movimento em piso plano continua aproximado e não prova a
física completa do cliente vanilla. A evidência privada sanitizada confirma os
resultados acima; nenhum log privado ou dado de autenticação é publicado aqui.

## Aceitação local do Chão de Cores — 2026-10-07

No candidato separado `e53c89f00e1ec08879ad6dad8a74a0d3722d69b548b93e0e04ee96b1eb263d3f`,
com 548 testes (547 aprovados, um ignorado, zero falhas/erros), `color-floor`
validou conclusão `VICTORY` / `COMPLETED`, saída `PLAYER_LEFT` e desconexão
`PLAYER_DISCONNECTED`. Cada percurso restaurou exatamente os 18 campos dos
jogadores e verificou por consulta nativa as 96 células contra o template.
`crash-color-floor` observou AIR nativo antes de SIGKILL, autenticou novamente
com AuthMe e restaurou os 18 campos e as 96 células.

O ensaio alargado anterior capturou/leu 4096 células e passou a verificação
pós-crash do template, mas o número exato de blocos AIR não foi registado. A
repetição atual no candidato
`7ff23154714b105720bf1fd376deed69af4f0897267a880395f4733d331a6dd2` preparou uma
referência nativa AIR de 4096 células em Y=120 e confirmou os 3712 blocos azuis
da secção completa do piso em AIR antes do `save-all` e SIGKILL do subprocesso
Paper próprio. Após reinício frio e autenticação AuthMe normal, os 18 campos de
cada jogador coincidiram, as 4096 células foram verificadas nativamente e
ficaram 12 sessões `CLOSED` / 12 snapshots `RESTORED`. Isto prova o cenário
delimitado de crash frio após remoção nativa superior a 256 blocos nesta secção;
não prova outras configurações/cenários nem prontidão geral.

O driver espera confirmação positiva dos chunks carregados antes de preencher
o piso; a primeira tentativa sem essa confirmação foi recusada pelo helper, e
um ensaio de captura duplicada recusou corretamente o destino existente sem o
substituir. Uma execução inicial de `setup-color-floor` ocorreu antes da
preflight SQLite falhar, por isso não é contada como prova de captura. A
asserção de AIR sobre toda a secção passou nesta repetição.

## Aceitação nativa local do Coliseu — candidato R5

`arena_acceptance.py` executa o percurso nativo limitado do Coliseu no fixture
privado já preparado em `/private/tmp/ciaac-paper-local-test`. O script não
edita `config.yml`, não cria/regista nem repõe contas sintéticas, não altera
permissões e não prepara inventários de baseline. Requer a configuração
sintética já revista e ativada nesse fixture, as três contas sintéticas já
existentes, o cliente nativo compilado em `target/local-arena-client` e o JAR
CIAACPlatform cujo SHA-256 esperado é
`6792e1924d90214e3a7c26919eb3cd0d0aaa83c16db0de38157f533129c1dc05`.

Antes de iniciar, o gate confirma o proprietário e permissões privadas do root,
Paper em `127.0.0.1:25567`, mundo `ciaac-synthetic-test`, hashes esperados do
plugin e da configuração, spawns e regiões revistos, kit fixo, credenciais
sintéticas existentes em ficheiro `0600` e uma pasta de saída nova e privada
sob `/private/tmp`. A inspeção SQLite é somente de leitura e exige o servidor
parado, ausência do ficheiro WAL e nenhuma sessão/snapshot pendente. Se qualquer
condição falhar, o runner para sem corrigir o fixture. Depois dos gates, inicia
e termina apenas o processo Paper que ele próprio cria. Os logs, leituras NBT e
evidência estruturada ficam na pasta privada indicada; não publicar os ficheiros
brutos.

```sh
python3 tools/arena-client-fixture/arena_acceptance.py \
  --authorized-loopback-fixture \
  --java /path/to/jdk25/bin/java \
  --private-output /private/tmp/ciaac-arena-r5-output \
  --expected-plugin-sha256 SHA256_DO_JAR_TESTADO \
  --expected-config-sha256 SHA256_DA_CONFIG_SINTETICA_REVISTA \
  --credentials /private/tmp/ciaac-paper-local-test/synthetic-credentials.private.json
```

Substitui o caminho de Java e os dois marcadores de SHA-256 pelos digests do
JAR testado instalado e da configuração sintética revista. Não usar o digest
de um candidato antigo com um JAR diferente. Confirma que o caminho de credenciais aponta
para o ficheiro existente com permissões `0600`; nunca imprimir nem passar os
valores das passwords na linha de comandos. A pasta de saída tem de estar vazia
e privada, e não pode conter a única cópia de qualquer evidência anterior.

O R5 do JAR indicado verificou kit light de espada de ferro/escudo, spawns
nativos, combate com movimento/velocidade nativos, saída, desconexão com
reautenticação, recuperação de crash e recusa de teleport de outsider. Isto é
um ensaio do fixture local e não altera nem aceita a Arena em produção. Kits de
armadura e formatos adicionais permanecem separados; em particular, confirmar
os itens do kit de armadura não prova letalidade aceite.

Em 2026-10-08, a ferramenta corrente repetiu o percurso completo no candidato
`b34b6a0…`: 21 ataques, letalidade em 29,95 s, saída, disconnect/AuthMe e crash
frio, 18 campos por jogador e 185 sessões/snapshots fechadas/restauradas no fim.
Uma tentativa anterior produziu escape devido a spawns trocados e não foi aceite
como letalidade; foi recuperada antes de repetir. O runner aguarda a pose do
cliente igual à posição nativa e escolhe o atacante voltado para a maior margem
do piso, sem forçar posições ou resultados. O vencedor esperado é esse atacante,
não uma conta fixa. Os limites de armadura/formatos/produção mantêm-se.

## Cliente Minecraft normal para Elytra — 2026-10-08

`elytra_acceptance.py` usa a ponte local já instalada num cliente Minecraft
normal em segundo plano. Não cria contas, altera passwords, concede permissões
ou muda configuração. O alvo é exclusivamente o fixture privado
`127.0.0.1:25567`; exige estado de recuperação limpo, JAR/config revistos,
endpoint e credenciais existentes em ficheiros regulares privados `0600`, e
uma pasta de saída nova `0700`. O cliente tem de estar desligado de servidores
antes do início e a GUI revista tem de medir 427 × 240. Os controlos continuam
bloqueados quando Minecraft está em primeiro plano.

```sh
python3 tools/arena-client-fixture/elytra_acceptance.py \
  --authorized-loopback-fixture \
  --java /path/to/jdk25/bin/java \
  --private-output /private/tmp/ciaac-elytra-native-output \
  --expected-plugin-sha256 SHA256_DO_JAR_REVISTO \
  --expected-config-sha256 SHA256_DA_CONFIG_SINTETICA_REVISTA \
  --player-name NOME_DA_CONTA_EXISTENTE_AUTORIZADA \
  --credentials /private/tmp/conta-autorizada.private.json \
  --bridge-endpoint /path/to/ciaac-local-control/endpoint.json
```

O ficheiro de credenciais contém o campo `password`; o valor nunca deve ser
passado na linha de comandos, publicado em logs ou incluído no Git. O endpoint
contém a ligação/token privados da ponte existente. Não copiar estes ficheiros
para o repositório. O runner preserva NBT, logs e evidência apenas na pasta
privada indicada. Só termina ou força crash no subprocesso Paper que criou.

A repetição nativa do driver no JAR `6792e19…` terminou com sucesso: voo e
impulso normais, dois anéis, saída, timeout, desligação/AuthMe e crash frio com
um foguete confirmado vivo depois de `save-all flush`. Todos os 18 campos
coincidiram com a baseline; os foguetes ficaram `REMOVED` e ausentes pelo UUID
exato. A base sintética terminou com 98 sessões `CLOSED` e 98 snapshots
`RESTORED`. Não é aceitação de instalações de produção ou de todas as condições
de voo. O encontro interrompido não recebeu vitória nem classificação.

Desligar o cliente pode exceder o prazo da ponte durante a descarga de chunks.
Nesse caso, o driver observa o estado real sem repetir o clique. Uma falha
preserva os registos; recuperar por autenticação normal antes de repetir, sem
apagar ou reclassificar sessões pendentes. As verificações offline atuais do
cliente sintético também passaram: 162 em 2026-10-08.

### Checks adicionais ainda sem aceitação — 2026-10-08

A versão atual acrescenta fronteira de Parkour e `anvil-edges`; os percursos
estão compilados, mas ainda não executados. O último pedido de iniciar o batch
não ocorreu porque automatic approval review atingiu o limite de utilização.
A cobertura anteriormente aceite de Parkour inclui concorrência, ordem e
timeout; não inclui o novo percurso de fronteira. O cenário de Bigornas com
marcador/ondas já aceite não prova ainda colocação nativa sobre um perigo e
esquiva por outro jogador. Preservar os gates até uma execução própria positiva.

Depois da aceitação nativa anterior de Elytra, a asserção de mundo foi reforçada
para exigir dimensão cliente e Paper, sem repetir entrada numa sessão já ativa.
Três casos offline verificaram sucesso e recusa de cada discrepância; as cinco
snapshots ativas anteriores também confirmam a dimensão Paper correta. A versão
atual desse guard aguarda repetição nativa; não alterar a proveniência da
execução anterior para a declarar feita depois da revisão.

### Retoma e resultados exatos — 2026-10-08

O batch R3 no candidato `b34b6a0…` aceitou a fronteira de Parkour após corrigir
o handler que anulava o próprio reset; concorrência, ordem e timeout passaram
novamente. `anvil-edges` confirmou impacto sobre a célula perigosa, outro jogador
seguro, duas esquivas, classificações exatas, marcadores removidos e piso intacto.
O batch terminou com 119 sessões fechadas/restauradas. As falhas anteriores e
a sua recuperação normal permanecem preservadas. Os novos cenários ligam os
resultados ao ID da partida ativa; uma vitória antiga não satisfaz o cenário.

`--case build-battle-isolation` tenta movimento normal para o plot alheio e
verifica a posição Paper, correção e restauro dos dois participantes. O gap
revisto excede o alcance normal de edição: o cenário não atribui uma tentativa
de edição distante a proteção eficaz. A repetição R4 foi aceite no candidato `b34b6a0…`: correções na fronteira
exata para os dois participantes, saída, 18 campos exatos e 200 células/sentinelas
preservadas. O driver espera teleporte estabilizado e mantém o caminho sem
blocos de teste; colisões no interior não satisfazem o gate.

### Guard Elytra atual aceite — 2026-10-08

A sequência integral da versão atual de `elytra_acceptance.py` passou no
candidato `b34b6a0…`, com verificação combinada de dimensão cliente/Paper. Os
18 campos, resultados normais e ausência do foguete após crash passaram; a
sequência fechou 132 sessões/snapshots e parou o Paper normalmente. As notas
anteriores de guard pendente descrevem o checkpoint antes desta repetição.

`finish_check` agora consulta a partida de cada participante no jogo do cenário,
aguarda todos os resultados normais e verifica consistência entre runs
concorrentes. Crash frio permite resultado ausente ou `NO_CONTEST` e recusa
qualquer classificação inventada; não substitui esta verificação por um resultado
antigo de outro encontro.

## Menus nativos

O peer observa os IDs de janela/estado e os conteúdos emitidos pelo servidor.
`menu-status`, `menu-click <slot> [left|right|shift|number|double|drop]` e
`menu-close` estão limitados às janelas CIAAC do alvo de loopback. Não usa
comandos de servidor para substituir um clique. Os logs só mostram rótulos
limitados de formato/equipamento/lane, sem metadados arbitrários de itens.

[menu_acceptance.py](menu_acceptance.py) reutiliza os portões da aceitação de
Arena: fixture parada e sem estado pendente, hashes exatos de JAR/configuração,
contas sintéticas existentes e pasta privada vazia. Exercita catálogo,
recusas de transferência, opções/grupos/desafios, prontidão, nove percursos de
entrada/saída, votos Build Battle e combate letal Arena. Compara o estado NBT
original e os resultados duráveis. Nunca apaga dados pendentes para passar;
a recuperação autenticada deve resolvê-los antes de repetir o ensaio.

A execução usa os mesmos argumentos de [arena_acceptance.py](arena_acceptance.py),
com `--authorized-loopback-fixture`, `--java`, `--private-output`,
`--expected-plugin-sha256`, `--expected-config-sha256` e `--credentials`.
Credenciais, bases, logs e NBT permanecem privados e fora de Git.

Em 2026-10-08, o driver completo passou no candidato de menus
`ec75a37a6db371795ebb5aac71feb85d16fc6368dcd2ebb4174750d5c3c43095`.
Incluiu lane explícita Arco, duas entradas Elytra após preparação/limpeza,
votos de tema e avaliação Build Battle e vitória letal Arena 1v1 com kit fixo.
Os 18 campos originais foram restaurados; o servidor foi parado sem pendências.
Os 198 checks offline e 664 testes de cada checkout também passaram.
Os limites da execução estão na [referência dos menus](../../docs/minigame-menus.md).
