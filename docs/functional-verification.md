# Verificação funcional e critérios de aceitação

Este documento separa o que está implementado no código, o que os testes
automatizados exercitam e o que ainda precisa de aceitação num Paper descartável.
Nenhum teste unitário ou de configuração prova que listeners, mundos, plugins
externos ou operações reais funcionam.

- Estado atualizado: 2026-10-07. Migração AuthMe e Arena ativadas no alvo.
  Login normal, entrada/saída da fila, teleporte dos dois participantes, combate
  nativo 1v1 com kit fixo e restauro após vitória/desconexão: `RUNTIME-VERIFIED`.
  Os checkpoints locais
  e as limitações continuam separados abaixo.

## Estado observado e bloqueios

No snapshot Crafty verificado em 2026-10-04, a admissão global está ativa. O
comando Paper `/minijogos estado` mostra todos os jogos fechados, exceto
`anvil-dodge`, que está à espera; `/coliseu estado` mostra Arena fechada com
zero jogadores. O mundo `world` tem UUID
`620f870d-9c24-4573-bb95-db1dde2e4b41`, confirmado por `/mvinfo` e pela
configuração do módulo Anvil Dodge instalado. A altura vertical Paper observada
é `-64` a `319` inclusive (`getMinHeight() == -64`, `getMaxHeight() == 320`,
exclusivo).

`retention.yml` e `minecarts.yml` existem como ficheiros separados no diretório
do plugin e ambos têm `enabled: false`; os placeholders de retenção também
estão desativados. Displays, anúncios Discord e conversa pública estão
desativados. O atualizador está ativo, mas o reinício automático após preparar
uma versão está desativado. `PlaceholderAPI-2.12.3.jar` existe no servidor; a
expansão carregada e a sua integração continuam por verificar.

O repositório separado `ciaac-minecraft-control` existe. O bridge passou as
verificações de menu em segundo plano, screenshot e blocos do mundo carregados;
a verificação de movimento limitado e reposição também passou. Esta evidência pertence ao
controlador e não prova admissão nem movimento funcional nos minijogos. Os
estados acima descrevem comandos/configuração inspecionados e não substituem um
ensaio de aceitação completo.

Antes de qualquer ensaio de jogo, confirmar no servidor descartável o JAR e a
versão Paper efetivamente carregados, UUID e altura do mundo, configuração
resolvida, barreira global, listeners registados, autenticação nLogin e estado
de recuperação. A admissão global ativa não substitui estas verificações. Não
ativar os módulos atualmente fechados no servidor real como método de teste.

## Jogos

| Funcionalidade | Evidência automatizada disponível | Aceitação Paper pendente |
| --- | --- | --- |
| Arena / Coliseu | `ArenaDomainTest`, `StakedArenaTest`, `ArenaItemManifestBuilderTest`, `ColiseumSettingsTest`, `PaperConfigurationResolverTest` | Produção ativa: 1v1 com kit fixo, ambos os teleportes, dano nativo, vitória e restauro de 15 campos após combate/desconexão verificados em 2026-10-07. Equipas maiores e casos adicionais de espectadores no alvo continuam pendentes; testes destrutivos e de depósitos pertencem ao laboratório descartável. Apostas desativadas. |
| Build Battle | `BuildBattleMatchTest`, `BuildBattleOperationTest`, `BuildBattleThemeVotingTest`, `BuildBattlePaperSettingsTest`, `TemplateArtifactTest` | Preparar e reiniciar parcelas/template reais; testar criação, votação, limite de tempo, proteção de parcelas, reset e recuperação após reinício. |
| Batata Quente | `HotPotatoGameTest`, `HotPotatoPaperSettingsTest` | Testar passe, temporizador, eliminações, último jogador, fronteiras e limpeza com jogadores reais e entidades Paper. |
| Sumo de Repulsão | `SumoSessionTest`, `SumoPaperSettingsTest` | Testar colisões e repulsão, rondas, fronteira, quedas, empate e saída; confirmar que spawns e plataforma correspondem ao mundo. |
| Parkour de Checkpoints | `ParkourSessionTest`, `ParkourPaperSettingsTest` | Testar ordem de checkpoints, progresso, timeout, queda, saída e conclusão; validar percurso e teletransportes no mundo descartável. |
| Campo de Tiro com Arco | `ArcherySessionTest`, `ArcheryPaperSettingsTest` | Testar lanes, atribuição de alvos/projéteis, pontuação, falhas de disparo, timeout e remoção de entidades. |
| Fuga às Bigornas | `AnvilDodgePaperSettingsTest` | Não foi encontrado teste de domínio dedicado ao jogo. Validar ondas, temporização, perigos etiquetados, eliminação, limpeza e recuperação em Paper. É o único módulo de jogo ativo no snapshot, mas isso não demonstra execução correta. |
| Piso das Cores | `ColorFloorGameTest`, `ColorFloorPaperSettingsTest`, `TemplateArtifactTest` | Testar rondas, seleção e remoção de células, quedas, template, reset e restauração após falha. |
| Anéis de Elytra | `ElytraRingsPaperSettingsTest`, `ElytraChunkPreparationTest` | Não foi encontrado teste de domínio dedicado ao percurso. Validar preparação de chunks, voo, ordem dos anéis, conclusão, saída, entidades e recuperação com o Paper e mundo de ensaio. |

## Serviços partilhados e integrações

| Funcionalidade | Evidência automatizada disponível | Aceitação Paper pendente |
| --- | --- | --- |
| Configuração e catálogo | `RuntimeConfigurationLoaderTest`, `PaperConfigurationResolverTest`, `ResolvedValuesTest`, `ModuleCatalogTest` | Confirmar que a configuração ativa carregada resolve para o mundo/UUID exatos, rejeita placeholders e erros, e mantém módulos inválidos fechados. Testar regiões de altura total nos extremos `-64` e `319`, excluindo `-65` e `320`. |
| Autenticação e admissão | `RecoveryAdmissionGateTest`, `ModuleAdapterTest`, `MinigameEventRouterTest` | Exercitar login nLogin real, autenticação tardia, desconexão e reconexão; jogadores não autenticados não podem entrar. Validar versões e callbacks reais do adaptador. Os testes não provam a integração nLogin. |
| Isolamento de sessão e transporte | `IsolationPolicyTest`, `ProtectedRegionTransportPolicyTest`, `CompositeBukkitPlayerStateGatewayTest`, `ExternalStateFacetHandlerTest`, `SessionIsolationCommandTest` | Numa conta de ensaio, verificar inventário/equipamento, efeitos, estatísticas/advancements e estado externo antes/depois; tentar teleporte, veículos, comandos, dano, blocos e mudança de mundo dentro e fora das regiões. |
| Recuperação | `RecoveryAdmissionGateTest`, `SqlitePersistenceTest`, `SessionIsolationCommandTest`, `PassportPaperRuntimeTest` | Reiniciar ou terminar o processo durante captura, partida e restauração; confirmar quarentena, bloqueio da sessão afetada, reconciliação SQLite e reabertura apenas após estado seguro. Não testar com snapshots de produção. |
| Retenção e Passaporte | `LisbonSeasonCalendarTest`, `PassportConcurrencyTest`, `PassportServiceTest`, `RetentionPrivacyPolicyTest`, `RetentionRepositoryTest`, `SqliteRetentionRepositoryTest`, `PassportPaperRuntimeTest` | `retention.yml` existe separado e `enabled: false`; placeholders de retenção também estão desativados. Preparar migração e backup descartáveis. Testar elegibilidade nLogin, sessão após meia-noite/DST, exclusões, reclamação repetida, recuperação, reinício e privacidade antes de ativar. |
| PlaceholderAPI / PAPI | `PassportPlaceholderExpansionTest` | `PlaceholderAPI-2.12.3.jar` existe, mas carregamento da expansão e integração não estão verificados. Verificar tokens vazios/privados, permissões, atualização e consumo por TAB/chat; manter integração desligada até validação. |
| Estatísticas e classificações | `ResultLedgerTest`, `StatisticsRepositoryTest`, `SqliteStatisticsRepositoryTest`, `StatisticsResultSinkTest` | Verificar gravação e idempotência após partidas reais, reinício, resultados sem competição/abortados, filtros exatos e desempates. Confirmar que nomes são resolvidos para apresentação e que não se guardam IPs, credenciais ou inventário. |
| Displays e projeções | `NativeDisplayConfigLoaderTest`, `NativeDisplayTextTest` | Displays estão desativados. Criar um display descartável e testar criação, atualização, remoção, chunks descarregados, texto hostil e ausência de interação que contorne comandos/permissões. |
| Anúncios | `AnnouncementGateTest`, `AnnouncementIdentityTest`, `AnnouncementServiceTest`, `SqliteAnnouncementRepositoryTest` | Anúncios Discord estão desativados. Verificar cooldowns, identidade/idempotência e falha/repetição com DiscordSRV real, sem alterar o estado da partida quando o envio falha. |
| Carrinhos | `MinecartSpeedAuditLoggerTest`, `MinecartSpeedConfigurationLoaderTest`, `MinecartSpeedOverridesTest`, `MinecartSpeedServiceTest` | `minecarts.yml` existe separado e `enabled: false`. Num Paper descartável testar só `RideableMinecart`, limites por UUID, carregamento/transferência e reposição do limite nativo ao desativar ou fechar o plugin. |
| Eventos de segurança | `SecurityEventLoggerTest` | Conversa pública está desativada. Verificar formato, limite e privacidade do log local. O consumidor externo não está provado: validar pseudonimização, idempotência, cursor/transação e política de retenção antes de qualquer entrega externa. |
| Atualizador | `GitHubReleaseClientTest`, `ReleaseManifestTest`, `UpdateVerifierTest`, `UpdaterCacheTest`, `UpdaterConfigurationTest` | O atualizador está ativo no snapshot; reinício automático está desligado. Confirmar repositório/chave/artefactos, preparar primeiro em staging, validar assinatura e JAR, observar o ficheiro preparado e só então ensaiar reinício com supervisor e servidor vazio. Não usar produção para o primeiro ensaio. |
| Comandos e permissões | `CiaacPlatformCommandTest`, `SessionIsolationCommandTest`, mais testes específicos de comandos nos módulos | Testar como consola, jogador autenticado e jogador sem permissões. Confirmar mensagens pt-PT, negação por mundo/região/estado, limites de argumentos e ausência de bypass por alias, clique, display ou comando administrativo. |

## Registo de execução automatizada

