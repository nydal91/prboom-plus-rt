// Game controller support, see i_gamepad.h for the button layout.

#include "i_gamepad.h"

#include <math.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>

#include "doomdef.h"
#include "doomstat.h"
#include "d_event.h"
#include "d_main.h"
#include "g_game.h"
#include "lprintf.h"
#include "m_argv.h"

#define MAX_PADS        4

#define STICK_DEADZONE  0.20f
#define TRIGGER_PRESS   0.35f
#define MENU_STICK      0.50f

// at full deflection: running speed, and degrees per second of turning
#define MOVE_FORWARD    50
#define MOVE_SIDE       40
#define TURN_PER_TIC    1100      // angleturn units (65536 = full circle)

#define MENU_REPEAT1_MS 400
#define MENU_REPEAT_MS  140

static SDL_GameController *pads[MAX_PADS];
#ifdef PRBOOM_TOUCH_TEST
static SDL_Joystick *test_pad;
static int SDLCALL TestRumble(void *userdata, Uint16 low, Uint16 high)
{
  if (low || high)
    lprintf(LO_INFO, "padtest: rumble %u %u\n", low, high);
  return 0;
}
#endif

// keys held by buttons, so they can be released on the right tic
typedef struct
{
  int key, key2;
  int down_tic;
  dboolean release_pending;
} held_t;

static held_t held[SDL_CONTROLLER_BUTTON_MAX];

// the controller was the last input used (see I_Gamepad_InUse)
static dboolean pad_in_use = false;
static int pad_tic = 0;

// keys held from axes
static dboolean fire_held, run_held;
static dboolean dpad_keys[4];   // up, down, left, right as posted in game

extern int messageToPrint;
extern dboolean messageNeedsInput;

static void PostKey(int key, dboolean down)
{
  event_t ev;

  if (key <= 0)
  {
    return;
  }
  memset(&ev, 0, sizeof(ev));
  ev.type = down ? ev_keydown : ev_keyup;
  ev.data1 = key;
  D_PostEvent(&ev);
}

static void SetHeldKey(dboolean *state, int key, dboolean want)
{
  if (*state != want)
  {
    PostKey(key, want);
    *state = want;
  }
}

static void OpenPad(int device_index)
{
  int i;

  if (!SDL_IsGameController(device_index))
  {
    return;
  }
  // already open (found at startup and announced again by SDL)
  for (i = 0; i < MAX_PADS; i++)
  {
    if (pads[i] && SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(pads[i]))
                   == SDL_JoystickGetDeviceInstanceID(device_index))
    {
      return;
    }
  }
  for (i = 0; i < MAX_PADS; i++)
  {
    if (!pads[i])
    {
      pads[i] = SDL_GameControllerOpen(device_index);
      if (pads[i])
      {
        lprintf(LO_INFO, "I_Gamepad: opened %s\n", SDL_GameControllerName(pads[i]));
        pad_in_use = true;
      }
      return;
    }
  }
}

static void ClosePad(SDL_JoystickID id)
{
  int i;

  for (i = 0; i < MAX_PADS; i++)
  {
    if (pads[i] && SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(pads[i])) == id)
    {
      lprintf(LO_INFO, "I_Gamepad: closed %s\n", SDL_GameControllerName(pads[i]));
      SDL_GameControllerClose(pads[i]);
      pads[i] = NULL;
    }
  }
}

void I_Gamepad_Init(void)
{
  int i;

  if (M_CheckParm("-nojoy"))
  {
    return;
  }
  if (SDL_InitSubSystem(SDL_INIT_GAMECONTROLLER) < 0)
  {
    lprintf(LO_WARN, "I_Gamepad: %s\n", SDL_GetError());
    return;
  }
#ifdef PRBOOM_TOUCH_TEST
  if (getenv("PRBOOM_PAD_SCRIPT"))
  {
    SDL_VirtualJoystickDesc desc;
    memset(&desc, 0, sizeof(desc));
    desc.version = SDL_VIRTUAL_JOYSTICK_DESC_VERSION;
    desc.type = SDL_JOYSTICK_TYPE_GAMECONTROLLER;
    desc.naxes = SDL_CONTROLLER_AXIS_MAX;
    desc.nbuttons = SDL_CONTROLLER_BUTTON_MAX;
    desc.name = "Test pad";
    desc.Rumble = TestRumble;
    test_pad = SDL_JoystickOpen(SDL_JoystickAttachVirtualEx(&desc));
  }
#endif
  for (i = 0; i < SDL_NumJoysticks(); i++)
  {
    OpenPad(i);
  }
  SDL_GameControllerEventState(SDL_ENABLE);
}

