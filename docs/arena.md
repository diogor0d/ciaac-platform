# Conceção da arena do Coliseu

- Estado: domínio e adaptador Paper `SOURCE-VERIFIED`; módulo desativado por omissão
- Atualizado: 2026-10-06 (`Europe/Lisbon`); Arena ativada no backend CIAAC com AuthMe.
- Produção: login normal e entrada/saída da fila `RUNTIME-VERIFIED`; partida com
  dois jogadores no alvo ainda `UNVERIFIED`. Os ensaios locais abaixo têm o seu
  próprio artefacto e âmbito; não equivalem a aceitação integral em produção.

No Paper local 26.2 build 84, o JAR
`463af6b312bbf4ecc981629ff2644680ed58e09f1ebdb7c4b7bf2350a675da7c` passou
ensaios nativos delimitados de 2v2, 3v3, fogo amigo, portal do Nether e formatos
2v3/3v2. O artefacto experimental posterior
`18191810391d7685035f51406fa82e26cfa66ff950b6409757b1a981abd50a24` continua a
falhar a recuperação após crash com seis participantes: um jogador regressou em
`ADVENTURE` apesar da baseline `SURVIVAL`. A suite dessa build tinha 403 testes (402
aprovados, um ignorado), mas esse resultado não fecha a aceitação nativa de
crash. Os detalhes e os limites estão na
[verificação funcional](functional-verification.md). Ensaios locais anteriores
no JAR `0099b09ed6258f0c278a072d8a6f6d7a3980b6f29266f0d3591612a746263f7a`
verificaram duelo fixo 1v1, desligação normal e recuperação após crash com
equipamento espelhado. A recuperação experimental após crash com seis
participantes falhou para um jogador real, que regressou em `ADVENTURE` em vez
de `SURVIVAL`; não é aceite. Os testes de portal e formatos assimétricos acima
pertencem ao JAR anterior e só valem para os casos documentados. Estes casos não
provam aceitação completa. A integração nLogin atual continua fechada por falta
de prova de conclusão. Em 2026-10-05, o adaptador opcional AuthMe passou login
normal, partida 2v3, saída normal e crash com cinco peers no JAR
`9fd508fef8d5fcd0c3f0c52f8e7b5fbe43d4a4e3bb7377add6f17fd20e4d2805`; os 13
campos coincidiram exatamente. Aceitação adicional no mesmo JAR verificou hits
de fogo amigo cancelados, dano inimigo, eliminação/espectador, 1v1, 2v2,
portal físico e recuperação de crash protegido 3v3 com seis participantes.
Persistem as limitações e pendências descritas no [contrato de autenticação](authentication.md)
e na [verificação funcional](functional-verification.md); isto não constitui
aceitação integral. A migração e ativação autorizadas de produção ocorreram
em 2026-10-06; ver o [registo de ativação](arena-activation.md). A configuração
por omissão mantém a Arena fechada; apenas o overlay revisto do alvo está ativo.
O fornecedor nativo de
estado do mundo suporta apenas a Arena; os outros oito minijogos permanecem
fechados sem os respetivos fornecedores de isolamento.

O código da arena gere a composição das filas, propostas imutáveis de partidas, o
ciclo de vida de lugar único, admissão no piso vinculada a UUID, decisões de
desligação/desistência, contratos de modo de equipamento e liquidação/reembolso
exatamente uma vez do depósito. `ArenaQueue` nunca divide um grupo nem mistura
filas de formato/equipamento. `ArenaLocationPolicy` e `ArenaEquipmentContract`
são contratos de domínio consumidos pelo adaptador Paper. O runtime liga o
controlador, os listeners de região e o fornecedor nativo de estado do mundo;
não existe integração implementada com WorldGuard.

## Limites do adaptador Paper

`paper/arena/ColiseumController` é um coordenador desativado por omissão. Quando
ativado com definições validadas, disponibiliza operações de entrada/saída da
fila, verificações de prontidão, desafios diretos, estado, tick, desligação e
desistência. Analisa formatos através da `ArenaFormatPolicy` configurada,
reserva o único lugar do domínio, chama `SessionCoordinator` antes de alterar o
estado do jogador, emite o token da região do piso, teletransporta para os
spawns configurados das equipas, regista a política de combate e restaura
através do coordenador de sessão. Todo o texto devolvido aos jogadores é
limitado e está em pt-PT.

