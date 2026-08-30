# Parkour de Checkpoints

Estado em 2026-08-24:

- `SOURCE-IMPLEMENTED`: domínio, controlador Paper, tokens de região e
  recuperação através de `SessionCoordinator` estão presentes.
- `RUNTIME-UNVERIFIED`: não foi realizado teste Paper descartável nem
  validação de um percurso em funcionamento.

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

Movimento e teleporte para fora do percurso são recusados. Saída, expulsão,
desconexão, morte, timeout, conclusão e encerramento revogam o token e restauram
o snapshot do jogador. Falhas de limpeza são isoladas; uma restauração incerta
mantém a sessão fechada para recuperação.

## Isolamento

`GameKey.CHECKPOINT_PARKOUR` usa a política `DEDICATED_WORLD`. A região
imutável do participante, o mundo e a ordem de checkpoints devem ser resolvidos
antes da admissão. O inventário, efeitos, localização e restante estado
temporário são protegidos por `SessionCoordinator`; o parkour não concede
itens, moeda ou outra progressão.

O router trata movimento, teleportes, checkpoints, dano, morte, saída, expulsão
e desconexão. Apenas o jogador autenticado da sessão pode avançar.

## Configuração

O resolvedor exige:

```text
checkpoint-order: lista sem repetições, no máximo 64 entradas
checkpoint-regions.<id>: exact cuboid for every ordered checkpoint
start: nested location
finish: nested location
attempt-timeout-seconds: 1..3600
```

O operador deve validar o mundo, UUID, regiões, spawns e a ordem num percurso
descartável. Não são guardadas aqui coordenadas reais.

## Resultados e classificações

Os resultados concluídos usam um modo estável do percurso e métricas limitadas
de checkpoints, tempo e vitórias. Saída, desconexão, timeout, fuga ou
restauração falhada produzem `NO_CONTEST` sem classificação. A pontuação só é
registada depois de a restauração ser confirmada.

## Limites de execução

A ligação Paper dos listeners, a entrega de hologramas/DiscordSRV e a aceitação
de um percurso real permanecem `RUNTIME-UNVERIFIED`. Nenhum display externo é
a fonte de verdade para admissão ou resultados.
