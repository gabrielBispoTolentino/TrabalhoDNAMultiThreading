# Tema 4 — Busca de motivos em DNA

Procura, numa sequência de DNA muito longa (FASTA), todas as ocorrências de um conjunto de
padrões curtos (motivos), aceitando até **2 letras diferentes** (distância de Hamming ≤ 2,
configurável com `--div`). Para cada padrão devolve as posições encontradas e o número de divergências.

Implementações (todas produzem **exatamente** o mesmo resultado que a sequencial):

| # | Versão | Classe | Ideia |
|---|--------|--------|-------|
| 1 | Cliente/servidor | `dna.net.MotifServer`, `dna.net.MotifClient` | O cliente envia o FASTA e os padrões por socket; recebe, para cada padrão, posições + divergências |
| 2 | Sequencial | `dna.core.SequentialSearch` | Referência para validar as outras |
| 3a | Threads — blocos | `dna.threads.BlockThreadSearch` | A sequência é dividida em T blocos de posições iniciais |
| 3b | Threads — padrões | `dna.threads.PatternThreadSearch` | Os padrões são divididos pelas T threads; cada uma percorre a sequência toda |
| 4 | MPJ Express | `dna.mpj.MpjSearch` | Cada processo recebe um trecho, que se **sobrepõe** ao seguinte em (maior padrão − 1) bytes; resultados reunidos no processo 0 |
| 5 | GPU (OpenCL/JOCL) | `dna.gpu.GpuSearch` + `motif_search.cl` | Um work-item por posição inicial da sequência |

## Estrutura

```
src/dna/
  Main.java            linha de comandos (generate, search, bench, selftest, server, client)
  Methods.java         escolhe a implementação pelo nome
  SelfTest.java        testes de correção rápidos
  core/                leitura FASTA, padrões, Hamming, resultados, gerador de dados
  threads/             versões com threads (blocos e padrões)
  mpj/                 versão MPJ Express
  gpu/                 versão OpenCL (kernel em motif_search.cl)
  net/                 servidor e cliente
lib/jocl-2.0.5.jar     bindings OpenCL para Java (só para a GPU)
build.sh  run.sh  run-mpj.sh
```

## Requisitos e compilação

* JDK 17 ou superior.
* MPJ Express 0.44 (para a versão 4): `export MPJ_HOME=~/Dependencias/mpj-v0_44`
* Para a GPU: um driver OpenCL instalado (NVIDIA, AMD, Intel ou `pocl` para CPU) e `lib/jocl-2.0.5.jar`.
  Sem GPU, `sudo pacman -S pocl` permite correr o kernel OpenCL no CPU (útil para validar).

```bash
export MPJ_HOME=~/Dependencias/mpj-v0_44
./build.sh              # compila; GPU/MPJ só são incluídos se as dependências existirem
./run.sh selftest       # 520 casos de teste aleatórios (com N, padrões de 1..40 letras, 1..64 blocos)
```

`run.sh` e `run-mpj.sh` usam `-Xmx2g` por omissão (altere com `JAVA_OPTS=...` ou `MPJ_JVM_OPTS=...`). Com 500 MB de sequência bastam 1 GB de heap para sequencial/threads/GPU e 1,5 GB para MPJ com 4 processos (medido), por isso corre numa máquina com 4 GB de RAM.

## Jar único (Windows / Linux, sem instalar nada além do Java)

`./build.sh` gera também `dist/TrabalhoDNA.jar` (~1 MB), que inclui:

* todas as classes do projeto e o kernel OpenCL;
* JOCL com as bibliotecas nativas para Windows e Linux (x86_64);
* MPJ Express 0.44 (modo multicore), arrancado por `dna.mpj.MpjLauncher`: **não é preciso instalar
  o MPJ, definir `MPJ_HOME` nem usar o `mpjrun`**.

Só é preciso **Java 17 ou superior** (`java -version`). Os comandos são iguais em Windows e Linux:

```bash
java -Xmx2g -jar TrabalhoDNA.jar selftest
java -Xmx2g -jar TrabalhoDNA.jar generate dados.fa padroes.txt --size-mb 500 --seed 42
java -Xmx2g -jar TrabalhoDNA.jar bench dados.fa padroes.txt --threads 1,2,4 --gpu
java -Xmx2g -jar TrabalhoDNA.jar search blocks dados.fa padroes.txt --threads 4 --verify
java -Xmx2g -jar TrabalhoDNA.jar mpj 4 dados.fa padroes.txt 2 res_mpj.txt --verify
java -Xmx2g -jar TrabalhoDNA.jar server --port 5000
java -Xmx2g -jar TrabalhoDNA.jar client blocks dados.fa padroes.txt --host localhost --port 5000
```

