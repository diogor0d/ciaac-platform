# Menus dos minijogos

Última atualização: 2026-10-08.

Este documento descreve a navegação nativa por inventário implementada em
`MinigameMenus`. A aceitação local abaixo cobre os fluxos de menu ensaiados;
a disponibilidade em produção depende da instalação e configuração do servidor.

## Abrir o menu

- `/minijogos` e `/minijogos menu` abrem o catálogo dos nove minijogos.
- O comando do jogo sem argumentos ou com `menu` abre diretamente a página
  desse jogo. Os comandos são `/coliseu` (alias `/arena`), `/buildbattle`
  (`/bb`), `/batataquente` (`/hotpotato`), `/sumo` (`/knockbacksumo`),
  `/parkour` (`/checkpointparkour`), `/arco` (`/archery`), `/bigornas`
  (`/anvildodge`), `/cores` (`/colorfloor`) e `/elytra` (`/elytrarings`).
- O catálogo apresenta Coliseu, Build Battle, Batata Quente, Sumo, Parkour,
  Campo de Tiro com Arco, Fuga às Bigornas, Piso das Cores e Anéis de Elytra,
  com o estado e número de participantes publicados pelo módulo. Selecionar
  um jogo abre a respetiva página. Jogos sem autorização aparecem indisponíveis.
- As páginas têm navegação Voltar, Fechar e Atualizar. Classificação e
  estatísticas pessoais encaminham para as consultas de chat existentes.

O jogador tem de estar online, válido, vivo, autenticado na ligação atual e
autorizado para o catálogo e para o jogo pedido. O menu não abre com um item no
cursor nem durante escritas de snapshots, transições de preparação ou fases de
restauro/recuperação. Uma sessão preparada cujo módulo está à espera pode
abrir a página do próprio jogo para cancelar a participação com segurança. Um
jogador já envolvido noutro jogo não pode usar a página para iniciar ações
noutro jogo.

## Coliseu

A página do Coliseu permite percorrer ciclicamente os formatos configurados e
os modos de equipamento disponíveis para a configuração atual. As opções de
modo são apresentadas como `kit`, `equipamento` e `aposta`, quando configuradas;
`aposta` só aparece se `modules.arena.staked-survival.enabled` estiver ativo.
O seletor `kit` escolhe o modo de kit fixo existente no servidor. Não existe
seletor de ID de kit no menu.

As opções ficam bloqueadas na fila e na reserva; a partida reservada mostra
o formato e equipamento efetivos. Os menus de prontidão fecham ao começar o
combate. O jogador pode abrir `/coliseu` novamente para sair com confirmação.

O jogador pode entrar na fila com o formato e modo escolhidos, selecionar um
jogador elegível para desafiar ou aceitar um desafio, e abrir a página de grupo
ou equipa. Os comandos de grupo disponíveis são criar, convidar, aceitar convite,
expulsar, sair, dissolver e consultar estado. Quando há uma partida reservada
à espera de prontidão, os participantes podem confirmar que estão prontos; o
teleporte e a entrega do modo de equipamento só acontecem após as confirmações
exigidas pelo fluxo da arena.

No modo de equipamento apostado, a entrada ou confirmação da aposta exige uma
etapa explícita de confirmação. O fluxo da arena apresenta no chat o manifesto
imutável, a regra de risco e a liquidação aplicável; a confirmação do menu não
substitui a leitura nem o consentimento ao manifesto atual. Todo o equipamento
apresentado fica em risco segundo as regras configuradas. A referência completa
do ciclo de vida e dos modos está em [arena.md](arena.md).

## Build Battle

Na fase de votação de tema, a página mostra as opções de tema do encontro e
permite escolher uma. Durante a votação de construções, mostra a parcela que o
jogador deve rever e as pontuações disponíveis para essa parcela. As opções
dependem da fase atual; o controlador continua a validar a ordem e elegibilidade
dos votos. A votação abre uma vez por fase/parcela depois dos teleportes, sem
reabrir continuamente uma página que o jogador fechou. O fluxo de temas,
construção e avaliação está descrito em [build-battle.md](build-battle.md).

