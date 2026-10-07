# Eventos de segurança

O plugin emite envelopes estruturados no log local. Não abre uma porta, não
envia dados para um fornecedor de IA ou Discord e não recebe comandos do
consumidor externo. A integração do consumidor continua separada; a sua
execução não foi validada.

## Emissão e privacidade

O prefixo da linha é CIAAC_SECURITY_EVENT_V1; o conteúdo seguinte é JSON
codificado em Base64 URL-safe sem padding. O JSON UTF-8 original é limitado a
4096 bytes antes da codificação; a linha Base64 resultante pode ser maior.
Eventos de sistema usam identidade SYSTEM. Eventos de jogador só incluem
identidade observada depois da autenticação atual. O produtor não recolhe
mensagens privadas, argumentos de comandos, endereços IP, credenciais ou
conteúdo de autenticação.

O conteúdo de conversa pública é opcional e está desativado por omissão em
config.yml. Só pode ser ligado quando public-chat-enabled e
public-chat-privacy-notice-approved são ambos true; a mensagem é limitada a
512 caracteres. Não ativar sem aviso de privacidade aprovado e avaliação da
necessidade de tratamento.

O produtor valida códigos, chaves de atributos e comprimentos antes da
serialização. Um evento é uma observação, não uma decisão de autorização. O log
local é a evidência de origem; entrega Discord ou avaliação por modelo não é o
registo autoritativo.

## Configuração e retenção

Em config.yml, security-events controla apenas as opções public-chat-enabled e
public-chat-privacy-notice-approved. Ambas começam false. Não atribuir a estas
opções a configuração de diário de recuperação, timeout de base de dados ou
retenção do Passaporte: são fronteiras diferentes e não fazem parte do contrato
do produtor de eventos descrito aqui. Não copiar logs brutos para issues,
releases ou documentação.

O timeout SQLite de 5000 ms é fixo no código; não há uma opção correspondente
na configuração. Não existe nesta configuração uma garantia configurável de
retenção ou eliminação dos registos locais de eventos de segurança ou da
evidência de minijogos. As regras de pseudonimização e retenção aplicadas pelo
consumidor externo são políticas separadas.

Alterações temporárias de velocidade dos carrinhos também emitem eventos no
produtor local, sem endereços IP. Falha de emissão é registada como aviso e não
significa entrega confirmada a um sistema externo.

## Contrato do consumidor

O contrato e o consumidor externo exigem validar a versão e o esquema, aplicar
idempotência por eventId e gravar evento e cursor na mesma transação. Uma
identidade autenticada local é dado pessoal; um lote enviado a fornecedor
externo deve pseudonimizar identidades e remover IP, UUID, nome e segredos. O
conteúdo público é opcional: só pode integrar um lote se o evento de origem
incluir essa mensagem e a aprovação do operador e o aviso de privacidade
estiverem explícitos; caso contrário, omiti-lo. Entrega Discord incerta exige
revisão humana e não deve ser repetida automaticamente. Estes são requisitos do
contrato/consumidor, não garantias de entrega ou retenção do produtor Paper.

Consultar o [contrato v1 e fixtures](https://github.com/diogor0d/ciaac-server/tree/main/contracts/admin-alerts),
o [README do consumidor](https://github.com/diogor0d/ciaac-server/tree/main/services/bots/ciaac-admin-alerts)
e a [política de segurança do plugin](../SECURITY.md). Contrato e consumidor
estão noutro repositório; links públicos não demonstram execução nem entrega
real.
