#!/system/bin/sh
# MIT. Fixed package/operation only. No socket, arbitrary command or APK input.
# The second argument is a root-private state directory: production uses
# /data/adb/thinkos-shell-state; development uses a disposable /data/local/tmp path.
set -eu
set -f
umask 077
PACKAGE=moe.kiwi.thinkos
PROPERTY=sys.thinkos.shell
action=${1:-}
state=${2:-}
case "$state" in /*) ;; *) echo "An absolute private state directory is required" >&2; exit 2 ;; esac

notify_app() {
  # Property writes have no ContentObserver. This fixed, private signal makes
  # an already-background app recheck AFTER the entire transaction is ready.
  # Missing/older APKs must not turn a successful system transaction into a failure.
  am broadcast --receiver-foreground -a moe.kiwi.thinkos.SHELL_CHANGED -n "$PACKAGE/.shell.ShellChangedReceiver" >/dev/null 2>&1 || true
}

appop_mode() {
  output=$(appops get "$PACKAGE" SYSTEM_ALERT_WINDOW) || return 1
  mode=$(printf '%s\n' "$output" | sed -n 's/^[[:space:]]*SYSTEM_ALERT_WINDOW: \([a-z]*\).*/\1/p')
  if [ -z "$mode" ]; then
    case "$output" in *"No operations"*) mode=default ;; *) return 1 ;; esac
  fi
  case "$mode" in allow|ignore|deny|default) printf '%s\n' "$mode" ;; *) return 1 ;; esac
}

snapshot() {
  if [ -f "$state/complete" ]; then return 0; fi
  previous=$(settings get global policy_control) || return 1
  [ "${#previous}" -le 4096 ] || return 1
  previous_mode=$(appop_mode) || return 1
  previous_property=$(getprop "$PROPERTY") || return 1
  mkdir -p "$state" || return 1
  chmod 700 "$state" || return 1
  printf '%s\n' "$previous" > "$state/policy-original" || return 1
  printf '%s\n' "$previous_mode" > "$state/appop-original" || return 1
  printf '%s\n' "$previous_property" > "$state/property-original" || return 1
  printf '1\n' > "$state/complete" || return 1
}

restore() {
  [ -f "$state/complete" ] || return 0
  failed=0
  # First disable the edge so policy/permission changes cannot leave a live overlay.
  current_property=$(getprop "$PROPERTY") || { current_property=unreadable; failed=1; }
  if [ "$current_property" = 2 ]; then
    expected=$(cat "$state/property-original")
    setprop "$PROPERTY" "$expected" || failed=1
    [ "$(getprop "$PROPERTY")" = "$expected" ] || failed=1
  fi
  current_policy=$(settings get global policy_control) || { current_policy=unreadable; failed=1; }
  if [ -f "$state/policy-applied" ] && [ "$current_policy" = "$(cat "$state/policy-applied")" ]; then
    expected=$(cat "$state/policy-original")
    if [ "$expected" = null ]; then settings delete global policy_control >/dev/null || failed=1
    else settings put global policy_control "$expected" || failed=1; fi
    [ "$(settings get global policy_control)" = "$expected" ] || failed=1
  fi
  current_mode=$(appop_mode) || { current_mode=unreadable; failed=1; }
  if [ "$current_mode" = allow ]; then
    expected=$(cat "$state/appop-original")
    appops set "$PACKAGE" SYSTEM_ALERT_WINDOW "$expected" || failed=1
    [ "$(appop_mode)" = "$expected" ] || failed=1
  fi
  if [ "$failed" = 0 ]; then
    rm -f "$state/complete" "$state/policy-original" "$state/policy-applied" "$state/appop-original" "$state/property-original"
    rmdir "$state"
  fi
  notify_app
  return "$failed"
}

apply() {
  snapshot || return 1
  previous=$(settings get global policy_control) || return 1
  [ "${#previous}" -le 4096 ] || return 1
  # Preserve later manual changes across another boot/application of the module.
  if [ -f "$state/policy-applied" ] && [ "$previous" != "$(cat "$state/policy-applied")" ]; then
    printf '%s\n' "$previous" > "$state/policy-original" || return 1
  fi
  current_mode=$(appop_mode) || return 1
  if [ "$current_mode" != allow ]; then printf '%s\n' "$current_mode" > "$state/appop-original" || return 1; fi
  merged=
  if [ "$previous" != null ]; then
    old_ifs=$IFS; IFS=:
    for section in $previous; do
      case "$section" in immersive.status=*|immersive.navigation=*|immersive.full=*|'') continue ;; esac
      merged=${merged:+$merged:}$section
    done
    IFS=$old_ifs
  fi
  merged=${merged:+$merged:}'immersive.full=*'
  printf '%s\n' "$merged" > "$state/policy-applied" || return 1
  settings put global policy_control "$merged" || return 1
  [ "$(settings get global policy_control)" = "$merged" ] || return 1
  appops set "$PACKAGE" SYSTEM_ALERT_WINDOW allow || return 1
  [ "$(appop_mode)" = allow ] || return 1
  setprop "$PROPERTY" 2 || return 1
  [ "$(getprop "$PROPERTY")" = 2 ] || return 1
  notify_app
}

case "$action" in
  apply) if ! apply; then restore || true; echo "ThinkOS Shell transaction failed" >&2; exit 1; fi ;;
  restore) restore ;;
  *) echo "Use apply or restore" >&2; exit 2 ;;
esac
