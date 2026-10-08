# Preparação das instalações dos nove minijogos

Última atualização: 2026-10-08.

Guia de planeamento para construir e configurar instalações novas. Não é uma
autorização para ativar módulos, alterar produção ou declarar aceitação. A
configuração de origem mantém `admission.enabled: false` e todos os
`modules.<jogo>.enabled: false`. A preparação controlada do Piso das Cores ou
Build Battle pode
ativar temporariamente a configuração global e a secção do modo para permitir
a captura, mas sem template válido o módulo continua fechado; isto não constitui
ativação nem aceitação.

## Preparar sem abrir a admissão

1. Trabalhar num Paper descartável. Guardar uma cópia da configuração existente,
   do JAR, da base persistente e de todo o diretório de recuperação. Não
   substituir o ficheiro inteiro por um exemplo deste documento: editar apenas
   as secções necessárias e preservar configurações de outros módulos.
2. Decidir qual o mundo principal e quais os mundos dedicados. O resolvedor
   exige nome e UUID do mundo carregado, com correspondência exata. Na consola
   local autorizada, `/ciaac instalações mundo <nome-exato>` devolve o UUID e
   os limites de altura do mundo carregado. Não deduzir o UUID pelo nome da
   pasta nem copiar o UUID de um fixture.
3. Construir e medir as regiões e localizações necessárias na mesma dimensão.
   As regiões são cuboides com `min: [x, y, z]` e `max: [x, y, z]`; os limites
   são inclusivos. As localizações usam `x`, `y`, `z`, `yaw` e `pitch`. Usar
   `full-height: true` só para uma fronteira que deva cobrir a altura carregada
   do mundo. Posições de admissão têm de ficar dentro das regiões indicadas na
   tabela e sobre terreno seguro, com espaço suficiente para o jogador.
4. Antes de ativar um modo, verificar região, altura, acessos de espectadores,
   chunks necessários, isolamento de estado, autenticação normal de conta e
   permissões públicas. Resolver todas as mensagens de configuração do módulo;
   um `enabled: true` não contorna um diagnóstico nem um fornecedor ausente.
5. Ensaiar entrada, mecânica, saída, desconexão, recuperação e restauro num
   servidor descartável com pelo menos o mínimo configurado de participantes
   comuns autenticados normalmente. Um jogador basta apenas nos modos que
   permitem esse mínimo. Não remover
   anti-cheat, conceder OP como bypass, nem usar contas ou dados de produção
   para preencher uma lacuna de configuração.
6. Só depois da aceitação, planear separadamente uma janela de manutenção e a
   alteração dos gates. Desativar novas admissões pelo procedimento aprovado,
   drenar sessões e verificar que não há recuperação pendente antes de editar
   geometria, mundos ou templates. Se existir sessão ambígua, quarentena ou
   restauro incompleto, parar e preservar a evidência. **Nunca apagar, truncar,
   substituir ou recriar registos de recuperação** para desbloquear uma
   instalação.

Os exemplos seguintes são esquemas documentais. `__SET_ME__` é um placeholder
que o resolvedor rejeita deliberadamente; não contém coordenadas nem UUIDs e
nunca deve ficar numa configuração ativada.

```yaml
world: {name: __SET_ME__, uuid: __SET_ME__}
regions:
  exemplo: {min: [__SET_ME__], max: [__SET_ME__]}
locations:
  entrada: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
```

Fragmentos ilustrativos das chaves específicas de cada módulo. Acrescentá-los à
secção correspondente da configuração já instalada, preencher todos os valores
e manter `enabled: false` durante a preparação. As regras e limites restantes
podem manter os defaults revistos em `src/main/resources/config.yml`.