## Campo de Tiro com Arco

O jogador pode escolher uma lane configurada ou `auto`, que seleciona a
primeira lane livre. A escolha fica bloqueada durante uma tentativa.

## Entrada, saída e consultas

Quando o estado do módulo permite entrada e o jogador ainda não tem uma sessão
ativa, a página apresenta Entrar. Sair abre uma confirmação porque abandonar
uma partida pode contar como derrota; o jogo executa a saída e a restauração do
estado original. Os resultados das ações continuam a ser comunicados através
das mensagens existentes no chat. O botão Classificação executa a consulta
pública `/minijogos top <jogo>` e As minhas estatísticas executa
`/minijogos estatisticas <jogo>`.

O catálogo e as páginas de jogo continuam a mostrar estados indisponíveis e
mensagens do módulo; o menu não ativa módulos nem contorna as condições de
admissão.

## Segurança e administração

Os ícones do inventário são apenas controlos. A interação aceite é clique
esquerdo normal num botão do inventário superior, com cursor vazio e evento não
cancelado. Cliques alternativos, arrastamentos, movimentos de itens, eventos de
criativo e ações de outros jogadores são recusados. O menu valida novamente a
identidade da ligação autenticada, o estado da sessão e do jogo antes de executar
uma ação; seleções de outros jogadores também são vinculadas à ligação atual
desse alvo. Menus antigos são invalidados quando o estado muda, e as páginas de
confirmação não são reabertas automaticamente após essa invalidação.

O acesso administrativo requer `ciaac.minigames.admin`, com `default: false`.
O menu administrativo só consulta e apresenta o estado dos módulos e do
atualizador. Não recarrega configuração, ativa módulos, nem altera geometria.
Configuração e captura de geometria/instalações continuam a ser operações de
consola local segundo [minigame-facility-setup.md](minigame-facility-setup.md).

Os menus usam inventários nativos do servidor e não exigem dependências
adicionais nem pacote de recursos.

## Aceitação local — 2026-10-08

No Paper 26.2 build 84 privado em loopback, o JAR
`ec75a37a6db371795ebb5aac71feb85d16fc6368dcd2ebb4174750d5c3c43095`
passou o driver nativo [menu_acceptance.py](../tools/arena-client-fixture/menu_acceptance.py).
As contas sintéticas usaram login AuthMe normal; não houve criação forçada de
sessões, teleporte do teste para simular entrada nem injeção de resultados.

- Os nove ícones, navegação e cinco tipos de clique alternativo foram ensaiados;
  a administração ficou oculta para o jogador comum.
- Os nove jogos passaram entrada/saída pelo menu com os 18 campos originais
  restaurados exatamente. O Arco usou a lane configurada pelo seletor.
- Coliseu passou cinco ações de grupo, ciclo de cinco formatos e dois modos,
  desafio/aceitação, prontidão, teleporte/kit e saída. O combate efetivo foi
  1v1 com kit fixo: 21 ataques normais, `VICTORY` / `LETHAL_DAMAGE` e restauro
  exato dos dois jogadores. A aposta desativada não apareceu no seletor.
- Build Battle passou dois votos de tema e duas avaliações pelo menu, com
  resultado `VICTORY` / `RESULT` e restauro exato.
- Elytra passou a primeira entrada e nova entrada depois da limpeza, sem
  precisar de repetir o clique de admissão.

As suites canónica e do espelho passaram 664 testes cada (663 aprovados e um
skip pré-existente). O cliente separado passou 198 checks offline. O servidor
foi encerrado normalmente; os totais acumulados eram 292 sessões CLOSED e
292 snapshots RESTORED, sem pendências/quarentena, com integridade SQLite ok.
Não houve alteração em produção, grupos ou permissões reais. A aceitação não
abrange jogo efetivo nos outros formatos/modos de equipamento da Arena,
apostas, administração positiva em runtime nem instalações reais.