O `ColiseumSpectatorBoundaryListener` registado complementa o listener genérico
de regiões: os combatentes ativos só podem permanecer na região de piso de
combate admitida, enquanto os participantes eliminados são movidos para as
bancadas públicas de espectadores e ficam confinados nelas. Uma tentativa de
fuga torna-se uma desistência controlada. O listener genérico de região imutável
fornece verificações de admissão e defesa em profundidade contra blocos, fluidos,
pistões e explosões.

O listener de espectadores aplica-se apenas a participantes numa partida ativa;
os jogadores públicos não são admitidos no piso de combate nem tratados como
espectadores da partida. A política genérica de transporte rejeita destinos
desconhecidos ou não autorizados, enquanto o movimento de veículos de um
passageiro da Arena admitido só é permitido quando permanece na região de piso
atual desse passageiro; os veículos recusados regressam à origem quando possível
ou têm os passageiros expulsos. O runtime regista estes listeners; os ensaios
locais citados acima não cobrem todos os caminhos de espectadores, veículos ou
eventos, que permanecem `UNVERIFIED`.

Os kits fixos são fornecidos por um `ArenaKitProvider` revisto e recebem a
etiqueta de item temporário existente. O modo protegido usa uma cópia separada,
capturada do equipamento ativo depois de preparada a fotografia persistente da
sessão. O `ArenaItemManifestBuilder` calcula a impressão digital de cada item
oferecido e conserva os itens proibidos no manifesto. No modo protegido, esses
itens são omitidos apenas da cópia de combate separada; a fotografia persistente
de sobrevivência não é editada e repõe-os. Nenhum item é largado no mundo.

 A admissão apostada falha deliberadamente de forma segura, salvo quando tanto a definição do operador
como a barreira persistente de recuperação do depósito estão prontas. O código inclui uma
`StakedEscrowPort`, reconciliação no arranque dos depósitos abertos, consentimento novo sobre
ambos os manifestos imutáveis, levantamento antes da admissão e reembolso exatamente uma vez
ou liquidação para o vencedor, indexados pelo ID do depósito, ID da partida/resultado e identidade de repetição da operação. Uma recuperação ambígua fecha novas apostas para revisão. Isto está `SOURCE-VERIFIED` e permanece `UNVERIFIED`; os drops de morte normais nunca são um mecanismo de depósito.

O adaptador exige o ID de ligação de autenticação validado separadamente,
geometria configurada da zona segura do spawn, registos de regiões não sobrepostas
e uma stack completa de persistência/isolamento do `SessionCoordinator`. Nenhum
destes requisitos implica implantação ativa ou fornece coordenadas de produção.

## Experiência

O Coliseu é um único piso físico de combate dentro da zona segura do spawn. Os
jogadores normais podem usar as bancadas de espectadores circundantes, mas não
podem entrar no piso. Os jogadores em fila ou desafiados permanecem onde estão;
só são teletransportados depois de a arena única ser reservada, todos os membros
da lista passarem a verificação de prontidão e os pré-requisitos de
admissão/recuperação serem confirmados.

A arena suporta:

- desafios diretos e filas de matchmaking;
- formatos simétricos `1v1`, `2v2` e `3v3`;
- formatos assimétricos explicitamente configurados, como `2v3`;
- kits temporários fixos;
- cópias protegidas do equipamento de sobrevivência selecionado pelos jogadores;
- partidas `1v1` de equipamento apostado, aceites por ambas as partes.

As partidas assimétricas são identificadas de forma visível e nunca misturadas
silenciosamente numa fila simétrica. As listas das equipas e o modo de
equipamento tornam-se imutáveis quando a arena é reservada. Fogo amigo, rondas,
temporizadores, empates, definições de kits e política de classificação continuam
a ser decisões de configuração.

## Ciclo de vida

### Regiões com altura total

