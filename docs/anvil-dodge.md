# Fuga às Bigornas

Última atualização: 2026-10-08.

`RUNTIME-VERIFIED`, com âmbito limitado, no candidato `6792e1924d90214e3a7c26919eb3cd0d0aaa83c16db0de38157f533129c1dc05`: duas ondas normais terminaram em `VICTORY` / `COMPLETED`; entrada tardia foi recusada; conclusão, saída, desconexão e crash frio restauraram exatamente os 18 campos Paper. No crash, após reinício e autenticação normal AuthMe, o marcador do plugin foi removido e a sua ausência confirmada no mundo. Os ensaios ocorreram num Paper descartável com peers sintéticos autenticados. Não demonstram aceitação em produção, com jogadores reais, nem todas as ondas, esquivas ou eliminações.

Evidência histórica local no candidato `0371202...`: conclusão, saída, desconexão, entrada tardia e crash ativo; o piso de 64 blocos `STONE` permaneceu inalterado. Nesse crash, a recuperação a frio restaurou os 18 campos, mas não produziu registo de resultado (`outcome log` nulo). Esse resultado nulo pertence ao ensaio histórico, não contradiz a vitória observada nos percursos atuais; a recuperação fria não prova por si só a persistência de resultado.

## Experiência do jogador

A rota em português é `/bigornas`:

```text
/bigornas estado
/bigornas ajuda
/bigornas entrar
/bigornas sair
```

`/bigornas pronto` não é suportado. O estado e as estatísticas estão disponíveis através de:

```text
/minijogos estado
/minijogos top anvil-dodge
/minijogos estatisticas anvil-dodge
```

## Mecânica e ciclo de vida

O controlador planeia ondas determinísticas a partir da seed configurada. Os perigos são armor stands marcador invisíveis, não persistentes, com capacete de bigorna, animados sobre o piso; partículas e som acompanham o impacto controlado. Não são bigornas persistentes nem blocos em queda de Minecraft vanilla, e não há drops ou alteração do terreno. O impacto segue o fluxo controlado de eliminação do jogo.

A configuração é recusada quando o total de marcadores planeados pela soma das ondas excede 4096, o limite de propriedade e limpeza. A altura da animação termina em `floor.minY + 8`, que tem de ficar abaixo do limite superior exclusivo do mundo. O módulo só pode admitir jogadores depois de resolver o mundo, a localização inicial, o piso e a fronteira.

## Instalação da área

Antes de ativar a admissão, confirmar manualmente:

1. Escolher o mundo principal e registar a região `boundary` para `anvil-dodge`, com papel de participação e limites imutáveis.
2. Definir `regions.floor` como o volume da grelha. A largura vezes profundidade não pode exceder 4096 células; a altura Y do piso deve estar dentro da fronteira.
3. Colocar `locations.start` dentro do próprio volume de `floor`, incluindo a coordenada Y. A localização `exit` também tem de estar configurada.
4. Reservar espaço vertical desde `floor.minY` até `floor.minY + 8`, inclusive, tanto no mundo como na fronteira. `floor.minY` tem de ser pelo menos o `minHeight` do mundo e `floor.minY + 8` tem de ser menor que `maxHeight`.
5. Carregar os chunks do piso, verificar que não há obstáculos no volume de animação e testar a entrada, saída e recuperação numa instalação descartável antes de abrir a admissão.

O assembler usa a fronteira imutável `anvil-dodge.boundary`; a grelha vem de `regions.floor`. A verificação de colocação não substitui a inspeção física de obstáculos ou de uma queda segura.

## Recuperação

`SessionCoordinator` guarda e recupera o estado do jogador e revoga o token da região nos percursos terminais. A limpeza durável identifica apenas os marcadores pertencentes ao plugin e ao encontro; a purga de todos os marcadores do encontro precede a restauração dos jogadores. Conclusão, saída, desconexão, encerramento e crash ativo passaram recuperação local nos cenários documentados. O ensaio histórico de crash no candidato `0371202...` não registou resultado de domínio; os percursos atuais confirmaram uma vitória normal, enquanto o crash frio verificou recuperação e limpeza. A persistência de resultados através de crash continua por validar.

O adaptador de perigos é fornecido pelo provider nativo; já não está ausente. Mantêm-se por demonstrar a execução em produção e a aceitação com clientes Minecraft reais.

## Configuração

Valores aceites pelo resolvedor:

```text
minimum-players: 1..64
maximum-players: minimum..64
wave-count: 1..10000
warning-ticks: 1..72000
wave-interval-ticks: 2..144000
hazards-per-wave-start: 1..4096
hazards-per-wave-increment: 0..4096
regions.floor: cuboide, grelha com no máximo 4096 células
total de marcadores planeados: no máximo 4096
```

A revisão das regras, a seed determinística, a política sem progressão e a entrada `tagged-anvil-hazard` são contratos de configuração. A admissão só abre quando a resolução completa não apresenta diagnósticos.

## Resultados

Os resultados usam o modo `ffa` e as métricas limitadas por jogador `survival_ms`, `waves`, `dodges` e `wins`. Uma conclusão classifica sobreviventes. Saída, desconexão, encerramento, configuração inválida ou restauração falhada produzem `no-contest` quando a recuperação termina com sucesso. O preset público é `survival_ms`, MAX; consultas limitadas exigem filtros explícitos.

## Impacto e esquiva nativos — 2026-10-08

No candidato `b34b6a033728e6d8bd44fb90e8c373c0c56be95520f2f7f721e568c35c4d4e23`,
`anvil-edges` confirmou recusa da fronteira enquanto se aguardava o segundo
jogador. Depois do início, leu a posição real do marcador e usou movimento
nativo para colocar um jogador na célula perigosa e o outro numa célula segura.
O primeiro foi eliminado; o segundo esquivou-se nas duas ondas e recebeu
`VICTORY` / `COMPLETED`. A consulta ligou resultado, classificações e métricas à
partida exata do cenário. Ambos restauraram os 18 campos; os dois marcadores
ficaram `REMOVED` e ausentes por UUID, e as 64 células do piso ficaram intactas.
Não é uma prova de colisão física com bigornas vanilla: o controlador resolve o
impacto pela célula do piso e os marcadores são entidades próprias do plugin.
