# Ativação da Arena do coliseu

Preparação em **2026-10-05**; migração e ativação autorizadas em **2026-10-06**.
Estado atualizado em **2026-10-07**: Arena **ativa** no backend CIAAC, com AuthMe
como único fornecedor. O JAR atual passou aceitação nativa de combate fixo 1v1
e recuperação por desconexão; ver a secção final. A validação não cobre equipas
maiores, espectadores ou outros minijogos. O servidor pertence a terceiro; não
criar outra instância no Crafty.

As secções anteriores à aceitação final descrevem os seus snapshots históricos;
pendências antigas não substituem o estado verificado da secção final.

A região WorldGuard `ciaac_coliseu`, prioridade 10, permite PvP e desativa
invencibilidade apenas no piso X=-418..-321, Z=6425..6470, Y=-64..319.
As outras regiões foram preservadas. Todos os jogadores admitidos e
autenticados têm os nós públicos por omissão; os nós administrativos permanecem
restritos. Apostas continuam desativadas.

## Revisão final de login — 2026-10-06

A validação no alvo encontrou um jogador sem sessão salvo dentro do piso:
a fronteira impedia a sua saída a pé. A revisão
`9c01c0009984ac421227f318adc0e4ddc1f1e13d34967cb364eff6deec96ebe3`
acrescenta uma deslocação para a saída de espectadores depois de autenticação
e conclusão da recuperação. Só atua sem sessão registada, após
`NO_RECOVERY_PENDING` ou `RECOVERED`; não desloca participantes ativos nem
recuperações pendentes/quarentenadas. Uma falha de teletransporte desliga o
jogador com uma mensagem segura, sem qualificação de Passport.

A suite final executou 435 testes: 434 aprovados, um ignorado, zero falhas/erros.
Comparação dos membros do JAR confirmou alterações apenas nas três classes
responsáveis por este hook e nos metadados de construção. Os formatos de dados,
contratos de combate e fornecedor AuthMe não mudaram. O JAR anterior e os dados
fechados atuais foram preservados no servidor; rollback desta revisão substitui
apenas o JAR CIAAC, sem reverter contas AuthMe ou mundos.
A aceitação nativa desta revisão confirmou login normal, deslocação para a
saída de espectadores e entrada/saída da fila. A leitura completa do inventário
após uma partida no alvo ainda requer um segundo participante.

## Candidato e evidência

O candidato inicial da migração `0.1.0-alpha.1`, anterior à revisão de login acima, tem SHA-256
`9fd508fef8d5fcd0c3f0c52f8e7b5fbe43d4a4e3bb7377add6f17fd20e4d2805`.
A reconstrução final preservou os membros internos do ZIP do JAR guardado.
Os XML finais do Surefire registam 428 testes, 427 aprovados, um ignorado e zero
falhas/erros; a primeira execução falhou ao criar temporários para testes de
symlink e a execução posterior definiu o diretório temporário no arranque.
O checkout contém alterações não commitadas; este digest identifica
um artefacto local, não uma release oficial assinada. A publicação e o atualizador seguem
o [contrato de releases](release-updater.md); não desativar a validação de
assinaturas para instalar este candidato.

A [verificação funcional](functional-verification.md) distingue os ensaios do
JAR anterior `463af6b312bbf4ecc981629ff2644680ed58e09f1ebdb7c4b7bf2350a675da7c`
dos ensaios deste JAR. Nesse artefacto anterior estão confirmados desafios e prontidão
autenticados em 2v2, 3v3, 2v3 e 3v2, forfeit por movimento nativo, desistência
parcial, fronteira de espectadores, cancelamento de duas agressões entre colegas
e de um portal Nether real, e restauração após encerramento normal.

O crash com seis participantes **falhou a aceitação** em dois artefactos:
quatro modos errados no JAR anterior e um no experimental
`18191810391d7685035f51406fa82e26cfa66ff950b6409757b1a981abd50a24`.
O nLogin escreveu estado limbo depois de CIAAC já ter marcado as sessões como
`CLOSED`/`RESTORED`. Nem o evento nem o flag público de autenticação da API 10.4
provam o fim dessas escritas; um atraso fixo não resolve o contrato.

