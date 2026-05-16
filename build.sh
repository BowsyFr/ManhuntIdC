#!/bin/bash
# ============================================================
#  Build script - Manhunt Plugin
#  Prérequis : Maven 3.6+, Java 21, connexion Internet
# ============================================================

set -e

echo "==> Build du plugin fr.bowsy.manhunt"
cd "$(dirname "$0")"

mvn clean package -q

JAR=$(find target -name "manhunt-*.jar" ! -name "*original*" | head -1)

if [ -z "$JAR" ]; then
  echo "[ERREUR] Le jar n'a pas été généré."
  exit 1
fi

echo "==> JAR généré : $JAR"
echo "==> Copie dans le dossier plugins/ si présent..."

if [ -d "../../plugins" ]; then
  cp "$JAR" "../../plugins/manhunt.jar"
  echo "==> Copié vers ../../plugins/manhunt.jar"
elif [ -d "../plugins" ]; then
  cp "$JAR" "../plugins/manhunt.jar"
  echo "==> Copié vers ../plugins/manhunt.jar"
else
  echo "==> Dossier plugins non trouvé. Copiez manuellement : $JAR"
fi

echo "==> Terminé !"
