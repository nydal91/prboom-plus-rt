#!/bin/sh
# PrBoom+ RT (ray query build) launcher.
#
#   ./run.sh                          play doom2.wad / doom.wad / ... if placed
#                                     next to this script, otherwise Freedoom
#   ./run.sh mymap.wad [patch.deh]    play a custom level / mod (.wad, .deh,
#                                     .bex or a .zip of them); the right base
#                                     game is picked from the levels it has
#   ./run.sh -iwad freedoom2.wad ...  any other arguments go to the game
#                                     (e.g. -warp 1 1, -skill 4)

here="$(cd "$(dirname "$0")" && pwd)" || exit 1

# the renderer presents through X11 (XWayland on Wayland desktops)
export SDL_VIDEODRIVER="${SDL_VIDEODRIVER:-x11}"

abspath() {
  case "$1" in
    /*) printf '%s\n' "$1" ;;
    *)  printf '%s/%s\n' "$PWD" "$1" ;;
  esac
}

# split the arguments into mod files and other game arguments
wads=""; dehs=""; args=""; iwad_given=no; doom2=no; doom1=no
nl='
'
for a in "$@"; do
  lower=$(printf '%s' "$a" | tr 'A-Z' 'a-z')
  if [ -f "$a" ] && [ "$iwad_given" != next ]; then
    case "$lower" in
      *.zip)
        dir="$HOME/.prboom-plus/mods/$(basename "$a" .zip)"
        if ! command -v unzip >/dev/null 2>&1; then
          echo "Install 'unzip' to play .zip mods, or extract the .wad from it." >&2; exit 1
        fi
        rm -rf "$dir"; mkdir -p "$dir"
        unzip -qjo "$a" '*.wad' '*.WAD' '*.deh' '*.DEH' '*.bex' '*.BEX' -d "$dir" 2>/dev/null
        for f in "$dir"/*; do
          case "$(printf '%s' "$f" | tr 'A-Z' 'a-z')" in
            *.wad) wads="$wads$f$nl" ;;
            *.deh|*.bex) dehs="$dehs$f$nl" ;;
          esac
        done
        continue ;;
      *.wad) wads="$wads$(abspath "$a")$nl"; continue ;;
      *.deh|*.bex) dehs="$dehs$(abspath "$a")$nl"; continue ;;
    esac
  fi
  [ "$iwad_given" = next ] && iwad_given=yes
  [ "$a" = "-iwad" ] && iwad_given=next
  args="$args$a$nl"
done

# which base game the levels are for: MAP01.. is Doom II, E1M1.. is Doom
oldifs=$IFS; IFS=$nl
for w in $wads; do
  if [ "$(head -c 4 "$w")" = "IWAD" ]; then
    args="-iwad$nl$w$nl$args"; iwad_given=yes; wads=$(printf '%s' "$wads" | grep -vxF "$w"); continue
  fi
  LC_ALL=C grep -aq 'MAP[0-9][0-9]' "$w" && doom2=yes
  LC_ALL=C grep -aq 'E[1-9]M[1-9]' "$w" && doom1=yes
done
IFS=$oldifs

cd "$here" || exit 1

if [ "$iwad_given" = no ]; then
  if [ "$doom2" = yes ]; then
    order="doom2.wad plutonia.wad tnt.wad freedoom2.wad"
  elif [ "$doom1" = yes ]; then
    order="doom.wad doomu.wad doom1.wad freedoom1.wad"
  else
    order="doom2.wad doom.wad doomu.wad plutonia.wad tnt.wad doom1.wad freedoom1.wad"
  fi
  for w in $order; do
    if [ -f "$w" ]; then
      args="-iwad$nl$w$nl$args"
      break
    fi
  done
fi

# rebuild the argument list: game args, then -file / -deh lists
set --
IFS=$nl
for a in $args; do set -- "$@" "$a"; done
if [ -n "$wads" ]; then set -- "$@" -file; for w in $wads; do set -- "$@" "$w"; done; fi
if [ -n "$dehs" ]; then set -- "$@" -deh;  for d in $dehs; do set -- "$@" "$d"; done; fi
IFS=$oldifs

exec ./prboom-plus "$@"
