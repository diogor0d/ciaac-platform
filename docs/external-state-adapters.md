# Adaptadores de estado externo

- Última atualização: 2026-10-08.
- Guards concretos de economia/permissões/claims/homes: `SOURCE-VERIFIED`; aceitação integrada: `UNVERIFIED`.

A CIAACPlatform descobre serviços Bukkit de
[`ExternalStateFacetPort`](../src/main/java/com/ciaac/minecraft/minigames/paper/isolation/ExternalStateFacetPort.java).
Ter Vault, EssentialsX, LuckPerms ou um plugin de claims instalado não regista
esse serviço. O runtime inclui também guards concretos para EssentialsX/Vault e
LuckPerms e SimpleClaimSystem/Essentials; um serviço externo já registado tem
precedência sobre um guard da mesma faceta. O provider Paper de
`TEMPORARY_WORLD_BLOCKS_AND_ENTITIES` integra facetas de
mundo para os nove modos no build revisto; o suporte concreto continua
condicionado à instalação dos providers especializados, listeners saudáveis e
configuração resolvida. A presença do provider não autentica nem admite
jogadores: configuração, conexão, autenticação e todas as verificações de região
continuam obrigatórias. Os ensaios nativos locais documentados abaixo e na
[matriz de validação](all-minigames-validation.md) não aceitam instalações de
produção.

## Autoridades que o minijogo não altera

O [`ReadGuardedExternalStatePort`](../src/main/java/com/ciaac/minecraft/minigames/paper/isolation/ReadGuardedExternalStatePort.java)
captura uma autoridade real que o minijogo não escreve. Entrada, purge e restauro
confirmam que essa autoridade continua igual à captura durável; nunca repõem
um saldo nem nós de permissões. Uma alteração externa, incluindo expiração de
permissões, fecha a recuperação e exige reconciliação. Preserva-se o novo estado
legítimo em vez de o sobrescrever com um snapshot antigo.

Esta política só serve facetas que a partida não modifica. Não fornece
transações distribuídas nem impede todos os plugins externos de alterar estado
entre duas leituras. Uma falha ou corrida posterior pode exigir quarentena.
Um checkpoint exclusivamente de leitura `PENDING` pode repetir a leitura;
isso não autoriza repetir uma escrita externa. Mesmo um replay `COMMITTED`
volta a verificar a autoridade atual antes de ser aceite.

- `essentials-2.22.0-economy-guard`: exige EssentialsX 2.22.0, os mesmos objetos
  de plugin Essentials/Vault ativos e a economia Vault selecionada pertencente
  ao provider nativo Essentials. Exige a inicialização das camadas de economia
  e nenhuma camada externa selecionada. Lê apenas uma conta já presente no
  cache real `ModernUserMap.userCache` por UUID, com UUID da conta e da configuração iguais.
  Não usa `getUser(UUID)` nem `Vault.getBalance()`, que podem carregar ou criar
  contas. A ligação lê o campo privado exato e chama apenas `Cache.getIfPresent`;
  `getOnlineUserCache()` não é populado por esta versão. Campos inacessíveis ou
  tipos incompatíveis fecham o adaptador. Os restantes getters públicos são
  ligados por reflexão limitada à API fixada;
  o snapshot inclui as versões Essentials/Vault e um `BigDecimal` canónico.
- `luckperms-5.5.65-permissions-guard`: exige LuckPerms 5.5.65 e o mesmo serviço
  Bukkit selecionado, pertencente ao plugin. Lê apenas utilizadores e grupos já
  carregados, por UUID/nome confirmado. Captura grupo primário, nós persistentes
  e transitórios e o grafo de grupos herdados, incluindo valor, contexto e
  expiração. Nós expirados são omitidos; nenhum nó é recriado. Grafo, payload,
  número de nós e contextos têm limites e validação canónica.

Não há fallback para nomes, versão incompatível, conta/grupo ausente, provider
substituído ou API em falta. O serviço de economia pode só ficar disponível
depois do tick de inicialização Essentials; a captura verifica a saúde atual.

## Claims e homes — preservação das autoridades carregadas