```yaml
modules:
  arena:
    enabled: false
    world: {name: __SET_ME__, uuid: __SET_ME__}
    regions:
      combat-floor: {min: [__SET_ME__], max: [__SET_ME__], full-height: true}
      spectator-benches: {min: [__SET_ME__], max: [__SET_ME__]}
    locations:
      team-a: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: __SET_ME__, pitch: 0}
      team-b: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: __SET_ME__, pitch: 0}
      exit: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
      recovery: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
  build-battle:
    enabled: false
    world: {name: __SET_ME__, uuid: __SET_ME__}
    world-template-marker: __SET_ME__
    regions:
      lobby: {min: [__SET_ME__], max: [__SET_ME__]}
      plot-01: {min: [__SET_ME__], max: [__SET_ME__]}
    plots:
      plot-01:
        region-id: plot-01
        spawn: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
    locations:
      lobby: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
      exit: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
      recovery: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
  hot-potato:
    enabled: false
    world: {name: __SET_ME__, uuid: __SET_ME__}
    regions:
      arena: {min: [__SET_ME__], max: [__SET_ME__], full-height: true}
    spawns:
      spawn-01: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
      # Acrescentar pelo menos maximum-players entradas dentro de arena.
    locations:
      lobby: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
      arena: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
      exit: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
      recovery: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
  knockback-sumo:
    enabled: false
    world: {name: __SET_ME__, uuid: __SET_ME__}
    regions:
      platform: {min: [__SET_ME__], max: [__SET_ME__]}
      boundary: {min: [__SET_ME__], max: [__SET_ME__], full-height: true}
    locations:
      side-a: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: __SET_ME__, pitch: 0}
      side-b: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: __SET_ME__, pitch: 0}
      exit: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
  checkpoint-parkour:
    enabled: false
    world: {name: __SET_ME__, uuid: __SET_ME__}
    regions:
      course-boundary: {min: [__SET_ME__], max: [__SET_ME__], full-height: true}
    locations:
      start: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
      exit: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
    checkpoint-order: [checkpoint-01]
    checkpoint-regions:
      checkpoint-01: {min: [__SET_ME__], max: [__SET_ME__]}
  archery-range:
    enabled: false
    world: {name: __SET_ME__, uuid: __SET_ME__}
    regions:
      range-boundary: {min: [__SET_ME__], max: [__SET_ME__]}
      lane-1: {min: [__SET_ME__], max: [__SET_ME__]}
    locations:
      exit: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
    lanes:
      "1":
        region-id: lane-1
        target-id: target-lane-1
        spawn: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
  anvil-dodge:
    enabled: false
    world: {name: __SET_ME__, uuid: __SET_ME__}
    regions:
      floor: {min: [__SET_ME__], max: [__SET_ME__]}
      boundary: {min: [__SET_ME__], max: [__SET_ME__], full-height: true}
    locations:
      start: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
      exit: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
  color-floor:
    enabled: false
    world: {name: __SET_ME__, uuid: __SET_ME__}
    regions:
      floor: {min: [__SET_ME__], max: [__SET_ME__]}
      boundary: {min: [__SET_ME__], max: [__SET_ME__], full-height: true}
    locations:
      start: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
      exit: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
  elytra-rings:
    enabled: false
    world: {name: __SET_ME__, uuid: __SET_ME__}
    world-template-marker: __SET_ME__
    regions:
      course-boundary: {min: [__SET_ME__], max: [__SET_ME__], full-height: true}
    locations:
      start: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
      exit: {x: __SET_ME__, y: __SET_ME__, z: __SET_ME__, yaw: 0, pitch: 0}
    ring-order: [ring-01, ring-02]
    ring-regions:
      ring-01: {min: [__SET_ME__], max: [__SET_ME__]}
      ring-02: {min: [__SET_ME__], max: [__SET_ME__]}
    course-revision: __SET_ME__
```

## Requisitos de cada instalação