Em 2026-10-04, a execução completa final com Maven passou: 250 testes,
249 aprovados e um ignorado porque precisa do registo de itens de um Paper real.
Inclui a resolução do overlay do Coliseu, regiões de altura total, compatibilidade
das regiões finitas e o feed do atualizador sem versão elegível.
O teste ignorado passou separadamente num Paper real 26.2 build 84, com
nLogin 2.0.24 e CIAACPlatform ativos. O servidor local usou apenas dados novos,
ficou ligado a `127.0.0.1:25567` e encerrou de forma limpa após o ensaio. Isto
prova a serialização de itens no runtime, não autenticação nem partidas PvP.
O mesmo probe confirmou o registo dos comandos e do listener nLogin e os
limites da região com a altura de um mundo real. A integração de login com
jogadores continua por ensaiar.

O primeiro arranque local revelou duas inconsistências da configuração de
origem: `modules.build-battle.plots` era recusado como `UNKNOWN_KEY`, e a data
não citada `season.anchor: 2026-09-15` era lida pelo YAML como `Date` e recusada
pelo Passaporte. O resolver agora reconhece `plots`; a data de origem está
citada, e o loader conserva compatibilidade com a data aprovada não citada.
O segundo arranque com o JAR corrigido e os ficheiros gerados anteriormente
passou sem esses erros. A data de início das épocas não foi alterada.

A admissão local permaneceu fechada: não havia fornecedores de
`ExternalStateFacetPort` para `ECONOMY`, `PERMISSIONS`, `CLAIMS_AND_HOMES` e
`TEMPORARY_WORLD_BLOCKS_AND_ENTITIES`. O código exige todas essas facetas;
a configuração do Coliseu não remove esta barreira. A listagem remota inclui
Vault/EssentialsX, LuckPerms e SimpleClaimSystem, mas a sua presença não fornece
automaticamente os adaptadores de captura/restauração da CIAACPlatform. Não
substituir fornecedores em falta por implementações vazias como prova de
isolamento ou aceitação.

A validação documental passou (`python3 scripts/check_docs.py --self-test` e
`python3 scripts/check_docs.py`).

Na continuação de 2026-10-04, foram corrigidas duas fronteiras adicionais:
payloads externos são pré-validados pelo próprio fornecedor, e entrada
temporária/purge validam o snapshot inteiro antes de qualquer mutação. Os testes
rejeitam payloads malformados, facetas em falta, facetas sem suporte, bytes
conflituantes e operações fora da thread principal sem alterar jogadores.
Fornecedores antigos sem validação explícita falham de forma fechada.

Também foi reproduzida e corrigida a passagem de uma cabeça de pistão vazia
pela fronteira protegida: os eventos podem ter uma lista de blocos movidos vazia,
embora a cabeça altere o mundo. A base e a cabeça passam a ser verificadas.
O [probe reproduzível](../tools/paper-runtime-probes/README.md) passou num Paper
26.2 build 84 real: extensão vazia bloqueada, retração vazia bloqueada e extensão
exterior permitida. Foi ainda confirmada a recusa do probe sem a opção JVM de
autorização, antes de criar o seu fixture. Ambos os ensaios terminaram com
encerramento limpo; o socket local ficou fechado.

O JAR exato destes ensaios tem SHA-256
`81fb2b5d6b85eab52659d71596849ad7b3b6a7e45909b2817f3b371defcce016`.
O ensaio do registo de itens, comandos, listener nLogin e limites de altura foi
repetido com este mesmo artefacto e passou. O [contrato de estado externo](external-state-adapters.md)
explicita as lacunas de contexto/idempotência que ainda impedem uma admissão
segura; a pré-validação não as elimina.

A ponte de controlo passou ensaios reais de look e seleção com reposição,
movimento limitado com expiração automática, release, inventário normal/criativo
e rejeição de ações inválidas. A posição, orientação e slot originais foram
repostos. Não expõe ataque/uso de itens nem navegação autónoma; esses comandos
não podem ser considerados testados. Numa inspeção anterior, o cliente estava
num ecrã de desconexão «Timed out», sem controlos pressionados. Na inspeção
seguinte, ainda em 2026-10-04, estava novamente ligado, em segundo plano e com
Chat aberto, sem controlos pressionados; esta consulta não alterou a sessão.
Numa consulta posterior, o cliente estava em primeiro plano num ecrã de opções
e recusou um pedido de look de zero graus; não havia controlos pressionados.
O tick de libertação passou a consultar também o foco nativo GLFW, como a
admissão de novos pedidos. O bridge atualizado foi compilado, os testes de
segurança passaram e os JARs de build/instalação coincidiram. O retorno do foco
manual durante um hold ativo e uma desconexão durante movimento continuam sem
ensaio independente.

No servidor real, Passaporte responde com os contadores e os carrinhos indicam
que estão desativados. O atualizador respondeu `UPDATER_CHECK_FAILED`; o feed
público contém apenas `v0.1.0-alpha.1` imutável e pré-lançamento, enquanto a
configuração aceita apenas estáveis. Essa condição reproduz a falha genérica no
código antigo; o código novo apresenta `NO_UPDATE` quando não há versão elegível.
A correção ainda não foi instalada no servidor. Não existe autorização para criar
um servidor de ensaio na instância Crafty do amigo; nenhum servidor remoto foi
criado ou alterado nesta verificação. A consulta `/version` foi
rejeitada, pelo que a versão do JAR carregado não foi confirmada por esse comando.

Registar cada ensaio Paper com versão do JAR/Paper, cópia de configuração sem
segredos, mundo/UUID, plugins e versões, passos reproduzidos, resultado, logs
redigidos e recuperação observada. Um resultado de teste automatizado não deve
ser marcado como aceitação Paper ou prova de implantação.

## Continuação do objetivo Arena — 2026-10-04

Esta secção atualiza a evidência anterior sem a substituir retroativamente.

- Maven `verify`: **272 testes, 271 aprovados, um ignorado**, sem falhas/erros.
- Contrato externo versão 2: identidade completa chega aos fornecedores;
  conexões obsoletas e snapshots de outra identidade são recusados. A autenticação
  e a entidade atual são verificadas antes de cada handler e no fim da operação.
  Um teste reproduziu a revogação durante um handler e confirmou que o seguinte
  deixa de executar em captura, entrada, purge e restauro.
- Schema 2 conserva a época capturada e nanosegundos. O teste de coordenador
  persiste e reabre SQLite com `123456789` nanosegundos, sem divergência do
  envelope. O fixture schema 1 mantém compatibilidade byte a byte.
- Journal externo: ensaios de intenção pendente após reabertura, replay
  confirmado após reconexão, conflitos de identidade/payload/resultado,
  corrupção, limites, versões incompatíveis e symlinks. Ainda não está ligado
  aos fornecedores ou ao runtime; não prova isolamento externo.
- O kit fixo distribui armadura e primeiro escudo para slots utilizáveis,
  conserva duplicados no armazenamento e recusa overflow. Todos os itens do
  kit aplicado recebem etiquetas de sessão.
- O probe guardado passou novamente no Paper 26.2 build 84: layout real dos
  itens, cópias e etiquetas, extensão/retração protegidas bloqueadas e extensão
  exterior permitida. O servidor encerrou limpo. Isto não testa equipamento num
  jogador, login, combate ou restauro de participantes.

JAR exato desta última execução Paper:
`4e4d0dc8faed8672967108febc6cc81599363b235aedd3435de70a815420019e`.
Os downloads oficiais de EssentialsX 2.22.0 e do release SimpleClaimSystem
1.13.1 foram comparados com os digests SHA-256 publicados e usados apenas no
servidor descartável. Essentials iniciou mas avisou que Paper 26.2 é uma versão
não suportada; funcionalidade económica/homes ainda exige ensaio específico.
O asset do release SimpleClaimSystem 1.13.1 declara internamente **1.13.0.9**.
Este artefacto não prova compatibilidade com o JAR remoto chamado 1.13.1.

A admissão mantém-se fechada por falta dos quatro fornecedores concretos.
Partida com dois jogadores autenticados, ataques normais, bloqueio de
espectadores, comparação completa de estado e recuperação com crash continuam
pendentes. Não houve alteração do backend ou das credenciais Crafty. O objetivo
permanece ativo; a implantação requer autorização explícita após aceitação.

## Guards de autoridades externas — 2026-10-04

O runtime passa a incluir guards reais de economia EssentialsX 2.22.0/Vault e
permissões LuckPerms 5.5.65, além dos serviços externos registados. O journal
externo tem ciclo de vida explícito, instante de commit durável e auditoria
idempotente. Os guards só verificam estado que a partida não altera; drift,
expiração de permissões ou perda da autoridade exige reconciliação e nunca
repõe saldos/nós antigos. Ver o [contrato e limitações](external-state-adapters.md).

`RUNTIME-VERIFIED`: num Paper 26.2 build 84 descartável, com EssentialsX 2.22.0 e
Vault 1.7.3-b131 reais, o probe confirmou o guard de economia: conta ausente
recusada sem criar/carregar conta, UUID errado recusado, captura/entrada, journal
reaberto, replay, purge/restauro sem alterar saldo, quatro eventos de auditoria
sem duplicação, e drift externo recusado sem sobrescrever o novo saldo. O ensaio
usou um objeto User Essentials real e uma entidade Player sintética limitada,
colocada temporariamente no cache carregado; não era um jogador ligado. Não
provou nLogin, admissão, combate ou restauro de um jogador real. Pistões e kit
real mantiveram os resultados anteriores. A evidência foi `PASS` e o servidor
encerrou de forma limpa, com a porta local fechada.

Este runtime usou o JAR SHA-256
`335fa6925bd44a36118aba3524cc34a97a956b09a252bbbe7130737f9f2c35ad`.
O primeiro fixture falhou ao carregar o `OfflinePlayerStub` Essentials por falta
de `org.bukkit.Achievement` no Paper 26.2. A segunda execução criou o objeto User
pela API pública com Player sintético, sem esse helper. A incompatibilidade do
helper permanece e o aviso Essentials de versão Paper não suportada não foi
suprimido; o sucesso do guard não prova compatibilidade geral Essentials.

O artefacto Vault veio do release oficial MilkBowl 1.7.3; SHA-256 local
`a6b5ed97f43a5cf5bbaf00a7c8cd23c5afc9bd003f849875af8b36e6cf77d01d`,
sem digest publicado confirmado. Não foi possível obter o JAR Bukkit exato
LuckPerms 5.5.65 dos URLs oficiais consultados: o endpoint de downloads apontava
para 5.5.85, e os builds históricos candidatos responderam 404. Nenhuma versão
mais recente foi usada como prova de compatibilidade 5.5.65. Os testes do leitor
LuckPerms e da ligação ao serviço permanecem evidência automatizada, sem ensaio
real desse provider.

Claims/homes e mundo temporário continuam incompletos. A configuração de ensaio
manteve a admissão fechada e não criou sessões ficticiamente autenticadas. Nada
foi implantado no backend. O acesso ao cliente Minecraft passa a ser combinado
com o utilizador antes de qualquer ensaio de jogo.

