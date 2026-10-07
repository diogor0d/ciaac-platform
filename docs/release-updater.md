# Atualizador de versões

Este documento descreve apenas o comportamento do atualizador no código-fonte.
A seleção e o empacotamento do controlador Xerial SQLite ficam fora do âmbito.
O atualizador está desativado por predefinição, não recebe nem guarda tokens
GitHub e consulta apenas um repositório público configurado.

Para preparar ou validar uma publicação, consultar também o
[runbook público de versões](RELEASES.md) e a [política de segurança](../SECURITY.md).

## Manifesto e assinatura

Uma versão publicada e imutável, que nunca pode ser rascunho, tem de conter
exatamente os artefactos configurados. O canal estável rejeita pré-lançamentos;
o canal de testes aceita versões alpha/beta apenas quando
`updater.github.allow-prereleases` está explicitamente ativo. A marcação
`prerelease` do GitHub tem de coincidir com a versão SemVer e a etiqueta tem de
ser exatamente `v<versão>`, sem metadados de compilação. O manifesto é um documento pequeno
`key=value`, terminado por LF:

```text
format=ciaac-platform-update-v1
release-id=123456789
version=0.2.0
artifact=ciaac-platform.jar
size=123456
sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
plugin-name=CIAACPlatform
plugin-main=com.ciaac.minecraft.platform.CiaacPlatformPlugin
```

A assinatura é a assinatura Ed25519 de 64 bytes, codificada em Base64, sobre os
bytes exatos do manifesto. A chave pública Ed25519 é fixada pelo operador na
configuração e não é criada nem obtida pelo atualizador.

O feed oficial usa a chave pública DER deste repositório com impressão digital
SHA-256 `1edb187a27b709a34129d7e122c56617c3da6fff9647572a4908474aed24e0a9`.
Uma rotação exige uma versão e procedimento explícitos; nunca substituir a
chave privada ou pública silenciosamente.

Antes de preparar uma versão, o código verifica o formato, o ID, a versão
semântica, o nome e tamanho do artefacto, o SHA-256, a assinatura, a chave
pública e os hosts permitidos. Qualquer falha fecha a operação sem instalar nem
apagar um artefacto.

## Preparação segura

A verificação de rede e os cálculos de digest não correm no thread principal.
Os caminhos são resolvidos abaixo de `Server#getPluginsFolder()`; valores
absolutos, traversal, symlinks perigosos, ficheiros excessivos, redirects
indevidos, downgrades, rascunhos e versões mutáveis são rejeitados. A descoberta
consulta no máximo 100 versões, rejeita paginação adicional e escolhe a maior
SemVer elegível, independentemente da ordem da API.

Uma lista válida sem versões elegíveis para o canal configurado, incluindo uma
lista vazia ou um feed estável que contenha apenas pré-lançamentos, produz
`NO_UPDATE` com diagnóstico de que não existe versão publicada elegível. JSON
inválido, listas excessivas ou versões ambíguas continuam a produzir falha.

O download é escrito primeiro para um ficheiro temporário `CREATE_NEW`. Depois
da validação, o ficheiro é movido atomicamente para a pasta de atualização,
usando o nome exato do JAR atualmente instalado. O atualizador nunca faz
`reload`, não desativa nem ativa plugins e não executa comandos; o Paper só
consome o ficheiro num reinício controlado posterior.

O fecho do plugin e a preparação são serializados. Uma operação que ganhe o
lock termina atomicamente e remove o ficheiro temporário. Um ficheiro já
preparado não é revertido só porque a gravação posterior do marcador de
reinício falhou; essa situação exige inspeção do operador.

## Reinício opcional

`restart-empty-server-after-staging` é falso por predefinição. Quando ativado,
o atualizador prepara primeiro o JAR e grava um marcador durável. Uma tarefa no
thread principal aguarda sem expulsar jogadores e chama
`Server#restart()` uma única vez quando o servidor fica vazio. Não chama
`Server#shutdown()`, não executa comandos e não faz hot-reload.

O operador tem de configurar um script ou supervisor de reinício. Ao arrancar,
o marcador é comparado com a versão instalada: uma versão igual ou mais recente
limpa-o e emite `RESTART_CONSUMED`; uma versão antiga emite
`RESTART_BLOCKED` e impede outro reinício automático para essa versão.

## Cache e evidência

O cache não secreto fica abaixo da pasta de dados do plugin:

```text
plugins/CIAACPlatform/updater-cache/latest-release.json
```

O ETag é associado ao corpo exato e ao canal configurado; mudar de canal força
uma consulta não condicional. O cache não é uma fonte de confiança: cada utilização repete as verificações de
manifesto, assinatura, digest, caminho e versão. Logs e marcadores não contêm
tokens, credenciais, conteúdo de produção ou dados de jogadores.

A atualização continua opcional e fechada até o operador validar o repositório,
a chave pública, os nomes de ficheiro, o script de reinício e o comportamento
num ambiente Paper descartável.