| Jogo e secção | Mundo e regiões | Localizações e dados adicionais exigidos |
| --- | --- | --- |
| Coliseu — `modules.arena` | Mundo principal. `regions.combat-floor` e `regions.spectator-benches` têm de ser disjuntas. O assembler regista `arena.combat-floor` como `PARTICIPANT_ONLY` imutável e `arena.spectator-benches` como `SPECTATOR_PUBLIC` imutável. | `team-a` e `team-b` dentro de `combat-floor`; `recovery` dentro de `spectator-benches`; configurar também `exit`. `full-height: true` é adequado para separar o piso de combate da população à altura inteira do mundo. O modo `fixed` exige pelo menos um `fixed-kits.<id>` não vazio; não se pressupõe nenhum kit. Rever formatos, fogo amigo e modos de equipamento separadamente. |
| Build Battle — `modules.build-battle` | Mundo dedicado. Configurar `regions.lobby` e uma região não sobreposta para cada plot; as regiões dos plots tornam-se `build-battle.<region-id>`. | `locations.lobby`, `exit` e `recovery`; para cada `plots.<id>`, definir `region-id` e `spawn`; o número de plots tem de cobrir `maximum-players`. `theme-pool` tem de ser único e não vazio. `world-template-marker` é o ID do artefacto de captura e reset. O template cobre o cuboide delimitador dos plots (incluindo os espaços entre eles), mas o reset só altera células pertencentes aos plots. |
| Batata Quente — `modules.hot-potato` | Mundo dedicado. `regions.arena` é registada como `hot-potato.arena`, fronteira imutável do mundo do jogo. | `locations.arena` dentro da região, mais `lobby`, `exit` e `recovery`. `spawns.<id>` precisa de pelo menos uma entrada dentro de `arena` por jogador até `maximum-players`. Colocar espectadores públicos fora da fronteira. A contagem decrescente é fixa em 20 segundos; confirmar linhas de visão e espaço de corrida. |
| Sumo — `modules.knockback-sumo` | Mundo principal. `regions.platform` contém os dois pontos de entrada; `regions.boundary` é registada como `knockback-sumo.boundary`, imutável e exclusiva do jogo. | `locations.side-a` e `side-b` dentro de `platform` e acima de `fall-threshold-y`; configurar `exit`. Separar público e espectadores da fronteira. O resolver exige exatamente dois jogadores, uma série ímpar e material/nível de knockback válidos. |
| Parkour de Checkpoints — `modules.checkpoint-parkour` | Mundo principal. `regions.course-boundary` é registada como `checkpoint-parkour.course-boundary`. `checkpoint-order` e `checkpoint-regions.<id>` têm de conter exatamente os mesmos IDs, sem repetição; os cuboides de checkpoint não podem sobrepor-se. | `locations.start` dentro de `course-boundary`; configurar `exit` se necessário para a instalação, embora o assembler atual não o use. Rever timeout, penalização e concorrência. Cada passagem é validada pela ordem do servidor; não desenhar o percurso com base apenas em distâncias visuais. |
| Campo de Tiro com Arco — `modules.archery-range` | Mundo principal. Configurar `regions.range-boundary` e uma região imutável por lane; cada `lanes.<id>.region-id` (ou `region`) tem de apontar para uma região existente. O assembler regista estas regiões como `archery-range.<region-id>`. As lanes não se podem sobrepor. | Configurar `locations.exit` e `lanes.<id>.spawn`, `target-id` e, se necessário, `region-id`. Cada banda requer pelo menos um ArmorStand válido, vivo e não-marker dentro da região da lane, com uma scoreboard tag exata por banda, como no exemplo após a tabela. São permitidos vários alvos para a mesma banda. Alvos ausentes, ambíguos ou inválidos mantêm a lane indisponível. |
| Fuga às Bigornas — `modules.anvil-dodge` | Mundo principal. `regions.floor` define a grelha; `regions.boundary` é registada como `anvil-dodge.boundary` e protege a participação. A grelha pode ter no máximo 4096 células. | `locations.start` dentro de `floor` e `exit` configurado. Confirmar espaço vertical, queda segura e região de fronteira. O controlador usa armor stands marcador pertencentes ao plugin, animados sobre o piso; não precisa de bigornas persistentes, drops ou alteração do terreno. |
| Piso das Cores — `modules.color-floor` | Mundo principal. `regions.floor` é simultaneamente a área da grelha e os limites exatos do template; o resolver exige uma única altura e no máximo 4096 células. `regions.boundary` é registada como `color-floor.boundary`. | `locations.start` dentro de `boundary`, sobre uma célula de `floor`, com Y exatamente uma altura acima do piso (`floor.maxY + 1`); `exit` configurado. `palette` tem de corresponder exatamente às cores no template e `restore-strategy` tem de ser `immutable-template`. O identificador do artefacto é `immutable-floor-template`; a revisão tem de coincidir com a revisão de regras resolvida. O ledger/manifesto e provider estão integrados no runtime. Conclusão, saída, desconexão e recuperação fria passaram no fixture local, incluindo os 18 campos de jogador e as 4096 células do template; outros cenários, instalações reais e prontidão operacional continuam pendentes. |
| Anéis de Elytra — `modules.elytra-rings` | Mundo dedicado. `regions.course-boundary` é registada como `elytra-rings.course-boundary`. `ring-order` e `ring-regions.<id>` têm de ter os mesmos IDs únicos; os cuboides não podem sobrepor-se. | `locations.start` dentro de `course-boundary` e `exit` configurado. Definir `course-revision`, `world-template-marker`, timeout, raio de pré-carregamento e orçamento de fogos. O runtime aceita atualmente `concurrent-runners: 1`. Confirmar que os chunks da rota estão carregados durante o ensaio; o módulo não deve forçar o carregamento para contornar falhas. |

