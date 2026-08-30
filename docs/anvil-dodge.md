# Fuga às Bigornas

Estado em 2026-08-24 (endurecimento do código-fonte reconciliado):

- `SOURCE-IMPLEMENTED`: `AnvilDodgeGame`, `AnvilDodgeController`, o adaptador de controlador/módulo, o resolvedor/montador, as portas de encaminhamento de eventos e o registo de resultados estão presentes.
- `RUNTIME-UNVERIFIED`: não foi executada uma instalação, uma aceitação Paper em funcionamento ou um teste Paper descartável.

## Experiência do jogador e comandos

A rota em português é `/bigornas`:

```text
/bigornas estado
/bigornas ajuda
/bigornas entrar
/bigornas sair
```

A ação partilhada `/bigornas pronto` não é suportada. O estado e as estatísticas públicas e pessoais estão disponíveis através de:

```text
/minijogos estado
/minijogos top anvil-dodge
/minijogos estatisticas anvil-dodge
```

As mensagens devolvidas ao jogador são limitadas a pt-PT e não expõem detalhes de implementação.

## Mecânicas e ciclo de vida

O módulo cria um controlador novo para cada encontro apenas depois de o controlador anterior terminar. A admissão respeita o máximo configurado e o jogo começa quando existe a composição mínima. O plano de ondas usa uma seed determinística para escolher células limitadas do piso. Em cada onda, os perigos são anunciados, os armor stands marcador pertencentes ao plugin são animados a partir de cima, as células seguras e perigosas são resolvidas e as esquivas são registadas.

Os perigos são armor stands marcador invisíveis e não persistentes, com capacete de bigorna, partículas e som. O controlador nunca coloca uma bigorna persistente, cria drops ou usa a morte letal normal como resultado. Um impacto controlado segue o percurso de eliminação do domínio. Os IDs de operação rejeitam entradas repetidas ou antigas de esquiva e eliminação.

## Isolamento, posicionamento e recuperação

`GameKey.ANVIL_DODGE` usa a política `SPAWN_SAFEZONE`. O assembler utiliza a região imutável `anvil-dodge.boundary`; o cuboide configurado em `regions.floor` define a grelha de células e a localização `start` é o ponto de admissão. A região tem de estar registada para este jogo e o chunk inicial tem de estar carregado.

`SessionCoordinator` guarda e recupera o estado do jogador e revoga o token de região em todos os percursos terminais. O router trata movimento, células do piso, teleportes, dano dos perigos, morte controlada, saída, expulsão e desconexão. A conclusão do encontro, a saída, o encerramento do plugin, a geometria inválida e uma falha de restauração seguem percursos de recuperação ou sem competição; os perigos são limpos em cada onda, resultado e recuperação.

Depois de uma desconexão direta do controlador, o wrapper fixo é atualizado para não deixar um marcador de encontro obsoleto. As tentativas de limpeza são independentes e as falhas são registadas sem expor detalhes internos aos jogadores. A recuperação durável da sessão continua a ser a autoridade.

O listener de perigos deve resolver apenas o marcador pertencente ao plugin `anvil-dodge-hazard` para este encontro. O código expõe essa dependência através de um `AnvilHazardResolver` injetado; a ligação Paper em funcionamento ainda não foi verificada.

## Configuração e preparação do operador

O resolvedor aceita:

```text
minimum-players: 1..64
maximum-players: minimum..64
wave-count: 1..10000
warning-ticks: 1..72000
wave-interval-ticks: 2..144000
hazards-per-wave-start: 1..4096
hazards-per-wave-increment: 0..4096
regions.floor: cuboide, no máximo 4096 células
```

O módulo exige ainda o mundo resolvido, `start` e a região de participação. A revisão das regras, a seed determinística, a política estrita sem progressão e a entrada do adaptador `tagged-anvil-hazard` são contratos de configuração do código-fonte. O operador deve validá-los num mundo descartável antes de ativar a admissão.

## Resultados e classificações

Os resultados usam o modo `ffa` e as métricas limitadas por jogador `survival_ms`, `waves`, `dodges` e `wins`. Um encontro concluído classifica os sobreviventes. Saída, desconexão, encerramento, configuração inválida e restauração falhada produzem `no-contest` quando todas as recuperações terminam com sucesso. O preset público é `survival_ms`, MAX; valores maiores são melhores. A consulta predefinida não filtra regras nem modo, pelo que uma classificação limitada exige uma consulta estatística explícita.

## Limites de execução

O adaptador está ligado no código a comandos, apresentações de estado e portas de eventos tipadas. Não existe evidência de registo de listeners em funcionamento, entrega de displays/hologramas, entrega DiscordSRV ou aceitação Paper em produção.
