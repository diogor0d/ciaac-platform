# Avisos de componentes de terceiros

- Documentado: 2026-08-27 (`Europe/Lisbon`)
- Âmbito: compilação da `CIAACPlatform` e artefacto distribuído do plugin

## Dependência incluída para execução

### Xerial SQLite JDBC

- Coordenada Maven: `org.xerial:sqlite-jdbc:3.53.2.1`
- Finalidade: disponibilizar o controlador JDBC e as bibliotecas SQLite nativas
  usadas nos registos de sessões, instantâneos, auditoria, resultados, anúncios
  e depósitos do plugin.
- Licença declarada pela dependência: Apache License 2.0.
- Aviso adicional do projeto de origem: o arquivo da dependência inclui
  `LICENSE.zentus` para o código anterior Zentus SQLite JDBC.

A configuração Maven inclui apenas `sqlite-jdbc` no artefacto sombreado do
plugin. A inspeção local do artefacto em 2026-08-24 encontrou o controlador
SQLite, o descritor de serviço JDBC, 45 entradas de bibliotecas nativas, a
`LICENSE` do projeto de origem e `LICENSE.zentus`; não encontrou classes Paper,
nLogin, SLF4J ou JUnit. Os ficheiros de licença em
`META-INF/maven/org.xerial/sqlite-jdbc/` têm de permanecer em todos os artefactos
de lançamento, e a verificação de cada lançamento deve repetir esta inspeção
antes da publicação.

## Dependências de compilação e fornecidas pelo servidor

- Paper API, nLogin API e SnakeYAML são dependências `provided`, fornecidas pelo
  servidor de destino e não por este JAR. A CIAACPlatform usa o SnakeYAML 2.2
  fornecido pelo Paper para rejeitar chaves duplicadas em `minecarts.yml`.
- JUnit Jupiter é usado apenas nos testes.
- LuckPerms API `net.luckperms:api:5.5` é `provided`: permite ler nós,
  contextos e grupos através da API oficial, sem incluir classes LuckPerms no
  JAR. A manutenção da integração cabe aos responsáveis da CIAACPlatform;
  alterar esta versão exige revisão de compatibilidade e ensaio Paper.
  [Origem e documentação oficial](https://luckperms.net/wiki/Developer-API).
- Apache Maven Shade Plugin é uma ferramenta de compilação e não é incluído
  como código de execução do plugin.

Este aviso regista a proveniência das dependências; não substitui os textos
integrais das licenças incluídos nas dependências nem qualquer licença exigida
para o código deste repositório antes de uma futura publicação.