Notas:
* `-Xmx2g` chega para 500 MB (medido: mínimo 1 GB para sequencial/threads/GPU e 1,5 GB para MPJ com 4 processos).
  Sem `-Xmx`, o Java usa 1/4 da RAM, o que pode não chegar numa máquina com 4 GB.
* Sem driver OpenCL, a GPU mostra "GPU indisponível" e tudo o resto funciona normalmente.
* No Windows, se os acentos aparecerem trocados na consola, corra `chcp 65001` antes.
* Como funciona o MPJ embutido: cada processo MPI é uma thread num `ThreadGroup` `SMPDevProcess` com o seu
  próprio `ClassLoader` para `mpi.*`/`mpjdev.*` (o MPI guarda estado em campos estáticos), tal como o
  `MulticoreStarter` do MPJ Express. Se `MPJ_HOME` não estiver definido, o launcher extrai o
  `mpjexpress.conf` do jar para uma pasta temporária e relança a JVM com essa variável.
* Num cluster (vários computadores) continua a ser preciso o MPJ instalado e o `mpjrun.sh -dev niodev`.

## Utilização

### Gerar dados (500 MB + 100 padrões de 12 a 20 letras)

```bash
./run.sh generate data/chr500.fa data/padroes.txt --size-mb 500 --patterns 100 --min 12 --max 20 --seed 42
```

O gerador insere cópias dos padrões (com 0, 1 ou 2 mutações) em posições aleatórias **e
exatamente a cruzar as fronteiras dos blocos** para divisões em 2..32 partes, para que o teste
das fronteiras não dependa da sorte. Também se pode usar um cromossoma real (ex.: hg38 chr1 em FASTA).

### Executar uma versão

```bash
./run.sh search seq      data/chr500.fa data/padroes.txt --out res_seq.txt
./run.sh search blocks   data/chr500.fa data/padroes.txt --threads 8 --out res_blocos.txt --verify
./run.sh search patterns data/chr500.fa data/padroes.txt --threads 8 --verify
./run.sh search gpu      data/chr500.fa data/padroes.txt --verify
```

`--verify` volta a correr a sequencial e compara as listas de posições.

### Comparar tudo (tempos, speedup, verificação)

```bash
./run.sh bench data/chr500.fa data/padroes.txt --threads 1,2,4,8,12 --gpu
```

### MPJ Express

```bash
./run-mpj.sh 4 data/chr500.fa data/padroes.txt 2 res_mpj.txt --verify      # modo multicore
# cluster:
$MPJ_HOME/bin/mpjrun.sh -np 8 -dev niodev -machinesfile machines -cp build/classes \
    dna.mpj.MpjSearch data/chr500.fa data/padroes.txt 2 res_mpj.txt
```

### Cliente / servidor

```bash
./run.sh server --port 5000                                   # terminal 1
./run.sh client blocks data/chr500.fa data/padroes.txt \
         --host localhost --port 5000 --threads 8 --out resultado.txt   # terminal 2
```

O cliente envia o ficheiro FASTA (bytes) e a lista de padrões; o servidor (uma thread por
cliente) executa o método pedido (`seq`, `blocks`, `patterns`, `gpu`) e devolve as ocorrências.

### Formato do resultado

```
# pattern CGATTAGCATGGCATCG count 38
1739331 2          <- posição (0-based)  divergências
1919382 1
...
```

## Como se mostra que está correto

1. Todas as versões são comparadas com a sequencial (`--verify`, `bench`, `run-mpj.sh ... --verify`):
   a lista de posições e divergências de cada padrão tem de ser **idêntica**.
2. Os ficheiros de saída podem ser comparados diretamente: `cmp res_seq.txt res_mpj.txt`.
3. `bench`, `search blocks` e o MPJ mostram quantas ocorrências **cruzam a fronteira** entre
   blocos/trechos — essas ocorrências estão presentes e o resultado continua idêntico.
4. `selftest` compara a versão otimizada com força bruta letra-a-letra em centenas de casos
   aleatórios (inclui `N`, padrões > 32 letras, mais blocos do que posições, etc.).

## Detalhes de implementação

**Hamming bit-paralelo (CPU).** Cada base usa 2 bits (A=00, C=01, G=10, T=11). Mantém-se uma
janela deslizante das últimas 32 bases num `long`. Para cada padrão (pré-codificado):
`x = janela ^ padrão; d = bitCount((x | x>>>1) & 0x5555... & máscara)`. São ~6 instruções sem
ramos por par (posição, padrão), e todos os padrões são testados numa única passagem pela memória.
Letras que não são ACGT (`N`, etc.) contam sempre como divergência. Padrões com mais de 32
letras usam a comparação letra-a-letra (`HammingMatcher.scanSimple`).

