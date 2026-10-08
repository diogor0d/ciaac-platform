# Artefactos de templates nativos

- Estado em 2026-10-07: captura controlada e provider/ledger do Piso das Cores
  `SOURCE-VERIFIED`; conclusão, saída, desconexão e crash passaram nos casos
  nativos delimitados descritos abaixo. A captura de 96 células e a captura
  alargada de 4096 células foram lidas de volta no Paper local. O candidato atual
  também passou um crash frio depois de 3712 células azuis confirmadas em AIR,
  com as 4096 células e o estado dos jogadores restaurados.
- Candidato Color Floor `e53c89f00e1ec08879ad6dad8a74a0d3722d69b548b93e0e04ee96b1eb263d3f`:
  548 testes, 547 aprovados, um ignorado, zero falhas/erros. Candidato geral
  mais recente `7ff23154714b105720bf1fd376deed69af4f0897267a880395f4733d331a6dd2`:
  563 testes, 562 aprovados, um ignorado, zero falhas/erros.
- Âmbito: artefactos só de leitura e revistos pelo operador, consumidos pelos
  adaptadores nativos de reset de Build Battle e de templates de Color Floor.
- Autoridade: o artefacto é uma entrada do operador, não um registo de
  implantação. Há captura controlada do Piso das Cores a partir do mundo
  carregado; continua sem existir ferramenta de captura de Build Battle.

## Localização e propriedade

Os adaptadores nativos leem apenas este diretório de dados do plugin:

```text
<plugin-data>/templates/<artifact-id>.template
```

O assembler atual cria o repositório em
`plugin.getDataFolder()/templates`. Os IDs de artefacto são nomes de ficheiro
limitados; traversal de caminhos, ficheiros symlink, ficheiros em falta e
ficheiros acima do limite de leitura de 64 MiB são rejeitados. O repositório
nunca escreve artefactos nem lê dados de blocos ativos durante o carregamento.
O comando de preparação do Piso das Cores escreve separadamente um novo
artefacto nesse diretório, em modo create-only, e exige leitura posterior
validada pelo repositório. Nunca substitui um ficheiro existente.

O checksum é uma verificação de integridade, não uma assinatura. A revisão
humana do operador, o procedimento de manutenção e a identidade do servidor
alvo continuam a ser responsabilidades separadas.

## Formato do ficheiro

O formato é texto UTF-8 orientado por linhas. Linhas vazias e linhas que começam
por `#` são ignoradas. Os seguintes campos escalares são obrigatórios exatamente uma vez:

```text
format=ciaac-template-v1
artifact-id=<bounded-id>
revision=<bounded-revision>
world-uuid=<exact-world-uuid>
world-name=<exact-loaded-world-name>
bounds=<world-uuid>,<minX>,<minY>,<minZ>,<maxX>,<maxY>,<maxZ>
checksum-sha256=<64 lowercase hexadecimal characters>
```

Cada linha de bloco é:

```text
block=<x>,<y>,<z>|<Bukkit BlockData string>
```

Color Floor também exige uma linha de cor para cada linha de bloco:

```text
color=<x>,<y>,<z>|<FloorColor name>
```

O código aceita os nomes de cores do domínio `RED`, `BLUE`, `GREEN`, `YELLOW`,
`PURPLE`, `ORANGE` e `CYAN` (a capitalização é normalizada durante a análise do
artefacto). O Build Battle normalmente omite linhas de cor. Chaves escalares
desconhecidas, campos escalares duplicados, coordenadas duplicadas, coordenadas
malformadas, identificadores sem limites, separadores de linha dentro de
valores, cores não suportadas e valores de dados de blocos que o Paper não
consiga analisar são inválidos.

## Checksum canónico

A entrada SHA-256 é texto canónico UTF-8, montado pelo código-fonte pela
seguinte ordem:

```text
artifact-id\n
revision\n
world-uuid\n
world-name\n
<bounds-world-uuid>|<minX>|<minY>|<minZ>|<maxX>|<maxY>|<maxZ>\n
```

Depois, cada coordenada `block` é ordenada por X, Y e Z e contribui com:

```text
block|<x>|<y>|<z>|<block-data>|<color-or-empty>\n
```

`checksum-sha256` é o digest SHA-256 hexadecimal em minúsculas dessa sequência
exata de bytes. O repositório recalcula o digest antes de expor o artefacto a um
adaptador Paper. Uma linha, coordenada, nome de mundo, limite, revisão, ID do
artefacto ou cor alterado muda o checksum.

## Limites e identidade

Todas as coordenadas têm de estar dentro de `bounds`, e cada artefacto tem de
cobrir o cuboide inclusivo completo, em vez de um subconjunto esparso. O código
limita tanto o volume como as linhas de blocos a 1 000 000 blocos. O UUID do
mundo do volume do artefacto tem de ser igual ao seu campo `world-uuid`.

