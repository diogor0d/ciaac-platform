# Anéis de Elytra

Estado em 2026-08-24 (endurecimento do código-fonte reconciliado):

- `SOURCE-IMPLEMENTED`: `ElytraRingsGame`, `ElytraRingsController`, a fronteira
  de definições/preparação de chunks do mundo dedicado, a ligação do
  módulo/assembler fixo, as portas do encaminhador de eventos e o registo de
  resultados estão presentes.
- `RUNTIME-UNVERIFIED`: não foi executado qualquer teste de aceitação de
  chunks/tickets/voo em funcionamento, instalação ou teste Paper descartável.
  Os testes Maven completos e Paper descartável ficam adiados.

## Experiência do jogador e comandos

A rota em português é `/elytra`:

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
exata em funcionamento e o feedback no cliente permanecem não verificados.

## Mecânicas e ciclo de vida

Elytra é um controlador fixo para um jogador. A admissão prepara apenas a área
finita do percurso, adiciona tickets de chunks do plugin e espera até que todos
os chunks necessários estejam carregados e tenham ticket. O jogador é
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
depois de a instância anterior atingir um estado terminal.

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
autoridade. A mutação de entidades/ambiente e a física de voo em funcionamento
continuam a exigir verificação num Paper descartável.

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
para obter uma classificação comparável do percurso. A persistência das
estatísticas e a classificação em funcionamento não foram verificadas durante a
execução.

## Limites de execução

O código expõe o estado dos chunks, o estado do módulo, entradas tipadas do
controlador e portas de comandos/apresentação em português. Não foi demonstrada
qualquer implantação, ciclo de vida ativo de tickets de chunks, entrega por
hologramas/DiscordSRV ou resultado de voo/recuperação num Paper descartável.