static float Axis(SDL_GameControllerAxis axis)
{
  int i, best = 0;

  // strongest input of all controllers
  for (i = 0; i < MAX_PADS; i++)
  {
    if (pads[i])
    {
      int v = SDL_GameControllerGetAxis(pads[i], axis);
      if (abs(v) > abs(best))
      {
        best = v;
      }
    }
  }
  return best < 0 ? best / 32768.0f : best / 32767.0f;
}

static dboolean Button(SDL_GameControllerButton b)
{
  int i;

  for (i = 0; i < MAX_PADS; i++)
  {
    if (pads[i] && SDL_GameControllerGetButton(pads[i], b))
    {
      return true;
    }
  }
  return false;
}

// a stick, with dead zone removed: magnitude 0..1, keeping its direction
static void Stick(SDL_GameControllerAxis ax, SDL_GameControllerAxis ay, float *x, float *y)
{
  float sx = Axis(ax), sy = Axis(ay);
  float mag = sqrtf(sx * sx + sy * sy);

  if (mag < STICK_DEADZONE)
  {
    *x = *y = 0.0f;
    return;
  }
  float scaled = (mag - STICK_DEADZONE) / (1.0f - STICK_DEADZONE);
  if (scaled > 1.0f)
  {
    scaled = 1.0f;
  }
  *x = sx / mag * scaled;
  *y = sy / mag * scaled;
}

static void ButtonKeys(SDL_GameControllerButton b, int *key, int *key2)
{
  *key = *key2 = 0;

  if (menuactive || messageToPrint)
  {
    switch (b)
    {
      case SDL_CONTROLLER_BUTTON_A:
        *key = key_menu_enter;
        if (messageToPrint && messageNeedsInput) *key2 = 'y';
        break;
      case SDL_CONTROLLER_BUTTON_B:
        if (messageToPrint && messageNeedsInput) *key = 'n';
        else *key = key_menu_backspace;
        break;
      case SDL_CONTROLLER_BUTTON_START:
        *key = key_menu_escape;
        break;
      default:
        break;   // d-pad: see I_Gamepad_Tic
    }
    return;
  }

  switch (b)
  {
    case SDL_CONTROLLER_BUTTON_A:
    case SDL_CONTROLLER_BUTTON_X:             *key = key_use; break;
    case SDL_CONTROLLER_BUTTON_B:             *key = key_speed; break;
    case SDL_CONTROLLER_BUTTON_Y:             *key = key_rt_flashlight; break;
    case SDL_CONTROLLER_BUTTON_LEFTSHOULDER:  *key = key_prevweapon; break;
    case SDL_CONTROLLER_BUTTON_RIGHTSHOULDER: *key = key_nextweapon; break;
    case SDL_CONTROLLER_BUTTON_START:         *key = key_escape; break;
    case SDL_CONTROLLER_BUTTON_BACK:          *key = key_map; break;
    default: break;
  }
}

dboolean I_Gamepad_HandleEvent(const SDL_Event *ev)
{
  switch (ev->type)
  {
    case SDL_CONTROLLERDEVICEADDED:
      OpenPad(ev->cdevice.which);
      return true;

    case SDL_CONTROLLERDEVICEREMOVED:
      ClosePad(ev->cdevice.which);
      return true;

    case SDL_CONTROLLERBUTTONDOWN:
    {
      held_t *h = &held[ev->cbutton.button];

      pad_in_use = true;
      if (h->key || h->key2)
      {
        // still held from a quick earlier press
        PostKey(h->key, false);
        PostKey(h->key2, false);
      }
      ButtonKeys(ev->cbutton.button, &h->key, &h->key2);
      h->down_tic = pad_tic;
      h->release_pending = false;
      PostKey(h->key, true);
      PostKey(h->key2, true);
      return true;
    }

    case SDL_CONTROLLERBUTTONUP:
    {
      held_t *h = &held[ev->cbutton.button];

      // a press must last a tic, or the game wouldn't see it at low frame rates
      if (h->down_tic == pad_tic)
      {
        h->release_pending = true;
      }
      else
      {
        PostKey(h->key, false);
        PostKey(h->key2, false);
        h->key = h->key2 = 0;
      }
      return true;
    }

    case SDL_CONTROLLERAXISMOTION:
      // polled in I_Gamepad_Tic; a real push (not stick drift) counts as use
      if (abs(ev->caxis.value) > 16000)
      {
        pad_in_use = true;
      }
      return true;

    default:
      return false;
  }
}

