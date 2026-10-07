# Preparação das instalações de Sumo e Batata Quente

Atualizado: 2026-10-07 (`Europe/Lisbon`).

A construção e a ativação de produção aguardam o operador. Os módulos ficam
desativados até existirem instalações reais, UUIDs resolvidos e aceitação do
local. O ensaio local usa mundos e contas sintéticos; não fornece coordenadas
reais nem transforma uma instalação futura em verificação de produção.

## Sumo de Repulsão

- Usar o mundo de spawn/safezone configurado para este modo. Identificar o nome
  e UUID exatos; não copiar o UUID do mundo descartável.
- Construir uma plataforma imutável com espaço para combate 1v1. Colocar os
  spawns `side-a` e `side-b` sobre blocos sólidos, com espaço livre para cabeça,
  separados e voltados um para o outro. Ambos ficam dentro de `boundary`.
- Registar `platform` e uma fronteira `boundary` que cubra toda a altura nativa
  do mundo no retângulo reservado. Separar entradas e bancadas públicas desse
  retângulo. A fronteira não concede acesso por grupo: requer sessão admitida.
- Construir queda segura/anel inferior e definir `fall-threshold-y` abaixo dos
  spawns. Cruzar a fronteira ou alcançar esse Y termina a ronda. Não usar lava,
  drops, recompensas ou circuitos que alterem o terreno durante o jogo.
- Colocar lobby/saída/recuperação em piso seguro fora da fronteira. O restauro
  normal regressa à localização guardada antes da admissão.
- Escolher `best-of-rounds` ímpar (três significa duas vitórias), timeout e nível do item de knockback. Os dois
  jogadores entram com `/sumo entrar`; não existe ready check separado.

## Batata Quente

- Criar um mundo dedicado, separado da sobrevivência/spawn, e resolver nome e
  UUID reais. Construir piso imutável, sem armadilhas ou entidades interativas
  que possam alterar inventários ou gerar drops.
- Registar `arena` em toda a altura do retângulo reservado. Configurar um spawn
  seguro dentro da arena para cada jogador permitido por `maximum-players`.
  Colocar lobby/saída/recuperação em piso seguro fora desse retângulo.
- Garantir linhas de visão e espaço para correr. O passe exige clique normal,
  alcance permitido e linha de visão. Não há bypass para administradores.
- As bancadas de espectadores públicos ficam fora da fronteira; participantes
  eliminados assistem em SPECTATOR até ao restauro. Não confundir estas duas
  populações ao desenhar acessos.
- Configurar número de jogadores, fuse inicial/mínimo, redução, cooldown,
  alcance e timeout. O countdown atual é 20 segundos; `/batataquente entrar`
  inicia a fila e `/batataquente sair` remove o jogador.

## Ativação quando as instalações estiverem prontas

1. Guardar backup frio do JAR/configuração/dados de recuperação e verificar que
   não há sessões pendentes. Preservar o JAR de produção anterior para rollback.
2. Preencher todos os campos na configuração existente, mantendo os restantes
   módulos/grupos/autoridades. Validar geometria, UUIDs e permissões de jogador
   vs. administração. Os nós administrativos continuam negados por omissão.
3. Confirmar Paper 26.2 build 84/commit `26e81c4`, AuthMe 6.0.1-b2770,
   Multiverse-Inventories 5.3.5 quando presente e as versões dos adaptadores com a evidência do
   ensaio. Atualizar Paper/adaptadores exige revisão de compatibilidade; não
   abrir os gates apenas porque os plugins carregam.
4. Avisar jogadores antes de qualquer reinício ou ação que os afete, indicando
   intenção e que Codex atua em nome de Diogo. Instalar/ativar apenas estes modos
   no momento acordado e realizar aceitação no local construído.
5. Testar entrada/teleports, combate/passes, vitória, saída e desconexão,
   restauro do inventário original e bloqueio de espectadores. Verificar
   resultados exatamente uma vez e sessões CLOSED/snapshots RESTORED.
6. Se houver perda de fornecedor, sessão ambígua ou quarentena, fechar novas
   admissões e preservar a evidência; não apagar dados ou conceder OP como
   recuperação. Reverter apenas com os jogadores já restaurados.

A faceta de mundo preserva a política imutável; não reconstrói uma instalação
que um operador editou. Drenar sessões antes de alterar regiões/mundos. Discord,
displays externos e todo o comportamento físico de um cliente vanilla exigem
aceitação separada do ensaio com peers sintéticos.
