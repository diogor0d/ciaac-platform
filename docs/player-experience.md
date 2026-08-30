# Experiência do jogador

## Princípios comuns

Cada jogo ativado apresenta a mesma vista compacta:

```text
<nome do jogo> — <estado>
Jogadores: <atuais>/<máximo>
Usa <comando> para entrar
```

Os estados nativos são `Fechado`, `À espera`, `Preparação`, `Em jogo`,
`Votação`, `A terminar` e `A restaurar`. Um módulo indisponível aparece
como `temporariamente indisponível` e não pode ser usado para entrar.

As apresentações são projeções só de leitura. Um clique ou interação chama o
mesmo comando autenticado que o chat; o holograma não concede nada por si só.
Nomes, motivos e textos controlados pelo jogador são tratados como texto
simples, sem MiniMessage, comandos, menções ou placeholders.

## Entrada e preparação

Os comandos públicos são:

```text
/minijogos estado
/minijogos ajuda
/minijogos entrar <jogo>
/minijogos sair
```

A entrada verifica autenticação, permissão, mundo, região e capacidade. O jogo
só altera o estado depois de o snapshot durável e o token de admissão estarem
confirmados. A fila, a composição e a reserva da arena são idempotentes e não
duplicam jogadores após reconnect ou repetição de comandos.

## Isolamento

Cada participante recebe um token limitado à região e ao encontro. Movimento,
teleporte, veículos, entidades, blocos, dano, inventário e comandos passam pela
política do módulo. Um lutador ativo fica no piso admitido; um eliminado fica
na zona pública de espectadores. Jogadores públicos não entram em nenhum desses
estados. Veículos recusados voltam à origem ou expulsam os passageiros.

A fronteira de `SessionCoordinator` guarda inventário, armadura, efeitos,
localização, modo de jogo, voo, saúde, fome e restante estado temporário. Os
jogos não concedem progressão, moeda ou itens sem um contrato explícito.

## Mensagens e apresentações

As mensagens são curtas, em pt-PT, e não revelam stack traces, UUIDs, caminhos,
metadados de itens, credenciais ou IDs de operação. Cada encontro envia apenas
uma mensagem de espera, uma mensagem de início iminente e uma mensagem de
resultado confirmado. Repetições não reiniciam o período de espera.

Os displays da safezone e os consumidores TAB/chat usam projeções e placeholders.
A entrega DiscordSRV é opcional e falhas de entrega não alteram o estado do jogo;
ficam apenas no registo operacional.

## Saída, falhas e recuperação

Saída, expulsão e desconexão executam a limpeza do módulo de forma independente.
O controlador revoga tokens, remove marcadores e restaura o snapshot. Uma falha
de limpeza é registada para operadores sem expor detalhes ao jogador; a
monitorização durável de isolamento permanece a autoridade de recuperação.

Uma morte, reinício, mudança de mundo, timeout, desativação do plugin ou falha
de restauração segue o mesmo percurso fechado. O encontro seguinte só abre
quando todas as sessões estão seguras. Uma sessão em quarentena impede qualquer
resultado classificado.

## Limites de execução

A ligação de listeners Paper, displays reais, hologramas, TAB, chat e DiscordSRV
tem de ser validada em ambiente descartável. A documentação descreve contratos
de código-fonte; não constitui prova de instalação ou funcionamento em produção.