#ifdef PRBOOM_TOUCH_TEST
// PRBOOM_PAD_SCRIPT="gametic,b|a,index,value;..." drives a virtual pad
static void TestInject(void)
{
  static unsigned char done[256];
  const char *p = getenv("PRBOOM_PAD_SCRIPT");
  int index = 0;

  for (; test_pad && p && *p; index++)
  {
    int tic, n, v; char t;
    if (sscanf(p, "%d,%c,%d,%d", &tic, &t, &n, &v) == 4 && tic <= gametic && index < 256 && !done[index])
    {
      done[index] = 1;
      if (t == 'b') SDL_JoystickSetVirtualButton(test_pad, n, (Uint8)v);
      else SDL_JoystickSetVirtualAxis(test_pad, n, (Sint16)v);
      lprintf(LO_INFO, "padtest: tic %d %c %d = %d\n", gametic, t, n, v);
    }
    p = strchr(p, ';');
    if (p) p++;
  }
}
#endif

void I_Gamepad_Tic(void)
{
#ifdef PRBOOM_TOUCH_TEST
  TestInject();
#endif
  static int menu_dir = 0;
  static Uint32 next_repeat = 0;
  float lx, ly;
  int i;
  dboolean any = false;

  pad_tic++;

  for (i = 0; i < SDL_CONTROLLER_BUTTON_MAX; i++)
  {
    if (held[i].release_pending && held[i].down_tic != pad_tic)
    {
      PostKey(held[i].key, false);
      PostKey(held[i].key2, false);
      held[i].key = held[i].key2 = 0;
      held[i].release_pending = false;
    }
  }

  for (i = 0; i < MAX_PADS; i++)
  {
    any = any || pads[i] != NULL;
  }
  if (!any)
  {
    return;
  }

  Stick(SDL_CONTROLLER_AXIS_LEFTX, SDL_CONTROLLER_AXIS_LEFTY, &lx, &ly);

  if (menuactive || messageToPrint)
  {
    static const SDL_GameControllerButton dpad[4] = {
      SDL_CONTROLLER_BUTTON_DPAD_UP, SDL_CONTROLLER_BUTTON_DPAD_DOWN,
      SDL_CONTROLLER_BUTTON_DPAD_LEFT, SDL_CONTROLLER_BUTTON_DPAD_RIGHT,
    };
    const int menukeys[4] = { key_menu_up, key_menu_down, key_menu_left, key_menu_right };
    int dir = 0;
    Uint32 now = SDL_GetTicks();

    // release anything held for the game
    SetHeldKey(&fire_held, key_fire, false);
    SetHeldKey(&run_held, key_speed, false);
    for (i = 0; i < 4; i++)
    {
      SetHeldKey(&dpad_keys[i], i == 0 ? key_up : i == 1 ? key_down : i == 2 ? key_strafeleft : key_straferight, false);
    }

    for (i = 0; i < 4; i++)
    {
      if (Button(dpad[i]))
      {
        dir = menukeys[i];
      }
    }
    if (!dir && fabsf(lx) + fabsf(ly) > MENU_STICK)
    {
      if (fabsf(ly) >= fabsf(lx))
        dir = ly < 0 ? key_menu_up : key_menu_down;
      else
        dir = lx < 0 ? key_menu_left : key_menu_right;
    }

    if (dir != menu_dir || (dir && SDL_TICKS_PASSED(now, next_repeat)))
    {
      next_repeat = now + (dir != menu_dir ? MENU_REPEAT1_MS : MENU_REPEAT_MS);
      menu_dir = dir;
      if (dir)
      {
        PostKey(dir, true);
        PostKey(dir, false);
      }
    }
    return;
  }
  menu_dir = 0;

  SetHeldKey(&fire_held, key_fire, Axis(SDL_CONTROLLER_AXIS_TRIGGERRIGHT) > TRIGGER_PRESS);
  SetHeldKey(&run_held, key_speed, Axis(SDL_CONTROLLER_AXIS_TRIGGERLEFT) > TRIGGER_PRESS);

  SetHeldKey(&dpad_keys[0], key_up,          Button(SDL_CONTROLLER_BUTTON_DPAD_UP));
  SetHeldKey(&dpad_keys[1], key_down,        Button(SDL_CONTROLLER_BUTTON_DPAD_DOWN));
  SetHeldKey(&dpad_keys[2], key_strafeleft,  Button(SDL_CONTROLLER_BUTTON_DPAD_LEFT));
  SetHeldKey(&dpad_keys[3], key_straferight, Button(SDL_CONTROLLER_BUTTON_DPAD_RIGHT));
}

