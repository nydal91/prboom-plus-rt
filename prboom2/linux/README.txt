PrBoom+ RT for Linux x86-64 (ray query build)
=============================================

Ray traced Doom: PrBoom+ RT with the RayTracedGL1 renderer ported to
VK_KHR_ray_query, so it runs on any Vulkan GPU with ray tracing support.

Run
---
    ./run.sh

Freedoom (free Doom-compatible game) is included. To play original Doom,
copy doom.wad or doom2.wad (also plutonia.wad, tnt.wad) into this folder;
run.sh uses it automatically. Other examples:

    ./run.sh -iwad freedoom2.wad     Freedoom Phase 2 (Doom II style)
    ./run.sh -warp 1 1 -skill 3      start E1M1 directly
    ./run.sh -window -width 1280 -height 720

Settings and saves go to ~/.prboom-plus/. Ray tracing options (upscaling,
render scale, bounce quality) are in Options in the game menu.

Requirements
------------
- x86-64 Linux with glibc 2.29 or newer (Ubuntu 20.04+, Debian 11+,
  Fedora 30+, current Arch, Mint 20+, ...)
- Vulkan loader (libvulkan1 / vulkan-loader) and a driver with
  VK_KHR_ray_query: NVIDIA RTX (proprietary driver), AMD RX 6000+ (Mesa
  RADV), Intel Arc (Mesa ANV)
- X11 or XWayland

If the GPU lacks a feature, the game stops at startup with a message naming it.

Notes
-----
Music plays through the built-in OPL2 synth (Sound Blaster / AdLib sound).
- Indoor lights are derived from each map's sector light levels; the
  desktop RT release's hand-made map light data isn't included.
- Bundled: SDL2 2.30.9 and RayTracedGL1 in lib/. Licenses in licenses/.

CONTROLLERS
Xbox, PlayStation and other controllers work as soon as they are plugged in
or paired (USB or Bluetooth):

  In game                           In menus
  Left stick / d-pad  move, strafe  Left stick / d-pad  move the cursor
  Right stick         turn          A                   select (yes in prompts)
  RT                  fire          B                   back (no in prompts)
  LT or B             run           Start               close the menu
  A or X              use / open
  Y                   flashlight
  LB / RB             previous / next weapon
  Start               menu
  View / Back         automap

The controller rumbles when you fire and when you take damage; turn it off
with "Controller rumble" in Options -> Display Options.

CUSTOM LEVELS (PWADs) AND MODS
Give run.sh the mod's files, from anywhere:

  ./run.sh ~/Downloads/mymap.wad
  ./run.sh ~/Downloads/mymod.zip            (needs 'unzip'; extracted to ~/.prboom-plus/mods)
  ./run.sh levels.wad patch.deh -warp 1 1   (DeHackEd patches: .deh / .bex)

The base game is picked from the levels the mod contains: Doom II levels
(MAP01...) use doom2.wad if it's next to run.sh, otherwise Freedoom: Phase 2;
Doom levels (E1M1...) use doom.wad, otherwise Freedoom: Phase 1. Choose one
yourself with -iwad, e.g. ./run.sh -iwad plutonia.wad mymap.wad

The hand-placed ray tracing lights exist only for the original Doom maps;
custom levels are lit from their own sector light levels.
