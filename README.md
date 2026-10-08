# CIAACPlatform

[![Release](https://img.shields.io/github/v/release/diogor0d/ciaac-platform?include_prereleases&sort=semver&label=release)](https://github.com/diogor0d/ciaac-platform/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/diogor0d/ciaac-platform/ci.yml?branch=main&label=build)](https://github.com/diogor0d/ciaac-platform/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-25-informational)
![Paper](https://img.shields.io/badge/Paper-26.2-informational)

CIAACPlatform é o plugin Paper modular do CIAAC para minijogos, progressão,
controlo de carrinhos e observabilidade local.

Este é o repositório canónico do código, das compilações e das releases públicas
da CIAACPlatform. Em 2026-10-07, o JAR `d6cf9c8eca8d19352e3488f901a83eb8c1348e5471409b69bebadfc8040155cb`
passou aceitação nativa de
produção numa partida fixa 1v1 e na recuperação após desconexão do oponente
sintético. Isto não representa
aceitação dos restantes minijogos ou de equipas maiores. Consultar a
[verificação funcional](docs/functional-verification.md) e o
[registo de ativação](docs/arena-activation.md) para evidência e limites.


Sumo e Batata Quente passaram aceitação local limitada em 2026-10-07, com
peers autenticados, resultados e restauro exato. Produção destes modos aguarda
[construção e aceitação das instalações](docs/sumo-hot-potato-deployment.md).

> **Estado do projeto:** `0.1.0-alpha.1` é uma versão experimental. Código,
> compilação e testes automatizados estão verificados. A aceitação em produção
> da Arena está limitada ao 1v1 fixo e recuperação documentados em
> [Ativação da Arena](docs/arena-activation.md); outros modos e integrações
> continuam por validar.

A integração nLogin mantém a admissão/recuperação fechadas enquanto faltar prova
de conclusão do seu restauro. O [adaptador opcional AuthMe](docs/authentication.md)
passou ensaios locais de login normal, 2v3, saída e recuperação de crash com
cinco contas sintéticas. A migração autorizada de produção para AuthMe ocorreu
em 2026-10-06; o nLogin foi preservado com carregamento desativado.

Os [menus nativos dos nove minijogos](docs/minigame-menus.md) abrem com
`/minijogos` ou com o comando do jogo sem argumentos. A Arena oferece formato,
equipamento, desafios, grupos e prontidão por cliques; comandos explícitos
continuam disponíveis. A instalação desta alteração tem aceitação própria.

## Visão geral

O projeto reúne num único artefacto componentes que partilham contratos de
autenticação, isolamento de estado, persistência e recuperação. Os módulos que
dependem do ambiente permanecem desativados ou recusam admissão enquanto os
respetivos pré-requisitos não forem validados.

| Área | Funcionalidade |
| --- | --- |
| Minijogos | Coliseu, Build Battle, Batata Quente, Sumo, Parkour, Arco, Bigornas, Chão de Cores e Anéis de Elytra |
| Carrinhos | Limites de velocidade por mundo e substituições temporárias auditadas para testes |
| Passaporte CIAAC | Presenças, atividade, sequências, objetivos, recompensas e classificações |
| Isolamento | Captura, quarentena e restauro idempotente do estado do jogador |
| Segurança | Eventos locais limitados, validação fechada e fronteiras explícitas de privilégios |
| Atualizações | Descoberta SemVer, manifesto assinado com Ed25519 e preparação atómica do JAR |

O [índice funcional](docs/README.md) reúne os guias disponíveis para os módulos
e os contratos partilhados.

## Requisitos

- JDK 25;
- Maven 3.9 ou posterior;
- Paper API `26.2.build.84-stable`;
- dependências opcionais apenas para os módulos que as declaram.

## Compilar e testar

```text
mvn clean verify
```

O artefacto local é criado em `target/CIAACPlatform-<versão>.jar`. Uma
compilação local não constitui uma release oficial e não inclui a cadeia de
publicação assinada.

## Instalação experimental

1. Abrir a página de [Releases](https://github.com/diogor0d/ciaac-platform/releases).
2. Transferir `ciaac-platform.jar`, o manifesto e a assinatura da mesma versão.
3. Confirmar o SHA-256 do JAR indicado no manifesto.
4. Instalar o JAR numa instância Paper descartável, sem mundos ou dados valiosos.
5. Manter os módulos desativados até validar deliberadamente cada integração.

Não utilizar `/reload`. Alterações de ciclo de vida e atualizações devem ser
aplicadas através de um reinício controlado.

## Atualizações assinadas

O repositório `diogor0d/ciaac-platform` é a origem canónica das versões
públicas. Uma release aceite pelo atualizador tem de ser imutável e conter
exatamente:

```text
ciaac-platform.jar
ciaac-platform-update.properties
ciaac-platform-update.properties.sig
```

O atualizador verifica a assinatura Ed25519 do manifesto, a identidade, o
tamanho e o SHA-256 do JAR antes de preparar qualquer ficheiro. Rascunhos,
releases mutáveis, etiquetas ambíguas, assinaturas inválidas, downgrades e
caminhos inseguros são rejeitados.

Está desativado por predefinição:

```yaml
updater:
  enabled: false
  github:
    owner: diogor0d
    repository: ciaac-platform
    allow-prereleases: false
  restart-empty-server-after-staging: false
```

O canal estável não aceita pré-lançamentos. Um servidor de teste que deva
receber versões alpha ou beta tem de definir explicitamente
`allow-prereleases: true`.

Consultar o [contrato do atualizador](docs/release-updater.md) e o
[procedimento de publicação e recuperação](docs/RELEASES.md).

## Segurança

As principais propriedades implementadas no código são:

- arranque e admissão fechados perante configuração incompleta;
- separação explícita entre identidade autenticada, UUID e nome do jogador;
- isolamento de inventário, equipamento, XP, efeitos e localização quando
  aplicável;
- recuperação idempotente e rejeição de estado parcial ou ambíguo;
- chave privada de release restrita ao ambiente de publicação;
- ausência de chamadas de rede no produtor local de eventos de segurança.

Estas propriedades de origem não substituem a validação num servidor Paper.
Para comunicar uma vulnerabilidade, não abrir um issue público; utilizar o canal
privado descrito em [SECURITY.md](SECURITY.md).

## Documentação

| Documento | Conteúdo |
| --- | --- |
| [Índice funcional](docs/README.md) | Módulos, guias, contratos e navegação por funcionalidade |
| [Carrinhos](docs/minecarts.md) | Limites por mundo, comandos, permissões e substituições temporárias |
| [Passaporte](docs/passport.md) | Qualificação, épocas, recompensas, permissões e privacidade |
| [Eventos de segurança](docs/security-events.md) | Emissão local, privacidade, retenção e contrato do consumidor |
| [Atualizador](docs/release-updater.md) | Descoberta, manifesto, assinatura e preparação |
| [Releases](docs/RELEASES.md) | Publicação, validação e recuperação |
| [Política de segurança](SECURITY.md) | Âmbito e comunicação privada |
| [Componentes de terceiros](THIRD_PARTY_NOTICES.md) | Dependências incluídas e licenças do artefacto |
| [Estado externo](docs/external-state-adapters.md) | Contrato atual, fornecedores em falta e critérios de restauro |
| [Ensaios físicos locais](tools/paper-runtime-probes/README.md) | Probe descartável de proteção de pistões e condições do alvo |
| [`plugin.yml`](src/main/resources/plugin.yml) | Comandos, permissões e identidade do plugin |
| [`config.yml`](src/main/resources/config.yml) | Configuração e valores iniciais seguros |

## Estado verificável

Checkpoints históricos de 2026-08-31 e estado de produção verificado em 2026-10-07:

| Fronteira | Evidência |
| --- | --- |
| Compilação JDK 25 e testes Maven | `SOURCE-VERIFIED`: 209 testes, 207 aprovados e 2 ignorados por dependerem do ambiente |
| CI pública | `SOURCE-VERIFIED`: workflow do ramo `main` aprovado |
| Conteúdo do repositório | `SOURCE-VERIFIED`: sem dados operacionais, credenciais ou chaves privadas no payload revisto |
| Release assinada | Consultar a [release publicada](https://github.com/diogor0d/ciaac-platform/releases) |
| nLogin, outros contextos Paper/fornecedores e consumo real do atualizador | `UNVERIFIED` |
| Sumo/Batata Quente locais — 2026-10-07 | 478 testes (477 passaram, um skip); peers nativos, vitória/timeout, desconexão e crash com 18 campos restaurados; produção aguarda instalações |
| Produção — Arena/AuthMe em 2026-10-07 | JAR `d6cf9c8…`, 449 testes; combate nativo fixo 1v1 e desconexão do oponente sintético/reautenticação com restauro exato dos 15 campos `RUNTIME-VERIFIED`; equipas maiores, espectadores e outros minijogos não verificados |

## Dados e licenciamento

Este repositório não deve conter mundos, dados de jogadores, bases de dados,
logs de produção, endereços privados, credenciais, tokens, certificados ou
chaves privadas.

A visibilidade pública do repositório não concede, por si só, uma licença de
reutilização. Ainda não foi adotada uma licença de código aberto. Os componentes
de terceiros mantêm as respetivas licenças e avisos identificados em
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
