# Versões públicas e atualizador

- Documentado: 2026-08-30 (`Europe/Lisbon`)
- Repositório canónico: `diogor0d/ciaac-platform`
- Estado de execução Paper: `UNVERIFIED`

## Contrato de publicação

Cada versão oficial é uma GitHub Release imutável com etiqueta canónica
`v<SemVer>`, sem metadados de compilação. A marcação de pré-lançamento do GitHub
tem de coincidir com o sufixo SemVer. O lançamento contém exatamente:

- `ciaac-platform.jar`;
- `ciaac-platform-update.properties`;
- `ciaac-platform-update.properties.sig`.

O manifesto liga a assinatura Ed25519 ao ID numérico da release, versão,
artefacto, tamanho, SHA-256, nome do plugin e classe principal. A assinatura é
Base64 canónico de 88 bytes ASCII, sem BOM, espaços, CR ou LF. A chave privada
existe apenas no secret protegido `CIAAC_RELEASE_ED25519_PRIVATE_KEY_BASE64` do
ambiente GitHub `release`; a chave pública publicada fica em
`release-keys/ciaac-platform-ed25519-public.der.b64` e o workflow volta a
verificar a assinatura com essa cópia antes do carregamento.

Impressão digital SHA-256 da chave pública inicial:
`1edb187a27b709a34129d7e122c56617c3da6fff9647572a4908474aed24e0a9`.

## Fluxo de publicação

1. Alterar `pom.xml` e `plugin.yml` para a mesma versão SemVer e rever a diff.
2. Executar `mvn clean verify` e a verificação de conteúdo sensível.
3. Fazer merge da revisão aprovada no ramo predefinido.
4. Confirmar nas definições administrativas que as releases imutáveis estão
   ativas. O `GITHUB_TOKEN` do workflow não pode consultar essa definição.
5. Executar manualmente o workflow «Publicar versão assinada» com a etiqueta
   exata. O ambiente `release` deve exigir revisão humana.
6. O workflow cria um rascunho, obtém o ID, gera e assina o manifesto, verifica
   localmente os três artefactos e só depois publica o rascunho.
7. Confirmar pela API: `draft=false`, marcação `prerelease` correta,
   `immutable=true`, commit esperado e três nomes/tamanhos/digests esperados.

Um rascunho deixado por uma falha não deve ser publicado ou reutilizado sem
reconstrução e nova revisão. Nunca imprimir ou guardar a chave privada num
artefacto, log, cache ou checkout.

## Consumo

O canal estável usa `allow-prereleases: false`. Apenas um servidor descartável
ou um teste explicitamente aprovado deve usar `true`. O atualizador escolhe a
maior SemVer elegível numa janela limitada a 100 releases, falha se houver
paginação adicional e repete sempre as verificações de assinatura, identidade,
digest, tamanho e caminho antes de preparar o JAR.

Preparar um JAR não o ativa. O Paper apenas o consome num reinício controlado.
Não usar `/reload`. Antes de produção, validar descoberta real, preparação,
consumo, marcador anti-loop e recuperação num Paper descartável com contas,
mundos e dados sintéticos.