Após integrar os testes da ligação LuckPerms ao serviço, `mvn verify` passou:
291 testes, 290 aprovados, zero falhas/erros e um skip dependente do registo Paper.
O JVM de testes usou `-Djava.io.tmpdir=/private/tmp` através de `argLine`; os
fixtures do journal usam também o path canónico e mantêm os testes de symlinks.
O JAR recompilado tem SHA-256
`105c6aa3d6025e80a6e1c100102df90d48e6b8382aa6514c857c03b4e8df2238`.
Este hash identifica o build automatizado; a evidência runtime acima identifica
separadamente o JAR efetivamente executado, sem promover o novo hash a ensaio real.

## Claims/homes com plugins reais — 2026-10-04

O runtime liga agora o guard composto SimpleClaimSystem 1.13.0.9 + EssentialsX
2.22.0. O snapshot distingue o UUID do proprietário do número da claim, inclui
claims próprias, com membership ou ban, e preserva estado/política sem escritas
de restauro. O índice carregado é confirmado contra os pares persistidos no
datasource real; mismatch fecha a captura. Folia é recusado nesta ligação.

`RUNTIME-VERIFIED`: no Paper 26.2 build 84 descartável, dois registos sintéticos
SQLite foram carregados pelo arranque normal SimpleClaimSystem. Ambos tinham
claim ID 1, proprietários diferentes e chunks afastados do ensaio de pistões.
O probe confirmou leitura pelas APIs reais, UUID errado recusado, payload
estável, entrada sem alterar estado, journal reaberto, replay, purge/restauro
e quatro eventos de auditoria únicos. Uma permissão externa alterada foi
conservada; o guard recusou restauro e recaptura sobre o snapshot anterior. Um
registo temporário adicional na base, ausente do índice carregado, fechou a
leitura e foi removido pelo fixture. A base ficou com apenas as duas claims
sintéticas previstas.

O modelo real Essentials confirmou também a ordem dos aliases de homes e
conservou um UUID de mundo não resolvido sem o reescrever nem carregar o mundo.
A primeira tentativa de ligação SCS falhou porque `Class.getMethod` resolveu
classes de mapas opcionais ausentes. A ligação corrigida usa os getters do
plugin por assinatura exata com `MethodHandles`; não instala integrações de
mapas como workaround. A tentativa falhada manteve a admissão fechada.

O resultado final foi `PASS`, com
`claimsAndHomesAuthorityAndJournal`, `essentialsLoadedHomeModel`,
`essentialsEconomyAuthorityAndJournal` e `fixedKitLayoutAndTags` verdadeiros,
mais os três eventos físicos esperados de pistões. O servidor encerrou de forma
limpa e a porta loopback ficou fechada. JAR CIAAC efetivamente executado:
`0e40514156e3a5959d99a9a554d567aad7e2fb69d698a562b1bd7d56f7e73e04`.

Automação final: **308 testes, 307 aprovados, zero falhas/erros e um skip de
registo Paper**. As contas e Player de fronteira continuam sintéticos: nenhum
login nLogin, admissão, partida ou restauro de jogador ligado é provado. O
aviso Essentials de Paper não suportado permanece. A versão SCS carregada no
backend e LuckPerms 5.5.65 real continuam por verificar; mundo temporário ainda
sem fornecedor completo. Não houve ação no cliente nem alteração no backend.

## Permissões, providers exatos e cliente — 2026-10-04

As consultas de grupos via Crafty confirmaram `admin` com wildcard global e
parent `associado`; `softadmin` herda `associado`, sem grants administrativos
CIAAC próprios; `associado` herda `default`. A leitura das quatro páginas de
`default` não encontrou grants administrativos CIAAC. O script `/admin` eleva
`softadmin` acrescentando o parent `admin`; nesse estado, o wildcard concede
as permissões CIAAC. Esta política observada difere da política de menor
privilégio pretendida no repositório. Não foram alterados grupos ou scripts,
nem auditados grants individuais, contextos ou OP de todos os jogadores.
Ver a [matriz administrativa](admin-permissions.md).

Os handlers exigem o nó exato antes de executar a ação; as declarações
administrativas têm `default: false`. Foi corrigida a sugestão `admin` do
Passaporte, que agora exige `ciaac.retention.admin.view`. Os 26 testes focados
de permissões passaram, incluindo sender OP sem o nó, separação entre reload
e test dos carrinhos e rejeição antes de consultar o updater.

A leitura dos JARs exatos do backend identificou LuckPerms 5.5.65 e
SimpleClaimSystem com versão interna 1.13.1. O guard SCS foi fixado a essa versão;
o payload e ID experimentais 1.13.0.9 não são migrados automaticamente.
Os dois providers reais passaram em Paper local 26.2 build 84, juntamente com
Essentials/Vault, homes, claims, kit e pistões. A primeira limpeza LuckPerms
deixou nós persistidos: `deletePlayerData` remove apenas o mapping. O fixture
agora guarda um utilizador com apenas o parent `default`, confirma a remoção
dos nós, descarrega o utilizador e remove o mapping e grupos. Dois ensaios
sucessivos passaram; a base local ficou com zero nós, mappings e grupos do
fixture. Uma cópia privada conservou o estado anterior à limpeza exata das
quatro linhas sintéticas antigas. Nenhum registo de produção foi alterado.

O build limpo deste checkpoint passou **336 testes: 335 aprovados, zero
falhas/erros e um skip de registo Paper**. JAR efetivamente ensaiado:
`693915157a30329d65eff01df19242de202e4b90484d7742b851da8f3c7c66a3`.
O probe terminou com `PASS`, incluindo os cinco booleans de providers/kit e
os eventos físicos esperados; o processo terminou e a porta loopback fechou.
Este hash precede a integração posterior de ownership de projéteis e catálogo
por jogo; não prova esses novos caminhos.

O utilizador autorizou acesso autónomo ao cliente existente enquanto está
ausente. Modrinth abriu a instância Fabulously Optimized; o login normal nLogin
chegou ao mundo sem registo novo nem alteração de credenciais. A tentativa
anterior expirou via GrimAC; a nova submissão imediata funcionou.
Os pontos das duas equipas têm sand em Y=86 e espaço livre em Y=87–88;
o ponto de saída/recuperação tem suporte em Y=97 e espaço livre em Y=98–99.
`/minijogos` voltou a apresentar Coliseu fechado. Não houve partida, restauro
de jogador ligado nem ativação remota. O cliente mantém controlos do bridge
libertados. Este acesso substitui a necessidade anterior de handover por sessão;
implantação/reinício do backend continuam dependentes de autorização explícita.

O ledger de mundo da Arena e o manifest canónico estão implementados e
passaram testes de reabertura, identidade, fases, corrupção de schema, FK e
limites. O volume de cuboids deixou de calcular diferenças em `int`, evitando
que coordenadas extremas passassem a validação por overflow. O provider de
mundo e ownership de projéteis estão em implementação; ainda não são registados
pelo runtime. A admissão permanece fechada até ligar e testar os listeners,
reconciliação após unload/reinício e isolamento de blocos/interações.

## Projéteis e fases do mundo Arena — 2026-10-04

O probe em Paper 26.2 build 84, commit `26e81c4`, passou com os dois novos
campos `arenaProjectileOwnership` e `arenaWorldPortAndJournal` verdadeiros,
além dos guards de economia, LuckPerms, homes/claims, kit e pistões. JAR CIAAC
efetivamente executado: `700320cf22a4799467731aaeed47eb491fd1054b709b51a522f08a89ea21f783`;
probe: `8b44cde14a19a7e47e69619536ae90c56cef65c3110359c852006fe3497f5c14`.
O processo terminou com código zero, guardou todos os mundos e fechou a porta
loopback. O aviso Essentials de versão de servidor não suportada permanece.

Arrows normais e espectrais reais foram identificados antes da inserção e
confirmados no evento nativo. Um cancelamento de lançamento foi reconciliado;
drift de persistência recusou purge antes de eliminar qualquer entidade.
Purge repetido removeu apenas UUIDs owned e conservou um arrow exterior.
O primeiro ensaio de remoção reproduziu que `isInWorld()` fica verdadeiro após
`remove()` neste Paper; a confirmação foi corrigida para exigir inválido,
morto e ausência no índice UUID. Uma ausência não comprovada continua recusada.

O provider direto verificou o build exato, captura e ENTER repetido. Depois de
reabrir ledger/journal com um arrow real ainda carregado, repetiu PURGE e
RESTORE, mantendo quatro auditorias únicas. Restauro antes do purge e tentativa
de rearmar disponibilidade após falha de lifecycle foram recusados. Readiness
foi chamada explicitamente apenas nesse fixture sintético: estes resultados
não demonstram registo do listener de produção, autenticação ou admissão.

A proteção imutável passou também testes de interação de blocos, pressão de
entidades, ignição/formação, redstone e crescimento iniciado fora que atravessa
a fronteira. Mantém a decisão de uso do item em mão, permitindo a política de
bow/shield. O build posterior, com esse último teste adicional, passou **355
testes: 354 aprovados, zero falhas/erros e um skip de registo Paper**. Hash desse
build: `a43bb3ec19f63b5c75728049b60b4927ac6db8c3a1458bf4b231ba5af38084d2`;
o hash não é promovido automaticamente a prova de execução do probe.

O listener de projéteis e o provider de mundo continuam **não registados pelo
runtime**. A ligação à sessão/conexão autenticada, falhas de lifecycle,
unload/reinício, combate entre dois jogadores e restauro integrado ainda
precisam de aceitação. A admissão e ativação remota permanecem fechadas.

Uma revisão posterior corrigiu duas falhas no listener ainda não registado:
o tipo suportado é validado antes de iniciar ownership, evitando invalidar
globalmente o provider por rejeitar um trident sem mutação; uma intenção
PENDING criada antes de falha de lançamento recebe reconciliação no próximo
tick antes de recuperação. Só prova nativa de cancelamento pode marcar a
entidade removida; uma inconsistência continua fechada. O build após estas
correções manteve **355 testes, 354 aprovados e um skip**, hash
`65fbc5f1c4d1ba67286992e168b60287d36c6e4bdecc3d2fca4cdabe560ca080`.
Esses caminhos específicos ainda aguardam ensaio dos eventos nativos.

## Lifecycle nativo e ligação ao runtime — 2026-10-04

Este checkpoint substitui o estado anterior de listener/provider não registado.
O runtime instala agora o ledger e provider de mundo específico da Arena e
regista o listener de projéteis antes da montagem dos módulos. Readiness depende
do build Paper exato e dos hooks instalados; os restantes oito jogos continuam
fechados por não terem provider de mundo compatível. A admissão do fixture
manteve-se desativada: disponibilidade de facetas não é prova de admissão.

