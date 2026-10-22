package org.prboomplus.rt;

import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;

/**
 * Runs the engine (libmain.so) through SDL. Started by LauncherActivity,
 * which prepares the data and passes the IWAD path.
 */
public class DoomActivity extends SDLActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        final File home = getFilesDir();

        // Java exceptions (also those thrown into the SDL thread) end up in a
        // report that LauncherActivity shows on the next start
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            LauncherActivity.writeFile(new File(home, LauncherActivity.JAVA_CRASH_FILE),
                "Thread: " + thread.getName() + "\n" + LauncherActivity.stackTrace(e));
            if (previous != null) {
                previous.uncaughtException(thread, e);
            }
        });

        // removed by the engine on a clean exit (I_SafeExit(0))
        LauncherActivity.writeFile(new File(home, LauncherActivity.RUNNING_FILE), "running\n");

        try {
            Os.setenv("HOME", home.getAbsolutePath(), true);
        } catch (ErrnoException e) {
            Log.e(LauncherActivity.TAG, "Can't set HOME", e);
        }

        super.onCreate(savedInstanceState);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "RayTracedGL1", "main" };
    }

    @Override
    protected String[] getArguments() {
        String iwad = getIntent() != null ? getIntent().getStringExtra("iwad") : null;
        if (iwad == null) {
            iwad = new File(getFilesDir(), "freedoom1.wad").getAbsolutePath();
        }
        Log.i(LauncherActivity.TAG, "Using IWAD " + iwad);
        return new String[] { "-iwad", iwad };
    }
}