**Fronteiras.**
* *Threads/blocos:* a memória é partilhada, por isso cada thread é dona das posições
  **iniciais** `[início, fim)` do seu bloco mas pode ler até `fim + tamanho − 1`. Uma ocorrência
  que cruza a fronteira é encontrada pela thread onde começa, e só por essa (sem duplicados).
* *MPJ:* a memória não é partilhada, por isso o processo 0 envia a cada processo `r` os bytes
  `[s_r, e_r + maxLen − 1)` — trechos sobrepostos pelo tamanho do maior padrão. Cada processo só
  reporta posições iniciais em `[s_r, e_r)`. O processo 0 recebe os resultados pela ordem dos
  processos, o que mantém as posições ordenadas.
* *GPU:* a sequência é copiada para o dispositivo em trechos de 64 M posições, também com
  `maxLen − 1` bytes de sobreposição.

**Threads/padrões vs blocos.** Na divisão por padrões cada thread percorre a sequência toda
(T leituras completas da memória) e o paralelismo fica limitado ao nº de padrões; na divisão
por blocos cada byte é lido uma vez e o trabalho divide-se uniformemente.
Com poucos padrões e muitas threads a divisão por blocos escala melhor.

**GPU.** O kernel faz a comparação letra-a-letra com saída antecipada (padrões em memória
`__constant`). Cada ocorrência reserva uma posição no buffer de saída com `atomic_inc`; se o
buffer encher, o host aumenta-o e repete o trecho. Como a ordem do `atomic_inc` é arbitrária,
as listas são ordenadas no fim.

## Resultados experimentais

### Ambiente

| | |
|---|---|
| CPU | AMD Ryzen 5 3600X — 6 núcleos físicos / 12 threads (SMT) |
| Memória | 16 GB |
| Sistema | Arch Linux, kernel 7.2.11 |
| Java | OpenJDK 27.0.1 (compilado com `--release 17`), `-Xmx4g` |
| MPJ | MPJ Express 0.44, dispositivo `multicore` (uma máquina) |
| GPU | NVIDIA GeForce RTX 3050 8 GB (GA107), driver `nvidia-open` + `opencl-nvidia` |
| OpenCL | NVIDIA CUDA (GPU) e pocl 7.2 (OpenCL no CPU, usado para validar antes de haver driver) |

### Dados

* Sequência sintética de **500 000 000 bases** (`generate --size-mb 500 --seed 42`), FASTA com linhas de 80 letras.
* **100 padrões** aleatórios de 12 a 20 letras; até **2 divergências**.
* 5 000 cópias mutadas dos padrões em posições aleatórias + 496 cópias colocadas **exatamente a
  cruzar as fronteiras** dos blocos para divisões em 2..32 partes.
* Resultado: **230 650 ocorrências** (idênticas em todas as versões).

Os tempos medem só a busca (a leitura do FASTA, ~5 s, não está incluída). No MPJ incluem a
difusão dos padrões, o envio dos trechos e a recolha dos resultados. Antes das medições é feito
um aquecimento do JIT num pedaço de 2 MB.

### Threads e GPU (`bench --threads 1,2,4,6,12 --gpu`, 500 MB, uma única execução)

| Versão | Threads | Tempo (s) | Speedup | Eficiência | Ocorrências a cruzar fronteiras |
|---|---:|---:|---:|---:|---:|
| Sequencial | 1 | 57,58 | 1,00 | — | — |
| Blocos | 1 | 58,46 | 0,98 | 98 % | 0 |
| Blocos | 2 | 28,47 | 2,02 | 101 % | 1 |
| Blocos | 4 | 14,70 | 3,92 | 98 % | 3 |
| **Blocos** | **6** | **10,02** | **5,75** | **96 %** | 5 |
| Blocos | 12 | 13,76 | 4,18 | 35 % | 11 |
| Padrões | 1 | 57,98 | 0,99 | 99 % | — |
| Padrões | 2 | 29,50 | 1,95 | 98 % | — |
| Padrões | 4 | 16,78 | 3,43 | 86 % | — |
| Padrões | 6 | 15,10 | 3,81 | 64 % | — |
| Padrões | 12 | 18,55 | 3,10 | 26 % | — |
| **GPU — RTX 3050** | — | **2,85** | **20,21** | — | (8 trechos no dispositivo) |

