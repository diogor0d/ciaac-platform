# Validação de todos os minijogos

- Atualizado: 2026-10-08 (`Europe/Lisbon`).
- Validação local delimitada concluída num Paper descartável com contas e
  mundos sintéticos; produção não foi alterada por este trabalho.

## Resultado corrente — 2026-10-08

Candidato aceite nos percursos locais abaixo:
`b34b6a033728e6d8bd44fb90e8c373c0c56be95520f2f7f721e568c35c4d4e23`.
Os nove modos têm gameplay normal e restauro nativos delimitados; todos têm
também evidência de recuperação fria, com os candidatos e cenários originais
preservados nas secções seguintes. Isto permite preparar instalações a partir
do guia, não ativar produção sem ensaiar a geometria e os providers reais.

A repetição corrente passou Sumo/Batata Quente (`all`, que cobre apenas esses
dois modos), Parkour normal/crash, Arco, Build Battle, Bigornas, Chão de Cores,
`setup-repeat` e `build-battle-isolation`. Passaram também os casos delimitados
de fronteira/ordem/concorrência/timeout de Parkour, impacto/eliminações/esquiva
de Bigornas e o percurso completo de Elytra com cliente Minecraft real.
Os quatro score bands de Arco e os restantes crashes de mundo conservam a
evidência anterior: os seus controladores/providers têm bytecode idêntico no
candidato corrente; não foram reclassificados como novos ensaios.

O Coliseu R2 corrente passou kit light, spawns, 21 ataques nativos, resultado
exato `VICTORY` / `LETHAL_DAMAGE` em 29,95 s, saída, desconexão/AuthMe,
outsider recusado e crash frio com autenticação normal. Comparou os 18 campos
de ambos os jogadores em cada recuperação. A primeira repetição produziu
`ESCAPE_ATTEMPT` devido à escolha fixa do atacante com os spawns trocados;
foi preservada, recusada como prova de letalidade e recuperada antes do R2.
O driver escolhe agora o atacante voltado para a maior margem do piso,
utilizando apenas input normal e observações da posição nativa.

Verificação final: Paper parado, porta 25567 sem listener, SQLite íntegro,
185 sessões `CLOSED` / 185 snapshots `RESTORED`, `ops.json` vazio e cliente
Minecraft desligado. As suites canónica e do espelho passaram 648 testes
(647 aprovados, um skip pré-existente); os 162 checks offline do peer passaram.
Os dados brutos continuam privados, fora do Git. Armadura, formatos adicionais,
apostas, física vanilla completa, instalações reais e menus de inventário não
são declarados aceites por este trabalho.

## Critérios de aceitação

Cada modo precisa de entrada autenticada por um jogador comum, teleporte e
equipamento corretos, mecânicas e resultado nativos, recusa das ações proibidas,
saída/desconexão, recuperação após crash e restauro exato do estado original.
A confirmação de sessão `CLOSED` não substitui a comparação do estado Paper.
Para modos que alteram blocos ou criam entidades, a limpeza do mundo também
precisa de prova própria, incluindo reabertura após crash.

O percurso deve ser reproduzível sem OP, permissões globais, desativação do
anti-cheat ou fornecedores fictícios de isolamento. A ferramenta de peers usa
autenticação normal e pacotes nativos; o modelo de movimento em chão plano é
uma aproximação e não prova colisões nem física vanilla de voo.

## Matriz de evidência delimitada anterior

