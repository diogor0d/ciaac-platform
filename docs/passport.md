# Passaporte CIAAC

O Passaporte regista presença e atividade de jogadores com sessão atual
autenticada pelo adaptador nLogin. retention.yml começa com enabled: false;
PlaceholderAPI, apresentações seguras, partículas e integrações externas também
começam desativadas. A aceitação Paper e o consumo das integrações continuam
UNVERIFIED.

## Arranque, elegibilidade e recuperação

A barreira global de admissão mantém todos os jogadores sem admissão enquanto o
bootstrap da plataforma está pendente. Se esse bootstrap falhar, a barreira
global permanece fechada. Depois de o bootstrap ficar pronto, uma recuperação
pendente ou falhada bloqueia a sessão do jogador afetado; não fecha a admissão
global de jogadores sem essa sessão. O carregamento do plugin ou o estado de um
módulo isolado não bastam para abrir a barreira global.

A qualificação diária é reavaliada pelo amostrador a partir das ligações atuais
e autenticadas. A verificação continua através da meia-noite de Lisboa e volta
a considerar jogadores depois dos primeiros 10 minutos da sessão autenticada.
Uma sessão só conta se o jogador não estiver em OP, criativo ou espetador, nem
marcado ciaac.retention.excluded, e não tiver uma sessão de minijogo,
recuperação ou quarentena em curso. Apenas uma sessão em fase CLOSED deixa de
bloquear elegibilidade.

Comandos que alterem estado do Passaporte, incluindo reclamação e
personalização, exigem autenticação atual. Reclamações são recusadas durante
um minijogo ou recuperação/quarentena por concluir. Snapshots de consulta são
somente leitura. Validar a recuperação e o ledger antes de ativar o módulo.

## Qualificação e épocas

O calendário é fixo a Europe/Lisbon, com épocas de três meses desde 2026-09-15
e 14 dias de tolerância para reclamação. O carregador recusa alterações ao
calendário, aos limiares aprovados e à retenção detalhada.

- Uma presença diária exige 10 minutos numa sessão autenticada.
- Um dia ativo exige 15 minutos distintos amostrados com atividade observada,
  no máximo uma amostra por minuto.
- O objetivo semanal exige três dias ativos de segunda-feira a domingo em
  Lisboa.
- A pontuação inicial é 1 ponto por presença, 10 por dia ativo e 25 por
  objetivo semanal.
- Um congelamento semanal pode proteger uma falha de sequência; só existe um
  disponível e o seu consumo não é repetido.

As metas iniciais são sequências 3/7/14/30/60/90, objetivos semanais 3/6/9/12
e pontos 50/150/300/600/1000. Recompensas internas são registadas no ledger;
entregas externas têm estados persistentes. Resultado externo incerto exige
revisão manual e não é repetido automaticamente.

## Comandos e permissões

O descritor define ciaac.retention.use, true por omissão, para /passaporte.
O comando só está disponível a jogadores autenticados.

| Permissão | Predefinição | Uso |
| --- | --- | --- |
| ciaac.retention.use | true | Ver estado e recompensas |
| ciaac.retention.claim | true | Reclamar recompensa elegível fora de minijogos/recuperação |
| ciaac.retention.particles | true | Usar partículas conquistadas na zona segura |
| ciaac.retention.leaderboard | true | Consultar classificações |
| ciaac.retention.admin.view | false | Consultar estado administrativo |
| ciaac.retention.admin.review | false | Rever entregas pendentes; não presumir que existe comando para esta ação |
| ciaac.retention.admin.season | false | Administrar época; não presumir que existe comando para esta ação |
| ciaac.retention.excluded | false | Excluir a sessão das qualificações |

```text
/passaporte
/passaporte recompensas
/passaporte classificacao [presencas|atividade|pontos] [época]
/passaporte reclamar <reward-id>
/passaporte personalizar <titulo|distintivo|particulas> <reward-id|nenhum>
/passaporte admin
```

A classificação exige ciaac.retention.leaderboard; reclamar exige
ciaac.retention.claim; personalizar partículas exige
ciaac.retention.particles; admin exige ciaac.retention.admin.view. Os nós
admin.review e admin.season existem no descritor, mas a superfície de comandos
atual não os implementa. Conceder apenas permissões necessárias; não usar OP
como atalho.

## Configuração e dados

plugins/CIAACPlatform/retention.yml define enabled, calendário, limiares, metas
de recompensa, apresentação e integrações. Mantê-lo desativado até validar
migração SQLite, autenticação e recuperação num servidor descartável.
UltraCosmetics, GMusic, LuckPerms e Vault têm de permanecer desativados; o
carregador rejeita ativá-los sem validação da versão e do contrato.

platform.sqlite contém ledger e projeções do Passaporte. Eventos detalhados são
eliminados após 12 meses. Não são guardados percursos. Apresentações da zona
segura exigem UUID do mundo e coordenadas exatos. Os placeholders PAPI usam
`%ciaac_passport_<token>%`; `ciaac` é o identificador da expansão e `passport_`
é o prefixo dos parâmetros, consumidos por TAB e pelo formatador de conversa.
Os tokens incluem época, sequências, dias ativos, objetivos, pontos,
congelamento, título, distintivo e classificações.

Antes de ativar, testar num ambiente descartável autenticação atrasada, sessões
que atravessam meia-noite e DST de Lisboa, jogadores em jogo ou recuperação,
transições de época, reclamações repetidas e reinício durante recuperação. Não
usar dados de produção nem substituir o ledger atual por uma cópia antiga.