Maven: **366 testes, 365 aprovados, zero falhas/erros e um skip**. Os testes novos
cobrem confirmação de ownership, sessão/conexão inválida e callbacks depois do
encerramento. O listener ignora trabalho diferido após close e não agenda tarefas
quando o plugin está desativado, deixando intenções sem prova para recuperação.

O ensaio nativo passou em Paper **26.2-84-main@26e81c4**, o mesmo build confirmado
por leitura de `logs/latest.log` no Crafty. JAR CIAAC executado:
`c6bc1d6ce5c49989c0057405aee66a78ddb947e884e768a891b9b5ca6ba6cec8`;
probe: `5b81cc8087809ff1efe51433347796733cb8f71e470b2d25e601e2b7a7839813`.
`arenaProjectileLifecycle` e `arenaWorldRuntimeBootstrap` passaram, além dos
fixtures anteriores. O bootstrap confirmou o listener de produção único,
proteção de regiões, disponibilidade do provider específico de Arena e ledger
privado; voltou a confirmar a saúde depois dos eventos. O processo terminou com
código zero e a porta loopback fechada. O aviso Essentials de versão não
suportada permanece.

O unload real reproduziu uma particularidade do Paper: um arrow não persistente
pode ficar vivo, inválido e fora do índice UUID no fim do tracking. Não basta
tratar essa ausência como remoção. O handler valida a referência exata recebida
no callback contra ownership durável, tipo, mundo, etiqueta e flags antes de a
descartar; só confirma REMOVED depois de morto, inválido e não indexado. O ensaio
verificou inserção, hit em bloco, remoção direta e unload de chunk, preservando
um arrow exterior. O fixture usa o namespace do próprio probe para evitar
conflito com o ledger de produção; não acrescenta sessões nem autenticação.

A entrada autenticada por nLogin, lançamento por participante, combate entre
dois jogadores, restauro integrado, reconexão e recuperação após crash continuam
pendentes. A configuração do coliseu permanece INTENDED e não foi instalada
remotamente. Crafty e grupos de permissões não foram modificados.


O cliente sintético de aceitação ficou reproduzível em
[tools/arena-client-fixture](../tools/arena-client-fixture/README.md), separado
das dependências de produção. O artefacto MCProtocolLib 26.2 timestampado foi
verificado pelo digest oficial; declara protocolo 776. O peer chegou ao estado
GAME do Paper local, confirmou teleport nativo e ganhou a identidade offline
normal, reconhecida por LuckPerms/SimpleClaimSystem. Recusa execução sem a flag
explícita do fixture e o socket é fixo em loopback. A conta não foi autenticada:
o wizard inicial nLogin exige autorização administrativa para configurar. A
permissão temporária apenas local foi solicitada e ainda não foi aplicada.
Um pedido de entrada não criou sessões/snapshots; sem prova de que ultrapassou
o wizard, isso não confirma negação pelo router. Combate/restauro permanecem
pendentes. O primeiro processo local de gameplay terminou durante a interrupção
do ambiente; essa execução foi preservada como incompleta, sem afirmar shutdown
limpo. O arranque seguinte só ocorreu após verificar a porta fechada.

## Crash nativo do recurso Arena — 2026-10-04

Um fixture de duas execuções em Paper 26.2-84-main@26e81c4 confirmou o limite de
recuperação de projéteis. Após lease ARMED e arrow não persistente confirmado,
o driver terminou abruptamente apenas o processo local que tinha iniciado
(exit -9). Ao reabrir ledger/journal e carregar o chunk no novo processo, o arrow
owned estava ausente e o controlo exterior persistente tinha sobrevivido. Purge
e restauro foram recusados por falta de reconciliação; a lease e intenção
CONFIRMED ficaram intactas, sem novas auditorias ou remoção do controlo. Depois
da prova, o fixture removeu só esse controlo criado por ele, liberou o ticket e
o segundo processo encerrou com código zero e porta fechada.

CIAAC JAR executado:
`c6bc1d6ce5c49989c0057405aee66a78ddb947e884e768a891b9b5ca6ba6cec8`;
probe: `78f9ec28f64b477ed447a625eb6e9f84f146a5b2ab96d1fb64fe317d69a43a0c`.
O código de produção não mudou neste checkpoint. O primeiro seed não chegou a
ser crashed: recusou uma inserção num chunk ainda não plenamente tracked; o
fixture foi corrigido com ticket e espera nativa, conservando a prova rejeitada.
Esta aceitação confirma ausência ambígua fechada no provider, não a execução de
quarentena/restauro de uma sessão de jogador. nLogin setup, admissão autenticada,
combate/restauro integrados e implantação continuam pendentes.


O caminho normal do probe foi repetido com o mesmo JAR de probe `78f9ec28…`,
sem as flags da fase crash: PASS para todos os marcadores anteriores, incluindo
lifecycle e bootstrap de produção. A admissão foi temporariamente fechada
apenas no servidor descartável durante esse ensaio; a configuração de gameplay
original foi restaurada após comparação exata e o JAR do fixture foi arquivado
fora de `plugins`. O processo terminou com código zero e a porta fechada.

## Aceitação nativa autenticada da Arena — 2026-10-04

No Paper descartável **26.2-84-26e81c4**, o cliente Modrinth Fabulously
Optimized autenticou-se pelo fluxo normal do nLogin; o segundo participante foi
o `CiaacArenaPeer` sintético, ligado apenas a `127.0.0.1:25567`. O wizard local
`nlogin.admin` foi concedido temporariamente e revogado, com verificação
negativa, antes do gameplay. nLogin 2.0.24 não carregou a biblioteca ARGON2ID
nativa em ARM; a configuração local suportada usou BCRYPT2A. Nenhuma
configuração de produção foi alterada.

O ensaio encontrou e corrigiu diferenças nativas: Vitals recusava `fire=-20`;
foi ajustado para fogo assinado, ar/fome e exaustão em `0..40`. O probe antigo
de EssentialsX 2.22 usava `getOnlineUserCache`, que existe mas não é preenchido
pelo caminho normal `getUser`; a leitura agora usa o `userCache` privado exato
e `GuavaCache.getIfPresent`, sem carregar utilizadores. O formato antigo de
inventário rejeitava os três slots extra do Paper (mão secundária, corpo e sela); captura/pré-validação foram
alinhadas, e o codec nativo de componentes preserva slots distintos. Dez probes
booleanos confirmaram round-trip e dano de 7 num item; isto, isoladamente, não
é restauro de jogador.

A quarentena pré-existente manteve-se intacta após uma tentativa de reinício
autenticado: o estado continua `QUARANTINED`. Uma sessão nova independente não
é recuperação dessa quarentena. Essa sessão nova revelou colisão de operation ID
entre jogadores sob uma raiz partilhada; o `SessionCoordinator` agora inclui a
sessão durável de forma determinística. Um ensaio terminou com ambos os estados
`CLOSED` e snapshots `RESTORED` depois de uma tentativa de ativação falhar: a
hora já era anterior à preparação e a sessão recusou-a corretamente. Arena e
cinco controladores restantes usam agora um relógio de ativação novo.

Num caso subsequente, ambos os jogadores chegaram a `ACTIVE` com os kits e
spawns. Nesse ensaio, um disparo de arco/uso real após 30 segundos excedeu o
token de região e causou um forfeit incorreto; o checkpoint de combate abaixo
regista a correção aplicada. Apesar do forfeit, ambos os
snapshots terminaram `RESTORED` e as sessões `CLOSED`. A leitura NBT nativa
confirmou espada com dano 7, capacete dourado com dano 4, `bodyLeatherHorseArmor`,
sela e XP preservados (nível 7, progresso `0.52380955`, total 0). O disparo
exercitou uso; ataque ainda estava pendente nesse ensaio.

O cliente real recebeu negação para `ciaac carrinhos recarregar`,
`minijogos atualizacao` e a administração do Passaporte. LuckPerms confirmou
`nlogin.admin` e `ciaac.minigames.admin` efetivos como falsos. O inventário de
grupos de produção continua com wildcard global no grupo `admin`; `softadmin`
herda `associado`, mas não tem administração CIAAC; `default` e `associado`
também não têm administração CIAAC. Isto documenta observações de permissões,
não uma alteração de grupos.

No checkpoint, `mvn verify` reportou **371 testes: 370 aprovados, zero falhas e
um ignorado** dependente de registo Paper. Ensaios manuais em andamento podem
alterar a contagem futura. Não se afirma implantação, aceitação de todas as
funcionalidades, recuperação positiva da quarentena antiga ou vitória PvP.

## Combate nativo e resultado Arena — 2026-10-04

A validade da admissão de região renova-se após `ACTIVE` até ao prazo real do
combate, mantendo o token de entrada limitado a 30 segundos. Ambos os
participantes autenticaram-se pelo nLogin normal no Paper descartável
26.2-84-26e81c4. Aos 45 segundos da ativação, o cliente Modrinth usou o arco;
a reconciliação durável marcou `ARROW REMOVED` e a partida permaneceu ativa.
Isto prova o caminho de uso/remoção, não um acerto de arco nem dano PvP.

Após dez ataques corpo a corpo com intervalo de arrefecimento, um golpe letal
controlado de `22:22:16` (hora de Lisboa) terminou a partida real. O cliente
Modrinth mostrou o vencedor e `r0das` registou uma única `VICTORY` com motivo
`LETHAL_DAMAGE` para esta partida. Uma leitura apenas de consulta à base mostrou
seis sessões `CLOSED`, seis snapshots `RESTORED`, seis leases `RESTORED` e uma
arrow `REMOVED`, incluindo os dois pares de sessões dos ensaios falhados
anteriores e o par desta partida. O jogador real regressou à posição de saída
original `(-5.5, 80, 10.5)`, com saúde 20 e sem controlos pressionados.

A leitura NBT final desta partida ainda está a ser verificada; não atribuir-lhe
os valores de itens/XP da leitura confirmada no ensaio anterior. Ataque e uso
nativos estão agora exercitados, mas equipamento espelhado, desconexão,
recuperação positiva após reinício autenticado e acesso nativo a toda a altura
continuam pendentes. A quarentena antiga permanece por recuperar. Não houve
ativação de produção.

## Recuperação autenticada e fronteira de altura total — 2026-10-05

Este checkpoint supersede o estado pendente de recuperação autenticada e teste
nativo de altura total acima para o caso local descrito aqui. A quarentena antiga
e os seus arquivos de jogador/plataforma foram preservados integralmente; só o
fixture sintético novo foi reposto. Este caso não recuperou nem limpou a
quarentena antiga.

