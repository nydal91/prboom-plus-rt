// Touch controls for the Android build, see android_touch.h for the layout.

#ifdef __ANDROID__

#include "android_touch.h"

#include <math.h>
#include <string.h>

#include "doomdef.h"
#include "doomstat.h"
#include "d_event.h"
#include "d_main.h"
#include "g_game.h"
#include "v_video.h"

// zones, in normalized screen coordinates
#define TOP_BAR_H       0.18f
#define STICK_W         0.40f
#define SIDE_BTN_X      0.80f
#define FIRE_Y          0.62f

// stick: fraction of the screen height to start moving / to run
#define STICK_DEADZONE  0.035f
#define STICK_RUN       0.12f

// turning speed: mouse units per screen width of drag
#define TURN_SCALE      2600.0f

#define MAX_FINGERS     10

typedef enum
{
  ROLE_NONE,
  ROLE_STICK,
  ROLE_LOOK,
  ROLE_KEY,
} role_t;

typedef struct
{
  SDL_FingerID id;
  role_t role;
  float x0, y0;   // where it touched down
  float x, y;     // current position
  int key;        // ROLE_KEY: held key (0 = none)
  int key2;       // optional second key, e.g. enter in menus
} finger_t;

static finger_t fingers[MAX_FINGERS];
static float pending_turn = 0.0f;

// movement keys currently held by the stick
static dboolean held_up, held_down, held_left, held_right, held_run;

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

static void SetHeld(dboolean *held, int key, dboolean want)
{
  if (*held != want)
  {
    PostKey(key, want);
    *held = want;
  }
}

static finger_t *FindFinger(SDL_FingerID id)
{
  for (int i = 0; i < MAX_FINGERS; i++)
  {
    if (fingers[i].role != ROLE_NONE && fingers[i].id == id)
    {
      return &fingers[i];
    }
  }
  return NULL;
}

static finger_t *NewFinger(SDL_FingerID id)
{
  for (int i = 0; i < MAX_FINGERS; i++)
  {
    if (fingers[i].role == ROLE_NONE)
    {
      memset(&fingers[i], 0, sizeof(fingers[i]));
      fingers[i].id = id;
      return &fingers[i];
    }
  }
  return NULL;
}

static void TopBarKeys(float x, int *key, int *key2)
{
  int slot = (int)(x * 5.0f);

  *key2 = 0;
  switch (slot)
  {
    case 0:  *key = key_escape; break;
    case 1:  *key = key_map; break;
    case 2:  *key = key_menu_enter; *key2 = 'y'; break; // also answers "press Y" prompts
    case 3:  *key = key_prevweapon; break;
    default: *key = key_nextweapon; break;
  }
}

void AndroidTouch_Init(void)
{
  memset(fingers, 0, sizeof(fingers));

  // handle touches ourselves, without SDL turning them into mouse clicks
  SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
  SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");
}

dboolean AndroidTouch_HandleEvent(const SDL_Event *ev)
{
  switch (ev->type)
  {
    case SDL_FINGERDOWN:
    {
      float x = ev->tfinger.x, y = ev->tfinger.y;
      finger_t *f = NewFinger(ev->tfinger.fingerId);

      if (!f)
      {
        return true;
      }

      f->x0 = f->x = x;
      f->y0 = f->y = y;

      if (y < TOP_BAR_H)
      {
        f->role = ROLE_KEY;
        TopBarKeys(x, &f->key, &f->key2);
      }
      else if (x < STICK_W)
      {
        f->role = ROLE_STICK;
      }
      else if (x >= SIDE_BTN_X)
      {
        f->role = ROLE_KEY;
        if (y >= FIRE_Y)
        {
          f->key = key_fire;
        }
        else
        {
          f->key = key_use;
          f->key2 = menuactive ? key_menu_enter : 0;
        }
      }
      else
      {
        f->role = ROLE_LOOK;
      }

      if (f->role == ROLE_KEY)
      {
        PostKey(f->key, true);
        PostKey(f->key2, true);
      }
      return true;
    }

    case SDL_FINGERMOTION:
    {
      finger_t *f = FindFinger(ev->tfinger.fingerId);

      if (f)
      {
        if (f->role == ROLE_LOOK)
        {
          pending_turn += ev->tfinger.dx * TURN_SCALE;
        }
        f->x = ev->tfinger.x;
        f->y = ev->tfinger.y;
      }
      return true;
    }

    case SDL_FINGERUP:
    {
      finger_t *f = FindFinger(ev->tfinger.fingerId);

      if (f)
      {
        if (f->role == ROLE_KEY)
        {
          PostKey(f->key, false);
          PostKey(f->key2, false);
        }
        f->role = ROLE_NONE;
      }
      return true;
    }

    case SDL_KEYDOWN:
    case SDL_KEYUP:
      // Android back button: open / close the menu
      if (ev->key.keysym.sym == SDLK_AC_BACK)
      {
        PostKey(key_escape, ev->type == SDL_KEYDOWN);
        return true;
      }
      return false;

    case SDL_MOUSEMOTION:
    case SDL_MOUSEBUTTONDOWN:
    case SDL_MOUSEBUTTONUP:
      // synthetic mouse events generated from touches
      return ev->type == SDL_MOUSEMOTION ? ev->motion.which == SDL_TOUCH_MOUSEID
                                         : ev->button.which == SDL_TOUCH_MOUSEID;

    default:
      return false;
  }
}