O adaptador nLogin permanece fechado por falta de contrato de conclusão. O
adaptador AuthMe passou aceitação local limitada: login normal, 3v3, fogo amigo,
dano inimigo, eliminação/espectador, 1v1 por convite, 2v2, portal físico e
recuperação após crash protegido 3v3 com seis participantes. As 13 propriedades
de restauro foram exatas nos seis após crash. A saída normal foi exata para cinco
peers e teve delta de posição de 0,058 blocos no cliente humano. A continuação
confirmou 3v2, espectador por movimento e teleportes para Y=-40/Y=200, combate
1v1 com kit de produção sem arco, recusa de largar itens e restauro. A vista
nativa do inventário manteve-se aberta durante o login; GUI aberto por outro
plugin e bloqueio de dano recebido com escudo não foram ensaiados. Apostas
continuam desativadas; nenhuma liquidação foi testada. Os percursos locais
ensaiados estão verificados e o candidato preparado em
`runtime/staging/authme-arena-2026-10-05` na integração. Migração de contas e
aceitação no alvo continuam pendentes. A importação offline protegida no backend
SQLite real passou; não ativar antes de concluir a inspeção de recursos em recuperação e mundo,
backup/rollback consistente e autorização para artefacto e reinício. A inspeção
de 2026-10-06 confirmou os fornecedores instalados e permissões de grupos;
25 contas passaram o conversor oficial e atualização pública de metadados no
SQLite real, mantendo hashes, UUIDs e flags de autenticação falsas; uma repetição
não criou duplicados. Um hash existente passou a verificação AuthMe. O cliente
confirmou login nLogin pelo endereço público e suporte/espaço livre nas colunas
dos spawns preparados e saída. O UUID Bukkit atual do mundo continua por confirmar.
Estes resultados não demonstram migração AuthMe ou gameplay Arena no alvo.

A última limpeza local está confirmada em
`/private/tmp/ciaac-authme-e2e-2026-10-05/final-client-checks/cleanup-evidence.json`:
Paper terminou normalmente com código zero e gravação dos mundos; todos os
peers foram fechados, a porta local ficou fechada e os três JARs e configurações
originais CIAAC/AuthMe foram repostos byte a byte. Bases e mundos foram preservados.
A falta de prova de shutdown normal registada na primeira fase é histórica,
não descreve esta última execução.

## Inspeção antes da autorização

Quando o host voltar, recolher pelo acesso Crafty autorizado, apenas em leitura:

1. Confirmar o fornecedor de autenticação escolhido. nLogin permanece fechado
   até existir contrato suportado de conclusão, sem bypass por configuração,
   flag público, timer ou reflexão privada não validada. Se AuthMe for escolhido,
   rever a versão Paper `6.0.1-b2770`, o perfil e os efeitos da migração de contas
   numa cópia protegida. Recolher identidade do servidor CIAAC, estado, jogadores
   ligados, JAR carregado e versões reais de Paper, Java, fornecedor de
   autenticação, Vault, EssentialsX, LuckPerms, SimpleClaimSystem e integrações
   de mundo. Uma versão não suportada não autoriza remover o guard.
   A troca de autoridade exige supersessão da ADR 0004 no repositório de
   integração antes do corte. O conversor oficial importa alguns campos e
   copia hashes; não transfere TOTP/2FA nem a política premium/bypass e pode
   deixar uma importação parcial. Testar duplicados, UUIDs e passwords numa
   cópia protegida; definir recuperação/re-enrolment 2FA sem desativação silenciosa.
2. Configuração atual e defaults resolvidos. Rebasear o overlay
   [colosseum-arena.yml](examples/colosseum-arena.yml) sobre essa configuração;
   preservar as restantes secções. A cópia privada anteriormente preparada
   não comprova o estado atual do servidor.
3. UUID do mundo, suporte dos spawns, espaço livre e fronteira de espectadores.
   O retângulo de combate é X=`-418..-321`, Z=`6425..6470`, com
   `full-height: true`. Os pontos preparados são descritos em
   [Arena](arena.md); voltar a confirmar que são seguros no mundo atual.
4. Sessões pendentes, snapshots, leases e recursos em recuperação. Uma
   quarentena existente exige investigação; não apagar nem marcar como
   restaurado apenas para abrir a Arena.
5. Permissões efetivas nos contextos atuais, incluindo herança, concessões
   individuais, negações e OP. Usar os nós específicos descritos em
   [permissões administrativas](admin-permissions.md). A exportação histórica
   dos grupos não substitui essa verificação. Não conceder OP ou wildcards
   para facilitar o ensaio.

Preparar o diff exato, digest do JAR e snapshot da origem, lista de ficheiros,
backup verificável, janela de interrupção e plano de rollback. Submeter esse
pacote concreto para autorização. Não escrever ficheiros nem enviar comandos
de ciclo de vida ao backend durante esta inspeção.

## Execução autorizada e validação

