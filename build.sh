#!/usr/bin/env bash
# Compila o projeto. Partes opcionais:
#   GPU -> precisa de lib/jocl-*.jar
#   MPJ -> precisa de MPJ_HOME (MPJ Express) com $MPJ_HOME/lib/mpj.jar
set -e
cd "$(dirname "$0")"
OUT=build/classes
rm -rf build && mkdir -p "$OUT"

SRC=$(find src/dna -name '*.java' -not -path '*/gpu/*' -not -path '*/mpj/*')
CP="$OUT"

JOCL=$(ls lib/jocl-*.jar 2>/dev/null | head -1 || true)
if [ -n "$JOCL" ]; then
  SRC="$SRC $(find src/dna/gpu -name '*.java')"; CP="$CP:$JOCL"
  echo "GPU: ativado ($JOCL)"
else
  echo "GPU: desativado (coloque jocl-2.0.5.jar em lib/)"
fi

if [ -n "$MPJ_HOME" ] && [ -f "$MPJ_HOME/lib/mpj.jar" ]; then
  SRC="$SRC $(find src/dna/mpj -name '*.java')"; CP="$CP:$MPJ_HOME/lib/mpj.jar"
  echo "MPJ: ativado ($MPJ_HOME)"
else
  echo "MPJ: desativado (defina MPJ_HOME)"
fi

javac --release 17 -encoding UTF-8 -d "$OUT" -cp "$CP" $SRC
cp src/dna/gpu/motif_search.cl "$OUT/dna/gpu/" 2>/dev/null || { mkdir -p "$OUT/dna/gpu"; cp src/dna/gpu/motif_search.cl "$OUT/dna/gpu/"; }
echo "OK -> $OUT"
