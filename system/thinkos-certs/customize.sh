#!/system/bin/sh
# MIT. Installation checks only; no daemon, boot script or system partition write.
case "$API" in ''|*[!0-9]*) abort "! Invalid Android API" ;; esac
[ "$API" -ge 27 ] || abort "! Android API 27 or newer is required"
CA_DIR="$MODPATH/system/etc/security/cacerts"
[ -d "$CA_DIR" ] && [ ! -L "$CA_DIR" ] || abort "! Missing CA directory"
COUNT=0
for CERT in "$CA_DIR"/* "$CA_DIR"/.[!.]* "$CA_DIR"/..?*; do
  [ -e "$CERT" ] || [ -L "$CERT" ] || continue
  [ -f "$CERT" ] && [ ! -L "$CERT" ] || abort "! CA entries must be regular files"
  NAME=${CERT##*/}
  if [ "$NAME" = .replace ]; then
    [ ! -s "$CERT" ] || abort "! Nonempty .replace marker"
    continue
  fi
  printf '%s\n' "$NAME" | grep -Eq '^[0-9a-f]{8}\.[0-9]+$' || abort "! Invalid CA filename"
  IFS= read -r HEADER < "$CERT" || abort "! Empty CA file"
  [ "$HEADER" = '-----BEGIN CERTIFICATE-----' ] || abort "! Invalid PEM header"
  COUNT=$((COUNT + 1))
done
[ "$COUNT" -ge 100 ] && [ "$COUNT" -le 512 ] || abort "! Expected 100 to 512 CA certificates"
MODE=$(cat "$MODPATH/install-mode")
case "$MODE" in
  add)
    # add filenames were allocated against this exact ROM store. Never merge
    # into a different store: same-subject collisions could hide an old root.
    sha256sum -c "$MODPATH/baseline-sha256.txt" >/dev/null 2>&1 || abort "! Original CA store changed; cannot safely add"
    [ ! -e "$CA_DIR/.replace" ] || abort "! add mode cannot replace the directory"
    ;;
  replace) [ -f "$CA_DIR/.replace" ] || abort "! Missing replace marker" ;;
  *) abort "! Missing/invalid installation mode" ;;
esac
set_perm_recursive "$MODPATH" 0 0 0755 0644 u:object_r:system_file:s0
ui_print "- ThinkOS CA roots: $COUNT"
ui_print "- Systemless overlay; reboot to activate"
