# Anéis de Elytra

Estado em 2026-10-08:

- `SOURCE-VERIFIED`: `ElytraRingsGame`, `ElytraRingsController`, a fronteira
  de definições/preparação de chunks do mundo dedicado, a ligação do
  módulo/assembler fixo, as portas do encaminhador de eventos e o registo de
  resultados estão presentes.
- `RUNTIME-VERIFIED`, candidato `6792e19…`, 2026-10-08: o cliente Minecraft
  normal entrou num Paper local descartável com AuthMe, descolou, usou um
  impulso e atravessou dois anéis, com `VICTORY` / `COMPLETED`. Saída,
  timeout, desligação/reautenticação e crash frio com foguete vivo também
  restauraram exatamente os 18 campos originais. O foguete ficou `REMOVED`
  e ausente pelo UUID nativo, incluindo após o crash.
- `UNVERIFIED`: instalações reais, condições adicionais de voo e feedback de
  reinício fora do percurso. Consultar a [matriz datada](all-minigames-validation.md);
  não houve alteração em produção.

## Experiência do jogador e comandos

A rota em português é `/elytra`:

Sem argumentos, `/elytra` abre a [página do jogo no menu](minigame-menus.md).

```text
/elytra estado
/elytra ajuda
/elytra entrar
/elytra sair
```

`/elytra pronto` não é suportado. O estado e as estatísticas públicas usam:

```text
/minijogos estado
/minijogos top elytra-rings
/minijogos estatisticas elytra-rings
```

O controlador envia mensagens como `Anéis de Elytra: atravessa os anéis pela
ordem indicada!`, `Checkpoint N/M concluído.` e uma mensagem visível de
reinício quando uma tentativa fora dos limites regressa ao início. A medição
exata do voo aceite foi registada na prova local acima; outros percursos e o
feedback de reinício continuam sujeitos à matriz de validação.

## Mecânicas e ciclo de vida

Elytra é um controlador fixo para um jogador. Enquanto o módulo está ativo e
sem partida, prepara a área finita do percurso e mantém os tickets de chunks
do plugin. Só anuncia entrada disponível depois de todos os chunks necessários
estarem carregados e terem ticket; durante a preparação anuncia preparação,
sem criar partida, sessão ou snapshot. A admissão reutiliza essa preparação e
volta a validar a prontidão e a autorização da região. O jogador é
teletransportado para o início configurado e recebe uma Elytra etiquetada, além
dos fogos de artifício configurados quando ativados. As definições Paper de
produção aceitam apenas a entrada na próxima região ordenada exata
`ring-regions.<id>` cuboide; a correspondência por centro/raio é mantida apenas
para fixtures de código legadas sem regiões resolvidas. O jogo puro regista os
tempos parciais e impõe o timeout monotónico usando o tempo de tick injetado.

O movimento para fora do percurso do participante reinicia a tentativa e limpa o
progresso; a tentativa seguinte começa no primeiro anel. Um timeout invalida e
termina a tentativa. A conclusão, saída, desligação, encerramento, preparação de
chunks em falta/com falha e falha de restauração libertam os tickets e usam a
fronteira de recuperação da sessão. O módulo fixo só cria um novo controlador
depois de a instância anterior atingir um estado terminal. A preparação seguinte
usa uma nova instância; o encerramento do plugin também liberta os tickets
mantidos sem partida. O footprint continua limitado ao percurso e ao raio
configurado, com o limite existente de 65 536 chunks.

## Política de mundo, região e isolamento

`GameKey.ELYTRA_RINGS` é explicitamente `DEDICATED_WORLD`; não é um modo de
zona segura do spawn. `ElytraRingsPaperSettings` exige que o `worldId` da
revisão do percurso seja igual ao UUID do mundo carregado e que a localização
inicial esteja nesse mundo exato. O assembler regista a fronteira imutável do
mundo de jogo `elytra-rings.course-boundary`; a geometria ordenada
`ring-regions.<id>` tem de estar completa e não sobreposta. O preparador de
chunks cobre a rota desde o início até aos anéis e o raio de pré-carregamento
limitado, libertando apenas os seus próprios tickets.

O controlador e o router tratam movimento, teletransportes do plugin/externos,
morte controlada, saída/expulsão, desligação, prontidão dos chunks e fuga do
percurso. A morte é cancelada antes dos drops/experiência vanilla e entra em
recuperação. As pérolas de Ender são canceladas para participantes ativos; a
destruição/colocação direta de blocos no mundo dedicado é cancelada; e o uso de
fogos de artifício só é aceite para um foguete etiquetado pelo servidor,
pertencente à sessão ativa e dentro do orçamento configurado por servidor. O
percurso de eventos usa a decisão do item na mão independentemente da decisão do
bloco, pelo que um foguete válido de clique direito no ar não é descartado
apenas por não existir ação sobre um bloco. As contagens de itens visíveis no
cliente, a propriedade dos projéteis e a velocidade não são tratadas como
autoridade. A propriedade do foguete valida o componente nativo efetivo
`FIREWORKS`: duração 1 e zero efeitos. Em Paper 26.2, `FireworkMeta.getPower()`
pode devolver zero para o foguete cujo componente efetivo por omissão tem
duração 1; componentes ausentes, outras durações ou explosões continuam
recusados. A admissão do impulso exige voo nativo (`isGliding`); caminhada e
uso no chão não consomem o orçamento. A prova local acima confirma um impulso,
dois anéis e limpeza exata; não prova todas as condições de voo/recuperação.

