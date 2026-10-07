# Ensaios físicos num Paper local descartável

Este probe exercita eventos e física reais de pistões com o listener de proteção
da CIAACPlatform. Verifica também o layout real do kit fixo (armadura e escudo),
cópias dos itens configurados e etiquetas de sessão. Com EssentialsX 2.22.0 e
Vault reais, verifica ainda o guard de economia e journal com uma conta sintética
preparada pela API do plugin: rejeição de conta ausente sem a criar, captura,
reabertura, replay, auditoria única e recusa de sobrescrever um saldo alterado.
O fixture prepara essa conta com `ModernUserMap.getUser(Player)` e confirma
que a API normal a colocou no cache real `userCache`; não escreve no cache
obsoleto `getOnlineUserCache()`. Remove apenas a entrada sintética no fim e não representa
uma conexão de jogador. Verifica também o modelo real de homes, ordem dos aliases
numéricos, rejeição de UUID errado/conta ausente e um UUID de mundo não resolvido
que conserva a referência original sem carregar o mundo. A conta e os homes são
sintéticos; a leitura não usa o getter Essentials que normaliza referências.
Com SimpleClaimSystem 1.13.1 real, verifica também dois claims de owners
distintos com `id_claim=1`, leitura pelo modelo já carregado e comparação com o
índice persistente. O owner sintético é
`00000000-0000-0000-0000-000000000001`. O fixture confirma os claims próprios e
de membro pelas APIs reais, captura combinada de claims/homes com journal e
replay, rejeita drift de permissões sem sobrescrever o valor externo e injeta e
remove uma linha temporária na base local para confirmar que uma divergência
entre DB e API falha fechada. Esta verificação altera apenas o servidor e a base
de claims descartáveis.
Não testa admissão,
autenticação, equipamento aplicado a um jogador, restauro de jogadores ou
partidas. Não substitui os fornecedores de estado externo em falta.

Com LuckPerms 5.5.65, o probe verifica a ligação de produção à instância real do
serviço LuckPerms selecionado. Confirma que a leitura da UUID fixa ausente não
carrega/cria utilizador; cria um utilizador e grupos sintéticos pela API, com
nós normais/transitórios, permissões `false`, contextos, expiração e herança
encadeada; testa validação de UUID, captura e replay de ENTER/PURGE/RESTORE após
reabrir journal e SQLite, auditoria única e recusa de restauro após alteração de
nodes do utilizador/grupo sem desfazer a alteração externa. No fim, guarda o
utilizador apenas com a associação e grupo primário `default` antes de o
descarregar e remover o mapeamento UUID/nome (`deletePlayerData` não remove os
nós persistentes). A eliminação de cada grupo é tentada mesmo que uma etapa de
limpeza falhe; as falhas são anexadas ao erro principal ou reportadas
diretamente. Não autentica nem admite um jogador, nem verifica a
autorização/admissão.

O fixture de projéteis cria arrows normais/espectrais reais, com intenção
durável antes da inserção. Confirma eventos nativos, flags de isolamento,
cancelamento, recusa de drift antes de qualquer remoção, purge por UUID e
preservação de um arrow exterior. No Paper fixado, `isInWorld()` pode continuar
verdadeiro após `remove()`; a remoção direta só fica provada por entidade
inválida/morta e ausência no índice UUID.

O fixture direto do provider usa manifestos e identidades sintéticos para
captura, ENTER, reabertura do ledger/journal, PURGE/RESTORE repetidos, quatro
auditorias únicas, recusa de restauro antes do purge e invalidade permanente
após falha de lifecycle. Só este fixture chama `lifecycleReady()` diretamente.

O novo `ArenaProjectileLifecycleProbe` é um fixture nativo assíncrono separado:
regista a própria instância do listener, usa o namespace PDC do plugin de probe
e o seu ledger privado, sem colidir com a etiqueta/ledger do runtime. Testa
remoção direta, hit em bloco e tracking-end após unload num chunk privado
`(16,16)`, com ticket temporário; usa a pedra de apoio em `(3,202,3)` e verifica
que o purge não toca num arrow exterior. No fim remove apenas as entidades que
o próprio fixture criou.
No unload, o Paper deixa o objeto não persistente vivo, inválido e fora do
índice UUID. O callback valida esse objeto e a etiqueta contra o ledger, chama
`remove()` no objeto exato e só então regista remoção após confirmar que está
morto, inválido e sem índice. Não interpreta um UUID lookup vazio, por si só,
como autorização para apagar ou marcar como removido.

Separadamente, `CiaacPistonProbe` confirma a ligação do runtime de produção:
exatamente um `ArenaProjectileLifecycleListener`, o listener de proteção de
regiões, um `ArenaWorldStatePort` disponível apenas para `ARENA` e o ficheiro
privado do ledger. Essa verificação usa a instância real carregada pela
CIAACPlatform; não simula autenticação, admissão ou combate. O fixture manteve
a configuração/admissão da partida fechada. Nenhum destes ensaios prova a
entrada de um jogador autenticado, uma partida real, restauro de sessão após
crash ou implantação.

