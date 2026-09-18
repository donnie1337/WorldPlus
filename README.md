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

## Pré-geração completa dos mundos

O WorldPlus possui um sistema de pré-geração automática das áreas configuradas em cada mundo.

A pré-geração usa o campo `tamanho` de cada mundo e cobre toda a área configurada, independentemente dos valores de `rtp.mundos.*.raio-minimo` e `raio-maximo`.

Configuração atual:

| Mundo | Tamanho | Área pré-gerada |
|---|---:|---:|
| Overworld | 10.000 × 10.000 | Toda a área configurada |
| Mineração | 7.000 × 7.000 | Toda a área configurada |
| Nether | 7.000 × 7.000 | Toda a área configurada |
| End | 7.000 × 7.000 | Toda a área configurada |

### Como funciona

- a pré-geração começa automaticamente quando o WorldPlus é carregado;
- as chunks são processadas progressivamente, evitando tentar gerar o mundo inteiro de uma só vez;
- chunks que já foram geradas são identificadas e ignoradas;
- chunks novas são geradas e gravadas no disco;
- as chunks não ficam permanentemente carregadas apenas por causa da pré-geração;
- o progresso é informado no console;
- o processo continua até cobrir toda a área configurada de cada mundo.

O sistema de RTP utiliza uma área já pré-gerada para escolher uma localização aleatória. Os raios configurados no bloco `rtp` continuam controlando **onde o RTP pode sortear**, mas não limitam a área de pré-geração.

> **Atenção:** pré-gerar mundos grandes pode exigir bastante tempo, CPU, armazenamento e operações de disco. Em um mundo de 10.000 × 10.000 blocos, a quantidade de chunks é grande; o processo é deliberadamente progressivo para reduzir o impacto no servidor.

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