O [`EssentialsHomesAuthority`](../src/main/java/com/ciaac/minecraft/minigames/paper/isolation/EssentialsHomesAuthority.java)
captura o modelo de homes da conta UUID já carregada, fixado a EssentialsX 2.22.0.
Usa a mesma leitura não carregadora de `ModernUserMap.userCache` da economia.
Não chama `getHome()`: `LazyLocation.location()` pode resolver pelo nome do mundo
e reescrever o UUID guardado. A ligação lê apenas o campo privado conhecido
`UserData.holder` e os getters do modelo dessa versão, sem escrever campos. Esta
dependência de implementação é explícita; uma versão diferente ou campo/API
inacessível fecha o adaptador.

Captura nome, UUID e nome original do mundo, coordenadas, yaw/pitch e a ordem dos
homes, que afeta os aliases numéricos Essentials. Não carrega mundos, nem resolve
UUIDs desconhecidos pelo nome. Referências de mundo legadas sem UUID canónico,
contas ausentes, valores não finitos, duplicados ou limites excedidos são
recusados. Homes de mundos atualmente descarregados continuam representáveis
sem alterar a referência guardada. O payload limita-se a 2048 homes e 3 MiB.

O [`ClaimsAndHomesAuthority`](../src/main/java/com/ciaac/minecraft/minigames/paper/isolation/ClaimsAndHomesAuthority.java)
exige as duas autoridades reais e valida os dois payloads vinculados ao UUID.
O runtime regista a composição como
`scs-1.13.1-essentials-2.22.0-claims-homes-guard`. Homes,
isoladamente, não regista nem satisfaz `CLAIMS_AND_HOMES`.
Um payload não vazio de uma conta real sem homes é distinto de um fornecedor
substituto que ignore estado.

O [`SimpleClaimStateBinding`](../src/main/java/com/ciaac/minecraft/minigames/paper/isolation/SimpleClaimStateBinding.java)
exige o plugin SimpleClaimSystem ativo, os mesmos objetos nativos carregados e
a versão interna **1.13.1**. O JAR obtido por leitura do backend em 2026-10-04
declara esta versão e tem SHA-256
`e9c5cfe9c3b39f5f9e13bc122c5b427109fe37b57f5e43d0d559c24a9ef1acab`.
O asset oficial anteriormente ensaiado declarava 1.13.0.9; não é o mesmo
artefacto. A comparação binária confirmou os getters/modelos de claims usados
pelo adaptador, mas não prova a versão carregada nem a prontidão no backend.
O ID do guard e a versão no payload também mudaram: snapshots experimentais
1.13.0.9 são recusados, sem migração automática ou substituição de evidência.
Não inicializa o singleton da API nem usa
`getCPlayer`, que pode criar um registo em cache ou resolver nomes.
Os getters do plugin são ligados por assinatura exata com `MethodHandles`:
`Class.getMethod` nesta versão pode tentar resolver também integrações opcionais
de mapas ausentes e falhar antes de chegar ao getter de claims.

Captura claims em que o UUID é proprietário, membro ou banido. A identidade é
o par **UUID do proprietário + número da claim**, porque os números não são
globais. Preserva nomes, descrição, mundo/posição, chunks, membros, bans,
permissões explícitas falsas, venda/preço e a política real de grupos/overrides
por UUID. A ordem das regras de grupo é preservada. O snapshot canónico limita
claims a 4096, elementos agregados a 100 000 e bytes a 4 MiB; a composição com
homes limita-se a 7 MiB.

O índice carregado é comparado com os pares persistidos em `scs_claims_1`, por
um `SELECT` no datasource existente, sem ler credenciais ou escrever na base.
Uma claim persistida ausente do índice, duplicados ou uma falha da consulta
recusam a captura; um índice vazio durante carregamento não é aceite como prova
de ausência de claims. Folia é recusado porque esta versão não publica uma
barreira de conclusão do carregamento assíncrono. O limite da consulta é de
65 536 claims globais, com timeout de statement de dois segundos; esse timeout
não limita a espera para obter uma conexão do pool. Confirmar essa latência no
backend antes de ativar. A comparação não é uma transação distribuída: alterações
externas concorrentes podem exigir quarentena. Os guards não recriam claims,
homes nem quotas antigas sobre alterações legítimas.

