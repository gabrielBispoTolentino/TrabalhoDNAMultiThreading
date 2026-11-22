#!/usr/bin/env bash
# Executa o programa principal (sequencial, threads, GPU, servidor, cliente).
# Memória: ajuste JAVA_OPTS (500 MB de sequência -> mínimo -Xmx1g; por omissão -Xmx2g)
cd "$(dirname "$0")"
CP=build/classes
for j in lib/*.jar; do [ -f "$j" ] && CP="$CP:$j"; done
# Java 22+: permite ao JOCL carregar a biblioteca nativa sem avisos
V=$(java -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')
NA=""; [ "${V:-0}" -ge 22 ] && NA="--enable-native-access=ALL-UNNAMED"
exec java $NA ${JAVA_OPTS:--Xmx2g} -cp "$CP" dna.Main "$@"