## Pré-requisitos e efeitos

- JDK 25, Python 3 e `mvn verify` concluído no repositório canónico. Na
  verificação associada ao checkpoint de 2026-10-04: 366 testes, zero falhas,
  zero erros e um teste ignorado.
- Paper 26.2 build 84, commit `26e81c4`, com o JAR da CIAACPlatform correspondente ao código compilado.
- EssentialsX 2.22.0, Vault, SimpleClaimSystem 1.13.1 e LuckPerms 5.5.65
  instalados no servidor descartável. Para LuckPerms, usar somente o JAR
  previamente identificado com SHA-256
  `8b842d9d95c3f3c056e471214d156c0d3d704a46cbdd1a04a9bc47281d54b8a3`.
  O JAR SimpleClaimSystem 1.13.1 deve corresponder a SHA-256
  `e9c5cfe9c3b39f5f9e13bc122c5b427109fe37b57f5e43d0d559c24a9ef1acab`.
- Antes do arranque, a base local do servidor parado contém os dois claims
  sintéticos documentados no contrato do fixture, com owners `...0001` e
  `...0002`, ambos relevantes para `...0001`, no mundo `ciaac-synthetic-test`:
  chunks `(10,0)`/`(11,0)`, preços 17/23, respetivamente, e permissões das três
  categorias todas falsas. A segunda claim inclui `...0001` como membro. Os
  chunks afastados evitam interferência das regras SCS no ensaio de pistões.
- Servidor descartável, sem dados de produção, com `server-ip=127.0.0.1`,
  `server-port=25567` e `level-name=ciaac-synthetic-test`.
- EULA aceite pelo operador e autorização explícita para modificar este mundo.

O probe exige também a opção JVM `-Dciaac.local-test-probes=true`. Se qualquer
condição do alvo falhar, desativa apenas o próprio plugin antes de alterar o
mundo. Não acrescentar esta opção a um servidor normal.

No alvo autorizado, apaga o volume X=7..11, Y=199..201, Z=7..13, cria dois
pistões e alimenta-os com redstone. Mantém temporariamente o chunk (0,0)
carregado com um ticket do plugin. Verifica que uma cabeça vazia não entra na
região protegida, que o pistão exterior funciona e que uma cabeça protegida não
desaparece numa retração real. A retração parte de um pistão já estendido,
preparado pelo próprio fixture. O probe deixa também a conta sintética Essentials
e bases locais de ensaio fora de Git; remove a referência temporária ao cache
online. Não restaura os blocos do fixture;
remove o ticket e a carga forçada e encerra o servidor após o resultado.
Usar exclusivamente um mundo que possa ser descartado, sem carga forçada prévia.

## Compilação e execução

Depois de `mvn verify`, com `JAVA_HOME` apontado para o JDK 25:

```sh
python3 tools/paper-runtime-probes/build.py
```

O comando usa o classpath resolvido dos relatórios Surefire e cria apenas
`target/local-paper-probes/CiaacPistonProbe.jar`. Não instala nem inicia nada.
Copiar esse JAR para `plugins/` do servidor descartável e iniciar o Paper com
a opção JVM acima. Não incluir o probe em pacotes de implantação.

O resultado fica em `plugins/CiaacPistonProbe/evidence.json`. Só `result: PASS`,
com eventos bloqueados de extensão/retração e uma extensão exterior permitida,
mais os dez marcadores booleanos `true`, confirma os fixtures enumerados:
`fixedKitLayoutAndTags`, `inventorySnapshotLayout`,
`essentialsEconomyAuthorityAndJournal`, `essentialsLoadedHomeModel`,
`luckPermsAuthorityAndJournal`, `claimsAndHomesAuthorityAndJournal`,
`arenaProjectileOwnership`, `arenaWorldPortAndJournal`,
`arenaProjectileLifecycle` e `arenaWorldRuntimeBootstrap`. Os campos Essentials,
LuckPerms e claims/homes referem-se aos fixtures compostos, concluídos antes do
ensaio físico.

Checkpoint verificado em Paper **26.2 build 84, commit `26e81c4`**: SHA-256 do
JAR CIAACPlatform `c6bc1d6ce5c49989c0057405aee66a78ddb947e884e768a891b9b5ca6ba6cec8`;
SHA-256 do probe `5b81cc8087809ff1efe51433347796733cb8f71e470b2d25e601e2b7a7839813`.
Nesse arranque, ambos os campos Arena foram `true`, o servidor encerrou com
exit code 0 e o runtime fechou o provider no encerramento. Conferir também o
log do arranque e o encerramento limpo quando repetir o ensaio.
No macOS/Linux, o ficheiro de evidência recebe permissões `0600`.

