# Estado no código-fonte dos modos futuros

Estado em 2026-08-22:

- `SOURCE-VERIFIED`: os seis modos abaixo têm domínio puro, configuração
  validada, controlador/módulo Paper, isolamento de sessões, encaminhamento de
  eventos e código-fonte de estatísticas neste checkout. `ModuleCatalog`
  classifica-os como implementados no código-fonte.
- `UNVERIFIED`: a presença do código-fonte não abre a admissão. Não há
  implantação, evidência de aceitação Paper em execução nem teste Paper
  descartável neste registo. Os testes Maven completos e Paper descartável ficam
  adiados.


Atualização em 2026-10-07: Sumo tem aceitação local limitada, incluindo input,
knockback/vitória e restauro autenticado; ver
[verificação funcional](functional-verification.md). Os testes Maven completos
passaram. Os outros cinco modos deste catálogo mantêm os gates de aceitação
Paper/mundo indicados abaixo. A data de 2026-08-22 conserva o contexto histórico.

## Catálogo e política de localização

| Modo | Política do mundo | Rota portuguesa | Ciclo de vida no código-fonte | Métrica pública |
| --- | --- | --- | --- | --- |
| Sumo de Repulsão | Zona segura do spawn | `/sumo` | Uma fila/partida 1v1 | `wins`, maior |
| Parkour cronometrado | Zona segura do spawn | `/parkour` | Corridas cronometradas independentes | `time_ms`, menor |
| Campo de tiro com arco | Zona segura do spawn | `/arco entrar [lane-id]` | Um jogador ativo por pista | `score`, maior |
| Fuga às Bigornas | Zona segura do spawn | `/bigornas` | Uma partida fixa de vagas | `survival_ms`, maior |
| Piso de Cores | Zona segura do spawn | `/cores` | Uma partida fixa baseada em modelo | `wins`, maior |
| Anéis de Elytra | Apenas mundo dedicado | `/elytra` | Uma corrida individual fixa | `time_ms`, menor |

A superfície de comandos partilhada é:

```text
<route> estado | ajuda | entrar | sair | pronto
/minijogos estado
/minijogos ajuda
/minijogos top <game-id-or-route>
/minijogos estatisticas <game-id-or-route>
```

`pronto` é atualmente a ação genérica do encaminhador e devolve
`READY_UNSUPPORTED` para estes modos. O estado pode mostrar um módulo pronto no código-fonte,
mas a entrada permanece fechada até serem validados o mundo, regiões, jogadores,
modelos e caminho de listeners configurados.

## Mecânicas e isolamento dos modos

### Sumo de Repulsão

`SumoSession` e `SumoPaperController` implementam um ringue estrito para dois
jogadores, com melhor de várias rondas. `knockback-sumo.boundary` é a região dos
`side-a`/`side-b` são os locais de spawn configurados. O controlador emite
tokens de admissão por jogador, identifica o item temporário de repulsão, converte
quedas, vazio ou fuga do limite em saída do ringue, transforma o tempo limite
configurado num empate determinístico, devolve ambos os jogadores aos lados
configurados entre rondas não terminais e recupera após saída, desconexão, falha de preparação
ou encerramento. O combate é encaminhado apenas entre os dois participantes
ativos; o dano/morte letal vanilla não é a autoridade da partida. Consulte
[`knockback-sumo.md`](knockback-sumo.md).

### Parkour de checkpoints cronometrado

Cada `ParkourSession` começa na entrada e aceita apenas a região seguinte em
`checkpoint-order`. `checkpoint-parkour.course-boundary` contém as regiões de
checkpoint ordenadas. Uma fuga/queda devolve o jogador à entrada ou ao último
checkpoint, aplicando a penalização configurada; teletransportes externos,
checkpoints obsoletos, morte e desconexão seguem o percurso de recuperação
controlado. Corridas simultâneas ficam isoladas pelo ID da corrida e podem
opcionalmente ocultar-se entre si. Consulte
[`checkpoint-parkour.md`](checkpoint-parkour.md).

### Campo de tiro com arco

Um `ArcheryPaperController` serve todas as pistas resolvidas. A admissão escolhe a
primeira pista livre, salvo se o jogador indicar um ID numérico. Cada pista tem
a sua própria região imutável de participante, ponto de aparecimento,
identificador de alvo, token, marcações de projéteis e um jogador ativo. O controlador entrega exatamente o
número configurado de disparos, aceita apenas projéteis identificados pelo
servidor e bandas de pontuação configuradas, e limpa o estado após conclusão,
fuga, tempo-limite, desconexão ou falha de restauração.

As entidades-alvo do operador têm de transportar exatamente esta tag do scoreboard:

```text
ciaac-archery-target:<configured-target-id>:<score-band>
```

O ID tem de corresponder a `lanes.<id>.target-id`, a banda tem de existir em
`target-scores.*`, e a entidade tem de estar na região dessa pista. A política de
eventos regista o alvo e o controlador valida a sua pontuação no servidor. A
ponte ativa entre tag do scoreboard e entidade permanece `UNVERIFIED`.
Consulte
[`archery-range.md`](archery-range.md).

### Fuga às Bigornas

O `AnvilDodgeGame` determinístico executa vagas limitadas com semente em
`regions.floor` dentro de `anvil-dodge.boundary`. O controlador usa armor stands
marcadores identificados e não persistentes, partículas e som para avisos e
perigos em queda; nunca coloca bigornas persistentes nem cria drops. O
encaminhador de eventos resolve movimento nas células do chão, impacto de perigo
identificado, morte controlada, teletransporte, desconexão e recuperação.
Consulte [`anvil-dodge.md`](anvil-dodge.md).

### Piso de Cores