Uma região pode declarar `full-height: true` para abranger todo o intervalo vertical
do mundo carregado. Os vetores `min` e `max` continuam a exigir três coordenadas
inteiras válidas; os valores Y são validados, mas substituídos na resolução por
`World.getMinHeight()` e `World.getMaxHeight() - 1`, respetivamente. O limite
superior do Paper é exclusivo, enquanto o cuboide da plataforma inclui ambos os
extremos. Sem `full-height`, as regiões mantêm os limites Y configurados.

```text
IDLE (arena slot free)
  -> WAITING
  -> RESERVED
  -> READY
  -> ADMITTING
  -> ACTIVE
  -> FINISHING
  -> RESTORING
  -> CLOSED -> release arena slot -> IDLE

Qualquer transição insegura, incluindo uma fotografia parcial -> RECOVERING
  -> resultado terminal/reembolso ou liquidação -> RESTORING -> CLOSED
```

Os desafios diretos têm identificadores únicos, com expiração e de utilização única. Aceitar um
desafio inicia o mesmo fluxo de prontidão/admissão e não teletransporta nem
altera o inventário por si só. Só pode existir uma partida reservada, em
admissão, ativa, a terminar ou em restauração de cada vez.

As entradas da fila são separadas por formato e modo de equipamento. Mantêm uma
identidade e lista imutáveis do grupo, mas um grupo parcial ou indivíduo pode
entrar numa fila de formato de equipas para composição posterior pelo
matchmaker; nenhum grupo é dividido silenciosamente entre lados opostos. Um
desafio direto de equipas fixa ambas as listas completas e não sobrepostas antes
de o líder alvo o poder aceitar.

Desligações antes da admissão removem ou fazem falhar a verificação de prontidão.
Em combate ativo, o percurso atual do domínio regista uma desistência imediata
`DISCONNECT`; a definição `reconnectGrace` validada ainda não está ligada a uma
máquina de estados de reconexão. Desligação/reinício durante a restauração do
estado ou do depósito continua a ser trabalho de recuperação e nunca abre a
partida seguinte antecipadamente.
Se apenas algumas fotografias de jogadores forem confirmadas quando a preparação falha, apenas esses
jogadores fotografados são restaurados, é confirmado um resultado explícito sem
competição e o único lugar da arena permanece reservado até a partida atingir
`CLOSED`.

O router partilhado de saída/expulsão remove os bilhetes da Arena em fila através
do wrapper do módulo, invoca independentemente o percurso de desligação de cada
jogo presente e atualiza os marcadores de partida do wrapper após chamadas
diretas ao controlador. Uma falha operacional apenas do percurso é registada sem
expor detalhes da exceção aos jogadores; o monitor central de isolamento de
sessões continua a ser a autoridade persistente de recuperação.
Os percursos explícitos de saída/desistência também exigem uma transferência
bem-sucedida para espectadores; uma transferência falhada entra na recuperação
da partida, em vez de indicar que a equipa pode continuar enquanto o jogador
permanece num estado de arena incerto.

## Modos de equipamento

### Kit fixo

O jogador entra com um kit temporário configurado pelo plugin. O estado original
é registado antes de serem entregues os itens temporários e restaurado exatamente
uma vez.

### Equipamento protegido

O jogador seleciona ou traz equipamento de sobrevivência permitido, mas o
combate usa uma cópia. Durabilidade, consumo, drops, reparações, efeitos de
encantamentos ou outras alterações da partida nunca modificam os originais. Os
materiais proibidos configurados são omitidos da cópia temporária de combate e
reaparecem apenas através da restauração da fotografia de sobrevivência inalterada.

### Equipamento apostado

O modo apostado é uma exceção explícita que afeta a progressão:

1. Fixar uma lista `1v1` e regras exatas.
2. Construir um manifesto de depósito imutável para cada jogador.
3. Rejeitar itens proibidos, vinculados, não suportados ou sem preço segundo a
   política; nunca os apagar ou omitir silenciosamente.
4. Mostrar ambos os manifestos, o comportamento de desligação/empate/sem
   competição e a regra de liquidação.
