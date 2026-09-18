# WorldPlus

Plugin de geração e gerenciamento de mundos para Spigot.

## Objetivo

O WorldPlus cria mundos a partir de uma configuração YAML e permite controlar:

- nome do mundo;
- ambiente;
- seed;
- tamanho da WorldBorder;
- geração de estruturas;
- remoção da geração de Strongholds;
- carregamento automático dos mundos;
- teleporte administrativo.

## Stronghold / Portal do End

O requisito é manter a geração normal das estruturas do Overworld e da Mineração, mas remover completamente a Stronghold.

Isso significa:

- aldeias continuam;
- templos continuam;
- monumentos continuam;
- trial chambers continuam;
- outras estruturas continuam;
- Strongholds não são geradas;
- consequentemente, não existe sala do portal do End;
- consequentemente, não existe portal do End gerado pela Stronghold.

O WorldPlus faz isso sem desligar a geração geral de estruturas. Ele instala um datapack de worldgen que substitui apenas minecraft:strongholds para os mundos marcados com remover-strongholds: true.

## Importante

A alteração da geração de Stronghold precisa estar aplicada antes da criação do mundo.

Para gerar um mundo novo corretamente:

1. pare o servidor;
2. confirme o nome e a seed no config.yml;
3. remova a pasta do mundo se ela já tiver sido criada;
4. inicie o servidor com o WorldPlus instalado;
5. o plugin prepara o datapack antes da criação do mundo;
6. o mundo é criado com a geração configurada.

Não apague um mundo com dados importantes sem backup.

## Comandos

- /mundos
- /mundos lista
- /mundos criar <id>
- /mundos tp <id>
- /mundos recarregar

Permissão:

worldplus.admin

## Build

Requer Java 26 e Maven.

~~~text
mvn clean package
~~~

O resultado será:

~~~text
target/WorldPlus.jar
~~~

## Spigot

O projeto utiliza org.spigotmc:spigot-api:26.2-R0.1-SNAPSHOT e não depende de Paper.