void AndroidTouch_Tic(void)
{
  float dx = 0, dy = 0;
  dboolean stick = false;

  for (int i = 0; i < MAX_FINGERS; i++)
  {
    if (fingers[i].role == ROLE_STICK)
    {
      // screen is wider than tall: scale x so both axes feel the same
      dx = (fingers[i].x - fingers[i].x0) * ((float)SCREENWIDTH / (float)SCREENHEIGHT);
      dy = fingers[i].y - fingers[i].y0;
      stick = true;
      break;
    }
  }

  SetHeld(&held_up,    key_up,          stick && dy < -STICK_DEADZONE);
  SetHeld(&held_down,  key_down,        stick && dy >  STICK_DEADZONE);
  SetHeld(&held_left,  key_strafeleft,  stick && dx < -STICK_DEADZONE);
  SetHeld(&held_right, key_straferight, stick && dx >  STICK_DEADZONE);
  SetHeld(&held_run,   key_speed,       stick && sqrtf(dx * dx + dy * dy) > STICK_RUN);

  if (pending_turn != 0.0f)
  {
    event_t ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = ev_mousemotion;
    ev.data2 = (int)pending_turn;
    ev.data3 = 0;
    D_PostEvent(&ev);
    pending_turn = 0.0f;
  }
}

static void Outline(int x, int y, int w, int h, byte color)
{
  // keep inside the screen
  if (x < 0) { w += x; x = 0; }
  if (y < 0) { h += y; y = 0; }
  if (x + w > SCREENWIDTH)  { w = SCREENWIDTH - x; }
  if (y + h > SCREENHEIGHT) { h = SCREENHEIGHT - y; }

  if (w <= 0 || h <= 0)
  {
    return;
  }
  V_FillRect(0, x, y, w, 1, color);
  V_FillRect(0, x, y + h - 1, w, 1, color);
  V_FillRect(0, x, y, 1, h, color);
  V_FillRect(0, x + w - 1, y, 1, h, color);
}

void AndroidTouch_Draw(void)
{
  const int W = SCREENWIDTH, H = SCREENHEIGHT;
  const byte gray = 96; // a mid gray in the Doom palette

  if (gamestate != GS_LEVEL || menuactive)
  {
    return;
  }

  // top bar buttons
  for (int i = 0; i < 5; i++)
  {
    Outline(i * W / 5 + 2, 2, W / 5 - 4, (int)(TOP_BAR_H * H) - 4, gray);
  }

  // use and fire buttons
  Outline((int)(SIDE_BTN_X * W) + 2, (int)(TOP_BAR_H * H) + 2,
          (int)((1.0f - SIDE_BTN_X) * W) - 4, (int)((FIRE_Y - TOP_BAR_H) * H) - 4, gray);
  Outline((int)(SIDE_BTN_X * W) + 2, (int)(FIRE_Y * H) + 2,
          (int)((1.0f - SIDE_BTN_X) * W) - 4, (int)((1.0f - FIRE_Y) * H) - 4, gray);

  // stick: a box around where the finger went down
  for (int i = 0; i < MAX_FINGERS; i++)
  {
    if (fingers[i].role == ROLE_STICK)
    {
      int cx = (int)(fingers[i].x0 * W), cy = (int)(fingers[i].y0 * H);
      int r = (int)(STICK_RUN * H);
      Outline(cx - r, cy - r, 2 * r, 2 * r, gray);
      Outline((int)(fingers[i].x * W) - 3, (int)(fingers[i].y * H) - 3, 6, 6, 176);
    }
  }
}

#endif // __ANDROID__
