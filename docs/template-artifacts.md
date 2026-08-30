# Artefactos de templates nativos

- Estado: `SOURCE-IMPLEMENTED` em 2026-08-22; `RUNTIME-UNVERIFIED`.
- Âmbito: artefactos só de leitura e revistos pelo operador, consumidos pelos
  adaptadores nativos de reset de Build Battle e de templates de Color Floor.
- Autoridade: o artefacto é uma entrada do operador, não uma captura do mundo
  ativo nem um registo de implantação. O código não fornece uma ferramenta de captura.

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

Este é um contrato de preparação e validação, não um procedimento de implantação:

1. No servidor Paper alvo, registar o UUID exato do mundo e o nome carregado,
   respeitando maiúsculas/minúsculas. Não inferir nenhum valor a partir de uma cópia local.
2. Confirmar os limites configurados. Para Build Battle, calcular a partir de
   todas as regiões de parcelas como faz o assembler; para Color Floor, usar a
   região `floor` resolvida. Manter o volume resultante em 1 000 000 blocos ou menos.
3. Produzir o artefacto de texto através de um processo offline revisto
   independentemente. Não está implementada captura automática ativa nem amostragem no arranque.
4. Garantir que cada coordenada inclusiva tem exatamente uma linha de bloco; no
   Color Floor, acrescentar também exatamente uma linha de cor válida.
5. Calcular o checksum canónico, revê-lo independentemente e colocar o ficheiro
   em `<plugin-data>/templates/<artifact-id>.template` durante uma janela de
   manutenção autorizada.
6. Validar que todos os chunks relevantes já estão carregados antes de tentar
   um reset ou uma ronda Color Floor. Os adaptadores falham de forma segura em
   vez de carregarem chunks a pedido.
7. Verificar os diagnósticos de arranque/módulo e ensaiar reset e restauração num
   mundo descartável antes de considerar uma partida de produção.

Este repositório não deve conter coordenadas ativas, ficheiros de mundos,
segredos, passos de implantação, comando de captura ou percurso de eliminação/
recriação de mundos. Um checksum não autoriza um artefacto nem prova que este
foi revisto.

## Estado das falhas e da verificação

Artefactos em falta, campos malformados, chaves desconhecidas, ficheiros symlink,
ficheiros demasiado grandes, dados de blocos inválidos, cores inválidas,
cobertura incompleta, discrepância de checksum, revisão ou limites,
discrepância de UUID/nome do mundo, chunks descarregados, violações da thread
principal ou escritas interrompidas devem fechar o módulo relevante ou manter a
recuperação, em vez de adivinhar um template.

O código inclui testes específicos dos templates e verificações estáticas, mas
a fase completa Maven/testes está adiada. Nenhum mundo Paper ativo, prontidão de
chunks, instalação de artefacto, reset, alteração Color Floor ou percurso de
recuperação foi
`RUNTIME-VERIFIED`.