| Modo | Evidência delimitada disponível | Validação que ainda falta neste trabalho |
| --- | --- | --- |
| Coliseu | Candidato `6792e19…`, Paper local, R5: kit fixo light (espada de ferro e escudo), spawns exatos, combate nativo com 21 ataques, `VICTORY` / `LETHAL_DAMAGE` em 30,02 s; saída, desconexão e crash frio restauraram 18 campos; outsider impedido de entrar na região | Kit de armadura ainda sem letalidade aceite; ampliar formatos e ações recusadas, e manter aceitação de produção separada |
| Sumo | Candidato `6792e19…`: ring-out, repulsão, timeout, outsider e desconexão; repetição de crash frio agora restaurou exatamente 18 campos nativos | Completar ações recusadas e ensaios noutras instalações; aceitação de instalações reais continua fora desta validação local |
| Batata Quente | Candidato `6792e19…`: passe/cooldown/pavio, Spectator após eliminação, vitória, saída da fila e desconexão; repetição de crash frio agora restaurou exatamente 18 campos por jogador | Completar ações recusadas e ensaios noutras instalações; aceitação de instalações reais continua fora desta validação local |
| Parkour | Candidato `6792e19…`: checkpoints, vitória, saída, AuthMe e crash; final `0741a3b…`: dois runners concorrentes, finish antes do primeiro checkpoint recusado e timeout; 18 campos exatos | Fronteira aceite no candidato `b34b6a0…`; queda vertical e física de colisões/instalações reais continuam fora desta aceitação |
| Build Battle | Candidato `6792e19…`: votos de tema, construção/destruição/reconstrução nativa, vitória votada, saída, quit/AuthMe, timeout sem votos e crash frio; 18 campos e 200 células exatos, lobby e intervalos preservados | Fronteira dos dois lotes aceite no candidato `b34b6a0…`; edição direta de lote alheio não é alcançável no fixture. Ampliar geometria/capacidade nas instalações reais antes de ativar produção |
| Arco | Candidato `6792e19…`: arco real, `VICTORY` / `COMPLETED`; alvo ausente e lane ocupada recusados; saída, desconexão e timeout restauraram 18 campos. Crash depois do disparo restaurou estado e removeu a seta confirmada | Final `0741a3b…` aceitou os quatro score bands com pontuação exata; ampliar outras ações recusadas e manter aceitação de instalações reais separada |
| Bigornas | Candidato `6792e19…`: onda nativa concluída (`VICTORY` / `COMPLETED`); entrada tardia recusada; 64 células do piso inalteradas e marcador removido. Quit, desconexão e crash frio restauraram 18 campos | Impacto, eliminação e esquiva nas duas ondas aceites no candidato `b34b6a0…`; instalações reais continuam fora desta validação local |
| Chão de Cores | Candidato `6792e19…`: conclusão, saída, desconexão e crash frio restauraram 18 campos; leitura nativa confirmou as 4096 células do template após o crash | Completar restantes cenários locais; aceitação de instalações reais continua fora desta validação local |
| Elytra | Candidato `6792e19…`, R5: cliente Minecraft normal completou voo, impulso e dois anéis; vitória, saída, timeout, desligação/AuthMe e crash com foguete confirmado vivo restauraram 18 campos; foguete `REMOVED` e UUID ausente | Ampliar ordem inválida, resets e limites de voo; instalações reais continuam fora da validação local |

Os providers de isolamento dos nove modos estão agora integrados no código. Isso
inclui os contratos delimitados de reset/ownership de Build Battle e de manifesto,
limpeza e orçamento de foguetes de Elytra; não equivale por si só à aceitação integral de todas as condições desses
dois modos; os percursos nativos aceites estão delimitados na matriz. Não remover verificações de mundo para fazer um teste passar. A
falha de restauro do ensaio anterior continua arquivada como evidência histórica.
O resultado corrente acima encerra a validação local delimitada. Cada aceitação
continua limitada às ações, cenários e candidatos registados; não é uma
declaração de prontidão de todas as variantes ou de produção.

## Preparação das instalações

Usar o mundo principal para Coliseu, Sumo, Parkour, Arco, Bigornas e Chão de
Cores. Build Battle, Batata Quente e Elytra exigem mundos dedicados. Nome e UUID
têm de corresponder ao mundo carregado; regiões de diferentes jogos não podem
sobrepor-se. Os guias de cada modo e o resolvedor são as autoridades de opções.

A facilidade de configuração será aceite com instalações sintéticas: obter
identidade do mundo, preparar coordenadas/regiões, produzir e verificar templates
quando necessários, detetar erros antes de abrir entradas e repetir a instalação
sem editar UUIDs ou checksums manualmente. A captura inicial e a repetição sobre artefactos existentes foram ensaiadas
localmente: recusa de substituição, digests preservados, reinício normal,
identidade/altura de mundo e nove módulos disponíveis. Não é uma instrução
de ativação de produção nem valida a geometria de uma instalação real.

