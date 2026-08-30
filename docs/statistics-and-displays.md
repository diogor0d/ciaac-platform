# Estatísticas e apresentações

## Contrato de resultados

Cada encontro ou execução terminal tem um UUID imutável `result_id`. O envelope
`MatchResult` contém jogo, revisão das regras, modo, temporada, motivo, métricas
e colocações. Os mapas são defensivamente imutáveis e cada jogador pode ter no
máximo 32 métricas; as chaves têm no máximo 32 caracteres e os valores são
inteiros limitados.

Os estados do resultado são `VICTORY`, `DRAW`, `NO_CONTEST`,
`CANCELLED` e `ABORTED`. Uma vitória de equipa pode ter vários vencedores
quando partilham o mesmo ID de equipa. Empates têm lugares iguais e não inventam
um vencedor; resultados sem competição, cancelados ou abortados não entram na
classificação.

## Persistência e idempotência

O contrato `StatisticsRepository` aplica cada resultado uma única vez. Uma
repetição idêntica não tem efeito; um payload diferente para o mesmo ID fecha o
processamento e conserva a evidência. `SqliteStatisticsRepository` fornece
reprodução transacional de resultados imutáveis. Falhas ou ausência do
repositório deixam o resultado sem confirmação e nunca o apresentam como
classificado.

Os nomes apresentados são dados mutáveis e nunca são a chave do registo. Linhas
de classificação não guardam endereços IP, segredos nLogin, identidades Discord,
NBT de itens nem snapshots de inventário.

## Projeções e classificações

A projeção em memória suporta filtros exatos por jogo, regras, modo e temporada,
janelas de tempo, agregações `SUM`/`AVERAGE`/`MAX`/`MIN`, ordenação
crescente ou decrescente, amostras mínimas, lugares de competição em empates,
ordenação estável por UUID e a posição do jogador fora do limite visível.

Cada predefinição pública usa um âmbito exato
`game + ruleset + mode + metric`; regras ou modos incompatíveis nunca são
agregados implicitamente. Só resultados classificados entram na projeção.
As métricas comuns incluem encontros, conclusões, vitórias, derrotas, empates,
forfaits, pódios, streaks e data da última participação; cada jogo pode definir
métricas adicionais.

Os modos estáveis são definidos pelo jogo: formato da arena e equipamento,
`1v1` para Sumo, `solo` para Build Battle, Parkour, Archery e Elytra, e
`ffa` para Hot Potato, Anvil Dodge e Color Floor. A temporada por predefinição
é `unseasoned`; uma temporada explícita só entra através do envelope de
resultado.

## Comandos e apresentações

```text
/minijogos top <jogo> [regras modo]
/minijogos estatisticas <jogo> [regras modo]
```

Sem identificadores explícitos, é escolhida automaticamente uma única projeção
disponível. Se não existir, é mostrada uma mensagem pt-PT sem resultados; se
existirem várias, a agregação é recusada e são sugeridos comandos exatos.

As classificações no jogo mostram:

- posição, nome atual, pontuação e amostras;
- jogo, regras, modo, métrica e temporada;
- estado do encontro e momento da atualização;
- empates com o mesmo lugar.

Os nomes são limitados e apresentados como texto, nunca interpretados como
MiniMessage, comandos, menções ou placeholders. A resolução de nomes ocorre no
momento da apresentação; o UUID permanece na persistência.

Cada módulo expõe uma vista de apresentação só de leitura:

```yaml
enabled: true
game: arena
metric: wins
aggregation: SUM
order: DESC
join-command: /coliseu entrar
```

Displays Paper, hologramas, TAB e DiscordSRV são consumidores de projeções; não
são a fonte de verdade para admissão, resultados ou persistência. A entrega real
e as políticas de retenção, correção e eliminação permanecem
`RUNTIME-UNVERIFIED`.