void I_Gamepad_GetMove(int *forward, int *side, int *turn)
{
  float lx, ly, rx, ry;
  int i;
  dboolean any = false;

  *forward = *side = *turn = 0;

  for (i = 0; i < MAX_PADS; i++)
  {
    any = any || pads[i] != NULL;
  }
  if (!any || menuactive)
  {
    return;
  }

  Stick(SDL_CONTROLLER_AXIS_LEFTX, SDL_CONTROLLER_AXIS_LEFTY, &lx, &ly);
  Stick(SDL_CONTROLLER_AXIS_RIGHTX, SDL_CONTROLLER_AXIS_RIGHTY, &rx, &ry);

  *forward = (int)(-ly * MOVE_FORWARD);
  *side = (int)(lx * MOVE_SIDE);
  // finer control near the center
  *turn = (int)(rx * fabsf(rx) * TURN_PER_TIC);
}

int gamepad_rumble = 1;

#ifdef __ANDROID__
#include <jni.h>

static void AndroidRumble(float low, float high, int ms)
{
  static jclass cls = NULL;
  static jmethodID mid = NULL;
  static int failed = 0, last_motors = -1;
  JNIEnv *env;
  jint motors;

  if (failed)
  {
    return;
  }
  env = (JNIEnv *)SDL_AndroidGetJNIEnv();
  if (!env)
  {
    return;
  }
  if (!mid)
  {
    // load through the activity's class loader, which knows the app's classes
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass act_cls = activity ? (*env)->GetObjectClass(env, activity) : NULL;
    jmethodID get_loader = act_cls ? (*env)->GetMethodID(env, act_cls, "getClassLoader", "()Ljava/lang/ClassLoader;") : NULL;
    jobject loader = get_loader ? (*env)->CallObjectMethod(env, activity, get_loader) : NULL;
    jclass loader_cls = loader ? (*env)->GetObjectClass(env, loader) : NULL;
    jmethodID load = loader_cls ? (*env)->GetMethodID(env, loader_cls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;") : NULL;
    jstring name = (*env)->NewStringUTF(env, "org.prboomplus.rt.GamepadRumble");
    jclass local = load ? (jclass)(*env)->CallObjectMethod(env, loader, load, name) : NULL;

    if ((*env)->ExceptionCheck(env))
    {
      (*env)->ExceptionClear(env);
      local = NULL;
    }
    if (local)
    {
      cls = (jclass)(*env)->NewGlobalRef(env, local);
      mid = (*env)->GetStaticMethodID(env, cls, "rumble", "(FFI)I");
    }
    (*env)->DeleteLocalRef(env, name);
    if (local) (*env)->DeleteLocalRef(env, local);
    if (loader_cls) (*env)->DeleteLocalRef(env, loader_cls);
    if (loader) (*env)->DeleteLocalRef(env, loader);
    if (act_cls) (*env)->DeleteLocalRef(env, act_cls);
    if (activity) (*env)->DeleteLocalRef(env, activity);
    if (!mid)
    {
      if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
      lprintf(LO_WARN, "I_Gamepad: rumble helper not found\n");
      failed = 1;
      return;
    }
  }

  motors = (*env)->CallStaticIntMethod(env, cls, mid, (jfloat)low, (jfloat)high, (jint)ms);
  if ((*env)->ExceptionCheck(env))
  {
    (*env)->ExceptionClear(env);
    motors = 0;
  }
  if (motors != last_motors)
  {
    lprintf(LO_INFO, "I_Gamepad: rumble drives %d motor(s)\n", (int)motors);
    last_motors = motors;
  }
}
#endif

void I_Gamepad_Rumble(float low, float high, int ms)
{
  int i;
  Uint16 lo, hi;

  if (!gamepad_rumble || demoplayback)
  {
    return;
  }
#ifdef __ANDROID__
  // SDL 2 can't rumble controllers on Android: GamepadRumble.java does it
  (void)i; (void)lo; (void)hi;
  AndroidRumble(low, high, ms);
  return;
#endif
  lo = (Uint16)(BETWEEN(0.0f, 1.0f, low) * 65535.0f);
  hi = (Uint16)(BETWEEN(0.0f, 1.0f, high) * 65535.0f);
  for (i = 0; i < MAX_PADS; i++)
  {
    if (pads[i])
    {
      SDL_GameControllerRumble(pads[i], lo, hi, (Uint32)ms);
    }
  }
}

dboolean I_Gamepad_InUse(void)
{
  int i;

  for (i = 0; i < MAX_PADS; i++)
  {
    if (pads[i])
    {
      return pad_in_use;
    }
  }
  return false;
}

void I_Gamepad_TouchUsed(void)
{
  pad_in_use = false;
}