O reinício desconecta os jogadores. Com autoridade para esse alvo e essa janela,
fechar novas entradas, concluir ou recuperar partidas abertas e parar de forma
controlada. Preservar uma cópia recuperável do JAR, configuração e estado
persistente consistente, incluindo SQLite e journals de recuperação. Instalar
apenas o artefacto aprovado e o overlay revisto. Não usar `/reload`.

Depois de iniciar, verificar o digest do ficheiro instalado, versão realmente
carregada, fornecedores resolvidos e estado da Arena. Um processo em execução
não é aceitação. Pelos fluxos normais do fornecedor escolhido e com contas de
ensaio aprovadas, verificar:

- negação pré-login e de comandos administrativos a jogadores sem os nós;
- desafio/fila e prontidão sem teleporte ou estado temporário antecipado;
- combate, regras de equipa e espectadores fora do retângulo a toda a altura;
- desistência/desconexão, encerramento das sessões e restauração sem itens
  temporários, incluindo XP, equipamento e localização;
- reconexão autenticada e recuperação de qualquer operação interrompida.

Usar contas/estado de ensaio autorizados e registar resultados sanitizados.
Não provocar crashes, duplicação ou cenários destrutivos em estado valioso de
produção; esses casos pertencem ao laboratório descartável.

## Rollback

Se a admissão, isolamento ou restauração falhar, fechar novas entradas e
preservar os dados e diagnóstico da falha. Parar o servidor antes de repor o
JAR/configuração guardados, e só reiniciar após confirmar compatibilidade com
o estado persistente atual. Não substituir a base por uma cópia anterior depois
de ocorrerem mutações: isso pode reexecutar restauros e duplicar itens.

Se o JAR anterior não compreender o estado novo, manter a Arena fechada e usar
a recuperação compatível revista. Registar separadamente o artefacto preparado,
o instalado, o carregado e o que passou aceitação. Credenciais Crafty, bases,
worlds, playerdata e logs privados permanecem fora de Git.

## Snapshot histórico de prontidão — 2026-10-07 (`0547103…`)

Nesse snapshot histórico, o JAR de produção tinha SHA-256
`0547103ac407ab66042cdfb100ac9b232a006ff58957845d7f1a2ce3723f69ea`.
A suite completa executou 445 testes: 444 aprovados, um ignorado, zero falhas/erros.
Em relação à revisão de login, mudaram apenas controlador, módulo, classificação
de respostas e publicador de anúncios; classes de autenticação, isolamento,
inventário e formatos persistentes permaneceram iguais.

O registo de comandos do alvo esclareceu a captura enviada pelo utilizador:
`/coliseu pronto` foi usado antes de existir uma reserva; depois foram aceites
desafios e usado `/coliseu sair` antes de ambos confirmarem. Não havia erro de
preparação registado. A mensagem genérica de recuperação descrevia um
cancelamento antes do combate e a CTA pública `[Entrar]` era enganadora.

Agora a composição da fila e a aceitação de desafios notificam cada participante
com `[Confirmar prontidão]`, que executa `/coliseu pronto`. Uma primeira
confirmação não captura inventários nem teletransporta; a segunda inicia a
preparação protegida, aplica equipamento e desloca ambas as equipas para os
spawns. Apostas, quando configuradas, continuam a exigir consentimento exato
antes de prontidão. O anúncio público de reserva já não contém a CTA de entrada.
Saída, desligação ou timeout antes de combate recebem uma mensagem explícita de
cancelamento, sem deslocar quem não tem snapshot. Falhas reais de preparação
não são classificadas como cancelamento aceite; restauro falhado mantém quarentena.
O estado mostra o número de participantes reservados e a fila conta jogadores,
não grupos. O módulo reconcilia membros com fila/reserva atuais para eliminar
bloqueios de grupo após encerramento, incluindo término por eventos.

Os testes focados usam proxies Bukkit e equipamento espelhado: provam os gates,
chamadas de captura/equipamento/teletransporte, timeout, falha de entrega UI,
quarentena e limpeza. Não provam inventário nativo do kit fixo em produção.
O utilizador confirmou que não há segundo jogador disponível; a partida nativa
no alvo, incluindo spawns, kit e comparação integral após saída, continua
`UNVERIFIED`. Não usar outra identidade, force-login, OP ou bypass para fechar
esse requisito. A entrada na fila conserva o inventário e a posição até ao
início do combate; deslocação imediata para bancadas não foi implementada sem
resposta à preferência apresentada.

