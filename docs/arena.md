# Conceção da arena do Coliseu

- Estado: subsistema de domínio `SOURCE-VERIFIED`; o adaptador e a execução Paper permanecem fechados
- Documentado: 2026-08-24 (`Europe/Lisbon`); reforço do código reconciliado
- Execução/implantação: `UNVERIFIED`; não existe configuração ou código da arena implantado

O código da arena gere a composição das filas, propostas imutáveis de partidas, o
ciclo de vida de lugar único, admissão no piso vinculada a UUID, decisões de
desligação/desistência, contratos de modo de equipamento e liquidação/reembolso
exatamente uma vez do depósito. `ArenaQueue` nunca divide um grupo nem mistura
filas de formato/equipamento. `ArenaLocationPolicy` e `ArenaEquipmentContract`
são contratos nomeados para futuros adaptadores, não integrações ativas de
WorldGuard, teletransporte, inventário ou persistência.

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
continua a fornecer verificações de admissão e defesa em profundidade contra
blocos, fluidos, pistões e explosões.

O listener de espectadores aplica-se apenas a participantes numa partida ativa;
os jogadores públicos não são admitidos no piso de combate nem tratados como
espectadores da partida. A política genérica de transporte rejeita destinos
desconhecidos ou não autorizados, enquanto o movimento de veículos de um
passageiro da Arena admitido só é permitido quando permanece na região de piso
atual desse passageiro; os veículos recusados regressam à origem quando possível
ou têm os passageiros expulsos. A ordem destes listeners e o comportamento real
no Paper permanecem `RUNTIME-UNVERIFIED`.

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
ou liquidação para o vencedor, indexados pelo ID do depósito, ID da partida/resultado e identidade de repetição da operação. Uma recuperação ambígua fecha novas apostas para revisão. Isto está `SOURCE-IMPLEMENTED` e permanece `RUNTIME-UNVERIFIED`; os drops de morte normais nunca são um mecanismo de depósito.

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
contratos correspondentes. Um adaptador Paper tem ainda de impor estas decisões
nos eventos de movimento, interação, inventário, dano, teletransporte, mundo e
entidades.

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
o combate é registada como desistência idempotente `DISCONNECT`. Isto é apenas
uma decisão do domínio até serem fornecidos um listener de execução e um diário
persistentemente gravado.

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
