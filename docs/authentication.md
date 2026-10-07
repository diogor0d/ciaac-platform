# Autenticação e conclusão do restauro

- Verificado localmente: 2026-10-05 (`Europe/Lisbon`).
- Produção: migração autorizada em 2026-10-06 para AuthMe 6.0.1-b2770;
  25 contas convertidas com hashes e UUIDs offline preservados. Login normal
  pelo endereço público e entrada/saída da fila: `RUNTIME-VERIFIED`.
- nLogin preservado com carregamento desativado; Arena ativada. Uma partida
  com dois jogadores em produção ainda não foi verificada.

A CIAAC só admite um jogador e recupera um snapshot depois da autenticação e
do fim do restauro do fornecedor, para o mesmo objeto `Player` e ID de ligação.
Uma ligação Paper, UUID ou flag de autenticação isolada não fornece essa prova.
Sem prova válida, mantém a admissão e a recuperação fechadas e preserva snapshots.

## nLogin nos ensaios locais de 2026-10-05

O nLogin 2.0.24 observado expõe API 11 em runtime; a dependência de compilação
é API 10.4. Nenhuma fornece o callback necessário ao adaptador atual.
`AuthenticateEvent` pode anteceder restauros tardios. Os construtores públicos
de `NLoginAuthenticationListener` continuam fechados; o arranque emite
`NLOGIN_STATE_COMPLETION_UNAVAILABLE`.

Desativar `limbo.hide-player-stats` resolveu o caso observado de game mode, mas
deixou restauros de velocidades. Temporizadores e velocidades positivas não
identificam fiavelmente o fim da limpeza quando outros plugins alteram velocidades.

## AuthMe opcional

O adaptador suporta a release Paper AuthMe `6.0.1-b2770`, com versão e bytes dos
componentes revistos verificados. Usa apenas APIs públicas `AuthMeApi`, `LoginEvent`
e `LogoutEvent`; não lê contas/passwords nem adiciona dependências ao JAR CIAAC.
Dois fornecedores ativos, versão diferente ou perfil inválido mantêm o gate fechado.