Para `lanes.<id>.target-id: target-lane-1`, tem de existir pelo menos uma
entidade válida por banda; cada entidade tem de ter a scoreboard tag
correspondente (não são tags de PDC). Podem existir várias entidades para a
mesma banda:

```text
ciaac-archery-target:target-lane-1:bullseye
ciaac-archery-target:target-lane-1:inner
ciaac-archery-target:target-lane-1:middle
ciaac-archery-target:target-lane-1:outer
```

Os IDs de região protegida usam o prefixo do jogo e o ID da configuração,
normalizados para minúsculas e hífenes, por exemplo
`checkpoint-parkour.course-boundary` ou `archery-range.lane-1`. O resolvedor
confirma que mundo, localização e região correspondem, valida limites e fecha
o módulo em caso de ausência, sobreposição ou geometria inválida. Regiões
protegidas de módulos ativados não podem sobrepor-se.

## Templates e preservação do mundo

Build Battle e Piso das Cores exigem artefactos de template válidos antes de
serem montados. Existem comandos de captura controlada, apenas na consola local
e com todas as sessões terminadas, incluindo recuperações e quarentenas. A
captura é de leitura; não altera blocos nem carrega chunks. O checksum gerado
comprova integridade, não revisão humana, autorização ou aceitação nativa.

Os ficheiros são lidos de
`plugins/CIAACPlatform/templates/<artifact-id>.template`. O formato, o checksum
canónico SHA-256, os limites e as regras de cobertura estão em
[templates nativos](template-artifacts.md). Build Battle usa o valor de
`world-template-marker` como ID do artefacto (e nome base do ficheiro
`<id>.template`), cobre todo o cuboide delimitador dos plots e limita esse
volume a 100 000 blocos; o total das células pertencentes aos plots não pode
exceder 65 536. O reset só escreve nessas células, em lotes de 256 por tick;
os espaços entre plots ficam preservados. A captura recusa blocos fora da
política segura, blocos com estado de inventário/tile, chunks descarregados e
templates existentes. Piso das Cores usa o ID fixo
`immutable-floor-template`, exige uma linha `color=` para cada `block=` e
cores correspondentes à paleta, e limita a grelha plana a 4096 células. O
checksum deteta
alterações, mas não é uma assinatura nem prova de autorização, revisão ou
implantação. Módulos ficam fechados se o artefacto, mundo, nome, UUID, revisão,
limites, chunks ou checksum não coincidirem.