Na revisão instalada, o login normal pelo endereço público preservou inventário,
ender chest, XP e modo. A posição mudou através da evacuação prevista e o estado
de voo mudou na reconexão. Entrada e saída da fila preservaram exatamente os
14 campos nativos comparados: inventário, ender chest, três campos de XP, modo,
slot selecionado, saúde, três campos de fome, abilities, posição e rotação.
A fila terminou com zero jogadores e o controlo de fundo sem teclas retidas.
Este ensaio não inclui captura do kit nem restauro depois de combate.

## Aceitação nativa de combate e recuperação — 2026-10-07

O JAR atualmente implantado, SHA-256
`d6cf9c8eca8d19352e3488f901a83eb8c1348e5471409b69bebadfc8040155cb`, substituiu
`0547103…` após reinício normal. A suite completa passou: 449 testes, 448
aprovados, um ignorado, zero falhas/erros. O snapshot de prontidão acima descreve
a revisão anterior e não identifica o artefacto atual.

No percurso público real, o proprietário e uma conta AuthMe comum de ensaio
aprovada chegaram aos spawns de combate A (-397.5, 87, 6447.5) e B
(-341.5, 87, 6447.5), com seis itens temporários etiquetados (espada de ferro,
escudo e armadura). Uma confirmação deixou os inventários originais intactos.
Após remoção explícita e autorizada da proteção NPP da conta de ensaio, o combate
nativo reduziu a saúde sintética de 20 para 8.7333355; os ataques seguintes terminaram em eliminação letal
e declararam o vencedor. Não se afirma que a primeira tentativa bloqueada tenha sido
uma ronda de dano. Ambos regressaram às bancadas e os 15 campos NBT foram
exatamente restaurados para cada jogador: `Inventory`, `EnderItems`, `equipment`,
`XpLevel`, `XpP`, `XpTotal`, `playerGameType`, `SelectedItemSlot`, `Health`,
`foodLevel`, `foodSaturationLevel`, `foodExhaustionLevel`, `abilities`, `Pos` e
`Rotation`.

Numa partida ativa separada, a saída normal do adversário sintético restaurou
imediatamente os mesmos 15 campos do proprietário que permaneceu ligado.
O jogador sintético AuthMe voltou a ligar-se com a mesma palavra-passe e completou a recuperação; os 15 campos de ambos ficaram exatos.
Isto confirma o caso 1v1 fixo e a desconexão normal. Equipas maiores,
espectadores e outros minijogos continuam por verificar.

A correção revoga a ligação antiga, aguarda nova época autenticada, ignora atores
desligados/não autenticados e atores já restaurados, e só fecha a partida após
todas as restaurações. Falha de recuperação mantém quarentena; não houve bypass
de autenticação, esquema ou inventário. A verificação SQLite/WAL terminou com
schema 6 e integridade válida; 7 sessões fechadas e 7 snapshots restaurados,
com apenas a quarentena QA sintética original preservada. Não há falha atual de
restauro. As 25 contas AuthMe originais e a whitelist original foram preservadas;
a conta de ensaio própria foi removida. Sem alterações de concessões LuckPerms.
A conta comum tinha `use=true`, `arena.use=true`, `admin=false`.

O primeiro ensaio sintético no JAR anterior produziu `RESTORE_FAILED` e
quarentena; apenas o equipamento QA vazio, pertencente ao ensaio, foi reposto
manualmente. A sessão diagnóstica em quarentena foi preservada e não deve ser
reexecutada ou apagada. A proteção inicial NPP impediu o combate; foi removida
apenas para QB pelo comando suportado `npp remove CIAACArenaQB`. Configuração
global e concessões NPP ficaram intactas. Jogadores com proteção inicial devem
optar explicitamente por `/npp remove-protection` e confirmar na GUI,
perdendo permanentemente essa proteção inicial, ou aguardar
quatro horas. Esta GUI pública não foi ensaiada e não há opt-out automático.
Apostas permanecem desativadas.

O rollback a frio `ciaac-cutover-2026-10-06-final/disconnect-fix-2026-10-07-original`
mantém o JAR anterior compatível e os dados CIAAC atuais. Rollback de código
substitui apenas o JAR; não reproduzir dados antigos. O ensaio Paper local com
GrimAC 2.3.74-b8677be, PacketEvents 2.13.0 e AuthMe confirmou 8 fechamentos,
8 restaurações e zero erros; fixture parado normalmente, removido e porta 25567
fechada. Fila real vazia e controlos da ponte libertados.

Os resumos sanitizados permanecem apenas nos caminhos locais ignorados
`runtime/verification/production-readiness-2026-10-06/arena-e2e-2026-10-07`,
`live-retry/native-victory-evidence.json` e
`live-retry/native-disconnect-evidence.json`. Não copiar payloads privados,
contas, logs ou configuração para Git.
