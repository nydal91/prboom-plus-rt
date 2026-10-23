# PrBoom+ RT for Android (arm64)

Ray traced Doom on phones with Vulkan ray queries, using the
`ray-query-android` branch of RayTracedGL1.

## Requirements

- Android 12 or newer, arm64
- a GPU whose Vulkan driver exposes `VK_KHR_ray_query` and
  `VK_KHR_acceleration_structure`, e.g. Snapdragon 8 Gen 2 or newer
  (Adreno 740+), Immortalis GPUs, Exynos 2200+ (Xclipse)

If the GPU lacks something, the game stops at startup with a message naming it.

## Game data

The app starts with a game picker. Freedoom: Phase 1 and 2 are bundled. To
add a game or a mod, tap **Add file...** in the picker, or open the file with
this app from a file manager ("Open with"):

- **Games (IWADs)**: `doom.wad`, `doom2.wad`, `plutonia.wad`, `tnt.wad`
- **Custom levels and mods**: `.wad` (PWADs), DeHackEd patches (`.deh`,
  `.bex`), or a `.zip` holding them

Each mod is listed with the base game it runs on, picked from the levels it
contains (Doom II levels use `doom2.wad` if added, otherwise Freedoom 2).
Long-press an entry to remove it. WADs placed in
`Android/data/org.prboomplus.rt/files/` are found too.

The hand-placed ray tracing lights exist only for the original Doom maps;
custom levels are lit from their own sector light levels.

## Touch controls

```
+-------+-------+-------+-------+-------+-------+
| MENU  |  MAP  | ENTER | LIGHT |PREV W |NEXT W |   top 18%: buttons
+-------+-------+-------+-------+-------+-------+
|                    |                 | USE |
|  move stick        |  drag to turn   +-----+
|  (left 40%)        |                 |FIRE |
+--------------------+-----------------+-----+
```

LIGHT turns the flashlight on and off. The stick runs when pushed far. The
overlay hides while a controller is in use and comes back when you touch the
screen. Back opens the menu. In menus, tap an item
to choose it, or drag anywhere to move the cursor (hold to repeat); on the
settings screens the first tap highlights a row and a second tap changes it,
then drag left/right to pick a value and tap to confirm. Bluetooth keyboards work too.

## Controllers

Xbox, PlayStation and other Bluetooth/USB controllers work as soon as they
are connected:

| In game | | In menus | |
|---|---|---|---|
| Left stick / d-pad | move, strafe | Left stick / d-pad | move the cursor |
| Right stick | turn | A | select (yes in prompts) |
| RT | fire | B | back (no in prompts) |
| LT or B | run | Start | close the menu |
| A or X | use / open | | |
| Y | flashlight | | |
| LB / RB | previous / next weapon | | |
| Start | menu | | |
| View / Back | automap | | |

The controller rumbles when you fire and when you take damage; turn it off
with "Controller rumble" in Options → Display Options.

## Known limitations

- Switching away from the app pauses the game; the renderer rebuilds its
  Vulkan surface when you return.
- Music plays through the built-in OPL2 synth (the Sound Blaster / AdLib
  sound of DOS Doom); SDL2_mixer, MP3/OGG music packs and soundfonts aren't built.
- Indoor lighting is derived from the maps' sector light levels; the hand
  made per-map light data of the desktop release isn't included.
- Speed depends heavily on the GPU. It renders at half resolution with
  FSR upscaling by default; change it in the ray tracing options.

## Building

The renderer is [RayTracedGL1](https://github.com/sultim-t/RayTracedGL1)
v1.1.1 with the patches in `rtgl1-patches/` (ray queries instead of ray
tracing pipelines, Android surface, fixes):

```
git clone https://github.com/sultim-t/RayTracedGL1 && cd RayTracedGL1
git checkout v1.1.1 && git am /path/to/prboom2/android/rtgl1-patches/*.patch
```

`build_apk.sh` builds everything without Gradle: RayTracedGL1, its shaders,
SDL2 and the game with the NDK, then the Java wrapper with javac/d8, and
packages, aligns and signs the APK. See the variables at the top of the script.
`RT_DATA` takes the `ovrd` folder of the official PrBoom+ RT 1.0.7 release
(materials and map lights), `IWAD` / `IWAD2` Freedoom 1 and 2.

The Linux build (`../linux/`) is a portable x86-64 build of the same source
(Zig as the C compiler, glibc 2.29+), configured with `-DPRBOOM_GL_SHIM=ON
-DRTGL1_LIBRARY=...`; `run.sh` there is its launcher.
