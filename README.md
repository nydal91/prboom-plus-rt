# PrBoom+ RT for Android and Linux

A port of [PrBoom: Ray Traced](https://github.com/sultim-t/prboom-plus-rt) by
Sultim to **Android** (arm64, Vulkan ray queries, e.g. Snapdragon 8 Gen 2 and
newer) and a portable **Linux x86-64** build (any Vulkan GPU with
`VK_KHR_ray_query`: NVIDIA RTX, AMD RX 6000+, Intel Arc).

Downloads are on the [Releases](../../releases) page.

## What's new compared to the original

- Renderer switched from ray tracing pipelines to `VK_KHR_ray_query`
  (compute shaders), which phone GPUs support; 4 descriptor sets instead of 9
  for Adreno; see `prboom2/android/rtgl1-patches/`
- Android app: touch controls (move stick, turn by dragging, FIRE / USE,
  MENU / MAP / ENTER / LIGHT / weapon buttons, tap menu items), game picker,
  import of games and mods (`.wad`, `.deh`, `.zip`), pause/resume
- Game controllers (Xbox, PlayStation, ...): analog movement and turning,
  triggers, menu navigation, rumble (also on Android)
- Official RT materials and per-map lights, converted for phone GPUs
- Music through the built-in OPL2 synth; fixed sound mixing that looped and
  distorted without SDL_mixer
- Show FPS option; custom levels get sector lighting instead of the original
  maps' hand-placed lights
- Fix for AMD (RADV) startup failure (`VK_ERROR_OUT_OF_POOL_MEMORY`)

## Game data

Freedoom: Phase 1 and 2 are included. To play Doom or Doom II, use your own
`doom.wad` / `doom2.wad`: on Android, add it in the game picker ("Add
file..."); on Linux, put it next to `run.sh`. The commercial game files are
not distributed here.

## Docs

- Android: [prboom2/android/README.md](prboom2/android/README.md) (controls,
  mods, building)
- Linux: [prboom2/linux/README.txt](prboom2/linux/README.txt)

Licensed under the GNU GPL v2, like PrBoom+ and the original PrBoom+ RT.