No carregamento nativo, os valores esperados durante a execução têm de coincidir exatamente:

- ID do artefacto e revisão configurada;
- UUID e nome exato do mundo carregado;
- limites da `CuboidRegion` configurada, incluindo o UUID do mundo;
- checksum recalculado e cobertura integral do volume.

Os adaptadores não substituem um mundo com o mesmo UUID por outro com nome
diferente. Qualquer discrepância devolve indisponível/vazio e deixa o módulo
fechado ou em recuperação.

## Contrato da Batalha de Construção

O resolver atual expõe o `worldTemplateMarker` configurado como ID do
artefacto. O `ConfiguredModuleAssembler` constrói o fallback de reset nativo
com esse ID e a revisão de ruleset configurada. O seu volume de reset é o
o cuboide delimitador de todas as regiões de parcelas configuradas, pelo que um
artefacto tem de conter
cada bloco na caixa X/Y/Z min/max completa, incluindo o espaço aparentemente
vazio entre parcelas. O código rejeita um artefacto parcial em vez de deixar
silenciosamente blocos construídos por jogadores fora das linhas listadas.

O reset começa apenas depois da fase de resultados e usa o contrato assíncrono
`BuildBattleResetPort`. A porta nativa valida todos os dados de blocos do
artefacto e a prontidão inicial dos chunks, aplicando depois no máximo o lote
configurado por tick da thread principal. O assembler liga atualmente 4 096
blocos por tick. Não usa física nem emite drops de blocos; nunca força o
carregamento de chunks nem elimina um mundo. Um serviço de reset personalizado
registado pode substituir o fallback, mas o controlador continua a esperar uma
conclusão limitada de início/consulta e uma falha segura.

## Contrato do Piso de Cores

O resolver atual fornece o ID de transferência nativo
`immutable-floor-template` através de `floorTemplate.identifier()`. O assembler
passa esse ID a `PaperColorFloorTemplatePort` e exige depois que a revisão e os
limites do artefacto coincidam com o ruleset resolvido e a região `floor`.
Um `ColorFloorTemplatePort` registado e disponível pode substituir o fallback
nativo, mas tem de manter a mesma fronteira de template imutável e de falha
segura. Se não for possível carregar nem o artefacto nativo nem um serviço
registado disponível, o assembly falha de forma segura com
`COLOR_TEMPLATE_UNAVAILABLE`.

Color Floor exige coordenadas `block` e `color` exatamente correspondentes,
uma a uma, e cobertura do volume completo. O seu controlador altera apenas
células de template validadas. Cada alteração e restauração ocorre na thread
principal e em lotes; os chunks nunca são forçados. Antes de uma alteração, o
bloco ativo tem de ser igual ao template imutável. Antes da restauração, o bloco
tem de continuar no estado temporário esperado ou já estar exatamente no estado
do template. Um bloco inesperado, chunk descarregado, lote interrompido ou
verificação falhada deixa a recuperação pendente e a admissão fechada. As
alterações não usam física nem criam drops normais de blocos.

## Preparação do operador

Este é um contrato de preparação e validação, não um procedimento de ativação:

1. No servidor Paper alvo, registar o UUID exato do mundo e o nome carregado,
   respeitando maiúsculas/minúsculas. Não inferir nenhum valor a partir de uma cópia local.
2. Confirmar os limites configurados. Para Build Battle, calcular a partir de
   todas as regiões de parcelas como faz o assembler; para Color Floor, usar a
   região `floor` resolvida. Manter o volume resultante em 1 000 000 blocos ou menos.
3. Para Build Battle, produzir o artefacto de texto por um processo offline
   revisto independentemente; não existe ferramenta de captura desse mundo.
   Para Piso das Cores, usar o comando local descrito abaixo, que amostra apenas
   as células configuradas e atualmente carregadas.
4. Garantir que cada coordenada inclusiva tem exatamente uma linha de bloco; no
   Color Floor, acrescentar também exatamente uma linha de cor válida.
5. Para Build Battle, calcular e rever o checksum e colocar o ficheiro em
   `<plugin-data>/templates/<artifact-id>.template` durante manutenção
   autorizada. Para Piso das Cores, a ferramenta calcula o SHA-256, cria o
   artefacto `immutable-floor-template` apenas se o destino ainda não existir e
   confirma a leitura pelo repositório antes de reportar sucesso.
6. Validar que todos os chunks relevantes já estão carregados antes de tentar
   um reset ou uma ronda Color Floor. Os adaptadores falham de forma segura em
   vez de carregarem chunks a pedido.
7. Verificar os diagnósticos de arranque/módulo e ensaiar reset e restauração num
   mundo descartável antes de considerar uma partida de produção.

Este repositório não deve conter coordenadas ativas, ficheiros de mundos,
segredos ou percurso de eliminação/recriação de mundos. Um checksum não
autoriza um artefacto nem prova que este foi revisto.

### Comandos locais de preparação