`ColorFloorGame` resolve rondas de reação determinísticas depois de entrar o
elenco mínimo. O controlador fixo usa um modelo imutável do chão, revisto pelo operador,
modelo, altera apenas células declaradas que não são alvo em lotes limitados na
thread principal e restaura os dados exatos dos blocos antes de continuar. A
duração do aviso é passada separadamente da janela insegura/de reação. Modelo
incompatível, células não carregadas, falha de alteração/restauração, saída,
desconexão e encerramento fecham a admissão e produzem recuperação/sem disputa.
O encaminhador central trata movimento, alterações de blocos, teletransportes,
morte controlada e desconexão; a morte é cancelada antes dos drops e da
experiência vanilla. Consulta [`color-floor.md`](color-floor.md).

### Anéis de Elytra

Elytra não é uma atividade do spawn. O controlador do mundo dedicado exige o
UUID do mundo da revisão do percurso, uma `elytra-rings.course-boundary` imutável,
regiões de anéis ordenadas e sem sobreposição e uma área finita de chunks. Entrega
Elytra/foguetes identificados apenas depois de carregados e marcados todos os
chunks necessários, aceita o anel seguinte dentro do raio configurado, reinicia
corridas fora dos limites e liberta as suas marcações em todos os percursos
terminais/de recuperação. O encaminhador cancela mortes de corredores ativos
antes de drops/experiência, rejeita pérolas do Ender e alterações diretas do
terreno, e aceita apenas foguetes identificados e contabilizados pelo servidor.
A implementação atual rejeita a configuração quando `concurrent-runners` não é
um; a física de voo e das entidades em execução permanece
`UNVERIFIED`. Consulta [`elytra-rings.md`](elytra-rings.md).

## Resultados, classificações e ecrãs partilhados

Os controladores registam resultados imutáveis indexados por UUID através do
`StatisticsResultSink` apenas depois da restauração da sessão. Os resultados
classificados contêm revisões das regras ou do percurso, qualificadores do modo,
métricas limitadas e códigos de motivo; recuperações e restaurações ambíguas são
consideradas sem resultado e não classificadas.
As predefinições do código-fonte são:

- Sumo: `wins`, SUM, o valor mais alto é melhor; modo `1v1`.
- Parkour: `time_ms`, MIN, o valor mais baixo é melhor; modo `solo`.
- Tiro com Arco: `score`, MAX, o valor mais alto é melhor; modo
  `solo-lane-<id>`.
- Fuga às Bigornas: `survival_ms`, MAX, o valor mais alto é melhor; modo `ffa`.
- Chão de Cores: `wins`, SUM, o valor mais alto é melhor; modo `ffa`.
- Elytra: `time_ms`, MIN, o valor mais baixo é melhor; modo `solo`; as regras
  correspondem à revisão do percurso.

As predefinições públicas de `/minijogos top` deixam atualmente vazios os filtros
de modo e revisão das regras. Podem, por isso, misturar pistas, modelos ou
revisões de percurso; usa uma `LeaderboardQuery` com âmbito explícito quando a
comparação exigir uma única revisão. Os ecrãs nativos e o adaptador opcional de
anúncios DiscordSRV consomem projeções de estado e resultados, mas não são
autoridades de admissão nem de auditoria. A sua entrega em execução não está
verificada.

## Configuração e controlos do operador

O resolvedor rejeita geometria ilimitada ou inconsistente e expõe os seguintes
contratos do operador:

- Sumo: exatamente dois jogadores, tempo limite e limiar de queda da ronda,
  material e nível de repulsão, dois pontos de aparecimento e
  `knockback-sumo.boundary`.
- Parkour: `checkpoint-regions` exatas e sem sobreposição, identificadores
  ordenados, tempo limite e penalização, limite de corridas simultâneas e opção
  de visibilidade.
- Tiro com Arco: pelo menos uma pista, região, ponto de aparecimento e
  identificador do alvo por pista, quatro faixas de pontuação limitadas, número
  de disparos, tempo limite da tentativa e material do arco.
- Bigornas: limites de jogadores, tempos e número de vagas, progressão do
  perigo, `regions.floor` com no máximo 4096 células, limite e resolvedor
  `tagged-anvil-hazard`.
- Chão de Cores: limites de jogadores, rondas, tempos, paleta, `regions.floor` e
  um artefacto `ColorFloorTemplatePort` `immutable-template` disponível.
- Elytra: marcador do modelo do mundo, ordem e regiões completas dos anéis,
  revisão estável do percurso, tempo limite, pré-carregamento e foguetes
  limitados e `allow-rockets`. O adaptador atual tem capacidade para um jogador;
  mantém `concurrent-runners: 1`. As definições Paper são mais restritivas do que
  os limites do resolvedor para o pré-carregamento (8 chunks) e os foguetes (64).

Todos os modos usam `AdmissionRequest` autenticado, `SessionCoordinator`,
tokens de região, estado temporário identificado, entradas de eventos limitadas
e percursos explícitos de restauração e revogação. A ausência de jogadores,
regiões, mundos, chunks, modelos, etiquetas de alvos ou resolvedores de eventos
injetados deve fechar o modo. Este catálogo não pode conter coordenadas reais,
segredos nem instruções de implantação.

## Limites da evidência

Os seis documentos de componente acima descrevem contratos atuais do código-fonte,
e não um servidor implantado. Antes de permitir a admissão, executa as
verificações Maven atuais e um teste Paper descartável que cubra entrada e
saída, eventos duplicados, desconexão, recuperação após reinício ou encerramento,
fuga de regiões, restauração de itens, identidade de alvos e perigos, libertação
de chunks, idempotência das estatísticas e encaminhamento de comandos e ecrãs.