## Contrato presente

Cada fornecedor declara contrato versão 2, ID estável, versão positiva do
snapshot, facetas sem sobreposição e disponibilidade. Captura bytes limitados
a 8 MiB e implementa `validateRestore(context, version, payload)` sem mutação.
Fornecedores legados, sem contexto ou sem validação, são recusados.

O [contexto de operação](../src/main/java/com/ciaac/minecraft/minigames/isolation/PlayerStateOperation.java)
transporta fase, IDs da operação e captura, snapshot, sessão, partida, jogador,
jogo, instante de captura e épocas capturada/atual da conexão. O gateway verifica
a autenticação atual e a entidade Paper exata. Entrada temporária exige a conexão
capturada; purge/restauro permitem uma reconexão atualmente autenticada. Um UUID
de conexão, por si só, não prova autenticação.

O handler valida a identidade, versão, catálogo e envelope do fornecedor, e
delega a validação dos bytes internos. A captura só devolve um envelope depois
de validar os bytes capturados. Antes de entrada temporária, purge ou restauro,
o gateway confirma todas as facetas, rejeita bytes diferentes para facetas do
mesmo handler e valida todos os payloads antes de procurar o jogador ou aplicar
qualquer mutação. Estas operações e a captura exigem a thread principal Paper.
As validações recebem cópias defensivas dos bytes usados no restauro.

Esta pré-validação evita uma mutação causada por payloads que já eram inválidos
no início da operação. Uma falha posterior de Bukkit, armazenamento ou plugin
externo continua a poder produzir estado parcial e exigir quarentena.

## Persistência e compatibilidade

Snapshots novos usam schema 2: guardam a época capturada e o instante com
nanosegundos. Isto corrige a divergência entre o instante original persistido
em SQLite e o envelope antigo, que o truncava a milissegundos. O coordenador
valida a correlação devolvida pela captura antes de a persistir e a propriedade
do snapshot ligado à sessão antes de purge/restauro.

O codec continua a ler schema 1 sem alterar os seus bytes. Esses snapshots não
ganham uma época inventada; os envelopes externos antigos não são aceites pelo
handler versão 2. Não os reescrever para contornar uma quarentena. O restauro
de facetas Bukkit legadas continua dependente da autenticação atual e de todas
as restantes verificações.

O [journal externo](../src/main/java/com/ciaac/minecraft/minigames/persistence/ExternalOperationJournal.java)
implementa SQLite próprio, schema 1, WAL e sincronização `FULL`, num diretório
privado exclusivo. Regista intenção durável `PENDING` antes de uma mutação e
resultado `COMMITTED` apenas depois de prova do efeito. Um retry pendente não
autoriza repetir a mutação. O digest vincula fornecedor/versão, fase, identidade
capturada e payload; uma reconexão autenticada não altera essa identidade.
Resultados têm checksum, limite de 8 MiB e cópias defensivas. O instante de commit
é persistido e usado no evento de auditoria idempotente; uma falha da auditoria
depois do commit pode ser reparada por replay sem inventar outro instante.
Paths com symlinks, incluindo sidecars SQLite,
stores sem versão já preenchidos e versões futuras são recusados. Em POSIX,
diretório e ficheiro usam `0700`/`0600`. Os dados ficam fora de Git.

Esta infraestrutura passou ensaios SQLite de reabertura, intenção incompleta,
replay confirmado, conflitos e corrupção. Está ligada ao ciclo de vida do
runtime e aos guards de economia/permissões/claims/homes. O formato de desenvolvimento
schema 1 agora exige `committed_at`; uma base antiga sem essa coluna é recusada,
sem migração ou timestamp inventado. A versão anterior não tinha sido ligada ao
runtime. Conservar qualquer store experimental antigo para análise, sem o usar
como prova de captura válida.

Testes de guards com autoridades simuladas demonstram replay, drift e auditoria;
não provam, por si, APIs carregadas, login ou partidas reais. A política de
recuperação e aceitação de cada fornecedor continua obrigatória.

