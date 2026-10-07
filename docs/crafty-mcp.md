# Acesso local ao Crafty Controller por MCP

- Documentado: 2026-10-03 (`Europe/Lisbon`; outubro de 2026).
- Âmbito: disponibilidade no posto de trabalho; não descreve nem altera a
  configuração do servidor.
- Ocorrência e verificação: 2026-10-03.
- Estado: `RUNTIME-VERIFIED` para descoberta de ferramentas, acesso HTTPS à API
  `4.10.4`, autenticação, enumeração de servidores e estatísticas. Nenhuma
  mutação de produção foi testada.
- Última verificação da configuração: credenciais repostas e URL corrigido
  para HTTPS em 2026-10-03; enumeração autenticada e estatísticas novamente
  aprovadas. O ficheiro permaneceu inalterado durante a verificação. Uma
  reposição anterior para o template vazio ficou registada no histórico do
  servidor; a origem dessa escrita não foi identificada.

No macOS, `crafty-mcp` versão `0.1.2` está instalado em
`~/.local/share/crafty-mcp`. A configuração MCP global do Codex usa o nome
`crafty`, inicia o cliente com `/opt/homebrew/bin/node` e aponta para
`launch.mjs` nessa pasta. O cliente descobriu 63 ferramentas. A configuração
privada está em `~/.config/crafty-mcp/config.json`, com permissões `0600`.

As ferramentas MCP nativas não estão expostas na conversa Codex atualmente
aberta. O script local `~/.local/share/crafty-mcp/call.mjs` permite chamar uma
ferramenta sem reiniciar o Codex e lê a configuração privada em cada chamada.
Para uma chamada apenas de leitura, `server_list` não recebe argumentos;
`server_get_stats` recebe `server_id` em JSON através do stdin. A disponibilidade
da ligação foi comprovada sem reiniciar a aplicação: a enumeração autenticada
devolveu um servidor e a consulta de estatísticas confirmou-o em execução, sem
jogadores ligados nesse momento. Esta observação datada não garante
disponibilidade futura nem comprova uma entrada Minecraft.

O cliente usa HTTPS. `CRAFTY_ALLOW_INSECURE=true` permite certificados
self-signed; o painel na porta 8443 não funciona por HTTP. As ferramentas
descobertas incluem operações com efeitos, como iniciar, parar ou reiniciar
servidores, executar comandos de consola, editar ficheiros e criar cópias de
segurança. Executar essas operações exige um pedido explícito do responsável.
As permissões efetivas das credenciais ainda não foram verificadas.

O registo operacional canónico, incluindo detalhes privados do endpoint e da
ligação, pertence ao repositório do servidor:
[runbook Crafty MCP](https://github.com/diogor0d/ciaac-server/blob/main/docs/runbooks/crafty-mcp.md).
O destino GitHub é uma referência ao caminho canónico; esta documentação local
no repositório `ciaac-server` ainda não foi publicada.
