# Autorização dos comandos administrativos

- Verificação: 2026-10-04 (`Europe/Lisbon`).
- Implementação e defaults: `SOURCE-VERIFIED`.
- Grupos e herança remotos: `RUNTIME-VERIFIED`, através de consultas LuckPerms
  exclusivamente de leitura enviadas pela API Crafty.
- Autorizações individuais, contextos efetivos de cada jogador e execução
  autenticada dos comandos no backend: `UNVERIFIED`.

Os handlers autorizam cada ação com `hasPermission` sobre o nó específico.
Não fixam nomes de grupos no código nem usam `isOp` como alternativa. As
sugestões de comandos administrativos exigem o mesmo nó da ação. Todos os nós
administrativos abaixo têm `default: false` no descritor.

| Ação | Permissão exigida |
| --- | --- |
| `/ciaac carrinhos recarregar` | `ciaac.minecarts.reload` |
| `/ciaac carrinhos definir`, `predefinir`, `repor`, `repor-tudo` | `ciaac.minecarts.test` |
| `/minijogos atualizacao` e menu Administração | `ciaac.minigames.admin` |
| `/passaporte admin` | `ciaac.retention.admin.view` |

O menu Administração só apresenta diagnósticos dos módulos e do atualizador.
O botão e a ação voltam a verificar o nó efetivo; nomes de grupos e OP não
substituem essa autorização. Ver [menus](minigame-menus.md).

`atualizacao` consulta o estado do atualizador; não é um comando de implantação.
`ciaac.retention.admin.review` e `ciaac.retention.admin.season` também começam
negados, mas não correspondem a ações implementadas na superfície atual.
O Passaporte exige adicionalmente um jogador autenticado na conexão atual.
Os comandos públicos conservam os seus defaults existentes.

## Acesso público ao Coliseu — 2026-10-06

O operador definiu que todos os jogadores admitidos e autenticados no servidor
devem poder participar na Arena, independentemente do grupo. O descritor da
fonte e o JAR candidato ensaiado já declaram `ciaac.minigames.use` e
`ciaac.minigames.arena.use` com `default: true`. O comando `/coliseu` e o alias
`/arena` usam o nó público; jogar não exige `ciaac.minigames.admin`, que mantém
`default: false`.

Na ativação, verificar os efetivos dos grupos e jogadores atuais. Uma negação
individual, herdada ou contextual pode contrariar este acesso público; preparar
apenas a correção específica dos nós públicos se for encontrada. LuckPerms
continua a autoridade. Não substituir o acesso público por OP ou wildcards.
Os portões de autenticação, recuperação de inventário, sessão única e isolamento
continuam obrigatórios para proteger os jogadores de qualquer grupo.

As consultas remotas de 2026-10-06 não encontraram entradas de utilizadores ou
grupos para os dois nós públicos nem para `ciaac.minigames.arena.*`,
`ciaac.minigames.*` ou `ciaac.*`. A pesquisa de `*` encontrou apenas a concessão
existente de `admin`, previamente verificada como verdadeira. Não foi necessário
alterar permissões para preparar este acesso. Esta evidência cobre os overrides
persistidos pesquisados; os contextos efetivos de cada conexão e a disponibilidade
da Arena continuam por verificar na ativação do candidato.

## Política de grupos observada

| Grupo | Peso | Herança direta | Acesso administrativo CIAAC pela política do grupo |
| --- | --- | --- | --- |
| `admin` | 100 | `associado` | Concedido pelo wildcard `*` existente |
| `softadmin` | 99 | `associado` | Sem concessão direta ou herdada dos nós CIAAC |
| `associado` | 50 | `default` | Sem concessão direta ou herdada dos nós CIAAC |
| `default` | 10 | Nenhuma | Sem concessão dos nós CIAAC |

Foram consultados os quatro grupos, pais e todas as páginas de permissões.
O script `/admin` observado exige `staff.admin_toggle`, concedido a
`softadmin`; ao elevar, acrescenta `admin` ao utilizador e, ao deselevar,
remove-o. Assim, um `softadmin` elevado ganha os nós CIAAC pela herança `admin`.
O script também conserva a elevação após reconnect; não se pressupõe remoção
automática ao sair. Esta inspeção não executou o toggle.

O wildcard `*` e `luckperms.*` observados em `admin`, e permissões de alteração
de itens observadas em `softadmin`, são mais amplos do que os papéis de menor
privilégio pretendidos no repositório servidor. São uma discrepância existente,
não uma política criada ou aprovada por esta revisão. Reduzi-los ou alterar o
toggle requer uma revisão própria dos fluxos de elevação e autorização para
modificar o backend. Não houve alteração de grupos, permissões ou script.

Uma concessão individual, outro grupo acrescentado ao utilizador, um contexto
ou uma configuração externa pode alterar o resultado efetivo. Os nomes de
grupo não substituem a decisão LuckPerms de cada sender. A consola é uma origem
privilegiada externa à hierarquia de jogadores; o plugin continua a consultar
as permissões do sender. A proteção de Crafty/RCON/DiscordSRV não é provada por
estes testes de handlers.

## Regressão automatizada

Os testes verificam negação antes de consulta/mutação administrativa,
separação entre reload e teste de carrinhos, autorização do atualizador e
Passaporte, visibilidade das sugestões e defaults negados. Incluem um sender
que declara OP mas não tem o nó de carrinhos: OP não contorna o handler.
O defeito corrigido era a sugestão `admin` do Passaporte para todos os senders;
a execução já exigia `ciaac.retention.admin.view`.

Ver [verificação funcional](functional-verification.md) para resultados e
limites dos ensaios, [Carrinhos](minecarts.md) e [Passaporte](passport.md) para
os restantes comandos.


## Resultado de produção — 2026-10-07

Durante a aceitação nativa da Arena, a conta AuthMe comum de ensaio apresentou
permissões públicas efetivas `use=true` e `arena.use=true`, com `admin=false`.
Não foram alteradas concessões LuckPerms. AuthMe continuou como único fornecedor
e as 25 contas originais da migração foram preservadas. Esta observação é
`RUNTIME-VERIFIED` para essa conta e execução; não prova todos os contextos ou
heranças de todos os grupos. As consultas de grupo de 2026-10-06 acima continuam
a ser evidência histórica limitada, não uma auditoria atual completa.

O proprietário removeu a proteção NPP apenas da conta descartável de ensaio
pelo comando suportado `npp remove CIAACArenaQB`. A proteção inicial impediu a
primeira tentativa de combate; não se conta essa tentativa como ronda de dano.
Para jogadores públicos com proteção inicial, a remoção exige escolha explícita
por `/npp remove-protection` e confirmação na GUI, tornando-a permanente, ou
esperar quatro horas. A GUI pública não foi ensaiada. Não existe opt-out
automático e não foram alteradas configuração global ou concessões NPP.