Ver os [adaptadores](external-state-adapters.md), os
[templates](template-artifacts.md), o [Parkour](checkpoint-parkour.md) e a
[preparação de Sumo/Batata Quente](sumo-hot-potato-deployment.md). O
[guia das nove instalações](minigame-facility-setup.md) distingue requisitos
configuráveis dos contratos ainda pendentes.

## Checkpoint anterior do Parkour

O candidato local com a correção do teleporte de conclusão do Parkour tem SHA-256
`509c7865d3f84607f9d248c34d4bd87335832733e7cbc414e469edbd7467fb2a`.
Passou 482 testes do plugin: 481 passaram, um skip pré-existente, zero falhas e
erros. A ferramenta de peers passou 77 verificações offline. Não é um release
final nem prova implantação; alterações posteriores exigem nova identificação
do artefacto e repetição dos percursos afetados.

A primeira execução nativa do Parkour reproduziu uma vitória com posição final
incorreta apesar de inventário/XP e marcadores duráveis restaurados. A conclusão
ocorre dentro de `PlayerMoveEvent`; manter o destino antigo permite desfazer o
teleporte de recuperação. O router passa agora o destino realmente restaurado
para Paper. A repetição do candidato `509c786…` confirmou os 18 campos,
incluindo posição, em checkpoints ordenados, vitória, saída, quit e crash.

## Candidato anterior — 2026-10-07

SHA-256 do JAR CIAACPlatform: `7ff23154714b105720bf1fd376deed69af4f0897267a880395f4733d331a6dd2`.
Suite: 563 testes, 562 aprovados, um ignorado, zero falhas e erros. O Arco tem
prova nativa neste candidato para crash após lançamento de seta: o arco real
foi puxado/libertado, o UUID exato da seta foi confirmado viva antes de
`save-all` e SIGKILL do subprocesso Paper próprio; após reinício e autenticação
AuthMe normal com a mesma conta, os 18 campos coincidiram, a entidade foi
`REMOVED` e o UUID exato já não existia. O crash antigo anterior ao lançamento
continua registado como caso distinto; nenhum dos casos demonstra outras
condições de crash ou aceitação integral do modo.

O candidato `e53c89f00e1ec08879ad6dad8a74a0d3722d69b548b93e0e04ee96b1eb263d3f`
passou 548 testes (547 aprovados, um ignorado, zero falhas/erros) e suportou os
casos nativos de 96 células indicados na matriz. No candidato `7ff2315…`, o
fixture confirmou por consulta nativa 3712 células azuis em `AIR` na secção
completa do piso, antes de `save-all` e SIGKILL do processo Paper próprio.
Depois do reinício frio e autenticação AuthMe normal, comparou positivamente
as 4096 células com o template e restaurou os 18 campos de cada jogador; 12
sessões ficaram `CLOSED` e 12 snapshots `RESTORED`. Isto confirma recuperação
fria para esta secção e cenário. O ensaio anterior que deixou sessões
`QUARANTINED` mantém-se preservado como evidência histórica.

## Checkpoint de código anterior — 2026-10-08

SHA-256 do JAR CIAACPlatform: `6792e1924d90214e3a7c26919eb3cd0d0aaa83c16db0de38157f533129c1dc05`.
Suite completa: 648 testes, 647 aprovados, um skip pré-existente, zero falhas e
erros. Este resultado é evidência do candidato de código; os percursos nativos
que ainda não foram repetidos neste candidato permanecem pendentes.

No candidato nativo `037`, as Bigornas completaram duas ondas normais com
`VICTORY` / `COMPLETED`; o quit verificou 18 campos, 64 blocos `STONE`, remoção
do marcador e ausência da entidade no mundo nativo. Um crash frio durante
`ACTIVE`, seguido de reinício e autenticação AuthMe normal, restaurou os 18
campos, manteve os marcadores ausentes e deixou 24 sessões `CLOSED` e 24
snapshots `RESTORED`. No Arco, dois disparos reais deram `VICTORY` /
`COMPLETED`; alvo inexistente e lane ocupada foram recusados; saída, quit e
timeout verificaram os 18 campos, 28 sessões `CLOSED` e 28 snapshots
`RESTORED`. Estes resultados são delimitados ao candidato nativo `037` e aos
cenários descritos.

