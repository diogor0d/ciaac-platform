# Carrinhos

Este guia descreve comportamento de origem. A configuração incluída começa
desativada; aceitação em Paper, Multiverse ou GrimAC continua UNVERIFIED. Não
usar /reload.

## Política e configuração

O ficheiro plugins/CIAACPlatform/minecarts.yml usa schema-version 1. O limite
predefinido é 16,0 blocos/s e worlds começa vazio. Cada substituição de mundo
tem de conter o nome exato, UUID e terminal-speed-blocks-per-second. O UUID
impede que uma configuração antiga seja aplicada a outro mundo com o mesmo
nome. Os valores válidos são finitos e vão de 0,1 a 64,0 blocos/s.

O módulo altera apenas RideableMinecart através de setMaxSpeed; não reescreve
vetores de velocidade. Carrinhos de armazenamento, funil, fornalha, TNT,
comandos e spawner não são alterados. A política é aplicada a carrinhos
existentes quando um mundo ou entidades carregam, em novos carrinhos e após
transferências. O limite nativo anterior fica numa etiqueta persistente CIAAC e
é reposto quando a política é desativada ou o plugin fecha.

Exemplo de entrada; substituir o UUID pelo valor obtido do mesmo ambiente:

```yaml
schema-version: 1
enabled: false
default-terminal-speed-blocks-per-second: 16.0
worlds:
  spawn:
    uuid: UUID_REAL_DO_MUNDO
    terminal-speed-blocks-per-second: 20.0
```

Uma configuração inválida, com chaves YAML repetidas ou UUID incompatível
mantém a política anterior. O carregador limita o ficheiro a 65 536 caracteres
e rejeita valores não finitos ou fora do intervalo.

## Comandos e permissões

| Comando | Permissão | Predefinição | Efeito |
| --- | --- | --- | --- |
| /ciaac carrinhos estado | ciaac.minecarts.view | true | Mostra política e substituições temporárias |
| /ciaac carrinhos recarregar | ciaac.minecarts.reload | false | Lê e aplica minecarts.yml; se falhar, conserva a política anterior |
| /ciaac carrinhos definir &lt;velocidade&gt; [mundo] | ciaac.minecarts.test | false | Define substituição temporária para um mundo carregado |
| /ciaac carrinhos predefinir &lt;velocidade&gt; | ciaac.minecarts.test | false | Define substituição temporária predefinida |
| /ciaac carrinhos repor [mundo] | ciaac.minecarts.test | false | Remove substituição temporária de um mundo |
| /ciaac carrinhos repor-tudo | ciaac.minecarts.test | false | Remove todas as substituições temporárias |

A substituição temporária de mundo prevalece sobre a predefinição temporária,
que prevalece sobre minecarts.yml. As substituições não são persistidas e
desaparecem após reposição, recarregamento aceite ou reinício. Recarregar com
sucesso apaga todas as substituições; um recarregamento recusado conserva-as.

Jogadores podem omitir o mundo em definir ou repor para usar o mundo atual. A
consola tem de indicar o nome de um mundo carregado; a omissão nunca significa
alteração global. predefinir e repor-tudo são explicitamente globais.

Cada alteração temporária aceite emite CIAAC_SECURITY_EVENT_V1 no log local,
com identidade autenticada quando disponível, identificador de operação,
âmbito e valores anterior/novo; não inclui endereços IP. A entrega por um
consumidor externo não está demonstrada.

## Validação antes de ativar

1. Manter enabled: false até confirmar versões de Paper e dos plugins que
   interagem com carrinhos.
2. Preencher UUIDs a partir do mundo correto; não reutilizar UUIDs de outro
   ambiente.
3. Num Paper descartável, medir linhas, curvas, declives, colisões e mudanças
   de chunk.
4. Confirmar que carrinhos não montáveis não mudam e que desativar ou fechar o
   plugin repõe os limites originais.
5. Conceder ciaac.minecarts.test apenas aos operadores do ensaio e removê-la
   depois. Não usar OP ou permissões globais como atalho.

Este guia não autoriza alterações num servidor real nem constitui prova de
desempenho ou compatibilidade.
