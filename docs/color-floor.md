# Piso das Cores

Última atualização: 2026-10-08.

- `SOURCE-VERIFIED`: domínio, controlador, resolução do modelo e fronteira
  de sessão estão presentes.
- `RUNTIME-VERIFIED`, com âmbito limitado, no candidato
  `6792e1924d90214e3a7c26919eb3cd0d0aaa83c16db0de38157f533129c1dc05`:
  conclusão, saída, desconexão e crash frio foram exercitados em Paper
  descartável com AuthMe e peers sintéticos autenticados. Os 18 campos Paper
  foram restaurados exatamente e a leitura nativa confirmou as 4096 células
  do template após o crash. No ensaio de crash, 3712 células tinham sido
  alteradas para `AIR` antes de `save-all`; a comparação após reinício confirmou
  o template sem reparação feita pelo próprio teste.
- `UNVERIFIED`: cenários locais restantes, instalações reais e prontidão
  operacional. Ver a [matriz datada](all-minigames-validation.md).

## Experiência e comandos

```text
/cores estado
/cores ajuda
/cores entrar
/cores sair
```

A ação `/cores pronto` não é suportada. O estado e as estatísticas pessoais
usam as rotas comuns de `/minijogos`. Todas as mensagens são limitadas a
pt-PT.

## Mecânicas

`ColorFloorGame` executa rondas de reação. Em cada sinal, os jogadores que
estão sobre a cor indicada sobrevivem; os restantes são eliminados de forma
controlada. A paleta configurada tem entre 2 e 7 cores únicas. Cada mudança
valida o modelo imutável antes de alterar qualquer bloco.

O jogo termina ao atingir o número configurado de rondas ou quando existe um
único jogador vivo. A seed e o ID de operação impedem repetições e entradas
antigas. Os blocos são alterados e restaurados no thread principal, em lotes,
sem drops nem carregamento forçado de chunks.

## Isolamento e recuperação

`GameKey.COLOR_FLOOR` usa `SPAWN_SAFEZONE`. O controlador usa
`SessionCoordinator`, um token de admissão da região, teleportes controlados e
restauração do snapshot. O router trata movimento, teleporte, quebra/colocação
de blocos, morte controlada, saída, expulsão e desconexão.

Uma saída direta atualiza também o marcador do wrapper. Falhas de limpeza são
isoladas por módulo; a recuperação da sessão continua a ser a fronteira de
segurança. O inventário, efeitos, localização e demais estado temporário não
podem entrar na progressão normal.

## Configuração

```text
minimum-players: 1..64
maximum-players: minimum..64
rounds: 1..10000
cue-duration-ticks: 1..72000
inter-round-ticks: 1..144000
palette: 2..7 cores suportadas distintas, exatamente iguais ao conjunto de cores do modelo
floor: cuboide
```

O mundo, a região, a paleta e o modelo de blocos devem ser revistos pelo
operador. A montagem falha em segurança se o modelo não existir, estiver
incompleto ou não corresponder ao mundo e à revisão das regras.

## Resultados e classificações

Os resultados usam o modo `ffa` e métricas limitadas `survival_ms`, `rounds`,
`eliminated` e `wins`. Saída, desconexão, encerramento, configuração
inválida e restauração falhada produzem `NO_CONTEST` sem classificação. O
preset público é `survival_ms`, MAX; valores maiores são melhores.

## Limites de execução

A entrega de displays/hologramas e DiscordSRV continua `UNVERIFIED`; a
restauração nativa de blocos foi validada apenas para os cenários delimitados
acima. A classificação e o estado do jogo não dependem de displays externos.