Build Battle e Elytra já têm providers integrados. Elytra tem um voo nativo
aceite num candidato anterior; os respetivos percursos nativos no candidato
atual continuam em validação. Nenhum build, teste ou percurso
descrito aqui alterou a produção; os nove modos continuam sem declaração de
prontidão global.

## Ensaio nativo de Elytra e preparação — 2026-10-08

No candidato `d568f38…`, a captura nativa de Build Battle criou um artefacto
de 360 células, verificou as 200 células próprias dos plots e preservou o
intervalo entre parcelas e o lobby. Isto confirma a captura de instalação;
a construção, votação e recuperação integral do encontro continuam pendentes.

O cliente Minecraft normal, em segundo plano, entrou e autenticou-se no fixture
local com AuthMe. O ensaio de Elytra confirmou teleporte, equipamento temporário
e voo nativo (`FallFlying: 1b`), mas o impulso foi recusado pela verificação de
metadados. A sessão ficou em quarentena: este ensaio **falhou** e não confirma
restauro integral nem vitória. Os dados e logs de cada falha estão preservados
privadamente; não foram reclassificados como sessões recuperadas.

O diagnóstico no candidato `c094103…` registou potência Bukkit igual a zero,
sem efeitos, veículo, passageiros ou fogo. O bytecode do Paper 26.2 revisto
mostra que `FIREWORK_ROCKET` tem um componente nativo por omissão com potência
1, enquanto `FireworkMeta` lê o delta e devolve zero quando a potência não está
explicitamente sobrescrita. A correção tem de validar o componente efetivo,
mantendo recusados componentes ausentes, potências diferentes e explosões;
precisa de nova prova nativa de impulso, anéis e limpeza.

Foi também reproduzida a aplicação tardia do modo Survival por Multiverse,
um tick após mudar de mundo. A configuração do mundo dedicado de Elytra em
Adventure, através do comando nativo `mv modify <mundo> set gamemode adventure`,
manteve `playerGameType: 2` no ensaio seguinte, sem desativar a proteção global.
O setter imediato do controlador não substitui essa configuração do mundo.

Foi pedida uma melhoria de navegação por menus de inventário: catálogo dos nove
modos e seleção de formato, kit, desafio e prontidão no Coliseu. A inspeção
confirmou ações reutilizáveis e um marcador de inventários próprios do plugin,
mas nenhum menu de inventário implementado. Esta melhoria ainda é proposta;
não constitui evidência de funcionalidades GUI disponíveis ou aceites.

## Voo nativo aceite e falhas de Build Battle — 2026-10-08

No candidato `146829b9ac4b6a8af5d647f6559cae68f89cf97823bd1ad1bc16c8b21cd87265`,
a verificação usa o componente nativo efetivo `FIREWORKS`: duração 1 e sem
explosões. O componente ausente ou inválido continua recusado. A suite teve
637 testes, 636 aprovados e um skip pré-existente. O cliente Minecraft normal
entrou pelo AuthMe, descolou, usou um foguete e atravessou os dois anéis em
3174 ms. O resultado foi `VICTORY` / `COMPLETED`; os 18 campos nativos de estado
coincidiram exatamente com a baseline, incluindo inventário, equipamento,
XP, modo, posição e mundo. A sessão ficou `CLOSED`, o snapshot `RESTORED` e o
foguete `REMOVED`; a consulta pelo UUID exato confirmou ausência no mundo.
Esta prova substitui a pendência de impulso descrita no diagnóstico anterior;
não substitui os cenários de saída, desconexão, timeout ou crash frio.

O ensaio nativo de Build Battle no mesmo candidato falhou antes da construção:
Multiverse aplicou Adventure depois do Creative atribuído pelo jogo. A saída
normal dos peers também encontrou o bloqueio de teleporte de restauro no
controlador de Build Battle. O fixture terminou com uma sessão em quarentena,
outra ativa e apenas a sessão anterior de Elytra fechada/restaurada. A base e
os journals desse ensaio estão arquivados privadamente, sem reclassificar ou
apagar a quarentena para conseguir um resultado positivo.