`world-template-marker` do Elytra é um campo de configuração do percurso; não
é uma ferramenta de captura ou restauração do mundo. Não inferir que o nome
desse marcador valida um template de terreno. Providers de mundo para Build
Battle, Piso das Cores e Elytra estão ligados ao runtime e ao isolamento de
sessões; isto confirma integração de código, não aceitação de jogo nativo. O
provider do Piso das Cores trata apenas as células do template. O reset do
Build Battle só altera células pertencentes aos plots. Nenhum destes mecanismos
reconstrói alterações fora da área explicitamente capturada ou possuída.

### Consulta e captura local de instalações

Os comandos seguintes só aceitam a consola local na thread principal. Não
exigem `ciaac.minigames.admin` nem qualquer concessão de permissões; jogadores,
command blocks e consola remota são recusados sempre:

```text
/ciaac instalações mundo <nome-exato>
/ciaac instalações validar
/ciaac instalações capturar-cores
/ciaac instalações capturar-construcao
```

Usa exatamente o acento em `instalações`. `mundo` consulta apenas mundos
carregados e apresenta o UUID e as alturas mínima/máxima. `validar` apresenta a
configuração de origem resolvida, os diagnósticos e a disponibilidade corrente
dos módulos; não executa uma partida nem testa restauro ou recuperação.

Para preparar um Piso das Cores novo, mantém os outros módulos desativados,
define explicitamente `admission.enabled: true` e
`modules.color-floor.enabled: true`, e preenche mundo, geometria, paleta e
revisão válidos. Inicia o servidor normalmente: se o template ainda faltar, o
modo permanece fechado/indisponível. Visita o mundo e carrega os chunks da
região; a captura só aceita até 4096 células numa altura, feitas de lã ou
betão das sete cores suportadas, com as cores exatamente iguais à paleta.
Antes de capturar, todas as sessões têm de estar drenadas, incluindo sessões em
recuperação ou quarentena.

Executa `/ciaac instalações capturar-cores` na consola local. O comando cria
apenas `immutable-floor-template.template`, valida o SHA-256 e a leitura
posterior pelo repositório e recusa caminhos inseguros ou ficheiros existentes;
nunca sobrescreve nem ativa o módulo implicitamente. Após a manutenção, faz um
reinício normal e controlado (não `/reload`) e executa `validar` para consultar
a configuração e a disponibilidade.

Para um Build Battle novo, confirma primeiro que não existe artefacto com o
ID escolhido; conserva todos os outros módulos desativados. A captura exige
`admission.enabled: true` e `modules.build-battle.enabled: true` no fixture de
preparação. Sem o artefacto, o provider recusa partidas; verifica essa recusa
antes de capturar. Estes gates temporários servem para resolver a configuração
da captura e não constituem autorização de abertura em produção.
Configura o `world-template-marker` como ID seguro e único,
define a geometria completa dos plots e garante que todos os seus chunks estão
carregados. O volume delimitador dos plots tem limite de 100 000 blocos e a
soma das áreas dos plots tem limite de 65 536 células. Executa
`/ciaac instalações capturar-construcao` na consola local. O comando cria
`<world-template-marker>.template` uma única vez, valida a leitura e checksum,
e recusa template existente, blocos fora da política segura, tile entities ou
chunks descarregados. Depois de rever o artefacto e reiniciar normalmente,
`validar` consulta a disponibilidade. O reset em jogo altera apenas os blocos
dos plots, em lotes de 256 por tick; o espaço entre parcelas é preservado.

Configura também os mundos dedicados de Build Battle e Elytra para começarem em
Adventure, mantendo a proteção global do Multiverse ativa. No Multiverse-Core
a sintaxe nativa é `/mv modify <mundo> set gamemode adventure`; por exemplo,
`/mv modify minecraft:ciaac-elytra-test set gamemode adventure`. Confirma a
grafia do mundo e a ajuda da versão instalada antes de executar. Build Battle
alterna para Creative apenas na fase de construção e usa Adventure durante
votação/revisão; Elytra requer Adventure ao entrar no mundo. A configuração
correta do mundo não substitui a aceitação nativa. Estes passos de preparação
e os comandos `validar`/captura não demonstram uma partida aceite nem autorizam
abertura operacional.

