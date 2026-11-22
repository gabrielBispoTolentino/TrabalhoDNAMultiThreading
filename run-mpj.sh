#!/usr/bin/env bash
# Executa a versão MPJ Express em modo multicore (uma máquina).
#   ./run-mpj.sh <nProcessos> <dados.fa> <padroes.txt> [div] [saida.txt] [--verify]
# Para cluster: mpjrun.sh -np N -dev niodev -machinesfile machines -cp build/classes dna.mpj.MpjSearch ...
cd "$(dirname "$0")"
: "${MPJ_HOME:?defina MPJ_HOME (ex.: export MPJ_HOME=~/Dependencias/mpj-v0_44)}"
NP=$1; shift
exec "$MPJ_HOME/bin/mpjrun.sh" -np "$NP" -dev multicore ${MPJ_JVM_OPTS:--Xmx6g} -cp build/classes dna.mpj.MpjSearch "$@"