5. Obter confirmação nova de ambos os jogadores para esse ID de partida.
6. Confirmar o depósito antes da admissão na arena.
7. Reembolsar uma vez num percurso configurado de sem competição/cancelamento,
   ou liquidar uma vez para o vencedor confirmado.

Entrar numa fila, aceitar um desafio ou ter escolhido anteriormente o modo
apostado não constitui consentimento para o manifesto atual. As apostas de
equipas permanecem fechadas até existir uma regra de atribuição que cubra a
propriedade das equipas, empates, desistências parciais e vencedores desligados.

## Limites do piso e dos espectadores

A região configurada do piso de combate e a região de espectadores são distintas.
A admissão exige o UUID autenticado, o ID da partida atual e o token de piso ativo.

A fronteira do código falha de forma segura: a geometria `__SET_ME__` não
resolvida e os identificadores de kit/equipamento em falta são rejeitados pelos
contratos correspondentes. O adaptador Paper impõe estas decisões nos eventos
de movimento, interação, inventário, dano, teletransporte, mundo e entidades;
os casos abrangidos e as lacunas de aceitação estão registados na verificação
funcional.

A arena é imutável. Os testes têm de cobrir destruição/colocação de blocos, baldes, pistões,
fluids, explosions, redstone, containers, item frames, entities, vehicles,
projectiles, pearls, chorus fruit, portals, commands, plugin/console teleports,
morte/ressurgimento, desligação/reentrada e movimento entre mundos. As flags do
WorldGuard são específicas e seguem a ADR 0007; um `build: DENY` generalizado
não constitui todo o desenho.

Os espectadores não podem atravessar a fronteira do piso, colidir, causar dano,
selecionar alvos, passar itens, disparar projéteis, acionar mecânicas ou entrar
nos teletransportes dos participantes. Os participantes não podem fugir; uma
tentativa é negada ou resulta na desistência configurada, seguida de restauração/
liquidação controlada.

As desligacões antes da admissão entram em recuperação; uma desligação durante
o combate é registada como desistência idempotente `DISCONNECT`. O listener e o
diário persistente estão ligados no runtime; o caso local de desligação normal
foi verificado, sem provar todos os cenários de reconexão ou crash.

## Efeitos na sobrevivência e progressão

Os modos fixo e protegido usam o isolamento estrito sem progressão da ADR 0010.
Não alteram inventário, XP, saúde, efeitos, localização após restauração,
estatísticas, progressos, economia, permissões, claims, homes ou conquistas de
sobrevivência.

O modo apostado pode alterar a propriedade apenas para o depósito exato a que
foi dado consentimento. Todo o restante estado permanece isolado. As mortes na
arena são eliminações controladas, não percursos normais de drops de morte.

## Estatísticas e classificações

As métricas indexadas por UUID e específicas do modo/formato incluem o âmbito do
equipamento no modo estatístico (`1v1-fixed`, `2v3-protected` ou `1v1-staked`) e
podem incluir:

- partidas, vitórias, derrotas, empates, desistências, eliminações, rondas e
  sequências;
- registos individuais/equipas e simétricos/assimétricos;
- registos protegidos versus apostados;
- classificação e incerteza opcionais de partidas classificadas;
- indicadores de vitória mais rápida e reviravolta quando as regras os tornem
  relevantes.

Os desafios diretos não têm classificação por omissão. As estatísticas são
confirmadas uma vez por ID de partida e não concedem recompensas. Os itens
apostados são uma liquidação, não uma recompensa de classificação.

## Experiência e ecrãs em português

O ecrã nativo usa as etiquetas partilhadas `Fechado`, `À espera`, `Preparação`,
`Em jogo`, `A terminar` e `A restaurar`, além da mensagem de estado pt-PT
limitada, dos conjuntos de equipas/equipamento suportados, das contagens das
filas e de `/coliseu entrar`.
O chat apresenta ações clicáveis de entrada/estado quando suportado. A
verificação de prontidão lista ambas as equipas, simetria, modo de equipamento,
resumo do kit ou depósito, regra de fogo amigo, estado de classificação e
contagem decrescente.