Para voltar ao servidor de ensaio normal, remover apenas o JAR do probe e a
opção JVM. Os ficheiros de mundo, logs e evidência ficam fora de Git.


## Crash nativo do recurso — fixture separado

`ArenaWorldRestartProbe` acrescenta um caminho de duas execuções, separado dos
marcadores do ensaio normal. Exige as mesmas condições de loopback/mundo/JVM,
zero jogadores, o build Paper fixado e
`-Dciaac.local-test-restart-phase=seed` ou `verify`, com o mesmo
`-Dciaac.local-test-restart-id=<id-local>` de 1–64 letras minúsculas/números/hífens.
Sem essa fase, o probe executa o ensaio normal. Os IDs do fixture são sintéticos;
o objeto Player só fornece o UUID ao port direto, sem sessões/autenticação.

A fase seed cria um ledger/journal/audit privados em
`plugins/CiaacPistonProbe/restart-<id-local>`, uma lease ARMED e um arrow normal
não persistente confirmado em `(51.5,205,3.5)`. Cria também um arrow persistente
exterior em `(52.5,205,3.5)`, conserva os dois UUIDs no ficheiro privado e chama
save do mundo. Um ticket do próprio probe mantém o chunk `(3,0)` plenamente
carregado; o fixture espera os ticks nativos antes da inserção e do marcador
`result=SEED_READY`. A escrita do marcador é sincronizada e atomicamente
substituída. O processo seed permanece ativo para o driver local de crash.

Só após esse marcador, o driver autorizado termina abruptamente o **processo
filho que ele próprio iniciou**, sem executar shutdown do Paper. A segunda
execução usa `verify` e o mesmo id, carrega o chunk e exige que o controlo
persistente realmente tenha sobrevivido. Confirma o arrow owned ausente, mas
não o declara removido: purge e restauro têm de recusar precisamente a ausência
sem reconciliação. Exige a linha CONFIRMED e lease ARMED intactas, apenas as duas
auditorias CAPTURE/ENTER, e controlo exterior válido. Depois dessa prova, remove
só o controlo que o fixture criou, libera o seu ticket e encerra o Paper.

Verificação em **2026-10-04**: PASS com processo seed terminado por sinal 9 e
processo verify terminado com código zero, porta loopback fechada. O ficheiro
privado `evidence.properties` passou `absentOwnedArrowRejected`,
`durableIntentPreserved` e `unrelatedPersistedArrowPreserved`; conserva
`authenticatedPlayerRecovery=UNVERIFIED`. JAR CIAAC executado:
`c6bc1d6ce5c49989c0057405aee66a78ddb947e884e768a891b9b5ca6ba6cec8`;
probe deste ensaio:
`78f9ec28f64b477ed447a625eb6e9f84f146a5b2ab96d1fb64fe317d69a43a0c`.
O primeiro seed recusou confirmar um arrow sem tracking completo; esse processo
encerrou antes de qualquer crash e ficou preservado como FAIL. O ticket e a
espera de tracking corrigiram o fixture sem alterar a validação de produção.
Este teste prova a fronteira de recuperação do recurso, não recuperação de um
jogador, entrada autenticada ou restauro integral. Os ficheiros do driver,
logs, UUIDs de entidades e bases de dados ficam fora de Git.

## Snapshot nativo de inventário — 2026-10-04

O probe de snapshot executou o codec de produção sobre `ItemStack` nativos do
Paper e passou os dez marcadores booleanos, incluindo
`inventorySnapshotLayout`. O fixture confirma round-trip de 36 slots de
armazenamento, quatro de armadura, três extra (off-hand, corpo e sela), 27 slots
de Ender Chest, cursor e slot selecionado; também preserva espada com dano 7.
O comparador trata `null` e `AIR`/stack vazio como equivalentes, conforme o
codec nativo. Um array malformado com apenas um slot extra é recusado durante
captura, antes de commit.

O ensaio usa holders sintéticos e prova o codec, não restauração de inventário
numa sessão ligada. JAR CIAACPlatform SHA-256:
`effa5c9a1639dda1623c4874cd887daf3a3c83253f9cc152572fe48339c44c0e`.
Probe SHA-256:
`bba94d9452098284a2f8b2b1738d56082fb51a948aef18119a0295ef57049857`.

O fixture EssentialsX 2.22.0 anterior preenchia um mapa não usado, mascarando
o caminho normal. A preparação agora cria o utilizador sintético por
`ModernUserMap.getUser(Player)`, confirma a presença no `userCache` privado real
e remove-o no fim. A leitura da produção permanece não carregadora via
`GuavaCache.getIfPresent` da versão exata 2.22.0. Isto não fabrica autenticação
nem uma sessão real.