## Lacunas antes de um fornecedor utilizável

Completar as autoridades ainda em falta e testar APIs reais e falhas em cada
fronteira de mutação/commit. Nenhuma faceta em falta pode
ser preenchida por um fornecedor vazio.

| Faceta obrigatória | Candidato observado na listagem remota de JARs em 2026-10-04 (histórico) | Estado então observado |
| --- | --- | --- |
| `ECONOMY` | Vault e EssentialsX 2.22.0 | Guard de conta UUID carregada implementado; aceitação integrada ainda por verificar. |
| `PERMISSIONS` | LuckPerms 5.5.65 | Guard de utilizador/grafo carregado implementado, sem escritas de privilégios; aceitação integrada ainda por verificar. |
| `CLAIMS_AND_HOMES` | SimpleClaimSystem 1.13.1 e EssentialsX 2.22.0 | Guard dos modelos internos SCS 1.13.1/Essentials 2.22.0 implementado; identidade carregada remota e aceitação integrada ainda por verificar. |
| `TEMPORARY_WORLD_BLOCKS_AND_ENTITIES` | Paper 26.2 build 84, inicialmente apenas `ARENA` | Estado histórico: provider e listener ligados ao runtime e fixture de bootstrap/callbacks; outros jogos ainda não dispunham desta faceta nesse perfil. Ver a atualização de 2026-10-08 abaixo para a cobertura atual. |

## Cobertura runtime atual — 2026-10-08

O [`ArenaWorldStatePort`](../src/main/java/com/ciaac/minecraft/minigames/paper/isolation/ArenaWorldStatePort.java)
é o provider de `TEMPORARY_WORLD_BLOCKS_AND_ENTITIES`. A cobertura declarada
inclui `ARENA`, `KNOCKBACK_SUMO`, `HOT_POTATO`, `CHECKPOINT_PARKOUR`,
`ARCHERY_RANGE`, `COLOR_FLOOR`, `ANVIL_DODGE`, `ELYTRA_RINGS` e `BUILD_BATTLE`.
Os quatro últimos dependem de providers especializados não nulos, que o
[`MinigamePlatformRuntime`](../src/main/java/com/ciaac/minecraft/minigames/bootstrap/MinigamePlatformRuntime.java)
instala antes de publicar o handler. A cobertura corresponde aos contratos
limitados de isolamento por modo, não a um snapshot ou restauro integral do
mundo.

Os contratos nativos são distintos por modo: Arena e Arco guardam manifests das
regiões possuídas; Sumo, Batata Quente e Parkour capturam uma fronteira imutável
de região/mundo; Chão de Cores usa o template e ledger das células; Bigornas
acompanha perigos do plugin; Elytra acompanha o curso e os foguetes próprios;
Build Battle acompanha e repõe apenas as células pertencentes aos plots. As
operações passam pelo journal e pela validação de identidade/estado específica
de cada provider. A faceta não autoriza alterações de terreno fora dessas áreas
e contratos.

A implementação só fica disponível no servidor Paper oficial 26.2, build 84,
commit `26e81c4`, e depois de instalar os listeners centrais de região e ciclo
de vida. `available()` continua a exigir lifecycle pronto, sem falha ambígua e
correspondência exata desse build. Autenticação, conexão, configuração,
admissão e as outras facetas obrigatórias continuam gates independentes.

A [matriz datada](all-minigames-validation.md) regista ensaios nativos locais
limitados dos nove modos no candidato `6792e19…`. No JAR final
`0741a3b…`, só mudou a classe da ajuda da consola comparada com aquele
candidato; novos ensaios também aceitaram as fronteiras de Parkour e os quatro
score bands de Archery. Isto não declara cobertura integral de variantes nem
aceitação das instalações de produção.

Os nomes e versões acima são evidência de ficheiros observados em 2026-10-04,
não prova de API carregada ou de compatibilidade. Restaurar indiscriminadamente
um saldo ou todos os nós de permissões pode desfazer alterações legítimas de
outros sistemas. O fornecedor tem de delimitar o estado que possui e tornar
falhas ambíguas explícitas.

