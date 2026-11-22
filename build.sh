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

# ---------- jar único (dist/TrabalhoDNA.jar): classes + JOCL + MPJ Express (multicore) ----------
STAGE=build/jar
mkdir -p "$STAGE" dist
cp -r "$OUT"/. "$STAGE"/
if [ -n "$JOCL" ]; then
  unzip -qo "$JOCL" -x 'META-INF/*' -d "$STAGE"
fi
if [ -n "$MPJ_HOME" ] && [ -f "$MPJ_HOME/lib/mpj.jar" ]; then
  # mpiExp por último: as suas classes são as partilhadas entre processos (ver MpjLauncher)
  for j in log4j-1.2.11 xdev smpdev mpjbuf mpi mpjdev mpiExp; do
    unzip -qo "$MPJ_HOME/lib/$j.jar" -x 'META-INF/*' -d "$STAGE"
  done
  cp "$MPJ_HOME/conf/mpjexpress.conf" "$STAGE/dna/mpj/"
  mkdir -p "$STAGE/META-INF/licenses"
  cp "$MPJ_HOME/LICENSE.txt" "$STAGE/META-INF/licenses/MPJExpress-LICENSE.txt"
fi
printf 'Main-Class: dna.Main\nEnable-Native-Access: ALL-UNNAMED\n' > build/manifest.txt
jar --create --file dist/TrabalhoDNA.jar --manifest build/manifest.txt -C "$STAGE" .
echo "OK -> dist/TrabalhoDNA.jar ($(du -h dist/TrabalhoDNA.jar | cut -f1))"