Eficiência = speedup / nº de threads. Todas as linhas: `IDÊNTICO` à sequencial (230 650 ocorrências).
Acima dos 6 núcleos físicos os tempos variam muito: noutra execução, 12 threads/blocos deram 10,31 s.

### MPJ Express (`run-mpj.sh`, 500 MB, modo multicore)

| Processos | Tempo (s) | Speedup ² | Ocorrências a cruzar fronteiras | `cmp` com a saída sequencial |
|---:|---:|---:|---:|---|
| 2 | 29,45 | 1,97 | 1 | idêntico |
| 4 | 15,75 | 3,69 | 3 | idêntico |
| 6 | 10,60 | 5,48 | 5 | idêntico |
| 12 | 10,46 | 5,56 | 11 | idêntico |

² Em relação à sequencial medida na mesma sessão (58,08 s).

### GPU — decomposição do tempo (RTX 3050, 500 MB, 3 execuções)

| Fase | Execução 1 | Execução 2 | Execução 3 | % do total |
|---|---:|---:|---:|---:|
| Preparação (contexto OpenCL + compilar o kernel) | 0,158 s | 0,173 s | 0,184 s | ~7 % |
| Envio da sequência para a GPU (500 MB, 8 trechos) | 0,042 s | 0,045 s | 0,043 s | ~2 % |
| **Kernel** | **2,194 s** | **2,285 s** | **2,317 s** | **~88 %** |
| Receção dos resultados | 0,008 s | 0,009 s | 0,008 s | <1 % |
| Ordenação no host | 0,011 s | 0,021 s | 0,019 s | <1 % |
| **Total** | **2,482 s** | **2,607 s** | **2,642 s** | |

Para comparação, o mesmo kernel no CPU com pocl (Ryzen 5 3600X) demorou 74,33 s (0,78×). Foi isso que
se usou para validar o kernel antes de instalar o `opencl-nvidia`. O código escolhe primeiro uma GPU,
depois um acelerador e por fim o CPU.

### Análise

* **Divisão por blocos escala quase linearmente até ao nº de núcleos físicos** (≈100 % de eficiência
  com 2 e 4 threads, 94 % com 6). Cada thread lê só o seu bloco, uma vez, e não há sincronização
  durante a busca. Só há um `join` no fim e a junção das listas, que é desprezável (230 mil entradas).
* **Acima de 6 threads não há ganho.** As 12 threads lógicas partilham os 6 núcleos (SMT) e as mesmas
  unidades de execução. Como o ciclo interno é quase só aritmética de inteiros (XOR, OR, `bitCount`),
  o SMT ajuda pouco.
* **A divisão por padrões escala pior.** Cada thread percorre a sequência inteira: com T threads há
  T leituras completas dos 500 MB e T atualizações da janela deslizante por posição, em vez de uma. Com
  poucas threads o custo extra fica escondido, mas cresce com T (eficiência de 87 % com 4 e 67 % com 6).
  Também fica limitada a no máximo 100 threads úteis (nº de padrões) e o equilíbrio depende do tamanho
  dos padrões. Vantagem: não há fronteiras para tratar.
* **MPJ fica próximo das threads/blocos.** Em modo multicore os "processos" são threads da mesma JVM e
  as mensagens são cópias de memória, por isso o custo extra é sobretudo copiar ~500 MB para os
  trechos. Num cluster (`niodev`) somar-se-ia o envio pela rede: ~4 s para 500 MB em 1 Gbit/s, o que
  seria significativo face a ~10 s de busca. Aí compensaria cada processo ler o seu trecho diretamente
  de um sistema de ficheiros partilhado.
* **A GPU é a mais rápida: 20× face à sequencial e ~3,5× face à melhor versão CPU** (6 threads, 10,02 s).
  O problema é perfeito para GPU: 500 milhões de posições independentes, um work-item por posição,
  sem comunicação entre work-items, e o trabalho por posição é igual em todas.
* **Na GPU, a transferência não é o gargalo.** Enviar os 500 MB demora ~0,04 s (≈12 GB/s em PCIe) e os
  resultados são poucos (230 mil × 9 bytes). Cerca de 88 % do tempo é o próprio kernel, e só ~7 % é
  o custo fixo de criar o contexto e compilar o kernel. Esse custo fixo pesaria mais com sequências pequenas.
* **O kernel ainda pode melhorar.** Faz comparação letra-a-letra com saída antecipada (ramos que
  divergem dentro de um warp) e lê os padrões da memória constante. A codificação de 2 bits + XOR +
  `popcount` usada no CPU reduziria os acessos à memória e os ramos (ver "Limitações").
