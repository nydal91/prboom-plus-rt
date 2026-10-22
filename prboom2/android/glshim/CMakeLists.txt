cmake_minimum_required(VERSION 3.15)
project(glshim C)
# GLU tessellator (SGI libtess via GLUES) + no-op GL stubs, replacing libGL/libGLU
# for the ray traced renderer on platforms without desktop OpenGL.
add_library(glshim STATIC
    glshim_stubs.c
    libtess/dict.c libtess/geom.c libtess/memalloc.c libtess/mesh.c libtess/normal.c
    libtess/priorityq.c libtess/render.c libtess/sweep.c libtess/tess.c libtess/tessmono.c)
target_include_directories(glshim PRIVATE include libtess)
target_include_directories(glshim PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/../include)
set_target_properties(glshim PROPERTIES POSITION_INDEPENDENT_CODE ON)
