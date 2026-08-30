# CIAACPlatform

CIAACPlatform é o plugin Paper modular público da CIAAC para minijogos,
carrinhos, retenção e observabilidade local. Este repositório é a origem
canónica das versões públicas e o feed consultado pelo atualizador assinado.

> A versão `0.1.0-alpha.1` é experimental. A fonte compila e os testes
> automatizados passam, mas a aceitação completa num servidor Paper descartável
> e a implantação em produção continuam por verificar.

## Começar

- [Índice funcional e documentação dos módulos](docs/README.md)
- [Contrato técnico do atualizador](docs/release-updater.md)
- [Publicação, assinatura e recuperação](docs/RELEASES.md)
- [Política de segurança](SECURITY.md)
- [Avisos de componentes de terceiros](THIRD_PARTY_NOTICES.md)

Requisitos de compilação: JDK 25 e Maven 3.9 ou posterior.

```text
mvn clean verify
```

O artefacto distribuído é `ciaac-platform.jar`. As versões oficiais incluem
também um manifesto canónico e uma assinatura Ed25519. O atualizador permanece
desativado por predefinição; servidores de teste que pretendam receber alpha ou
beta têm ainda de ativar explicitamente `updater.github.allow-prereleases`.

## Estado e limites

- Código e testes: `SOURCE-VERIFIED` em 2026-08-30.
- Execução Paper, nLogin, Multiverse, GrimAC e implantação: `UNVERIFIED`.
- Não são incluídos mundos, dados de jogadores, bases de dados, logs,
  credenciais, chaves privadas nem configuração de infraestrutura.
- A visibilidade pública deste repositório não concede, por si só, uma licença
  de reutilização. Ainda não foi adotada uma licença de código aberto.

Todo o conteúdo destinado a jogadores e operadores está em português europeu.
Identificadores técnicos permanecem em inglês para estabilidade das interfaces.
