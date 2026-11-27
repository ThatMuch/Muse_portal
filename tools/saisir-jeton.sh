#!/bin/zsh
# Tape le jeton SDK, copié dans le presse-papiers du Mac, dans le champ ouvert sur
# le Portal (menu de maintenance > Jeton SDK Muse, champ touché et vide).
# Le jeton n'est jamais affiché ni gardé dans l'historique du terminal.
while true; do
  read -rs "REPONSE?Copie le jeton SDK (Cmd+C), puis appuie ici sur Entrée : " || exit 1
  echo
  # Un jeton collé ici est accepté aussi ; sinon on lit le presse-papiers.
  JETON=${REPONSE:-$(pbpaste)}
  JETON=${JETON//[[:space:]]/}
  if [[ ! $JETON =~ '^mgst_[A-Za-z0-9_-]{43}$' ]]; then
    echo "Pas de jeton SDK trouvé. Copie-le sur gadgets.muse.ai, puis recommence."
  elif adb shell input text "$JETON"; then
    pbcopy < /dev/null
    echo "Envoyé, presse-papiers vidé. Touche « Enregistrer » sur le Portal."
    unset JETON REPONSE
    exit 0
  else
    echo "Envoi impossible : vérifie le câble USB et le débogage du Portal."
  fi
done
