package org.prboomplus.rt;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Starts before the game:
 * - extracts the bundled data (shaders, prboom-plus.wad, Freedoom) to internal
 *   storage once per install/update, as the engine reads plain files;
 * - imports a .wad the app was opened with ("Open with" from a file manager);
 * - if the previous run failed, shows its error and log so it can be copied;
 * then launches DoomActivity.
 */
public class LauncherActivity extends Activity {

    static final String TAG = "PrBoomRT";
    static final String PREFS = "prboomrt";
    static final String PREF_IWAD = "iwad";
    private static final String PREF_DATA_STAMP = "data_stamp";

    // written by the engine (see SDL/i_main.c, lprintf.c) and by DoomActivity
    static final String LOG_FILE = "prboom.log";
    static final String ERROR_FILE = "error.txt";
    static final String JAVA_CRASH_FILE = "java_crash.txt";
    static final String RUNNING_FILE = "running.txt";

    private static final String BUNDLED_IWAD = "freedoom1.wad";
    private static final String[] KNOWN_IWADS = {
        "doom2.wad", "doom.wad", "doomu.wad", "plutonia.wad", "tnt.wad", "doom1.wad", "freedoom2.wad",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File home = getFilesDir();

        try {
            extractDataIfNeeded(home);
        } catch (IOException e) {
            Log.e(TAG, "Can't extract game data", e);
            writeFile(new File(home, JAVA_CRASH_FILE), "Can't extract game data:\n" + stackTrace(e));
        }

        importWadFromIntent(home);

        // "Show log" app shortcut (long-press the icon)
        if (ACTION_SHOW_LOG.equals(getIntent() != null ? getIntent().getAction() : null)) {
            showReport(collectReport(home, true), "Game log");
            return;
        }

        String report = collectReport(home, false);
        if (report != null) {
            showReport(report, "The game stopped last time");
        } else {
            startGame();
        }
    }

    private void startGame() {
        clearReport(getFilesDir());
        Intent i = new Intent(this, DoomActivity.class);
        i.putExtra("iwad", chooseIwad());
        startActivity(i);
        finish();
    }

    // --- error report

    /** Returns a report if the previous run ended with an error, or never reached a clean exit. */
    static final String ACTION_SHOW_LOG = "org.prboomplus.rt.SHOW_LOG";

    private String collectReport(File home, boolean always) {
        String error = readFile(new File(home, ERROR_FILE), 4000);
        String java = readFile(new File(home, JAVA_CRASH_FILE), 8000);
        boolean unclean = new File(home, RUNNING_FILE).isFile();

        if (!always && error == null && java == null && !unclean) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("PrBoom+ RT ").append(versionLine()).append('\n');
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("SoC: ").append(Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : "?").append("\n\n");
        if (error != null) {
            sb.append("Error:\n").append(error.trim()).append("\n\n");
        }
        if (java != null) {
            sb.append("Java:\n").append(java.trim()).append("\n\n");
        }
        if (!always && error == null && java == null) {
            sb.append("The game closed unexpectedly (native crash).\n\n");
        }
        String log = readTail(new File(home, LOG_FILE), always ? 20000 : 6000);
        if (log != null) {
            sb.append("Log (end):\n").append(log.trim()).append('\n');
        }
        return sb.toString();
    }

    private String versionLine() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void showReport(final String report, String title) {
        TextView text = new TextView(this);
        text.setText(report);
        text.setTextIsSelectable(true);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(11);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);

        final AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Start game", (d, w) -> startGame())
            .setNegativeButton("Close", (d, w) -> { clearReport(getFilesDir()); finish(); })
            .setNeutralButton("Copy", null)
            .setCancelable(false)
            .create();
        dlg.show();
        // keep the dialog open after copying
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("PrBoom+ RT error report", report));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
    }

    private static void clearReport(File home) {
        new File(home, ERROR_FILE).delete();
        new File(home, JAVA_CRASH_FILE).delete();
        new File(home, RUNNING_FILE).delete();
    }

    // --- IWAD selection

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

        // 3. a WAD bundled at build time (assets/default_iwad.txt names it)
        String bundled = readFile(new File(getFilesDir(), "default_iwad.txt"), 256);
        if (bundled != null) {
            File f = new File(getFilesDir(), new File(bundled.trim()).getName());
            if (!bundled.trim().isEmpty() && f.isFile()) {
                return f.getAbsolutePath();
            }
        }

        // 4. bundled Freedoom
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
        } catch (Exception e) {
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

    // --- data extraction

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

    // --- helpers

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    static void writeFile(File f, String text) {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "Can't write " + f, e);
        }
    }

    private static String readFile(File f, int maxBytes) {
        if (!f.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), maxBytes)];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return null;
        }
    }

    private static String readTail(File f, int maxBytes) {
        if (!f.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            long skip = Math.max(0, f.length() - maxBytes);
            long skipped = 0;
            while (skipped < skip) {
                long s = in.skip(skip - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            byte[] buf = new byte[maxBytes];
            int total = 0, n;
            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) {
                total += n;
            }
            return new String(buf, 0, total, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