Os comandos estão autorizados apenas pela consola local do servidor e pela
thread principal. Não exigem `ciaac.minigames.admin` nem uma concessão de
permissão: jogadores, command blocks e consola remota são sempre recusados.
A grafia do comando raiz inclui o acento:

```text
/ciaac instalações mundo <nome-exato>
/ciaac instalações validar
/ciaac instalações capturar-cores
```

`mundo` consulta apenas mundos já carregados e devolve o nome exato, UUID e
alturas mínima/máxima. `validar` mostra a resolução da configuração de origem e
a disponibilidade corrente dos módulos; não executa mecânicas, testes de
recuperação ou aceitação nativa.

`capturar-cores` exige configuração válida e explicitamente ativada para o
Piso das Cores, revisão/resolução de mundo coerentes e ausência de qualquer
sessão ativa, recuperação pendente ou quarentena. A captura só lê chunks já
carregados, uma grelha de uma camada com até 4096 células, cada uma composta por
lã ou betão de uma das sete cores permitidas e pertencente à paleta configurada.
Não carrega chunks nem altera blocos. O comando cria apenas
`immutable-floor-template.template`, calcula e valida o SHA-256 e confirma a
leitura do artefacto pelo repositório. Destino existente ou caminho inseguro
recusa a operação; não há opção de sobrescrita.

Verificação no Paper local: a consola capturou e validou por leitura posterior
templates de exatamente 96 e 4096 células numa camada, sem edição manual de UUID
ou checksum. No candidato Color Floor acima, conclusão nativa produziu
`VICTORY` / `COMPLETED`; saída produziu `PLAYER_LEFT`; e desconexão produziu
`PLAYER_DISCONNECTED`. Os 18 campos foram restaurados exatamente em cada caso e
as 96 células foram comparadas nativamente com o template. Um crash após AIR
nativo também restaurou dois jogadores e comparou as 96 células. No ensaio de
4096 células no candidato `e53c89f…`, a captura e leitura do artefacto passaram;
após crash com AIR nativo observado, os 18 campos foram restaurados e todas as
4096 células foram comparadas positivamente com o template. Esse checkpoint
anterior não guardou o número exato de células AIR. No candidato atual
`7ff23154714b105720bf1fd376deed69af4f0897267a880395f4733d331a6dd2`, uma
referência nativa de 4096 células AIR foi preparada em Y=120; o comando
`execute if blocks` confirmou que os 3712 blocos azuis da secção completa do
piso estavam em AIR antes de `save-all` e SIGKILL apenas do subprocesso Paper.
Após reinício frio e login
AuthMe normal, os 18 campos de cada jogador foram restaurados, as 4096 células
foram comparadas nativamente com o template e 12 sessões ficaram `CLOSED` com
12 snapshots `RESTORED`. O ensaio comprova recuperação fria após remoção nativa
superior a 256 blocos nessa secção; a evidência não generaliza para outros
cenários ou declara prontidão global.

A tentativa anterior que deixou duas sessões `QUARANTINED` por rejeição do
teleporte de restauro mantém-se arquivada como evidência histórica; os ensaios
posteriores usaram runtime novo, sem limpar artificialmente a quarentena antiga.
Uma captura duplicada recusou corretamente o artefacto existente sem o
substituir. A tentativa de preenchimento sem confirmação de chunks carregados
foi recusada; a captura alargada só foi repetida depois de o fixture observar a
carga dos chunks. A ferramenta de Build Battle continua em falta, e os casos
nativos delimitados não constituem declaração de prontidão global.

Para uma instalação nova, mantém os outros módulos fechados, configura
explicitamente `admission.enabled: true` e `modules.color-floor.enabled: true`
com geometria válida, e inicia o servidor normalmente. Sem o template, o modo
continua fechado. Depois de carregar a região e os chunks relevantes, executa
`capturar-cores` na consola local quando todas as sessões e recuperações
estiverem drenadas. Faz um reinício normal e controlado para carregar o novo
artefacto; não uses `/reload` nem esperes ativação implícita. Depois consulta
`validar` e confirma a disponibilidade/configuração reportadas. Esse resultado
não substitui aceitação nativa, testes de jogo/restauro ou a suite completa.

## Estado das falhas e da verificação

Artefactos em falta, campos malformados, chaves desconhecidas, ficheiros symlink,
ficheiros demasiado grandes, dados de blocos inválidos, cores inválidas,
cobertura incompleta, discrepância de checksum, revisão ou limites,
discrepância de UUID/nome do mundo, chunks descarregados, violações da thread
principal ou escritas interrompidas devem fechar o módulo relevante ou manter a
recuperação, em vez de adivinhar um template.

Em 2026-10-07, os 24 testes focados do fluxo de captura/exportação e as suites
dos candidatos indicados acima passaram. A captura real, os casos nativos
delimitados e as comparações descritas acima passaram no Paper local. Build
Battle continua sem ferramenta de captura.