* **OpenCL no pocl (CPU) é mais lento que o Java sequencial** (74 s). O kernel letra-a-letra foi
  pensado para GPU: no CPU perde para a versão bit-paralela do Java.

### Verificação da correção

1. **Igualdade com a sequencial:** todas as linhas acima dizem `IDÊNTICO` (comparação de cada lista
   ordenada de posição + divergências) e os ficheiros de saída do MPJ são iguais byte a byte (`cmp`).
2. **Fronteiras:** com N blocos/trechos há N−1 fronteiras, e em todas as execuções foram encontradas
   N−1 ocorrências a cruzá-las (as que o gerador lá colocou). Sem a sobreposição de `maxLen − 1`
   bytes estas ocorrências desapareceriam e a comparação falharia.
3. **Testes aleatórios (`./run.sh selftest`):** 520 casos com sequências de 1 a 5 000 bases, letras `N`,
   padrões de 1 a 40 letras (testando também o caminho para padrões > 32), 0 a 3 divergências e
   1, 2, 3, 7, 16 e 64 threads (mais blocos do que posições). Comparação com força bruta. 0 falhas.
4. **Coerência com a teoria:** numa sequência aleatória uniforme, um padrão de tamanho L tem
   ocorrência com ≤ 2 divergências numa posição com probabilidade
   p(L) = [1 + 3L + 9·L(L−1)/2] / 4^L (ex.: L = 12 → 3,8 × 10⁻⁵).
   Para os 100 padrões usados, o valor esperado é Σ (n − L + 1)·p(L) ≈ **225 489**. Somando os ~5 496
   implantes dá ≈ 230 985, contra **230 650** encontradas: diferença de 0,15 %, dentro do desvio padrão
   esperado (≈ √225 489 ≈ 475).

## Decisões de projeto

* **Memória:** a sequência fica num único `byte[]` (500 MB). Cada ocorrência ocupa um `long`
  (`posição << 2 | divergências`), por isso ordenar os `long` ordena por posição e a comparação entre
  versões é um `Arrays.equals`.
* **Sem sincronização no caminho crítico:** nas threads/blocos cada thread escreve em listas próprias;
  nas threads/padrões cada padrão tem a sua lista e pertence a uma só thread. Não há `synchronized`
  nem estruturas concorrentes durante a busca.
* **Posições iniciais como unidade de trabalho:** todas as versões dividem o conjunto de posições
  *iniciais*. A sobreposição (threads: leitura partilhada; MPJ/GPU: bytes extra) garante que cada
  ocorrência é vista inteira por quem é dono da posição onde começa: nada se perde e nada se duplica.
* **Letras fora de ACGT** (`N`, códigos IUPAC) são convertidas para `N` e contam sempre como divergência,
  para não haver falsas ocorrências em zonas desconhecidas de cromossomas reais.
* **Número de divergências configurável** (`--div 0..3`; o enunciado pede 2).

## Limitações

* O tamanho máximo da sequência é ~2 GB (limite de um array Java). Para mais, seria preciso dividir a leitura.
* O protocolo cliente/servidor guarda o FASTA recebido em memória no servidor. Não tem autenticação.
* O kernel GPU usa a comparação letra-a-letra. Uma versão bit-paralela (2 bits/base + `popcount`, como no CPU)
  deveria ser bastante mais rápida, mas não foi implementada.
* Na GPU, posições e padrões de cada ocorrência são devolvidos em `int` (trechos de ≤ 64 M posições), por isso
  um padrão com ocorrências excessivas (ex.: `--div 3` com padrões muito curtos) obriga a repetir trechos com mais memória.
* As medições de CPU são de uma execução cada (a GPU tem 3). Para o relatório final convém repetir 3–5 vezes
  e usar a mediana, sobretudo com 12 threads, onde a variação é maior.

## Reproduzir os resultados

```bash
export MPJ_HOME=~/Dependencias/mpj-v0_44
./build.sh
./run.sh selftest
./run.sh generate data/chr500.fa data/padroes500.txt --size-mb 500 --seed 42
./run.sh bench data/chr500.fa data/padroes500.txt --threads 1,2,4,6,12 --gpu
./run.sh search seq data/chr500.fa data/padroes500.txt --out res_seq.txt
for np in 2 4 6 12; do
  ./run-mpj.sh $np data/chr500.fa data/padroes500.txt 2 res_mpj_$np.txt
  cmp res_seq.txt res_mpj_$np.txt && echo "MPJ $np: idêntico"
done
```