As correções seguintes preservam o modo temporário durante `PREPARING` /
`ACTIVE` e permitem o teleporte de restauro apenas à sessão correspondente do
mesmo encontro em recuperação. O driver também passa a exigir a resposta
nativa de sucesso de cada voto de tema antes de iniciar construção. Estas
correções precisam de nova suite e repetição nativa antes de declarar aceitação
de Build Battle.

No candidato `a31fdfa…`, dois peers autenticados votaram temas, entraram nas
parcelas em Creative e colocaram, partiram e recolocaram um bloco através de
pacotes nativos normais. A saída restaurou os 18 campos e as 200 células
próprias, mantendo os sentinelas do intervalo e lobby. O cenário de desconexão
falhou depois de AuthMe: quatro sessões estavam `CLOSED` e quatro snapshots
`RESTORED`, mas o primeiro peer já restaurado voltou a Adventure. A entrada do
segundo peer repetiu `recoverAll`; `moveToWaiting` ainda alterava o primeiro
peer fechado antes de o teleporte ser recusado. A comparação nativa detetou
diferenças em modo e abilities, apesar dos registos fechados. O ensaio e as
baselines estão preservados privadamente.

O candidato `6792e19…` impede essa alteração antes de qualquer mudança de modo,
flight, token ou teleporte: só o participante exato com sessão do mesmo jogo e
encontro ainda `ACTIVE`, e sem quit em curso, pode transitar para a espera. O
guard de modo aplica-se apenas a Elytra e Build Battle; não interfere com os
modos Spectator usados na eliminação do Coliseu, Batata Quente e Chão de Cores.
A suite completa passou os 648 testes descritos acima; a campanha de validação
total continua em curso. A repetição já confirmou saída e desconexão/reautenticação com os 18
campos exatos e as 200 células próprias. Um encontro votado teve resultado
`VICTORY` / `RESULT`, dois resultados de jogador, pontuação total 8 e dois
votos; revisão e movimento nativos nas parcelas dos adversários, restauro
exato e preservação de lobby/espaço entre parcelas foram verificados. Timeout
de votos em falta terminou em `NO_CONTEST` / `VOTING_TIMEOUT`, sem votos
preenchidos por omissão. Um crash frio depois de alteração nativa de um bloco
foi seguido por reinício e autenticação normal com as mesmas credenciais;
verificou os 18 campos e todas as 200 células, com 20 sessões `CLOSED` e 20
snapshots `RESTORED` na base sintética corrente. Os sentinelas de lobby e
intervalo ficaram preservados em cada cenário. O runner terminou com código 0
e parou normalmente o servidor. As falhas históricas continuam arquivadas.

## Repetição de percursos nativos no candidato — 2026-10-08

No candidato `6792e1924d90214e3a7c26919eb3cd0d0aaa83c16db0de38157f533129c1dc05`,
o registo terminal do runner local confirma dez casos concluídos, todos com
código 0 e `all_success: true`: Parkour, Arco, Bigornas, Chão de Cores e os
crashes frios de Sumo, Batata Quente, Parkour, Arco após disparo, Bigornas e
Chão de Cores. Os ensaios usaram Paper descartável, AuthMe revisto, peers
sintéticos autenticados e listener em loopback; não tocaram na produção.

Nos percursos normais, Parkour terminou em `VICTORY` / `COMPLETED`; saída e
desconexão deram `NO_CONTEST`, e uma reentrada ativa foi recusada. O Arco
terminou com arco e eventos de projétil reais em `VICTORY` / `COMPLETED`; alvo
em falta e lane ocupada foram recusados, e saída, desconexão e timeout foram
verificados. Bigornas terminou em `VICTORY` / `COMPLETED`, recusou entrada
tardia e removeu o marcador do plugin. Em cada caso, a comparação nativa
confirmou a restauração exata dos 18 campos de jogador. O comparador inclui
inventário, equipamento, XP, modo, `Pos`, `Rotation`, `Dimension` e as duas
metades do UUID do mundo; os valores e UUIDs não são reproduzidos aqui. Chão de
Cores também terminou em `VICTORY` / `COMPLETED`; saída e desconexão restauraram
os 18 campos e a leitura nativa confirmou as 4096 células do template intactas.

