// Touch controls for the Android build, see android_touch.h for the layout.

#include "android_touch.h"

#ifdef HAVE_TOUCH_CONTROLS

#include <math.h>
#include <string.h>

#include "doomdef.h"
#include "doomstat.h"
#include "d_event.h"
#include "d_main.h"
#include "g_game.h"
#include "v_video.h"
#include "lprintf.h"
#include "m_menu.h"

// zones, in normalized screen coordinates
#define TOP_BAR_H       0.18f
#define STICK_W         0.40f
#define SIDE_BTN_X      0.80f
#define FIRE_Y          0.62f

// stick: fraction of the screen height to start moving / to run
#define STICK_DEADZONE  0.035f
#define STICK_RUN       0.12f

// turning speed: mouse units per screen width of drag
#define TURN_SCALE      20000.0f

#define MAX_FINGERS     10

typedef enum
{
  ROLE_NONE,
  ROLE_STICK,
  ROLE_LOOK,
  ROLE_KEY,
  ROLE_MENU,      // in a menu: a tap on an item, or a drag (becomes ROLE_STICK)
} role_t;

// menus: drag distance that turns a tap into the stick, and arrow key repeat
#define MENU_DRAG       0.04f
#define MENU_STICK      0.05f
#define MENU_REPEAT1_MS 400
#define MENU_REPEAT_MS  160

typedef struct
{
  SDL_FingerID id;
  role_t role;
  float x0, y0;   // where it touched down
  float x, y;     // current position
  int key;        // ROLE_KEY: held key (0 = none)
  int key2;       // optional second key, e.g. enter in menus
  int down_tic;   // touch_tic when the key went down
  Uint32 down_ms;
} finger_t;

// Released buttons stay pressed until the game has built at least one tic
// with them down (and for a minimum time): at low frame rates a quick tap's
// down and up arrive in the same frame and would cancel out.
#define MIN_PRESS_MS 100
#define MAX_RELEASES 16

typedef struct
{
  int key, key2;
  int earliest_tic;
  Uint32 earliest_ms;
} release_t;

static release_t releases[MAX_RELEASES];
static int release_count = 0;
static int touch_tic = 0;

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

  lprintf(LO_INFO, "touch: key %d %s\n", key, down ? "down" : "up");

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

static void QueueRelease(int key, int key2, Uint32 down_ms)
{
  if (release_count < MAX_RELEASES)
  {
    release_t *r = &releases[release_count++];
    r->key = key;
    r->key2 = key2;
    r->earliest_tic = touch_tic + 1;
    r->earliest_ms = down_ms + MIN_PRESS_MS;
  }
  else
  {
    PostKey(key, false);
    PostKey(key2, false);
  }
}

