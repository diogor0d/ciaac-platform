# Parkour de Checkpoints

Última atualização: 2026-10-08.

- `SOURCE-VERIFIED`: domínio, controlador Paper, tokens de região e
  recuperação através de `SessionCoordinator` estão presentes.
- `RUNTIME-VERIFIED`, com âmbito limitado: no candidato
  `6792e1924d90214e3a7c26919eb3cd0d0aaa83c16db0de38157f533129c1dc05`, dois
  checkpoints ordenados terminaram em `VICTORY` / `COMPLETED`; saída e
  desconexão deram `NO_CONTEST`, e a reentrada ativa foi recusada. Crash frio,
  reinício e autenticação normal AuthMe restauraram exatamente os 18 campos
  Paper. Os ensaios ocorreram em Paper descartável com peers sintéticos.
- `UNVERIFIED`: queda vertical e colisões vanilla, percurso físico de produção,
  integração de apresentações e aceitação de instalações reais. Concorrência,
  ordem e timeout têm a aceitação local delimitada abaixo.

## Experiência e comandos

```text
/parkour estado
/parkour ajuda
/parkour entrar
/parkour sair
```

A ação partilhada `/parkour pronto` não é suportada. O jogador recebe
mensagens limitadas a pt-PT; o estado público e as estatísticas usam os comandos
de `/minijogos`.

## Mecânicas

O jogador é admitido num percurso configurado e recebe um token limitado à sua
sessão. Os checkpoints têm ordem imutável e cada passagem é validada pelo
servidor. Um checkpoint só pode ser concluído uma vez e a progressão não pode
ser falsificada por coordenadas, eventos antigos ou outra sessão.

Movimento e teleporte para fora do percurso são recusados. Uma queda aplica a
penalização configurada e devolve o jogador ao início ou ao último checkpoint.
Saída, expulsão, desconexão, timeout, conclusão e encerramento seguem os
caminhos de recuperação da sessão. Falhas de limpeza são isoladas; uma
restauração incerta mantém a sessão fechada para recuperação. Os cenários acima
foram aceites em Paper descartável; não representam aceitação de uma instalação
real nem validação do percurso físico completo.

## Isolamento

`GameKey.CHECKPOINT_PARKOUR` usa a política `SPAWN_SAFEZONE`: o mundo configurado
tem de ser o mundo principal do servidor. A região imutável `course-boundary`,
o mundo e a ordem dos checkpoints têm de ser resolvidos antes da admissão. O
inventário, os efeitos, a localização e o restante estado temporário são
protegidos por `SessionCoordinator`; o parkour não concede itens, moeda ou outra
progressão. A cobertura local acima está validada para os cenários delimitados.

O router trata movimento, teleportes, checkpoints, dano, morte, saída, expulsão
e desconexão. Apenas o jogador autenticado da sessão pode avançar.

## Configuração

O resolvedor exige as seguintes opções em `modules.checkpoint-parkour`:

```text
world: nome e UUID do mundo principal
regions.course-boundary: limites imutáveis da área do percurso
locations.start: ponto de entrada no mundo configurado
checkpoint-order: lista de IDs sem repetições, até 64 entradas
checkpoint-regions.<id>: cuboide exato e sem sobreposição para cada ID ordenado
run-timeout-seconds: 1..86400
fall-penalty-milliseconds: 0..600000
concurrent-runners: 1..64
hide-other-runners: booleano
```

`locations.exit` pode existir na configuração, mas não é utilizado pelo
assembler atual. Também não existe uma localização `finish`: a conclusão
acontece ao atravessar o último checkpoint da ordem configurada. O operador
deve validar o nome e UUID do mundo, a região de limites, os cuboides e a ordem
num servidor Paper local descartável. Não são guardadas aqui coordenadas reais.

## Resultados e classificações

Os resultados concluídos usam um modo estável do percurso e métricas limitadas
de checkpoints, tempo e vitórias. Saída, desconexão, timeout, fuga ou
restauração falhada produzem `NO_CONTEST` sem classificação. A pontuação só é
registada depois de a restauração ser confirmada.

## Limites de execução

Os listeners e o percurso sintético acima têm prova nativa limitada. O modelo
de movimento dos peers aproxima um chão plano; não prova saltos e colisões de
um percurso construído. Hologramas/DiscordSRV e as restantes variantes
permanecem `UNVERIFIED`. Ver a [matriz de validação](all-minigames-validation.md).
Nenhum display externo é a fonte de verdade para admissão ou resultados.

## Concorrência, ordem e timeout locais — 2026-10-08

No JAR final `0741a3b…`, `parkour-edges` confirmou dois runners concorrentes,
saída com os 18 campos originais, recusa nativa de finish antes do primeiro
checkpoint e `NO_CONTEST` / `TIME_LIMIT` sem completar o percurso. A posição
Paper confirmou a recusa da passagem; a sessão continuou ativa até saída
normal. Os inputs foram cardinais e limitados, sem teleport ou eventos
injetados. Este peer modela chão plano: não prova colisões vanilla ou quedas
verticais. Ver a [matriz datada](all-minigames-validation.md).

## Reset de fronteira no Paper — 2026-10-08

O novo ensaio nativo revelou que cancelar o evento de movimento depois do
teleporte de reset fazia o Paper regressar à posição anterior, junto da
fronteira. O router agora aplica a penalização/reset do controlador e usa a
localização efetiva do jogador como destino do mesmo evento. Não autoriza
movimento fora da região. O primeiro ajuste, ainda com um cancelamento anterior
na mesma rota, não resolveu o sintoma e ficou arquivado.

No candidato `b34b6a033728e6d8bd44fb90e8c373c0c56be95520f2f7f721e568c35c4d4e23`,
`parkour-edges` passou: caminhada limitada para fora, correção nativa e posição
Paper no gate inicial, sessão ainda ativa e saída com os 18 campos exatos.
Concorrência, finish fora de ordem e timeout voltaram a passar; cada resultado
foi ligado ao ID da partida criada pelo cenário. As tentativas falhadas foram
restauradas por autenticação normal e comparadas antes de repetir, sem apagar
recuperações nem reseed antes da verificação. A suite completa mantém 648 testes
(647 aprovados e um skip pré-existente).
