#ifndef __ANDROID_TOUCH__
#define __ANDROID_TOUCH__

#if defined(__ANDROID__) || defined(PRBOOM_TOUCH_CONTROLS)
#define HAVE_TOUCH_CONTROLS 1

#include <SDL.h>
#include "doomtype.h"

// Touch controls for phones:
//
//  +-------+-------+-------+-------+-------+-------+
//  | MENU  |  MAP  | ENTER | LIGHT |PREV W |NEXT W |   top 18%: buttons
//  +-------+-------+-------+-------+-------+-------+
//  |                    |                 | USE |
//  |  move stick        |  drag to turn   +-----+
//  |  (left 40%)        |                 |FIRE |
//  +--------------------+-----------------+-----+
//
// The Android back button opens the menu. The overlay hides while a game
// controller is in use and comes back when the screen is touched.

void AndroidTouch_Init(void);
// Returns true if the event was consumed
dboolean AndroidTouch_HandleEvent(const SDL_Event *ev);
// Called once per tic, posts movement keys and turning
void AndroidTouch_Tic(void);
// Draws the control outlines over the frame
void AndroidTouch_Draw(void);

#endif // HAVE_TOUCH_CONTROLS

#endif