O DiscordSRV recebe no máximo uma mensagem de espera sujeita a intervalo, uma
mensagem de início e um resultado confirmado. As mensagens de apostas nunca
listam NBT de itens ou conteúdos sensíveis e nunca criam menções a partir de
texto controlado por jogadores.

## Entradas necessárias do operador

- UUID/nome do mundo de spawn, geometria do piso e bancadas, IDs/prioridades das
  regiões, spawns das equipas, saídas, fallback dos espectadores e localização
  segura de recuperação.
- Tamanho máximo das equipas e formatos assimétricos permitidos.
- Kits fixos, regras de permissão/bloqueio de itens protegidos, lista negra das
  apostas e política de serialização do depósito.
- Regras de rondas/temporizador/empate/desistência/reconexão/fogo amigo/
  classificação.
- Comportamento de cancelamento, desligação, desqualificação e sem competição
  das apostas.
- Detalhes de compatibilidade de WorldGuard, nLogin, GrimAC, plugin de
  teletransporte, ecrãs e DiscordSRV.

## Configuração do Coliseu existente — 2026-10-04

O [overlay preparado](examples/colosseum-arena.yml) substitui apenas
`modules.arena`. Foi aplicado ao alvo em **2026-10-06**, após a migração
autorizada para AuthMe e validação do login normal. Não substituir a
configuração completa: o Anvil Dodge e os outros serviços têm configuração
própria que deve ser preservada.

A inspeção do cliente e `/mv info` confirmaram o mundo `world`, UUID
`620f870d-9c24-4573-bb95-db1dde2e4b41`, com altura de construção `-64..319`.
As colunas de blocos carregadas mostraram areia em Y=86 e espaço livre para os
spawns em Y=87. Os pontos de equipa são `(-397.5, 87, 6447.5)` e
`(-341.5, 87, 6447.5)`, orientados um para o outro. O limite pedido é inclusivo
em blocos: X=`-418..-321`, Z=`6425..6470`, com `full-height: true`.

A faixa de espectadores proposta fica a sul, fora desse retângulo:
X=`-380..-360`, Z=`6471..6478`, Y=`92..110`. A recuperação fica em
`(-369.5, 98, 6473.5)`, sobre uma laje com espaço livre acima. A configuração
não cria nem altera blocos. Uma região de combate de altura total impede também
que o público use bancadas que estejam dentro do retângulo horizontal pedido;
a faixa exterior evita essa sobreposição.

O kit fixo entrega a espada no inventário, equipa automaticamente a armadura
de ferro e coloca o escudo na mão secundária. A cópia survival protegida também está disponível. Apostas
ficam desativadas nesta preparação. `reconnect-grace-seconds: 0` reflete o
comportamento atual: uma desconexão ativa termina a participação imediatamente.
Ensaios locais posteriores verificaram um duelo fixo 1v1, restauração após
desligação e recuperação autenticada após crash com estado espelhado. Não provam
aceitação de espectadores, restantes modos, apostas ou integração completa.

O [procedimento de ativação](arena-activation.md) delimita a inspeção em leitura,
o pacote para autorização, a interrupção, a aceitação e o rollback.
Antes de ativar: escolher o fornecedor de autenticação suportado e rever a
migração de contas se AuthMe for escolhido; guardar o JAR e a configuração atuais,
validar num Paper descartável com esse fornecedor e as mesmas integrações,
aplicar apenas o overlay e reiniciar de forma controlada (sem `/reload`). Validar
admissão, combate,
fronteiras e restauração; para rollback, parar, repor o JAR/configuração guardados
e reiniciar. Não apagar nem substituir SQLite ou o diário de recuperação.


A continuação de 2026-10-05 confirmou o kit sem arco no cliente real, combate,
recusa de largar itens e restauro após saída, além de 3v2 e da barreira exterior
por movimento e teleportes para Y=-40/Y=200 no mesmo JAR atual. Ver os resultados
e limites exatos na [verificação funcional](functional-verification.md).
O pacote está preparado localmente para revisão de migração/ativação no alvo;
não foi instalado nem aceite em produção.
