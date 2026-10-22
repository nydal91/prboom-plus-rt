#ifndef __I_GAMEPAD__
#define __I_GAMEPAD__

#include <SDL.h>
#include "doomtype.h"

// Game controllers (Xbox, PlayStation, ...) through SDL's GameController API.
//
// In game:                          In menus:
//   left stick   move / strafe        left stick, d-pad   move the cursor
//   right stick  turn                 A                   select ('y' in prompts)
//   RT           fire                 B                   back ('n' in prompts)
//   LT           run                  Start               close the menu
//   A, X         use / open
//   B            run
//   Y            flashlight
//   LB / RB      previous / next weapon
//   d-pad        move
//   Start        menu
//   Back / View  automap

void I_Gamepad_Init(void);
// Returns true if the event was consumed
dboolean I_Gamepad_HandleEvent(const SDL_Event *ev);
// Called once per tic
void I_Gamepad_Tic(void);
// Analog movement and turning for the next ticcmd
void I_Gamepad_GetMove(int *forward, int *side, int *turn);

// Rumble: motor strengths 0..1 (low = heavy, high = light), duration in ms.
// Does nothing when "Controller rumble" is off or no controller can rumble.
extern int gamepad_rumble;
void I_Gamepad_Rumble(float low, float high, int ms);

#endif
