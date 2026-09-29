#!/usr/bin/env bash
# Monta o APK do Festa Sync sem Android Studio/Gradle — só as ferramentas oficiais do SDK
# (aapt2, javac, d8, zipalign, apksigner). Uso:  ./android/build.sh
# Resultado: android/build/festa-sync.apk
#
# Ferramentas esperadas em ~/android-build-tools (ou na pasta indicada em TOOLS=...):
#   jdk/  sdk/platforms/android-35  sdk/build-tools/35.0.0
# A chave de assinatura (festa-sync.jks) também fica lá, FORA do git: é ela que deixa
# instalar uma versão nova por cima da antiga no celular. Perdeu a chave = cada pessoa
# precisa desinstalar o app antigo antes de instalar o novo.
set -euo pipefail

TOOLS="${TOOLS:-$HOME/android-build-tools}"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"
export JAVA_HOME="$TOOLS/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$TOOLS/sdk/build-tools/35.0.0"
ANDROID_JAR="$TOOLS/sdk/platforms/android-35/android.jar"
KEYSTORE="$TOOLS/festa-sync.jks"
PASSFILE="$TOOLS/festa-sync-keystore-senha.txt"

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build"
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$ANDROID_JAR" "$JAVA_HOME/bin/javac"; do
  [ -e "$f" ] || { echo "Faltando: $f (veja android/LEIA-ME.md)"; exit 1; }
done

# chave de assinatura: cria na primeira vez (senha aleatória guardada ao lado)
if [ ! -f "$KEYSTORE" ]; then
  echo "Criando chave de assinatura em $KEYSTORE ..."
  head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24 > "$PASSFILE"
  chmod 600 "$PASSFILE"
  keytool -genkeypair -keystore "$KEYSTORE" -alias festasync -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$(cat "$PASSFILE")" -keypass "$(cat "$PASSFILE")" -dname "CN=Festa Sync" >/dev/null
fi
PASS="$(cat "$PASSFILE")"

echo "1/5 recursos (aapt2)"
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" --manifest "$HERE/AndroidManifest.xml" \
  --java "$OUT/gen" --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "$OUT/res.zip"

echo "2/5 compilando Java"
javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/classes" $(find "$OUT/gen" "$HERE/src" -name '*.java')

echo "3/5 convertendo pra Android (d8)"
"$BT/d8" --release --lib "$ANDROID_JAR" --min-api 24 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)

echo "4/5 alinhando (zipalign)"
"$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "5/5 assinando (apksigner)"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias festasync --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "$OUT/festa-sync.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/festa-sync.apk"
rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk" "$OUT"/*.idsig

echo
echo "Pronto: $OUT/festa-sync.apk ($(du -h "$OUT/festa-sync.apk" | cut -f1))"
