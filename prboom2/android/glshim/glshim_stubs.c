/*
 * No-op OpenGL/GLU entry points for builds without desktop OpenGL (Android).
 * The ray traced renderer (VID_MODERT) never calls them: they only satisfy the
 * linker for the OpenGL renderer code that RT depends on for declarations.
 * Each returns 0 (NULL / GL_FALSE / GL_NO_ERROR) in case one is reached.
 * Generated from the undefined symbols of a link without libGL/libGLU.
 */
#include <stdint.h>

intptr_t glAlphaFunc(void) { return 0; }
intptr_t glBegin(void) { return 0; }
intptr_t glBindTexture(void) { return 0; }
intptr_t glBlendFunc(void) { return 0; }
intptr_t glCallList(void) { return 0; }
intptr_t glClear(void) { return 0; }
intptr_t glClearColor(void) { return 0; }
intptr_t glClearDepth(void) { return 0; }
intptr_t glColor3f(void) { return 0; }
intptr_t glColor4f(void) { return 0; }
intptr_t glColor4fv(void) { return 0; }
intptr_t glColorMask(void) { return 0; }
intptr_t glColorPointer(void) { return 0; }
intptr_t glCopyTexSubImage2D(void) { return 0; }
intptr_t glCullFace(void) { return 0; }
intptr_t glDeleteLists(void) { return 0; }
intptr_t glDeleteTextures(void) { return 0; }
intptr_t glDepthFunc(void) { return 0; }
intptr_t glDepthMask(void) { return 0; }
intptr_t glDepthRange(void) { return 0; }
intptr_t glDisable(void) { return 0; }
intptr_t glDisableClientState(void) { return 0; }
intptr_t glDrawArrays(void) { return 0; }
intptr_t glEnable(void) { return 0; }
intptr_t glEnableClientState(void) { return 0; }
intptr_t glEnd(void) { return 0; }
intptr_t glEndList(void) { return 0; }
intptr_t glFlush(void) { return 0; }
intptr_t glFogf(void) { return 0; }
intptr_t glFogfv(void) { return 0; }
intptr_t glFogi(void) { return 0; }
intptr_t glGenLists(void) { return 0; }
intptr_t glGenTextures(void) { return 0; }
intptr_t glGetFloatv(void) { return 0; }
intptr_t glGetIntegerv(void) { return 0; }
intptr_t glGetString(void) { return 0; }
intptr_t glGetTexImage(void) { return 0; }
intptr_t glGetTexLevelParameteriv(void) { return 0; }
intptr_t glHint(void) { return 0; }
intptr_t glLoadIdentity(void) { return 0; }
intptr_t glLoadMatrixf(void) { return 0; }
intptr_t glMatrixMode(void) { return 0; }
intptr_t glNewList(void) { return 0; }
intptr_t glOrtho(void) { return 0; }
intptr_t glPixelStorei(void) { return 0; }
intptr_t glPolygonOffset(void) { return 0; }
intptr_t glPopMatrix(void) { return 0; }
intptr_t glPushMatrix(void) { return 0; }
intptr_t glReadPixels(void) { return 0; }
intptr_t glRotatef(void) { return 0; }
intptr_t glScalef(void) { return 0; }
intptr_t glScissor(void) { return 0; }
intptr_t glShadeModel(void) { return 0; }
intptr_t glStencilFunc(void) { return 0; }
intptr_t glStencilOp(void) { return 0; }
intptr_t glTexCoord2f(void) { return 0; }
intptr_t glTexCoord2fv(void) { return 0; }
intptr_t glTexCoordPointer(void) { return 0; }
intptr_t glTexEnvf(void) { return 0; }
intptr_t glTexEnvi(void) { return 0; }
intptr_t glTexGenf(void) { return 0; }
intptr_t glTexGenfv(void) { return 0; }
intptr_t glTexImage2D(void) { return 0; }
intptr_t glTexParameterf(void) { return 0; }
intptr_t glTexParameteri(void) { return 0; }
intptr_t glTranslatef(void) { return 0; }
intptr_t glVertex2f(void) { return 0; }
intptr_t glVertex2i(void) { return 0; }
intptr_t glVertex3f(void) { return 0; }
intptr_t glVertex3fv(void) { return 0; }
intptr_t glVertexPointer(void) { return 0; }
intptr_t glViewport(void) { return 0; }
intptr_t gluBuild2DMipmaps(void) { return 0; }
intptr_t gluScaleImage(void) { return 0; }
