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

Freedoom: Phase 1 is bundled. To play original Doom, open your `doom.wad`
(or `doom2.wad`, `plutonia.wad`, `tnt.wad`) with this app from a file
manager ("Open with"); it is copied into the app and used from then on.
WADs placed in `Android/data/org.prboomplus.rt/files/` are used too.

## Touch controls

```
+--------+--------+--------+--------+--------+
|  MENU  |  MAP   | ENTER  | PREV W | NEXT W |   top 18%: buttons
+--------+--------+--------+--------+--------+
|                    |                 | USE |
|  move stick        |  drag to turn   +-----+
|  (left 40%)        |                 |FIRE |
+--------------------+-----------------+-----+
```

The stick runs when pushed far. Back opens the menu. In menus, move with
the stick and confirm with ENTER or USE. Bluetooth keyboards and gamepads
work through SDL.

## Known limitations

- Switching away from the app pauses the game; the renderer rebuilds its
  Vulkan surface when you return.
- No music (sound effects work); SDL2_mixer isn't built.
- Indoor lighting is derived from the maps' sector light levels; the hand
  made per-map light data of the desktop release isn't included.
- Speed depends heavily on the GPU. It renders at half resolution with
  FSR upscaling by default; change it in the ray tracing options.

## Building

`build_apk.sh` builds everything without Gradle: RayTracedGL1, its shaders,
SDL2 and the game with the NDK, then the Java wrapper with javac/d8, and
packages, aligns and signs the APK. See the variables at the top of the script.
