#!/bin/zsh
# Types the SDK token, copied in the computer's clipboard, into the field open on the Portal
# (maintenance menu > Muse SDK token, field touched and empty). macOS only (pbpaste).
# The token is never shown nor kept in the shell history.
while true; do
  read -rs "ANSWER?Copy the SDK token (Cmd+C), then press Enter here: " || exit 1
  echo
  # A token pasted here is accepted too; otherwise the clipboard is read.
  TOKEN=${ANSWER:-$(pbpaste)}
  TOKEN=${TOKEN//[[:space:]]/}
  if [[ ! $TOKEN =~ '^mgst_[A-Za-z0-9_-]{43}$' ]]; then
    echo "No SDK token found. Copy it on gadgets.muse.ai, then try again."
  elif adb shell input text "$TOKEN"; then
    pbcopy < /dev/null
    echo "Sent, clipboard cleared. Touch \"Save\" on the Portal."
    unset TOKEN ANSWER
    exit 0
  else
    echo "Could not send: check the USB cable and the Portal's debugging."
  fi
done