O gate de recuperação exige a capacidade de autenticação e que
`ConnectionRegistry.isCurrent` confirme o objeto de ligação e a época exatos.
Violações não autenticadas adiam a auditoria (`DEFERRED_UNAUTHENTICATED`),
conservam o estado durável pendente e mantêm a negação de transporte. O conjunto
de casos do serviço foi atualizado com oito verificações. `mvn verify` passou:
**379 testes, 378 aprovados, um ignorado, zero falhas/erros**. JAR usado:
`0099b09ed6258f0c278a072d8a6f6d7a3980b6f29266f0d3591612a746263f7a`.
O adiamento de auditoria está coberto pelos testes; o ensaio nativo abaixo não
observou esse gate exato nem deve ser usado como prova dele.

No Paper local, foram emitidos teleports de consola Bukkit para
`(20.5, 200, 10.5)`, `(20.5, -20, 10.5)` e `(20.5, 80, 10.5)`. Os três destinos
ficam dentro dos limites horizontais Arena do fixture (`X=0..40`, `Z=0..20`),
em alturas diferentes. A admissão negou os teleports do jogador não admitido.
A mensagem vanilla «Teleported» não foi tratada como prova de movimento: a
consulta nativa seguinte confirmou o cliente fora, na saída `(-5.5, 80, 10.5)`.
Uma repetição no build atual consultou o estado depois de cada destino e
confirmou a mesma posição, ligação ativa, saúde 20 e nenhum controlo pressionado.
Evidência privada: `/private/tmp/ciaac-paper-local-test/verification-2026-10-05-full-height-native-denial/evidence.json`.
Ao andar normalmente, avançou apenas até `x=-0.03898` enquanto caía para
`y=73.32778` na lacuna do piso do laboratório; não entrou na região.
Isto verifica a negação nativa, não uma passagem bem-sucedida pela fronteira.

Para o ensaio de crash com estado espelhado, ambos os participantes estavam
`ACTIVE` e ambos os snapshots `TEMPORARY_APPLIED`. A baseline nativa tinha duas
pérolas no slot 1 do inventário normal e uma Elytra com dano 9 equipada no slot
de peito. O loadout temporário do modo survival protegido reteve esses itens
proibidos; o jogador não os retirou durante o combate. `save-all` persistiu o
estado do jogador ainda dentro da Arena; um driver guardado terminou apenas o
processo Java local que iniciara, com SIGKILL/exit -9. Após reiniciar o mesmo JAR
com a mesma base, a fase somente de leitura confirmou `ACTIVE`/`TEMPORARY_APPLIED`
para ambos; a sessão real de nLogin ficou congelada após o encaminhamento nativo
pré-login de 150 ms, sem alteração da posição. Depois do login nLogin normal,
ambos os jogadores foram recuperados (`RECOVERED`), e as duas sessões terminaram
`CLOSED`, snapshots `RESTORED` e leases `RESTORED`.

A leitura NBT final confirmou espada com dano 7, duas pérolas, capacete com dano
4, `bodyLeatherHorseArmor`, sela, Elytra com dano 9 e XP nível 7 / progresso
`0.52380955` / total 0 restaurados; o cliente regressou à saída original, com
saúde 20 e controlos libertados. Não foi observado vazamento do kit etiquetado
por PDC. Este é um ensaio local positivo de recuperação autenticada após crash,
com estado espelhado e a mesma base/JAR. Um teste de desconexão normal ainda
está pendente e pode revelar outros defeitos; não se declara a aceitação completa
das funcionalidades.

O backend remoto continuava indisponível nas observações anteriores. Não houve
ativação de produção nem escrita de credenciais ou configuração remota.

## Desligação normal e duelo no build atual — 2026-10-05

Este checkpoint supersede a pendência de ensaio de desconexão normal acima,
apenas para os dois casos locais descritos. Ambos usaram o JAR SHA-256
`0099b09ed6258f0c278a072d8a6f6d7a3980b6f29266f0d3591612a746263f7a`.

No caso de desconexão, a partida terminou em `VICTORY` / `DISCONNECT`; ambas as
sessões fecharam (`CLOSED`), ambos os snapshots foram restaurados
(`RESTORED`) e ambas as leases terminaram `RESTORED`. O peer completou um novo
login nLogin normal e regressou com inventário vazio e posição de saída. O
estado nativo de inventário e XP do cliente real também foi consultado após a
restauração. Evidência local verificada em 2026-10-05.

No ensaio de duelo do mesmo build, um cliente Minecraft real usou arco e combate
corpo a corpo contra o peer sintético nativo, sem injeção de eventos ou morte
forçada. A partida terminou em `VICTORY` / `LETHAL_DAMAGE` às
`2026-10-05T12:23:18.487937Z`; o arrow foi confirmado como `REMOVED`. Ambas as
sessões ficaram `CLOSED`, snapshots `RESTORED` e leases `RESTORED`. NBT confirmou
restauro da espada com dano 7, duas pérolas, capacete dourado com dano 4,
`bodyLeatherHorseArmor`, sela, Elytra com dano 9 e XP nível 7 / progresso
`0.52380955` / total 0. Ambos regressaram às posições de saída originais; o
peer ficou com inventário/equipamento vazio e o cliente real sem controlos
pressionados.

Os dois resultados são aceitação local dos casos de desligação e duelo fixo
descritos, não aceitação de todos os modos, espectadores, equipamentos,
integrações ou recuperação. A configuração da Arena permanece INTENDED e não
foi aplicada; o servidor de origem continua indisponível e não houve ativação de
produção.

Uma consulta de permissões nativa separada, em 2026-10-05, negou ao cliente
autenticado normalmente `/ciaac carrinhos recarregar`, `/minijogos atualizacao`
e `/passaporte admin`. `ciaac.minigames.admin` e `nlogin.admin` eram efetivamente
falsos; não foi concedido OP nem alterado qualquer grupo de produção. Esta
verificação local não autoriza alterações de permissões.

## Equipa nativa 2v2 e fronteira de espectadores — 2026-10-05

Build atual: JAR CIAACPlatform SHA-256
`4a94c59e18e32a8215d35ec068cedf3118011d6be874181c192826d5c2b4555f`; `mvn verify`
passou com **396 testes, 395 aprovados, zero falhas/erros e um ignorado**. A
fábrica partilhada de pedidos de admissão rejeita autenticação ausente, expirada,
invalidada ou de época anterior. Ações, prontidão, grupo e roster do Coliseu
exigem autenticação atual; aceitar um desafio revalida o roster original
imutável.

No Paper local 26.2-84, quatro clientes nLogin normais formaram duas equipas
2v2 por desafio e os quatro chegaram a `ACTIVE`. Quando o primeiro jogador saiu
com `/coliseu sair`, passou a espectador real e perdeu a admissão ao piso; os
outros três mantiveram tokens e combate ativo. Teleports do espectador para
`x=20.5`, `z=10.5`, em `y=200`, `-20` e `80`, foram negados sem encerrar a
partida. O voo nativo parou em `x=-1.076`, dentro da bancada inclusiva
`X=-10..-2`; depois, o estado nativo mostrou `held=[]` e os tokens continuavam
revogados. Um teleport de consola a um combatente foi cancelado pela proteção de
transporte existente antes do handler da Arena; o roster não mudou, portanto
este caso não é prova de forfeit.

A saída posterior de outro colega terminou em `VICTORY` / `PLAYER_LEFT`. As
quatro sessões ficaram `CLOSED` e os quatro snapshots `RESTORED`. A comparação
serializada nativa de cada jogador confirmou inventário, Ender Chest, XP, modo
de jogo e posição originais exatamente iguais à baseline. Evidência privada:
`/private/tmp/ciaac-paper-local-test/verification-2026-10-05-native-team-boundary`
(amostras iniciais e restauradas, resumo final e backup da base). Um segundo
ensaio 2v2 também restaurou os quatro jogadores; o total do laboratório ficou
em 16 sessões `CLOSED` e 16 snapshots `RESTORED`.

Os testes unitários da fronteira confirmam encaminhamento antes do handler
`HIGHEST` genérico e um callback enfileirado; usam fixture com domínio já
eliminado e não provam transferência de atributos nativos após eliminação.
Tentativas nativas de movimento para trás e salto não confirmaram forfeit:
o combatente permaneceu perto de `x=0` e `ACTIVE`; a causa continua em
investigação. Portais, fogo amigo, equipas assimétricas, 3v3 e crash com quatro
participantes continuam por verificar. Isto é aceitação local delimitada, não
aceitação de todas as funcionalidades. O backend Tailscale estava indisponível;
não houve ativação de produção nem alterações de permissões de produção.

## Fronteira nativa 2v2 corrigida — 2026-10-05

Este checkpoint supersede a incerteza do ensaio 2v2 anterior sobre a passagem
nativa de um combatente para espectador. Build atual: JAR SHA-256
`463af6b312bbf4ecc981629ff2644680ed58e09f1ebdb7c4b7bf2350a675da7c`; `mvn verify`
passou com **398 testes, 397 aprovados, zero falhas/erros e um ignorado**. A
causa era uma verificação duplicada de movimento no router partilhado a
`LOWEST`, que cancelava a ação antes de o handler Arena `HIGH` poder enfileirar a
verificação de região. A política duplicada e o campo da fábrica foram
removidos; o router mantém a negação de teleports externos não iniciados por
plugins para combatentes ativos. Movimento espacial e fronteira de teleporte de
plugin ficam no handler Arena `HIGH`, preservando cancelamentos anteriores de
outros plugins; o handler genérico `HIGHEST` não recebe uma violação já
consumida. Dois testes de integração reproduziram a falha antes da correção e
passaram depois.

No Paper local, quatro clientes nLogin normais voltaram a formar duas equipas
2v2 e chegaram a `ACTIVE`. Um combatente recuou durante 1000 ms até `x=1.057` e
continuou por mais 400 ms, cruzou a fronteira e passou a espectador nas bancadas;
o seu token do piso foi removido, enquanto os outros três combatentes e tokens
permaneceram ativos. O estado nativo confirmou `held=[]`. Teleports do
espectador para o piso em `(20.5, 200, 10.5)`, `(20.5, -20, 10.5)` e
`(20.5, 80, 10.5)` foram todos negados sem terminar a partida. A saída normal
posterior de um colega terminou em `VICTORY` / `PLAYER_LEFT`; as quatro sessões
ficaram `CLOSED` e os quatro snapshots `RESTORED`. Hashes serializados nativos
confirmaram inventário, Ender Chest, XP, modo de jogo e posição baseline dos
quatro jogadores. Evidência privada:
`/private/tmp/ciaac-paper-local-test/verification-2026-10-05-native-boundary-forfeit-fixed/evidence.json`.
O total do laboratório chegou a 20 sessões `CLOSED` e 20 snapshots `RESTORED`.

A fronteira de espectadores e o percurso de saída ficam verificados apenas para
este caso local. Portais, fogo amigo, formatos assimétricos, 3v3 e crash com
quatro participantes continuam pendentes. O backend Tailscale permanece
indisponível; não houve ativação nem alteração de produção.

