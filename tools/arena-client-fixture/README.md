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
valores fora dessa lista são recusados. O nLogin deve inicializar e autenticar
cada conta sintética pelo fluxo normal.

Compilar com JDK 25 e um executável Maven existente:

```sh
python3 tools/arena-client-fixture/build.py --maven /path/to/mvn
```

A compilação resolve apenas as dependências da ferramenta, valida o digest do
protocolo e escreve classes/classpath em `target/local-arena-client`. Não liga
a nenhum servidor. Para execução autorizada, usar esse diretório e classpath
com `LocalArenaPeer --authorized-loopback-fixture [--fixture-peer 2|3|4|5]` num stdin aberto. A leitura
de comandos aceita somente `register`, `login`, `coliseu`, `minijogos` e
`passaporte`, além de `status` e `quit`; nunca imprime os argumentos. Não enviar
credenciais num terminal com echo, nem guardá-las em Git.

O peer reconhece teleports nativos, envia o ack e a posição resultante,
reconhece batches de chunks e emite fim de tick. A biblioteca trata keepalive e
a troca de estado de configuração. Mantém-se estacionário: não implementa
física, navegação ou combate autónomo. Não serve para provar esses controlos.
As mensagens do servidor são anunciadas sem imprimir texto ou argumentos.
A conexão expira ao fim de 15 minutos e fecha ao sair do processo.

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