Depois de uma desligação direta do controlador, o wrapper fixo é atualizado para
que uma tentativa terminal não deixe um marcador de partida obsoleto. As falhas
de limpeza são isoladas e registadas no percurso sem expor detalhes internos aos
jogadores; a incerteza da restauração de tickets de chunks e de sessões
permanece em falha segura.

## Configuração e preparação do operador

O resolver aceita:

```text
world-template-marker: marcador do operador limitado
ring-order: lista ordenada sem repetições (pelo menos dois anéis)
ring-regions.<id>: região exata e sem sobreposição para cada anel
course-revision: identificador estável da revisão
run-timeout-seconds: 1..86400
preload-radius-chunks: 0..8
firework-rockets: 0..64
allow-rockets: boolean
concurrent-runners: exatamente 1; outros valores são rejeitados como não suportados
```

O adaptador fixo atual tem capacidade `1`; valores diferentes de `1` produzem
um diagnóstico de configuração e não admitem participantes. O marcador de
modelo resolvido e a entrada ordenada dos alvos dos anéis são contratos de
configuração; a identidade chunk/mundo e a prontidão das regiões são as
verificações atualmente visíveis no controlador.

## Resultados e classificações

Os resultados usam o modo `solo`, a revisão do percurso como ruleset e métricas
limitadas `time_ms`, `rings`, `resets` e `wins`. Timeout, recuperação de
reinício, desligação, encerramento e restauração falhada são sem competição/sem
classificação. A predefinição pública é `time_ms`, MIN, em que o menor valor é
melhor, mas `/minijogos top elytra-rings` deixa atualmente vazios os filtros de
ruleset/modo e pode misturar revisões do percurso. Utilize uma consulta com âmbito
para obter uma classificação comparável do percurso. Os resultados normais foram consultados na base persistente no ensaio local.
A apresentação de classificações em funcionamento e a comparação entre
revisões continuam sem aceitação nativa. Após crash frio, a recuperação
restaura o estado, mas não reconstrói o controlador de partida volátil: o
resultado durável do ensaio foi ausente, sem vitória ou classificação atribuída.

## Limites de execução

O código expõe o estado dos chunks, o estado do módulo, entradas tipadas do
controlador e portas de comandos/apresentação em português. Voo e recuperação num Paper descartável foram demonstrados para os cenários
datados acima. Não foi demonstrada implantação de instalações reais, entrega
por hologramas/DiscordSRV ou inspeção nativa de todos os tickets de chunks.

## Aceitação nativa completa do fixture — 2026-10-08

O R5 do JAR `6792e1924d90214e3a7c26919eb3cd0d0aaa83c16db0de38157f533129c1dc05`
terminou com sucesso e parou normalmente o seu subprocesso Paper. O uso no chão
não criou um foguete autorizado. O voo nativo (`FallFlying: 1b`) e o impulso
completaram ambos os anéis; saída deu `NO_CONTEST` / `PLAYER_LEFT`, timeout deu
`NO_CONTEST` / `INVALIDATED` e desligação com login normal deu `NO_CONTEST` /
`PLAYER_DISCONNECTED`. Cada percurso comparou positivamente os 18 campos da
baseline, incluindo inventários, equipamento, XP, posição e mundo.

O crash ocorreu apenas depois de voo e lançamento normais, confirmação durável
`CONFIRMED`, `save-all flush` e consulta nativa de que o UUID exato do foguete
continuava vivo. O subprocesso próprio recebeu SIGKILL; após reinício e AuthMe
normal com a mesma conta, os 18 campos coincidiram, o claim ficou `REMOVED` e o
UUID já não existia no mundo. A base sintética ficou com 93 sessões `CLOSED` e
93 snapshots `RESTORED`; o encontro interrompido não recebeu resultado
classificado. Não houve criação artificial de projéteis nem restauro pelo teste.

Uma tentativa anterior de desligação excedeu o prazo da ponte enquanto o
Minecraft descarregava o mundo. O clique tinha ocorrido; repetir esse clique
seria incorreto. O runner passa a observar o estado real após o timeout sem
reenviar a ação. A sessão pendente desse ensaio foi recuperada por AuthMe
normal e comparada com a baseline preservada, sem apagar os registos. As
falhas anteriores de descolagem continuam arquivadas; a repetição usa input
limitado e observa `OnGround` antes de pedir voo, sem teleport forçado.

A repetição pela ferramenta pública `tools/arena-client-fixture/elytra_acceptance.py`
confirmou novamente todos estes cenários no mesmo JAR `6792e19…`; terminou com
98 sessões `CLOSED` e 98 snapshots `RESTORED`. Consultar o
[contrato e invocação](../tools/arena-client-fixture/README.md).

## Repetição do guard e candidato atual — 2026-10-08

A ferramenta pública `elytra_acceptance.py`, incluindo o guard reforçado de
dimensão do cliente e do Paper, passou a sequência integral no candidato
`b34b6a033728e6d8bd44fb90e8c373c0c56be95520f2f7f721e568c35c4d4e23`.
Voo normal, foguete, os dois anéis, saída, timeout, desligação/AuthMe e crash
frio com foguete vivo confirmado restauraram todos os 18 campos. O ledger ficou
`REMOVED` e a entidade ausente por UUID. Paper parou normalmente com 132 sessões
`CLOSED` e 132 snapshots `RESTORED`. Isto resolve a repetição pendente do guard,
sem mudar o âmbito dos cenários anteriores ou declarar todas as condições de
voo/instalações reais aceites.