## Permissões e estado runtime

Os nove nós de uso de comando declarados em `plugin.yml` têm `default: true`:
`ciaac.minigames.arena.use`, `ciaac.minigames.buildbattle.use`,
`ciaac.minigames.hotpotato.use`, `ciaac.minigames.sumo.use`,
`ciaac.minigames.parkour.use`, `ciaac.minigames.archery.use`,
`ciaac.minigames.anvildodge.use`, `ciaac.minigames.colorfloor.use` e
`ciaac.minigames.elytra.use`. Consultas gerais usam
`ciaac.minigames.use`, também pública. Estes nós não concedem OP nem ignoram
autenticação, admissão, isolamento ou estado de recuperação. A permissão
administrativa `ciaac.minigames.admin` é distinta e começa com `default: false`;
nenhum modo precisa dela para participação. Os comandos locais de preparação
de instalações usam a própria identidade da consola local e não consultam esse
nó: não conceder permissões a jogadores nem alterar OP para os executar.
Confirmar os nós efetivos com a autoridade de permissões do servidor e não
conceder wildcards para resolver uma recusa.

O resolver só abre um módulo se `admission.enabled` estiver ativo, a secção do
módulo estiver ativada, o esquema e as chaves forem válidos, o mundo e a
geometria forem resolvidos e não houver diagnósticos. A configuração fornecida
mantém a admissão global e os nove módulos fechados. Além disso, a política
`strictNoProgress` exige os fornecedores de isolamento reais. O runtime liga
providers de mundo para Build Battle, Piso das Cores e Elytra em
`ArenaWorldStatePort`; a integração de código, por si só, não demonstra
aceitação nativa, recuperação pós-crash nem prontidão operacional. A matriz
datada identifica os cenários nativos delimitados já aceites. O provider só fica disponível no Paper **26.2 build 84,
commit `26e81c4`**, depois da instalação saudável dos listeners de proteção e
ciclo de vida. Não remover a
barreira de isolamento para contornar essa limitação. AuthMe ou nLogin têm de
estar em análise separada antes de qualquer decisão: o runtime fecha admissão
se ambos estiverem ativos; o ramo atual de nLogin não fornece prova de
conclusão de autenticação/restauro e mantém admissão fechada. Só usar um
fornecedor cujo adaptador de conclusão tenha sido validado no alvo. O código
atual liga AuthMe quando está ativo sozinho; isto não equivale a aceitação das
instalações dos minijogos. Consultar [autenticação](authentication.md) e
[adaptadores](external-state-adapters.md).

A evidência nativa continua delimitada a cenários e candidatos: a repetição
atual confirmou dez casos locais para Parkour, Arco, Bigornas, Piso das Cores,
crash frio de Sumo e Batata Quente; Build Battle confirmou os percursos de
construção, votação, saída, desconexão, timeout e crash frio descritos na
matriz. Coliseu e Elytra têm as suas próprias evidências delimitadas. Isto não
confirma instalações reais, toda a física vanilla, todos os formatos ou
prontidão global. A repetição de preparação passou no candidato final: os nove
módulos ficaram válidos e WAITING antes/depois de reiniciar; as capturas existentes
foram recusadas sem alterar os digests. As quatro bandas do Arco produziram os
scores exatos. Parkour confirmou concorrência, recusa do finish fora de ordem,
timeout e regresso ao gate após atravessar a fronteira no candidato `b34b6a0…`.
Os casos de colisão/queda vanilla continuam fora deste ensaio. Um teste de domínio, configuração válida,
fixture sintético ou estado `CLOSED`/`RESTORED` isolado não prova aceitação
completa. Ver a [matriz datada de validação](all-minigames-validation.md) e a
página específica de cada modo antes de qualquer decisão operacional.