## Equipa nativa 3v3 e fogo amigo — 2026-10-05

No mesmo JAR `463af6b312bbf4ecc981629ff2644680ed58e09f1ebdb7c4b7bf2350a675da7c`
e Paper local 26.2-84, seis clientes nLogin normais formaram duas equipas 3v3
por desafio direto. O estado avançou por `RESERVED` e `READY` com contagens
`[3,3]`; após os seis estarem prontos, todos chegaram a `ACTIVE` com tokens de
piso. Um cliente real atacou um colega de equipa com espada de ferro. Um
observador nativo somente de leitura contou dois eventos reais
`EntityDamageByEntityEvent` no estágio `MONITOR`, ambos cancelados como fogo
amigo; a saúde dos seis permaneceu 20. O observador não alterou eventos nem
saúde.

A saída normal dos três membros de uma equipa terminou a partida em `VICTORY` /
`PLAYER_LEFT`. As seis sessões ficaram `CLOSED`, os seis snapshots `RESTORED`;
hashes serializados nativos confirmaram inventário, slots extra e Ender Chest,
XP, modo de jogo e posição exatamente iguais à baseline para os seis jogadores.
Evidência privada:
`/private/tmp/ciaac-paper-local-test/verification-2026-10-05-native-3v3-friendlyfire-portal/evidence.json`.
O laboratório totalizou 26 sessões `CLOSED` e 26 snapshots `RESTORED` neste
checkpoint.

Este ensaio confirma apenas o desafio 3v3, fogo amigo cancelado e a restauração
normal destes seis clientes no ambiente local. O teste de portal não foi
executado de forma conclusiva: o cliente parou de receber input e não foi
observado pacote de portal; portanto, não se afirma negação de portal. Equipas
assimétricas e crash com seis participantes continuam pendentes. Produção
permanece inativa e indisponível.

## Portais, formatos assimétricos e crash de seis jogadores — 2026-10-05

No Paper local 26.2-84 e JAR
`463af6b312bbf4ecc981629ff2644680ed58e09f1ebdb7c4b7bf2350a675da7c`, um cliente
nativo ativo tentou atravessar um portal do Nether. Um observador somente de
leitura registou uma tentativa `NETHER_PORTAL` em `MONITOR`, cancelada uma vez.
A partida terminou em `VICTORY` / `ESCAPE_ATTEMPT` (não `PLAYER_LEFT`);
inventário, Ender Chest, XP, modo de jogo e posição foram restaurados à baseline.
A evidência privada inclui amostras do portal, estado ativo e restauração em
`/private/tmp/ciaac-paper-local-test/verification-2026-10-05-native-portal-asymmetric`.

Foram também admitidos formatos assimétricos nativos 2v3 (`[2,3]`) e 3v2
(`[3,2]`) com cinco clientes autenticados nLogin, prontos e `ACTIVE` com tokens
de piso; o sexto cliente era apenas observador exterior e não contou como
participante. A saída normal dos três membros da equipa adversária terminou
cada partida em `VICTORY` / `PLAYER_LEFT`. Em ambas, os seis estados nativos
(inventário, Ender Chest, XP, modo de jogo e posição) corresponderam exatamente
à baseline após restauração.

Um ensaio separado de crash com seis participantes falhou o critério de
restauração completa. Antes do crash, todos os seis estavam autenticados e
`ACTIVE` com inventários temporários; foi terminado apenas o processo Java local
guardado, e o mesmo JAR/base foram reabertos. Após login nLogin normal, os seis
inventários, Ender Chests, XP e posições voltaram à baseline, mas quatro
jogadores cuja baseline era `SURVIVAL` regressaram em `ADVENTURE`; os dois cuja
baseline era `ADVENTURE` regressaram corretamente. Logo, embora as seis sessões
tenham ficado `CLOSED` e snapshots `RESTORED`, esta recuperação não é aceite
como correta até o modo de jogo também ser restaurado. Evidência privada da
falha e modos persistidos:
`/private/tmp/ciaac-paper-local-test/verification-2026-10-05-native-six-player-crash/failure-evidence.json`
e `persisted-baseline-modes.json`. Após estes casos, o laboratório totalizou
50 sessões `CLOSED` e 50 snapshots `RESTORED`; estes contadores não anulam a
falha de modo de jogo.

Portais e os dois formatos assimétricos estão verificados apenas nos casos
locais descritos. A recuperação após crash com seis jogadores permanece
reprovada para quatro modos de jogo e está sob investigação; nenhuma correção é
reivindicada. Produção continua inativa e indisponível.

## Seguimento do crash nativo com seis jogadores — 2026-10-05

Após cinco novos testes do listener de autenticação, `mvn verify` passou com
**403 testes, 402 aprovados, zero falhas/erros e um ignorado**. A build
experimental SHA-256
`18191810391d7685035f51406fa82e26cfa66ff950b6409757b1a981abd50a24` continua a
falhar a aceitação de crash com seis jogadores; não há correção aprovada. A
melhoria de origem verifica a ligação/época original antes de aceitar a
recuperação, mas não resolveu a ordenação entre o restauro do provider e o
listener nLogin.

Um primeiro ensaio com o artefacto `712b3ddab9cd6f867a9bed6ea572a31e2e25d99857e63fc57f69406da5b8aa06` também falhou; o cliente real expirou, deixando uma sessão pendente. Num
ensaio posterior, a sessão pendente foi recuperada corretamente após mudança de
build, mas esse caso isolado não é aceitação do cenário completo de crash. O
seguimento nativo atual iniciou outro 3v3 com seis clientes autenticados e
`ACTIVE`; contagens de diamantes no Ender Chest foram verificadas através do
namespace `minecraft:item` (`/item` sem namespace havia sido consumido pelo
Essentials e não conta como evidência). Os seis inventários nativos tinham
hashes distintos e modos baseline mistos `SURVIVAL, SURVIVAL, SURVIVAL,
ADVENTURE, SURVIVAL, ADVENTURE`.

Após guardar estado temporário e terminar apenas o processo Java loopback
protegido com SIGKILL, o mesmo JAR e base reiniciaram e os seis fizeram login
nLogin normal. Inventário, slots extra, Ender Chest, XP e posições foram
restaurados para os seis; porém o jogador real `r0das`, cuja baseline era
`SURVIVAL`, voltou em `ADVENTURE`. Os cinco peers sintéticos recuperaram o modo
correto. A trace indica que o restauro de mobilidade persistido foi `SURVIVAL`,
e o handler CIAAC restaurou `ADVENTURE` → `SURVIVAL` em
`15:09:56.187`; depois, o restauro limbo nLogin escreveu `SURVIVAL` →
`ADVENTURE` em `15:09:56.201`. A evidência privada inclui
`/private/tmp/ciaac-paper-local-test/verification-2026-10-05-native-six-player-crash-following-tick/failure-evidence.json`,
`native-mode-trace.log`, base e log pós-crash. O estado durável fechou 64 sessões
e restaurou 64 snapshots; estes contadores não provam restauração integral.

Depois de preservar a evidência, o fixture foi limpo manualmente, o modo do
jogador real voltou a `SURVIVAL`, e o servidor local e os peers foram parados.
Essa limpeza operacional não é evidência de recuperação correta. A leitura do
pipeline interno nLogin sugere transporte multi-fila sem garantia de atraso de
tick fixo; o significado público de conclusão autenticada continua sob revisão.
Não se afirma correção. Os ensaios nativos de portal e formatos assimétricos
acima pertencem ao JAR anterior `463af6b312bbf4ecc981629ff2644680ed58e09f1ebdb7c4b7bf2350a675da7c`;
não os atribuir ao artefacto experimental. Produção permanece inativa.

## Gate de conclusão de autenticação — 2026-10-05

A investigação do listener nLogin 2.0.24/API 10.4 confirmou que o sinal público
`isAuthenticated` e `AuthenticateEvent` podem ocorrer antes da limpeza do estado
limbo. O bytecode instalado inclui um transporte periódico assíncrono de
200 ms; o ramo de transporte escolhido em cada ligação não foi instrumentado.
O agendamento posterior em filas de entidades não oferece garantia
de ordem por número fixo de ticks. Portanto, nenhum atraso fixo de tick nem o
sinal público atual constitui prova de que a autenticação e a limpeza terminaram.

Os construtores públicos atuais do listener não emitem a capacidade de conclusão
necessária. Até existir uma integração suportada que produza essa capacidade,
o gate falha fechado: admissão de minijogos, autenticação do Passaporte e
recuperação autenticada de sessões pendentes permanecem fechadas e não são
aceites como utilizáveis. Snapshots e sessões pendentes são preservados; não há
desvio por configuração ou reflexão privada.

O candidato de revisão preservado, SHA-256
`c668014d7b255a9a7b45bf5294c7116c79d5b444d253d0e7ac1872b59e2c2366` passou
408 testes, 407 aprovados, um ignorado e zero falhas/erros. Nove testes do
listener verificam ausência de conclusão, mutação tardia, callback duplicado,
época substituída, desconexão e scheduler retirado. Um teste de recuperação
confirma que falta de capacidade deixa a sessão pendente intacta antes de
parar o jogo, ler snapshots ou emitir auditoria de restauro.

No artefacto nativo
`3d93e8cc2167bd6d3e0680ecb32584889a40257bdcf6fecca76dc4a0e2f7f532`, seis
clientes fizeram login nLogin normal e emitiram `AuthenticateEvent`; nenhum
recebeu capacidade CIAAC, token de piso ou participação. O cliente Modrinth
mostrou login bem-sucedido e negação de `/coliseu entrar`. Não foi criada
partida nem sessão nova; as 64 sessões e snapshots históricos permaneceram
`CLOSED`/`RESTORED`. Estes contadores não são aceitação de recuperação desta
build. A comparação de todas as 738 entradas dos dois JARs confirmou conteúdos
idênticos; apenas a embalagem ZIP mudou o digest no build final.

As verificações `clean verify` canónica e espelhada passaram os mesmos 408
testes. Os artefactos reconstruídos têm respetivamente SHA-256
`02d5971cef31bd50718b79d898a63a0a31b30c2a65c71c1c45fc1b4536ff3a0a` e
`7356ddf001f57eec44fa811f008c3a94d414f4df8f415c4e96fbbe7d5cc2e4f3`.
Os 738 conteúdos de entradas correspondem exatamente ao candidato de revisão;
não são apresentados como digests do ficheiro usado no ensaio nativo.

Evidência privada em
`/private/tmp/ciaac-paper-local-test/verification-2026-10-05-native-completion-gate`
inclui estado, eventos, negação renderizada e equivalência dos conteúdos. Os
cinco peers e o servidor loopback foram parados de forma controlada; o bridge
terminou com `held=[]`. Este gate impede admissão prematura, mas a integração
de conclusão suportada continua um bloqueio para tornar a Arena executável.
Não existe aceitação de gameplay ou crash no candidato atual, nem ativação de
produção.