Após crash frio, reinício e nova autenticação normal por AuthMe, Sumo, Batata
Quente, Parkour, Arco e Bigornas também restauraram exatamente os 18 campos e
fecharam as sessões com snapshots `RESTORED`. No Arco, o crash ocorreu depois
do disparo confirmado de uma seta real; após recuperação, o registo nativo
confirmou a seta removida e a sua ausência no mundo. Em Bigornas, o marcador
temporário foi removido e não existia no mundo após recuperação. Chão de Cores
alterou 3712 células para `AIR` antes do crash; após reinício, AuthMe confirmou
os 18 campos dos dois jogadores e a leitura das 4096 células do template, sem
reparação feita pelo próprio teste. Nos casos de crash, o resultado durável foi
`null`: a recuperação está verificada para estes cenários, mas não foi registada
uma vitória ou outro resultado de partida.

Esta repetição fecha os dez casos locais indicados, não a aceitação completa
dos nove modos nem de instalações reais. Permanecem os cenários por modo da
matriz que continuam locais, incluindo variantes de Coliseu e condições adicionais
de Elytra, bem como ampliação da geometria/capacidade e das ações
recusadas de Build Battle. Os percursos de Build Battle já registados para este
candidato estão concluídos. A aceitação de cada instalação real continua fora
desta validação local.

## Coliseu nativo R5 — 2026-10-08

No JAR `6792e1924d90214e3a7c26919eb3cd0d0aaa83c16db0de38157f533129c1dc05`,
o runner local terminou com código 0 e `complete: true`. No fixture Paper
descartável, o kit fixo light continha espada de ferro e escudo nativos, e os
dois jogadores apareceram nos spawns configurados. Movimento e velocidade
nativos acompanharam 21 ataques nativos; a partida terminou em
`VICTORY` / `LETHAL_DAMAGE` aos 30,02 s, antes do limite de 45 s.

Saída durante combate produziu `VICTORY` / `PLAYER_LEFT`; quit durante combate,
reautenticação normal pela mesma conta AuthMe e recuperação produziram
`VICTORY` / `DISCONNECT`. Os dois casos restauraram os 18 campos nativos de
ambos os jogadores exatamente. Um crash frio com dois participantes ativos,
seguido de reinício e autenticação normal AuthMe, recuperou ambos e restaurou
os mesmos 18 campos; o resultado durável foi `null` (sem classificação).
O registo sintético ficou com 82 sessões `CLOSED` e 82 snapshots `RESTORED`.
O teleport de um outsider para a região foi recusado e os 18 campos ficaram
inalterados.

A cobertura deste R5 é o kit light fixo e os cenários listados. Uma tentativa
separada com kit de armadura confirmou itens nativos, mas não atingiu o limite
de letalidade definido; não conta como letalidade aceite. As tentativas R1–R4
permanecem preservadas: R1 esperou uma mensagem de início para ambos quando só
o último jogador pronto a recebe; R2 suspendeu o modelo de movimento após uma
correção do servidor; R3 atingiu o limite de perseguição; R4 terminou numa
vitória `ESCAPE_ATTEMPT`. Os estados desses ensaios foram recuperados com
autenticação AuthMe normal. São limitações desses percursos do driver e não
evidência de defeito do plugin.

Isto fecha o R5 local para estes cenários; não demonstra aceitação de kit de
armadura nem partida nativa em produção. Os formatos, kits e ações recusadas
restantes continuam sujeitos à matriz da Arena.

## Elytra R5 e consistência da ajuda — 2026-10-08

O cliente Minecraft normal completou todos os percursos locais de Elytra no
candidato `6792e19…`: voo e impulso com dois anéis, saída, timeout, desligação
com autenticação normal e crash frio com foguete nativo ainda vivo. Cada caso
comparou os 18 campos da baseline. Ambos os foguetes registados ficaram
`REMOVED` e ausentes pelo UUID exato após conclusão ou recuperação. O runner
terminou com `complete: true`, 93 sessões `CLOSED` e 93 snapshots `RESTORED`.
O encontro interrompido não recebeu vitória nem resultado classificado. As
falhas de input/observação anteriores mantêm-se preservadas; não foram
corrigidas por apagar dados de recuperação.

