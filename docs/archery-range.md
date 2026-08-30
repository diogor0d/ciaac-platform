# Campo de Tiro com Arco

Estado em 2026-08-24 (endurecimento do código-fonte reconciliado):

- `SOURCE-IMPLEMENTED`: o domínio, o controlador, o assembler e o router de eventos estão presentes.
- `RUNTIME-UNVERIFIED`: a ligação Paper, as entidades de alvo e a entrega de apresentações ainda não foram validadas em execução.

## Experiência do jogador e comandos

A rota em português é `/arco`:

```text
/arco estado
/arco ajuda
/arco entrar [lane-id]
/arco sair
```

`lane-id` é opcional e numérico, limitado a `0..1024`. Sem esse valor, o
controlador escolhe a primeira lane livre por ordem crescente. Existe no máximo
um jogador ativo por lane. A ação partilhada `/arco pronto` não é suportada.

As mensagens devolvidas ao jogador são limitadas a pt-PT. Uma lane ocupada,
um disparo inválido ou uma tentativa de sair do campo são recusados sem expor
detalhes internos.

## Mecânicas e ciclo de vida

O assembler cria um controlador para todas as lanes resolvidas e atribui um ID
de sessão por execução. Cada tentativa começa no spawn da lane, tem o timeout
configurado e termina ao atingir o número limitado de disparos, ou quando o
jogador sai, se desconecta, excede o tempo, fica inválido ou não pode ser
restaurado.

Os projéteis são marcados no servidor com a sessão e a lane e cada projétil é
consumido uma única vez. A pontuação só é aceite quando a banda resolvida pelo
servidor coincide com a configuração.

## Alvos e segurança dos projéteis

As entidades de alvo são entradas pertencentes ao operador e resolvidas pelo
servidor. A política exige uma tag de scoreboard exata:

```text
ciaac-archery-target:<configured-target-id>:<score-band>
```

`<configured-target-id>` tem de coincidir com
`lanes.<id>.target-id`; a banda tem de ser uma chave de `target-scores`
(normalmente `bullseye`, `inner`, `middle` ou `outer`). A entidade tem
de estar na região registada da lane. Tags ausentes, estrangeiras, ambíguas ou
fora da região fecham a operação; o projétil é limpo. A ponte entre tags e
entidades Paper em funcionamento permanece `RUNTIME-UNVERIFIED`.

O controlador expõe as marcas tipadas e as chaves PDC
(`ciaac:archery-session`, `ciaac:archery-lane`,
`ciaac:archery-target-lane` e `ciaac:archery-target-band`) para um listener
posterior orientado a entidades.

## Isolamento e recuperação

`GameKey.ARCHERY_RANGE` usa a política `SPAWN_SAFEZONE`. Cada lane tem uma
região imutável `archery-range.<region-id>`; o spawn e o alvo configurado têm
de permanecer nessa lane e as lanes não podem sobrepor-se.

O controlador emite um token de admissão específico da lane e utiliza
`SessionCoordinator` e tags de itens temporários. Movimento ou teleporte para
fora, saída, expulsão, morte, timeout, conclusão e encerramento revogam a lane e
restauram o snapshot do jogador. O router cancela dano de projéteis para que
uma flecha não possa ferir jogadores através do dano normal de entidades.

A limpeza após saída ou expulsão chama o percurso de desconexão do controlador e
depois a saída do wrapper, removendo a execução da lane e a identidade da
execução. Falhas de rota são isoladas e não expõem operações internas aos
jogadores; uma restauração durável incerta fecha a admissão.

## Configuração e preparação do operador

O resolvedor exige pelo menos uma lane numérica e estes valores limitados:

```text
lanes.<id>.region-id (or region)
lanes.<id>.target-id
lanes.<id>.spawn: nested location
shots-per-attempt: 1..128
attempt-timeout-seconds: 1..3600
target-scores.bullseye: 0..100
target-scores.inner: 0..100
target-scores.middle: 0..100
target-scores.outer: 0..100
bow-material: valid item material
```

O mundo resolvido, as regiões, os spawns, os IDs dos alvos e o mapa de
pontuação do servidor devem ser revistos pelo operador. Não são guardadas aqui
coordenadas reais nem UUIDs de entidades.

## Resultados e classificações

Os resultados concluídos usam o modo `solo-lane-<lane-id>` e incluem métricas
limitadas `lane`, `score`, `shots`, `bullseyes`, `time_ms` e `wins`.
Desconexão, fuga, timeout ou restauração falhada produzem `no-contest`. O
preset público é `score`, MAX; valores maiores são melhores.

## Limites de execução

`MinigameEventRouter` trata movimento e teleporte na lane, lançamento e impacto
de projéteis, limpeza, cancelamento de dano, morte, saída, expulsão e
desconexão. Comandos e apresentações expõem apenas o estado; as tags dos alvos
e a pontuação imutável do servidor permanecem autoritativas. A entrega
DiscordSRV e o registo de listeners Paper continuam sem verificação de execução.