### Contrato em falta e reprodução sanitizada

O próximo adaptador precisa de um callback por autenticação/ligação que só seja
emitido depois de todas as escritas limbo (incluindo conclusões assíncronas de
teleporte), sem trabalho nLogin posterior capaz de repor a baseline temporária.
Deve permitir ligação à época original, não emitir conclusão em falha/retirada
e não reexecutar recuperação por callbacks duplicados. Esse callback não está
exposto na API 10.4 inspecionada. Os
[eventos documentados](https://jd.nickuc.com/nlogin/allclasses-index.html) e a
[API interna pública](https://jd.nickuc.com/nlogin/com/nickuc/login/api/nLoginAPI.nLoginInternal.html)
também não documentam esse contrato; o
[guia oficial](https://docs.nickuc.com/nlogin/development/usage/) ainda indica
10.4 e admite eventos assíncronos. Estas páginas foram consultadas em
2026-10-05; ausência de documentação não prova o comportamento de versões não
testadas.

Reprodução local para revisão do fornecedor: Paper 26.2-84, nLogin 2.0.24,
contas sintéticas sem OP, modos baseline mistos `SURVIVAL`/`ADVENTURE`, estado
persistente capturado antes de equipamento/mobilidade temporários da Arena.
Admitir 3v3 por login/desafio/prontidão normais; guardar estado nativo, interromper
apenas o Java loopback descartável e reabrir o mesmo JAR/base. Após login normal,
observar o modo baseline restaurado pelo handler CIAAC e a escrita limbo nLogin
posterior que o substitui. Repetiu-se mesmo com callback CIAAC atrasado dois
ticks. O caso exige uma conclusão suportada do fornecedor ou um modo de integração
que comprove a ausência de escritas posteriores incompatíveis; trocar dois por
mais ticks ou corrigir apenas o evento de gamemode não prova inventário, voo ou localização.
Este registo prepara a reprodução, mas nenhum pedido foi enviado ao fornecedor.

## Investigação da configuração limbo nLogin — 2026-10-05

A [configuração oficial nLogin](https://docs.nickuc.com/nlogin/config/template/)
recomenda desativar `limbo.hide-player-stats` quando há conflitos com plugins que
gerem esses campos. A recomendação também consta do ficheiro fornecido pela
versão instalada 2.0.24. Isso constitui uma mitigação suportada a investigar;
a ausência de callback público não prova que toda a alternativa segura seja
impossível nem exige substituir o nLogin.

No Paper loopback descartável 26.2-84, foi alterado apenas
`limbo.hide-player-stats: false`. Login normal, ocultação de inventário e
`limbo.block-player-movement: true` foram mantidos. A variante instalada usa a
chave `limbo.hide-player-inventory`; não copiar a chave de inventário aninhada
do template web antigo sem confirmar a versão. Cinco contas sintéticas
tentaram `/coliseu entrar` antes do login: nenhuma recebeu autenticação CIAAC,
token de piso ou partida.

A reprodução usou exclusivamente o JAR histórico experimental
`18191810391d7685035f51406fa82e26cfa66ff950b6409757b1a981abd50a24`, cuja
recuperação era agendada dois ticks após `AuthenticateEvent`. O gate do código
e candidato atuais não foi alterado. Cinco clientes fizeram login normal,
formaram uma partida 2v3, confirmaram prontidão e chegaram a `ACTIVE` com cinco
tokens. A baseline incluía modos `SURVIVAL`/`ADVENTURE`, inventários e Ender
Chests não vazios e velocidades lentas num jogador. Após `save-all flush`, foi
interrompido apenas o Java loopback identificado pelo comando e wrapper exatos.
O mesmo JAR/base foi reaberto e os cinco jogadores voltaram a autenticar-se.

Neste único crash, os cinco estados observados corresponderam exatamente à
baseline: gamemode, voo permitido/ativo, velocidades, saúde, alimento, saturação,
exaustão, mundo, posição, digest de inventário/slots extra/Ender Chest e XP.
As cinco sessões novas terminaram `CLOSED` e snapshots `RESTORED` (totais
históricos 69/69). O êxito reduz o conflito de gamemode neste ensaio, mas não
constitui garantia de ordem entre filas, nem aceitação do candidato atual ou
do cliente Modrinth, que não foi usado neste experimento.

Uma reconexão normal posterior, sem recuperação CIAAC pendente, reproduziu uma
perda de estado restante: o jogador com walking speed `0.00002` e flying speed
`0.00001` ficou com `0.2` e `0.1` após login. O bytecode instalado confirma que
o ramo de restauro com stats desativados ainda chama `setWalkSpeed` e
`setFlySpeed`, normalizando valores baixos. A proteção de movimento é uma opção
independente; desativá-la não elimina esses setters do restauro. O restauro de
localização também é independente e inclui um caminho `teleportAsync` sem
callback público de conclusão. O nLogin chama `updateInventory`, mas a revisão
não encontrou setters de conteúdo Bukkit: isso é uma atualização da vista,
não prova de substituição dos itens no servidor.

Conclusão delimitada: `hide-player-stats: false` é uma mitigação promissora para
o gamemode, mas isoladamente não verifica restauro integral ou uma barreira de
conclusão. Antes de abrir o gate, continua necessário um contrato suportado de
conclusão, ou um perfil suportado que demonstre que todas as escritas restantes
são compatíveis com o estado CIAAC. Não se aceitou atraso arbitrário, polling
de `isAuthenticated` ou reflexão privada como contrato. Nenhum pedido foi
enviado ao fornecedor e nenhuma alternativa foi implementada por suposição.

Evidência privada:
`/private/tmp/ciaac-paper-local-test/verification-2026-10-05-nlogin-stats-disabled`,
incluindo baseline, pré-login, crash, comparação integral e reconexão normal.
No fim, os cinco peers foram fechados, Paper terminou com exit 0 e a porta
loopback ficou fechada. Configuração nLogin, JAR com gate e observer originais
foram restaurados byte a byte. Backend offline, Crafty e credenciais não foram
contactados ou alterados.

## 2026-10-05 — AuthMe opcional e recuperação com conclusão suportada

- Ocorrência, verificação e documentação: 2026-10-05 (`Europe/Lisbon`).
- Estado: `RUNTIME-VERIFIED` apenas para os casos abaixo; nLogin atual fechado,
 aceitação completa e produção `UNVERIFIED`. Backend offline; trabalho nesse
 destino suspenso, sem probes ou alterações Crafty nesta investigação.
- Paper local: 26.2 build 84, `127.0.0.1:25567`, mundo sintético e cinco contas
 novas AuthMe. nLogin/dados originais preservados; nenhuma migração de contas.
- AuthMe Paper oficial: `6.0.1-b2770`, SHA-256
 `7704335e9e73a634d9d926344f77897f4c74f78f82453a59f5aa0f8d2722450a`.
- JAR CIAAC do ciclo final, idêntico antes/depois do crash: SHA-256
 `9fd508fef8d5fcd0c3f0c52f8e7b5fbe43d4a4e3bb7377add6f17fd20e4d2805`.

O novo adaptador público aplica o [perfil revisto](authentication.md). A primeira
tentativa de crash expôs uma incompatibilidade real: o filtro de isolamento
bloqueava `/login` durante recuperação pendente. A primeira correção assumia
`PluginCommand`, mas AuthMe Paper regista comandos Brigadier. A correção final
usa o mapa público de comandos e `PluginIdentifiableCommand`, verifica o único
fornecedor ativo e permite apenas login quando falta autenticação CIAAC.
Comandos externos e cancelamentos impostos por outros listeners são preservados.

Resultados nativos:

1. Cinco ligações antes do login: zero capacidades CIAAC, participantes ou
  tokens de piso. O registo de conta sozinho não concedeu autenticação CIAAC.
2. Login normal através do AuthMe real concedeu capacidade ao mesmo `Player`
  e ID de ligação, depois do `LoginEvent`. Sem eventos falsos, força de login,
  contas op, reflexão privada sobre AuthMe ou alterações ao registry pelo observer.
3. Partida `2v3 kit` atingiu `ACTIVE`, com cinco combatentes e tokens de piso.
  Saída normal restituiu exatamente os 13 campos para os cinco jogadores.
4. Nova partida `2v3 kit`, `save-all flush`, interrupção apenas do Java Paper
  pertencente à fixture, reinício com o mesmo JAR/configuração/base de dados e
  cinco logins normais. Nenhuma recuperação antes da autenticação; depois,
  todos os 13 campos coincidiram exatamente. Nenhum participante/token residual.
5. Campos comparados: game mode, `allowFlight`, `flying`, velocidades de marcha
  e voo, saúde, comida, saturação, exaustão, mundo, posição, hash conjunto de
  inventário/extra/ender chest e XP (nível, fração, total). Baselines misturavam
  `SURVIVAL`/`ADVENTURE`, ender chests não vazios e velocidades `0.00002`/`0.00001`.
6. A base CIAAC terminou com 104 sessões `CLOSED` e 104 snapshots `RESTORED`;
  estes totais incluem histórico da fixture, não 104 novos casos nesta investigação.
7. Uma edição apenas de comentário no perfil revogou as cinco capacidades
  existentes. Repor o ficheiro no mesmo processo não as reativou.
8. Paper terminou com código zero, peers foram fechados, porta local ficou
  fechada e os três JARs originais da fixture foram repostos byte a byte.

O relogin ordinário posterior, sem snapshot CIAAC pendente, normalizou as
velocidades pequenas para `0.2`/`0.1`. A fonte oficial
`LimboServiceHelper.createLimboPlayer` substitui velocidades `<= 0.01` antes
de capturar o novo limbo. Não é sobrescrita tardia do restauro CIAAC: os ensaios
de saída/crash preservaram esses valores depois do evento de conclusão.
Esta limitação de comportamento do fornecedor deve ser avaliada antes de
escolher AuthMe para produção. A fixture manteve o aviso já conhecido de
Essentials sobre versão Paper não suportada; não se afirma compatibilidade
geral dessa stack.

Menus imediatos (incluindo a tarefa tardia AuthMe de fechar uma vista), hits PvP,
apostas, espectadores, clientes humanos e aceitação completa dos outros modos
não foram repetidos nesta build. Não reutilizar os resultados de combate de
builds históricas como prova da atual. Nenhum deployment foi realizado.
A investigação não prova uma integração nLogin utilizável.

Evidência privada conservada em
`/private/tmp/ciaac-paper-local-test/verification-2026-10-05-authme-completion/verified-profile-final`:
artefacto identificado, baselines, estados pré-login, comparações, logs e
`evidence.json`. Não copiar logs/configuração privada para o repositório.

A build final incluiu também a limpeza de tentativas de autenticação na saída,
mesmo quando o listener de ciclo de ligação já removeu a época, e exige
`GroupOptions.enablePermissionCheck: false` para impedir a troca temporária
de grupos AuthMe. Mantém as verificações de permissões LuckPerms/comandos.
O ciclo completo de partida/saída e crash foi repetido no hash final acima,
com os 13 campos exatos para os cinco jogadores e revogação por edição do perfil.
A suite final passou 428 testes: 427 aprovados, zero falhas/erros, um ignorado.

## 2026-10-05 — Aceitação AuthMe nativa adicional no JAR atual

Os ensaios abaixo usam o mesmo JAR CIAAC
`9fd508fef8d5fcd0c3f0c52f8e7b5fbe43d4a4e3bb7377add6f17fd20e4d2805`; a
reconstrução preservou os membros internos do ZIP do JAR guardado. O Paper
26.2 build 84 descartável usou AuthMe `6.0.1-b2770`, seis identidades
autenticadas por login normal (um cliente Modrinth real e cinco peers), sem
acesso à produção ou migração de contas.

- Num 3v3 `fixed`, o observer registou três hits de fogo amigo, todos cancelados,
  e três hits de adversários não cancelados. A saúde das vítimas de fogo amigo
  permaneceu 20; os hits adversários causaram dano. Um disparo de arco foi
  aceite. O fixture local acrescenta arco e flechas ao kit; o exemplo
  canónico/preparação do kit fixo não inclui esses itens. O uso visual do escudo
  não demonstra bloqueio de dano recebido.
- Uma eliminação removeu o participante ativo e o token do piso, conservando a
  partida; o jogador ficou em `SPECTATOR` nas bancadas. Uma tentativa de entrada
  por teleporte para Y=200 manteve-o fora e sem token. A prova disponível não confirma a
  tentativa abaixo do mundo.
- A saída normal terminou a partida por `PLAYER_LEFT`. Os cinco peers
  coincidiram nos 13 campos com a baseline; no cliente humano, a posição teve
  delta de 0,058 blocos. Num crash separado de 3v3 com equipamento protegido,
  foi terminado o processo Java local. Após login AuthMe normal, os 13 campos
  coincidiram exatamente para os seis participantes. Amostras em 1, 6 e 15
  segundos após login não mostraram alteração tardia. Antes do login, o humano
  não tinha capacidade CIAAC nem token de piso.
- Um convite 1v1 clicável no chat, aceite pelo destinatário, chegou a `ACTIVE`.
  O desafio apostado foi recusado como `REQUEST_INVALID`, sem partida nem
  escrow. Não se testou payout, settlement ou refund. Um 2v2 chegou a `ACTIVE`
  e o resultado persistido foi `DRAW` / `ROUND_TIMEOUT`.
- Durante uma partida, o cliente atravessou um portal Nether físico em
  `x=8`, `y=80..82`, `z=10..11`, com o eixo do portal em Z. O observer registou
  um `PlayerPortalEvent` real `NETHER_PORTAL` e um cancelamento; o resultado foi
  `VICTORY` / `ESCAPE_ATTEMPT` e o jogador foi restaurado.
- O chat de ajuda e a apresentação do inventário foram vistos num cliente
  humano. A corrida de fecho de vista por tarefa AuthMe tardia imediatamente
  após login continua por testar.

A execução final, confirmada pelos XML do Surefire, teve 428 testes: 427
aprovados, um ignorado e zero falhas/erros. O primeiro Maven run falhou ao criar
diretórios temporários para testes de symlink; uma execução posterior com
diretório temporário definido passou. O contador histórico da base local passou
a 122 sessões `CLOSED` e 122 snapshots `RESTORED`; são acumulados históricos,
não 122 casos novos. A aceitação não cobre 3v2 no mesmo hash, todos os fluxos de
espectador/limites verticais, todos os menus nem a integração completa.

A limpeza está confirmada em
`/private/tmp/ciaac-authme-e2e-2026-10-05/cleanup-evidence.json`: nenhum processo
Java da fixture ou listener local foi encontrado, os peers estavam desligados e
os três JARs originais e o perfil AuthMe foram repostos por hash. A base local
não foi revertida. O encerramento gracioso do Paper não foi confirmado, pois os
identificadores do processo original já não estavam disponíveis. Produção,
migração AuthMe e aceitação de ativação continuam `UNVERIFIED`.

## 2026-10-05 — Fecho dos ensaios nativos e staging

A continuação usou o mesmo JAR CIAAC `9fd508fef8d5fcd0c3f0c52f8e7b5fbe43d4a4e3bb7377add6f17fd20e4d2805`
e o perfil AuthMe revisto, agora com o kit de produção sem arco/flechas:
espada e escudo, com armadura de ferro equipada automaticamente.

- 3v2 `fixed` chegou a `ACTIVE`; a desistência terminou em `VICTORY / PLAYER_LEFT`.
  Os cinco peers coincidiram exatamente nos 13 campos e ficaram sem participante/token.
- O cliente real, sem token de piso, tentou entrar por movimento normal e por
  teleporte para Y=-40 e Y=200. Todas as tentativas ficaram fora do retângulo.
  O movimento foi repetido com piso exterior contínuo para separar a barreira
  da queda causada pelo terreno sintético. Só a fixture local recebeu esses blocos.
- Num 1v1 com o kit de produção, dois hits reais adversários foram aceites e
  causaram dano. Duas tentativas de largar a espada pela vista do inventário
  foram recusadas; o digest voltou a coincidir depois de fechar a vista/cursor.
  A saída restaurou os 13 campos do peer e 12 campos exatos do cliente humano;
  neste último, a diferença de posição foi 0,058 blocos após movimento.
  Nenhum participante/token ficou ativo e o inventário original foi restaurado.
- A vista nativa do inventário permaneceu aberta em cinco amostras que abrangem
  a transição pré-login/autenticado. Isto cobre o fluxo normal do cliente;
  não demonstra uma corrida com `InventoryOpenEvent` de um GUI aberto por outro
  plugin, cuja tarefa AuthMe de fecho tardio exige ensaio próprio se aplicável.

O estado persistente terminou com 129 sessões `CLOSED` e 129 snapshots `RESTORED`,
totais históricos, não 129 casos novos. Paper terminou normalmente com código
zero e gravação dos mundos; todos os peers foram fechados e a porta local ficou
fechada. Os três JARs e as configurações originais CIAAC/AuthMe foram repostos
byte a byte. Bases e mundos não foram revertidos.

Evidência privada em `/private/tmp/ciaac-authme-e2e-2026-10-05/final-client-checks`;
os resumos sanitizados e os JARs identificados estão no pacote privado de integração
`runtime/staging/authme-arena-2026-10-05`. Estado atualizado:
`STAGED_LOCAL_TESTED_CANDIDATE_TARGET_PENDING`. Estão verificados os percursos
AuthMe/Arena ensaiados, não todos os minijogos ou integrações. Apostas permanecem
desativadas, a mitigação de um golpe recebido com escudo não foi medida e a
migração de contas/stack/permissões de produção continua `UNVERIFIED`.
Nenhum acesso ao backend foi feito.

## Snapshot histórico — 2026-10-07 prontidão (`0547103…`)

A correção de prontidão passou 445 testes (444 aprovados, um ignorado).
Reserva por fila/desafio notifica ambos com `/coliseu pronto`; primeira confirmação
não prepara nem desloca, segunda prepara e inicia. Cancelamento pré-combate,
timeout, falha de entrega UI, quarentena e limpeza de grupos têm regressões.
Estes testes usam proxies Bukkit e equipamento espelhado; não equivalem a um
inventário de kit fixo nativo. A nova revisão está descrita no
[registo de ativação](arena-activation.md).
O utilizador confirmou ausência de um segundo participante de produção; a
comparação nativa completa de inventário/XP/posição/modo antes e depois de uma
partida no alvo continua pendente desse acesso, sem forçar identidades alheias.

Na revisão instalada, entrada e saída da fila foram ensaiadas no cliente nativo:
os 14 campos comparados de inventário/ender/XP/vitais/modo/abilities/posição/rotação
ficaram iguais ao baseline após login; a fila terminou vazia. O login normal
preservou inventário, ender chest, XP e modo, mas alterou posição (evacuação
prevista) e voo na reconexão. Isso não prova equipamento do kit e restauro de uma
partida completa. O arranque e os bytes do JAR/config/região instalados passaram
a verificação final independente.

## 2026-10-07 — Aceitação nativa de combate e desconexão

O JAR atualmente implantado SHA-256
`d6cf9c8eca8d19352e3488f901a83eb8c1348e5471409b69bebadfc8040155cb` substituiu
`0547103…`; 449 testes, 448 aprovados, um ignorado e zero falhas/erros.
No percurso público real, proprietário e conta AuthMe comum aprovada foram
teletransportados para os dois spawns de combate e receberam seis itens temporários
etiquetados. Uma confirmação não alterou os inventários originais. Depois da
remoção explicitamente autorizada da proteção NPP da conta de ensaio, ataques
nativos reduziram a saúde sintética de 20 para 8.7333355. Ataques seguintes
terminaram em eliminação letal e declararam o vencedor. Ambos regressaram às bancadas. Os 15 campos NBT foram
exatamente restaurados para cada jogador: `Inventory`, `EnderItems`, `equipment`,
`XpLevel`, `XpP`, `XpTotal`, `playerGameType`, `SelectedItemSlot`, `Health`,
`foodLevel`, `foodSaturationLevel`, `foodExhaustionLevel`, `abilities`, `Pos` e
`Rotation`.

Numa partida ativa separada, desconectar o adversário sintético restaurou
imediatamente os 15 campos do proprietário que permaneceu ligado. O cliente
AuthMe sintético voltou a ligar-se e reautenticou-se com a
mesma palavra-passe; após a recuperação, ambos os jogadores tinham os 15 campos
exatos. Isto confirma o duelo 1v1 fixo e a saída normal. Grupos maiores,
espectadores e outros minijogos não estão verificados.

O encerramento revoga a ligação anterior e mantém a partida pendente até nova
época autenticada, ignorando atores offline/não autenticados e atores já
restaurados. Só fecha após todas as restaurações; falha mantém quarentena.
SQLite/WAL schema 6 e integridade passaram: 7 fechamentos e 7 restauros, somente
a quarentena QA sintética original preservada, sem falhas de restauro atuais.
O caso sintético no JAR anterior com `RESTORE_FAILED` permanece histórico; não reexecutar nem
apagar a respetiva quarentena. Detalhes de operação estão em
[ativação da Arena](arena-activation.md). Os resumos locais do fixture são
históricos e não substituem a aceitação pública subsequente.