A ajuda da consola local passou a incluir `capturar-construcao` nas mesmas
listas que já apresentavam `capturar-cores`. A suite completa voltou a passar
648 testes: 647 aprovados, um skip pré-existente, zero falhas e erros. O JAR
resultante tem SHA-256
`0741a3bb40baee40d68cc595f561989e8b9e8e612cdcd352691e1204f14b9c5c`.
A comparação de todos os ficheiros `.class` com `6792e19…` confirmou que só
`CiaacPlatformCommand.class` mudou; todos os controladores, isolamento,
autenticação, persistência e mecânicas dos nove modos são byte a byte iguais.
A prova de gameplay acima continua identificada pelo JAR em que ocorreu; a
ajuda e preparação do artefacto final exigem a sua própria repetição nativa.

## Preparação repetida e condições de gameplay — 2026-10-08

No JAR final `0741a3bb40baee40d68cc595f561989e8b9e8e612cdcd352691e1204f14b9c5c`,
`setup-repeat` confirmou os nove módulos `configuração=válida; módulo=WAITING`
antes e depois de reinício normal. As capturas de Build Battle e Chão de Cores
sobre artefactos existentes foram recusadas. Os digests de ambos os templates e
da configuração mantiveram-se exatos, assim como nome, UUID e alturas dos
mundos consultados pela consola. Não houve edição manual de UUID/checksum.
A ajuda normal e de comando desconhecido apresentou `capturar-construcao`.

`parkour-edges` admitiu dois corredores ao mesmo tempo e restaurou os 18 campos
de ambos após saída normal. O movimento nativo cardinal contornou o primeiro
checkpoint e tentou entrar no finish primeiro: a passagem foi recusada, a
posição nativa ficou antes da região e a sessão permaneceu ativa; sair produziu
`NO_CONTEST` / `PLAYER_LEFT`. Uma tentativa sem completar checkpoints terminou
em `NO_CONTEST` / `TIME_LIMIT`, com os mesmos 18 campos originais. Não foi
injetado evento nem usado teleport para atravessar os checkpoints. O modelo
plano do peer não demonstra colisões vanilla nem quedas verticais reais.

`archery-bands` efetuou quatro tentativas distintas de dois disparos nativos.
Bullseye, inner, middle e outer deram respetivamente score 20, 14, 10 e 4;
cada tentativa registou exatamente dois shots, dois bullseyes apenas na
primeira e zero nas restantes. Os quatro resultados foram `VICTORY` /
`COMPLETED`; os 18 campos originais coincidiram após cada tentativa. O batch
terminou com código 0 para os dois casos e 106 sessões `CLOSED` / 106 snapshots
`RESTORED`. O runner e o Paper próprio terminaram normalmente.

A ferramenta pública `elytra_acceptance.py` também repetiu a sequência integral
no JAR `6792e19…` com sucesso, 98 sessões `CLOSED` / 98 snapshots `RESTORED` e
remoção exata dos foguetes. O seu contrato exige cliente normal em segundo
plano, endpoint e credenciais privados existentes, GUI revista e hashes
explícitos; não cria contas nem muda configuração. As 162 verificações offline
do cliente de pacotes passaram em 2026-10-08.

## Revisão de ferramentas antes da retoma — 2026-10-08

Depois da repetição aceite, a revisão de `elytra_acceptance.py` reforçou a
asserção de admissão: o runner agora exige observação combinada de sessão
ativa e dimensão do cliente, seguida da dimensão nativa Paper exata. Uma
sessão ativa sem confirmação do mundo termina o teste e não reenvia entrada.
Três checks offline aceitaram o caso correto e recusaram discrepância no
cliente ou no Paper, sempre com um único pedido de admissão. A revisão das
cinco snapshots ativas da repetição anterior confirmou que todas já estavam
na dimensão Elytra correta. O guard novo não tem ainda repetição runtime;
a prova de gameplay conserva a versão da ferramenta em que ocorreu.