// a short press: down now, up after the game has seen it
static void PressKey(int key)
{
  if (key > 0)
  {
    PostKey(key, true);
    QueueRelease(key, 0, SDL_GetTicks());
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
      else if (menuactive && x < SIDE_BTN_X)
      {
        // decided when the finger moves or lifts
        f->role = ROLE_MENU;
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

      {
        static int logged = 0;
        static const char *names[] = { "none", "stick", "look", "button", "menu" };
        if (logged < 200)
        {
          logged++;
          lprintf(LO_INFO, "touch: finger down %.2f,%.2f -> %s\n", x, y, names[f->role]);
        }
      }

      if (f->role == ROLE_KEY)
      {
        f->down_tic = touch_tic;
        f->down_ms = SDL_GetTicks();
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
        if (f->role == ROLE_MENU && fabsf(f->x - f->x0) + fabsf(f->y - f->y0) > MENU_DRAG)
        {
          f->role = ROLE_STICK;
        }
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
          QueueRelease(f->key, f->key2, f->down_ms);
        }
        else if (f->role == ROLE_MENU)
        {
          int key = M_TouchTap(f->x0, f->y0);
          lprintf(LO_INFO, "touch: menu tap %.2f,%.2f -> %d\n", f->x0, f->y0, key);
          PressKey(key);
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
#ifdef PRBOOM_TOUCH_TEST
  void AndroidTouch_TestInject(void);
  AndroidTouch_TestInject();
#endif
  float dx = 0, dy = 0;

  touch_tic++;

  // delayed button releases, see MIN_PRESS_MS
  for (int i = 0; i < release_count; )
  {
    if (touch_tic > releases[i].earliest_tic && SDL_TICKS_PASSED(SDL_GetTicks(), releases[i].earliest_ms))
    {
      PostKey(releases[i].key, false);
      PostKey(releases[i].key2, false);
      releases[i] = releases[--release_count];
    }
    else
    {
      i++;
    }
  }
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

  if (menuactive)
  {
    // menus move with the arrow keys, one step per push, repeating when held
    static int menu_dir = 0;
    static Uint32 next_repeat = 0;
    int dir = 0;
    Uint32 now = SDL_GetTicks();

    SetHeld(&held_up, key_up, false);
    SetHeld(&held_down, key_down, false);
    SetHeld(&held_left, key_strafeleft, false);
    SetHeld(&held_right, key_straferight, false);
    SetHeld(&held_run, key_speed, false);

    if (stick && fabsf(dx) + fabsf(dy) > MENU_STICK)
    {
      if (fabsf(dy) >= fabsf(dx))
        dir = dy < 0 ? key_menu_up : key_menu_down;
      else
        dir = dx < 0 ? key_menu_left : key_menu_right;
    }

    if (dir != menu_dir)
    {
      menu_dir = dir;
      PressKey(dir);
      next_repeat = now + MENU_REPEAT1_MS;
    }
    else if (dir && SDL_TICKS_PASSED(now, next_repeat))
    {
      PressKey(dir);
      next_repeat = now + MENU_REPEAT_MS;
    }
  }
  else
  {
  SetHeld(&held_up,    key_up,          stick && dy < -STICK_DEADZONE);
  SetHeld(&held_down,  key_down,        stick && dy >  STICK_DEADZONE);
  SetHeld(&held_left,  key_strafeleft,  stick && dx < -STICK_DEADZONE);
  SetHeld(&held_right, key_straferight, stick && dx >  STICK_DEADZONE);
  SetHeld(&held_run,   key_speed,       stick && sqrtf(dx * dx + dy * dy) > STICK_RUN);
  }

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

  // a marker under every finger, so it's visible whether touches arrive
  for (int i = 0; i < MAX_FINGERS; i++)
  {
    if (fingers[i].role != ROLE_NONE)
    {
      int s = H / 20;
      Outline((int)(fingers[i].x * W) - s, (int)(fingers[i].y * H) - s, 2 * s, 2 * s, 176);
      Outline((int)(fingers[i].x * W) - s + 1, (int)(fingers[i].y * H) - s + 1, 2 * s - 2, 2 * s - 2, 176);
    }
  }

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


#ifdef PRBOOM_TOUCH_TEST
// Desktop test: PRBOOM_TOUCH_SCRIPT="tic,d|m|u,fingerid,x,y;..." pushes SDL finger
// events through SDL's queue at the given game tics, like Android would.
#include "d_player.h"
#include "doomstat.h"

void AndroidTouch_TestInject(void)
{
  static int last_tic = -1;
  static unsigned char done[256];
  const char *script = getenv("PRBOOM_TOUCH_SCRIPT");
  const char *p = script;
  int index = 0;

  if (!script || gametic == last_tic)
  {
    return;
  }
  last_tic = gametic;

  for (; p && *p; index++)
  {
    int tic, id; char t; float x, y;
    if (sscanf(p, "%d,%c,%d,%f,%f", &tic, &t, &id, &x, &y) == 5 && tic <= gametic
        && index < (int)sizeof(done) && !done[index])
    {
      done[index] = 1;
      SDL_Event e;
      memset(&e, 0, sizeof(e));
      e.type = t == 'd' ? SDL_FINGERDOWN : t == 'u' ? SDL_FINGERUP : SDL_FINGERMOTION;
      static float last_x[16], last_y[16];
      e.tfinger.fingerId = id;
      e.tfinger.x = x;
      e.tfinger.y = y;
      if (id >= 0 && id < 16)
      {
        e.tfinger.dx = t == 'd' ? 0 : x - last_x[id];
        e.tfinger.dy = t == 'd' ? 0 : y - last_y[id];
        last_x[id] = x;
        last_y[id] = y;
      }
      SDL_PushEvent(&e);
      lprintf(LO_INFO, "touchtest: tic %d push %c finger %d at %.2f,%.2f\n", gametic, t, id, x, y);
    }
    p = strchr(p, ';');
    if (p) p++;
  }

  static int last_log = -100;
  if (gamestate == GS_LEVEL && gametic - last_log >= 35)
  {
    last_log = gametic;
    player_t *pl = &players[consoleplayer];
    lprintf(LO_INFO, "touchtest: tic %d ammo %d pos %d,%d angle %u\n", gametic,
            pl->ammo[am_clip], pl->mo ? pl->mo->x >> 16 : 0, pl->mo ? pl->mo->y >> 16 : 0,
            pl->mo ? (unsigned)(pl->mo->angle >> 24) : 0);
  }
}
#endif

#endif // HAVE_TOUCH_CONTROLS