Na [implementação oficial](https://github.com/AuthMe/AuthMeReloaded/blob/6.0.1/authme-core/src/main/java/fr/xephi/authme/process/login/ProcessSyncPlayerLogin.java),
o `LoginEvent` sucede ao restauro de limbo. O perfil elimina teletransportes,
comandos e transferências posteriores. O callback CIAAC é agendado na entidade e
revalida ligação, fornecedor, perfil e autenticação antes da recuperação.
O evento de registo e a flag de autenticação isolada nunca concedem acesso.

Aplicar explicitamente em `plugins/AuthMe/config.yml` antes de um arranque completo:

```yaml
settings:
  restrictions:
    noTeleport: true
  GameMode:
    ForceSurvivalMode: false
  registration:
    force: true
    forceKickAfterRegister: false
    forceLoginAfterRegister: true
Hooks:
  bungeecord: false
  sendPlayerTo: ''
  useEssentialsMotd: false
  disableSocialSpy: false
limbo:
  recreateEnderPearls: false
GroupOptions:
  enablePermissionCheck: false
```

Os sete mapas de `plugins/AuthMe/commands.yml` ficam na raiz e devem estar vazios
depois das migrações de configuração feitas pelo AuthMe:

```yaml
onJoin: {}
onLogin: {}
onSessionLogin: {}
onFirstLogin: {}
onRegister: {}
onUnregister: {}
onLogout: {}
```

Só `ServerLoadEvent.STARTUP` estabelece a prova. Reload, desativação/substituição
do fornecedor, edição do perfil, logout, desconexão ou perda de autenticação
revogam a capacidade. Repor ficheiros no mesmo processo não reabre o adaptador;
exige novo arranque. A deteção usa identidade, tamanho e data de modificação dos
ficheiros; não protege contra alterações deliberadas que preservem esses metadados.

O perfil desativa a troca temporária de grupos do AuthMe. As verificações normais
de permissões continuam a cargo do LuckPerms e dos comandos; este requisito não
concede permissões administrativas nem desativa essas verificações.

A recuperação pendente permite apenas o login do fornecedor ativo. A propriedade
do comando é verificada por `PluginIdentifiableCommand`, incluindo comandos
Brigadier AuthMe e aliases `l`/`log`. Outros comandos externos e logout numa sessão
já autenticada continuam bloqueados. Cancelamentos de outros listeners são preservados.

## Evidência e limites

Cinco contas sintéticas passaram login normal, `2v3 kit`, saída normal e crash
com o mesmo JAR antes/depois. Os 13 campos comparados coincidiram exatamente
com as baselines. Editar o perfil revogou o acesso e repor o ficheiro não o
reativou. Consultar [verificação funcional](functional-verification.md).

## Aceitação nativa adicional — 2026-10-05

No mesmo JAR `9fd508fef8d5fcd0c3f0c52f8e7b5fbe43d4a4e3bb7377add6f17fd20e4d2805`,
seis participantes (o cliente Modrinth `r0das` e cinco peers sintéticos)
autenticaram-se pelo AuthMe normal. Um 3v3 `fixed` chegou a `ACTIVE`. O observer
contou três hits de fogo amigo, todos cancelados, sem perda de saúde; três hits
entre adversários foram aceites e causaram dano. Um disparo de arco também foi
aceite. O arco e as flechas pertenciam ao kit adicional do fixture, não ao kit
fixo documentado para a preparação. O uso do escudo foi observado visualmente,
mas não prova que um ataque recebido tenha sido bloqueado.

Uma eliminação removeu o combatente e o token do piso enquanto a partida
continuou. O espectador eliminado tentou entrar no piso por teleporte para Y=200 e ficou
na saída; o artefacto não confirma a tentativa abaixo do mundo. O término normal
por `PLAYER_LEFT` restaurou exatamente os 13 campos dos cinco peers; no cliente
humano, a posição teve uma diferença de 0,058 blocos. Num crash separado com
equipamento protegido 3v3, o processo Java local foi terminado. Após logins
AuthMe normais, os 13 campos coincidiram exatamente nos seis participantes; as
observações de um, seis e quinze segundos após login não mostraram divergência.
Antes do login, o jogador humano não tinha capacidade CIAAC nem token.

Um desafio 1v1 aceite por convite clicável no chat chegou a `ACTIVE`. Um portal
Nether físico foi atravessado pelo jogador durante uma partida: o observer
registou uma tentativa `NETHER_PORTAL`, cancelada uma vez, e a partida terminou
em `VICTORY` / `ESCAPE_ATTEMPT` com restauro. Um 2v2 chegou a `ACTIVE` e terminou
por `DRAW` / `ROUND_TIMEOUT`. A tentativa de desafio apostado foi recusada com
`REQUEST_INVALID`, sem partida ou escrow; não houve pagamento nem teste de
liquidação. Estes casos não ativam apostas.

O chat de ajuda e a apresentação do inventário foram observados num cliente
humano. A corrida em que uma tarefa AuthMe tardia fecha uma vista imediatamente
após login continua sem ensaio. Também não houve migração de contas AuthMe,
3v2 no mesmo artefacto ou aceitação integral de todos os fluxos. O pacote de
produção continua um rascunho; não foi feita operação nem probe no backend.

No login ordinário sem recuperação CIAAC pendente, o AuthMe normaliza
velocidades inferiores ou iguais a `0.01` ao capturar limbo. A saída e a
recuperação CIAAC preservaram as velocidades originais após o evento de
conclusão. A limpeza foi verificada em
`/private/tmp/ciaac-authme-e2e-2026-10-05/cleanup-evidence.json`: não havia
processo Java da fixture nem listener local, todos os peers estavam desligados,
e os hashes dos três JARs originais e da configuração AuthMe foram repostos.
A base de dados não foi revertida. Não se confirmou encerramento gracioso do
Paper, porque os identificadores originais do processo já não estavam
disponíveis; não declarar esse resultado.

O adaptador não converte contas por si. A migração de produção foi executada
separadamente em 2026-10-06, após autorização explícita, cópia fria validada e
conversão com a ferramenta oficial. O nLogin e os seus dados foram preservados
para rollback; nunca executar simultaneamente os dois fornecedores.

### Importação de contas — checkpoint offline de 2026-10-05

O conversor oficial 6.0.1 foi executado contra um SQLite novo com contas
sintéticas e destino em memória. Passaram mapeamento de campos, omissão de
campos inválidos, duplicados/destino existente nesta fixture e parsing de UUIDs.
A origem não foi alterada. O conversor não importa TOTP/2FA nem premium UUID;
copia hashes sem escolher o algoritmo por conta e não constitui uma transação
atómica. Este ensaio não verificou login com hashes reais, falha parcial ou
migração de contas existentes. O plano de produção continua pendente dessas
validações e da política explícita de recuperação de identidade/2FA.

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

## 2026-10-06 — Compatibilidade das contas existentes

Com o backend novamente acessível, a inspeção em leitura confirmou nLogin
2.0.19 em produção, diferente do 2.0.24 usado nos ensaios anteriores.
Foram inspecionadas cópias protegidas de 25 contas: hashes Argon2id, UUIDs
offline correspondentes aos nomes originais e nenhum nome duplicado após
normalização. Não existem valores email, Discord, Mojang ou Bedrock nessas
contas; as configurações por conta contêm apenas idioma. Isto não prova a
política global de integrações externas.

A implementação `Argon2Id` do JAR AuthMe fixado aceitou o hash existente da
conta do operador com a password autorizada e recusou uma password incorreta.
O conversor oficial importou as 25 contas primeiro para um destino de registo
de chamadas e depois para o backend SQLite real do AuthMe. Passaram nomes,
hashes, UUIDs offline, email e datas de registo. `saveAuth` omite o último IP e
data de login; uma chamada adicional à API pública `DataSource.updateSession`
preservou esses campos, sem alterar timestamps ou ativar flags de autenticação
ou sessão. A segunda execução manteve 25 linhas e os mesmos metadados, sem
duplicados. A origem e o schema do destino ficaram inalterados. Um ensaio
sintético separado no SQLite real aceitou a password correta e recusou uma
incorreta. As passwords dos restantes jogadores não foram verificadas.
Isto não prova um login Paper com contas migradas no servidor de produção.

A configuração preparada foi gerada e interpretada pelo schema ConfigMe do
JAR fixado. Define explicitamente `settings.security.passwordHash: ARGON2ID`;
o default SHA256 não verifica estes hashes. Preserva limites de password 5..32,
uma tentativa de login e bloqueio por 15 minutos. Usa SQLite, coluna UUID
`playerUUID`, sessões automáticas desativadas e o perfil de conclusão revisto.
Nenhum ficheiro foi instalado nem conta migrada em produção. O cliente
autorizado confirmou separadamente o login nLogin normal pelo endereço público;
esse login pode atualizar os timestamps habituais da conta. Evidência privada no
repositório de integração em `runtime/verification/production-readiness-2026-10-06`.