## Aceitação mínima

Testar com identidades e dados sintéticos: captura/restauro sem perda, operações
repetidas, mudança de conexão, UUID errado, fornecedor desativado, mudança de
versão e crash antes/depois de cada mutação e commit. Uma tentativa ambígua
mantém a sessão fechada até reconciliação; não instalar fornecedores vazios
para abrir a admissão.

Os testes de payload, identidade, codec, journal e gateway encontram-se no
[registo funcional](functional-verification.md). A aceitação completa dos
fornecedores continua pendente.

## Perfil inicial do isolamento da Arena — histórico, 2026-10-04

O [`ArenaWorldManifest`](../src/main/java/com/ciaac/minecraft/minigames/paper/isolation/ArenaWorldManifest.java)
conserva duas regiões Arena imutáveis, disjuntas e do mesmo UUID: combate e
espectadores. O codec é canónico, limitado, com UTF-8 estrito e rejeição de
geometria inválida. O [`ArenaWorldLedger`](../src/main/java/com/ciaac/minecraft/minigames/persistence/ArenaWorldLedger.java)
guarda a identidade integral da captura, checksum do manifest, fases e UUIDs
únicos de arrows. Exige schema/constraints exatos, `quick_check` e
`foreign_key_check`; estados experimentais incompatíveis não são migrados.

O [`ArenaProjectileOwnership`](../src/main/java/com/ciaac/minecraft/minigames/paper/isolation/ArenaProjectileOwnership.java)
regista intenção antes da inserção nativa, exige etiquetas completas, mundo/tipo
exatos, persistência desligada e pickup proibido. Confirma adição e remoção por
identidade; uma ausência sem prova de lifecycle continua a bloquear purge.
Prevalida todas as entidades antes de apagar uma e nunca faz limpeza por área.
O [`ArenaWorldStatePort`](../src/main/java/com/ciaac/minecraft/minigames/paper/isolation/ArenaWorldStatePort.java)
combina o manifest, ledger e journal, exigindo purge concluído antes de restauro.
O port não restaura blocos nem reivindica uma cópia integral do mundo: esta
faceta conserva a política imutável das regiões e acompanha apenas projéteis
normais/espectrais que o ledger atribui à sessão.

O [`MinigamePlatformRuntime`](../src/main/java/com/ciaac/minecraft/minigames/bootstrap/MinigamePlatformRuntime.java)
cria o ledger privado em `plugins/CIAACPlatform/arena-world` e regista o handler
antes de montar os módulos, mas apenas em Paper **26.2 build 84, commit
`26e81c4`**. Regista o
[`ArenaProjectileLifecycleListener`](../src/main/java/com/ciaac/minecraft/minigames/paper/isolation/ArenaProjectileLifecycleListener.java)
depois dos listeners centrais de região e antes da montagem dos módulos, e só
marca o provider disponível após instalar o listener. A cobertura declarada é
exclusivamente `ARENA`; os outros jogos continuam sem a faceta temporária e
indisponíveis sob a política estrita. A montagem ainda pode fechar a Arena por
configuração incompleta ou por qualquer outra condição de admissão.

O listener liga lançamentos à conexão autenticada e à admissão atual. Uma falha
ambígua invalida o provider pelo resto do runtime e encaminha a sessão para
`WORLD_ISOLATION_FAILURE`. O encerramento exige a thread principal, remove o
listener, invalida o provider e faz callbacks já agendados ignorarem trabalho;
durante `onDisable` não agenda novas reconciliações. Para o unload nativo de
uma entidade não persistente, o índice UUID pode ficar vazio enquanto o objeto
continua vivo. O callback valida primeiro o objeto e a etiqueta contra o ledger,
descarta só essa entidade e grava remoção após confirmar estado morto, inválido
e ausência do índice. Uma consulta UUID vazia, sozinha, nunca prova remoção.