Os cenários adicionais `anvil-edges` e fronteira de `parkour-edges` estão
implementados e compilados, mas a tentativa de iniciar o batch foi recusada
pelo mecanismo de automatic approval review por limite de utilização. A ação
não executou e a revisão não determinou que fosse insegura. Não foi contornada.
Assim, impacto/esquiva nativa de Bigornas e regresso da fronteira do Parkour
continuam `RUNTIME-UNVERIFIED`; não reduzir estes gates a uma compilação.
O fixture ficou parado, sem listener, com 106 sessões `CLOSED` e 106 snapshots
`RESTORED`. Nenhuma sessão pendente foi apagada/recriada. Retomar os checks
preservando este estado quando o mecanismo de revisão estiver disponível.

A inspeção final do fixture também confirmou `ops.json` vazio: nem o cliente
normal autorizado nem os peers sintéticos eram OP. A base corrente contém
resultados normais para os nove modos, distintos das sessões de crash sem
resultado classificado. O JAR instalado no fixture parado coincide com o
candidato final `0741a3b…`; a produção não foi consultada nem modificada.

## Retoma da revisão e regressão de fronteira — 2026-10-08

O mecanismo de revisão voltou a estar disponível. O batch inicial revelou a
falha real de reset de Parkour descrita na página do modo; o primeiro ajuste
ainda falhou por um cancelamento anterior na rota. Ambos os ensaios falhados
foram preservados e os três participantes voltaram a autenticar-se normalmente,
confirmando todos os 18 campos antes de novo ensaio. Nada foi apagado/resemeado
para classificar uma tentativa como aceite.

Candidato atual: `b34b6a033728e6d8bd44fb90e8c373c0c56be95520f2f7f721e568c35c4d4e23`.
A suite completa passou novamente: 648 testes, 647 aprovados, um skip
pré-existente e zero falhas/erros. A comparação dos 748 `.class` com `0741a3b…`
identificou apenas `MinigameEventRouter.class` diferente; as evidências dos
candidatos anteriores conservam os seus hashes e âmbito.

O batch R3 terminou com ambos os casos e código zero: Parkour confirmou gate
após tentativa de fronteira, concorrência, ordem e timeout; Bigornas confirmou
fronteira, impacto/eliminações, esquiva nas duas ondas e resultado da partida
exata. Todos os campos originais e os marcadores/piso de Bigornas passaram.
Paper parou normalmente com 119 sessões `CLOSED` e 119 snapshots `RESTORED`.
As consultas novas exigem o ID exato da partida; quatro checks offline recusam
vitória antiga e participantes repartidos por partidas distintas. O ensaio de
fronteira é uma regressão nativa real, sem novo helper de produção só para testar
uma cópia do handler.

O novo `build-battle-isolation` passou na repetição R4: ambos os participantes
foram corrigidos na fronteira exata do próprio lote, continuaram ativos e
restauraram os 18 campos; 200 células e sentinelas ficaram preservadas. As
tentativas preliminares, incluindo uma colisão com bloco de teste e teleporte
ainda em trânsito, não foram aceites como prova de fronteira. Edição direta do
lote alheio não é alcançável neste fixture e não foi declarada aceite. Paper
parou normalmente com 127 sessões fechadas/restauradas. A repetição do guard
Elytra está em execução e continua pendente. A preparação documentada agora exige o
número mínimo configurado de jogadores autenticados e distingue os cenários
locais já aceites das instalações reais. Produção continua sem alterações.

## Elytra com guard corrente — 2026-10-08

A ferramenta pública repetiu a sequência completa no candidato `b34b6a0…` com
sucesso: cliente Minecraft real, dimensões cliente/Paper confirmadas, voo/impulso
e dois anéis, saída, timeout, desligação normal/AuthMe e crash com foguete
confirmado vivo depois de flush. Os 18 campos e a remoção exata do foguete
passaram. A sequência terminou com 132 sessões `CLOSED` e 132 snapshots
`RESTORED`; o Paper próprio parou normalmente. O guard corrente já tem prova
runtime, substituindo apenas a lacuna indicada no checkpoint anterior.

A repetição dos restantes percursos no candidato corrente está em curso, depois
da mudança no router partilhado. O driver agora liga todos os resultados aos
registos da partida de cada participante, incluindo runners concorrentes;
recuperação fria não pode satisfazer o check com vitória antiga ou resultado
classificado inventado. As evidências guardadas conservam os hashes e versões
em que ocorreram.
