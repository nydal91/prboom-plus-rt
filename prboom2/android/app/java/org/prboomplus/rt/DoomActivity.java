package org.prboomplus.rt;

import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * PrBoom+ RT launcher.
 *
 * - Extracts the bundled data (shaders, prboom-plus.wad, Freedoom) from the APK
 *   assets to internal storage once per install/update; the engine reads plain files.
 * - Opening a .wad file with this app (from a file manager) imports it and makes
 *   it the IWAD, e.g. your own doom.wad or doom2.wad.
 * - IWADs placed in Android/data/org.prboomplus.rt/files/ are also used.
 */
public class DoomActivity extends SDLActivity {

    private static final String TAG = "PrBoomRT";
    private static final String PREFS = "prboomrt";
    private static final String PREF_IWAD = "iwad";
    private static final String PREF_DATA_STAMP = "data_stamp";

    // Preferred order when several IWADs are available
    private static final String[] KNOWN_IWADS = {
        "doom2.wad", "doom.wad", "doomu.wad", "plutonia.wad", "tnt.wad", "doom1.wad", "freedoom2.wad",
    };
    private static final String BUNDLED_IWAD = "freedoom1.wad";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        File home = getFilesDir();
        try {
            extractDataIfNeeded(home);
        } catch (IOException e) {
            Log.e(TAG, "Can't extract game data", e);
        }

        importWadFromIntent(home);

        try {
            Os.setenv("HOME", home.getAbsolutePath(), true);
        } catch (ErrnoException e) {
            Log.e(TAG, "Can't set HOME", e);
        }

        super.onCreate(savedInstanceState);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "RayTracedGL1", "main" };
    }

    @Override
    protected String[] getArguments() {
        String iwad = chooseIwad();
        Log.i(TAG, "Using IWAD " + iwad);
        return new String[] { "-iwad", iwad };
    }

    private String chooseIwad() {
        // 1. last imported WAD
        String imported = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_IWAD, null);
        if (imported != null && new File(imported).isFile()) {
            return imported;
        }

        // 2. a known IWAD in the app's external files folder
        File ext = getExternalFilesDir(null);
        if (ext != null) {
            for (String name : KNOWN_IWADS) {
                File f = new File(ext, name);
                if (f.isFile()) {
                    return f.getAbsolutePath();
                }
            }
        }

        // 3. bundled Freedoom
        return new File(getFilesDir(), BUNDLED_IWAD).getAbsolutePath();
    }

    /** Copies a .wad the app was opened with into internal storage and selects it. */
    private void importWadFromIntent(File home) {
        Uri uri = getIntent() != null ? getIntent().getData() : null;
        if (uri == null) {
            return;
        }

        String name = queryDisplayName(uri);
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".wad")) {
            name = "imported.wad";
        }
        // the engine detects some games by file name, e.g. tnt.wad / plutonia.wad
        name = new File(name.toLowerCase(Locale.ROOT)).getName();

        File dir = new File(home, "iwads");
        File dst = new File(dir, name);
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("can't create " + dir);
            }
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(dst)) {
                if (in == null) {
                    throw new IOException("can't open " + uri);
                }
                copy(in, out);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_IWAD, dst.getAbsolutePath()).apply();
            Toast.makeText(this, "Using " + name, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Can't import " + uri, e);
            Toast.makeText(this, "Couldn't import " + name, Toast.LENGTH_LONG).show();
        }
    }

    private String queryDisplayName(Uri uri) {
        if ("file".equals(uri.getScheme())) {
            return uri.getLastPathSegment();
        }
        try (Cursor c = getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Can't query name of " + uri, e);
        }
        return null;
    }

    /** Extracts assets once per APK install/update. */
    private void extractDataIfNeeded(File home) throws IOException {
        long stamp;
        try {
            stamp = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            stamp = -1;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (stamp != -1 && prefs.getLong(PREF_DATA_STAMP, 0) == stamp
                && new File(home, BUNDLED_IWAD).isFile()) {
            return;
        }

        long t = System.currentTimeMillis();
        copyAssetTree(getAssets(), "", home);
        prefs.edit().putLong(PREF_DATA_STAMP, stamp).apply();
        Log.i(TAG, "Extracted game data in " + (System.currentTimeMillis() - t) + " ms");
    }

    private static void copyAssetTree(AssetManager am, String path, File dst) throws IOException {
        String[] children = am.list(path);
        if (children == null || children.length == 0) {
            // a file (or an empty folder, which assets don't keep)
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("can't create " + parent);
            }
            try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dst)) {
                copy(in, out);
            }
            return;
        }

        for (String child : children) {
            // skip folders the build tools add to every APK
            if (path.isEmpty() && (child.equals("images") || child.equals("webkit"))) {
                continue;
            }
            String childPath = path.isEmpty() ? child : path + "/" + child;
            copyAssetTree(am, childPath, new File(dst, child));
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }
}