O probe local confirmou callbacks de inserção, remoção direta, hit em bloco e
tracking-end de chunk, preservando um projétil exterior durante o purge. O
probe de bootstrap confirmou exatamente um listener de produção, o listener
de regiões, o provider disponível apenas para `ARENA` e o ficheiro do ledger
privado. Estes ensaios não autenticam um jogador nem provam admissão, combate
real, restauro de uma sessão, recuperação após crash ou implantação. No fixture
nativo, a configuração/admissão da partida manteve-se fechada. A aceitação integrada e a
implantação continuam por fazer.

O catálogo de isolamento passa a declarar jogos suportados. A disponibilidade
de uma faceta exclusivamente Arena não declara suporte aos restantes jogos;
a montagem e admissão consultam a cobertura do jogo concreto. O default dos
fornecedores existentes mantém o catálogo global por compatibilidade, enquanto
o novo provider declara apenas `ARENA`. Autenticação e todas as facetas da
política estrita continuam obrigatórias.

O probe local passou com arrows reais e com as fases do provider/journal,
incluindo reabertura, replay, recusa de restauro prematuro e auditoria única.
Usa identidades sintéticas; o ensaio direto chama readiness apenas no fixture.
O ensaio separado de bootstrap confirmou os listeners e provider registados
pelo runtime real, sem simular autenticação ou admissão. A proteção de regiões também
nega interação de blocos, pressão de entidades, ignição, formação, crescimento
que cruza a fronteira e alteração de redstone. A utilização de bow/shield não
é negada por essa proteção de blocos. Ver o
[checkpoint funcional](functional-verification.md) para hashes e limites.


## Sumo e Batata Quente: instalações imutáveis — estado de 2026-10-07

Este registo documenta a extensão inicial a estes dois modos. A cobertura
reportada aqui foi depois ampliada aos nove modos, conforme a secção de
2026-10-08 acima; as declarações de ausência para os outros jogos descrevem o
estado naquele momento.

A cobertura observada em 2026-10-07 incluía `ARENA`, `KNOCKBACK_SUMO` e
`HOT_POTATO`. A descrição anterior de cobertura exclusiva da Arena é histórica.
`ImmutableMinigameWorldState` usa um manifest distinto, limitado e ligado à
captura, com identidade do jogo, região, papel, UUID do mundo, limites de altura
e versão exata do servidor. Capture/enter/purge/restore usam o journal durável;
replays e restauro exigem a mesma política de proteção. Não é um snapshot de
blocos nem um serviço de reconstrução de terreno.

Estes dois jogos não criam blocos ou entidades do mundo. Os listeners de região
e isolamento continuam obrigatórios; projéteis lançados por participantes
isolados são recusados. Mantêm-se todos os outros adaptadores e os gates de
autenticação, recuperação e compatibilidade. Mudança de região/mundo/versão
com sessões pendentes fecha a recuperação em vez de inventar um rollback.
Os seis modos que exigem terreno ou entidades próprios continuam fechados
quando falta a respetiva faceta; esta extensão não os aceita.

A ativação de produção aguarda instalações construídas, configuração real e
aceitação do local. Os testes de journal e identidade provam o contrato de
origem; não substituem os ensaios nativos de jogabilidade.

## Coordenação opcional com Multiverse-Inventories — 2026-10-07

Durante isolamento, incluindo recuperação autenticada, CIAAC cancela os quatro
hooks públicos de leitura/escrita de perfis do Multiverse-Inventories. Isto
impede que um teleporte ou alteração de modo de jogo substitua o inventário já
restaurado, ou grave equipamento temporário num perfil de sobrevivência.
Jogadores sem sessão isolada mantêm o comportamento normal do fornecedor.

A ligação opcional verifica a identidade do fornecedor **5.3.5**, os bytes das
classes críticas, métodos e HandlerLists independentes. Mudança de versão,
perda ou substituição do fornecedor fecha os preflights de captura e restauro;
não se aceita a nova implementação apenas porque expõe métodos semelhantes.
Sem o fornecedor no arranque, a ligação é opcional; ativá-lo posteriormente
exige reinício/revisão. A aceitação local de Batata Quente confirma a entrada e
saída entre mundos com inventário, equipamento e XP originais preservados.
A configuração/perfis persistentes do Multiverse não são desativados nem
substituídos por este adaptador.
