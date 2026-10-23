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
import android.widget.ListView;
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
import java.util.List;
import java.util.Locale;

/**
 * Starts before the game:
 * - extracts the bundled data (shaders, prboom-plus.wad, Freedoom) to internal
 *   storage once per install/update, as the engine reads plain files;
 * - imports .wad / .deh / .zip files ("Open with" from a file manager, or
 *   "Add file..."): game IWADs and mods (custom levels), see WadLibrary;
 * - if the previous run failed, shows its error and log so it can be copied;
 * - lets the player choose a game or mod, then launches DoomActivity.
 */
public class LauncherActivity extends Activity {

    static final String TAG = "PrBoomRT";
    static final String PREFS = "prboomrt";
    private static final String PREF_DATA_STAMP = "data_stamp";

    // written by the engine (see SDL/i_main.c, lprintf.c) and by DoomActivity
    static final String LOG_FILE = "prboom.log";
    static final String ERROR_FILE = "error.txt";
    static final String JAVA_CRASH_FILE = "java_crash.txt";
    static final String RUNNING_FILE = "running.txt";

    private static final String BUNDLED_IWAD = "freedoom1.wad";

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

        library = new WadLibrary(home);
        if (savedInstanceState == null) {
            // not again when the activity is recreated, e.g. on rotation
            importFromIntent(getIntent());
        }

        // "Show log" app shortcut (long-press the icon)
        if (ACTION_SHOW_LOG.equals(getIntent() != null ? getIntent().getAction() : null)) {
            showReport(collectReport(home, true), "Game log");
            return;
        }

        String report = collectReport(home, false);
        if (report != null) {
            showReport(report, "The game stopped last time");
        } else {
            showPicker();
        }
    }

    // --- game picker

    private static final String PREF_LAST_GAME = "last_game";
    private static final int REQUEST_ADD = 1;

    private WadLibrary library;
    private AlertDialog picker;

    private void showPicker() {
        clearReport(getFilesDir());
        final List<WadLibrary.Game> games = library.games(getExternalFilesDir(null));
        if (games.isEmpty()) {
            Toast.makeText(this, "No game data found", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // last played first
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LAST_GAME, null);
        for (int i = 0; i < games.size(); i++) {
            if (games.get(i).key().equals(last)) {
                games.add(0, games.remove(i));
                break;
            }
        }

        String[] titles = new String[games.size()];
        for (int i = 0; i < games.size(); i++) {
            titles[i] = games.get(i).title;
        }

        if (picker != null) {
            picker.dismiss();
        }
        picker = new AlertDialog.Builder(this)
            .setTitle("Choose a game")
            .setItems(titles, (d, which) -> startGame(games.get(which)))
            .setNeutralButton("Add file\u2026", null)
            .setNegativeButton("Quit", (d, w) -> finish())
            .setCancelable(false)
            .create();
        picker.show();
        picker.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQUEST_ADD);
        });
        ListView list = picker.getListView();
        if (list != null) {
            list.setOnItemLongClickListener((parent, view, pos, id) -> {
                confirmRemove(games.get(pos));
                return true;
            });
        }
    }

    private void confirmRemove(final WadLibrary.Game g) {
        final File target = g.modDir != null ? new File(g.modDir) : new File(g.iwad);
        boolean removable = g.modDir != null || target.getParentFile().equals(library.iwadDir());
        if (!removable) {
            Toast.makeText(this, "Built-in games can't be removed", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Remove " + g.title + "?")
            .setPositiveButton("Remove", (d, w) -> {
                deleteTree(target);
                showPicker();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_ADD || result != RESULT_OK || data == null) {
            return;
        }
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                importUri(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            importUri(data.getData());
        }
        showPicker();
    }

    private void startGame(WadLibrary.Game g) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LAST_GAME, g.key()).apply();
        Intent i = new Intent(this, DoomActivity.class);
        i.putExtra("iwad", g.iwad);
        if (g.modDir != null) {
            File dir = new File(g.modDir);
            i.putExtra("files", paths(WadLibrary.modFiles(dir, ".wad")));
            i.putExtra("deh", paths(WadLibrary.modFiles(dir, ".deh", ".bex")));
        }
        startActivity(i);
        finish();
    }

    private static String[] paths(List<File> files) {
        String[] out = new String[files.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = files.get(i).getAbsolutePath();
        }
        return out;
    }

    private void startGame() {
        showPicker();
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

    // --- import ("Open with" from a file manager, or "Add file...")

    private void importFromIntent(Intent intent) {
        Uri uri = intent != null ? intent.getData() : null;
        if (uri != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            importUri(uri);
        }
    }

    private void importUri(Uri uri) {
        String name = queryDisplayName(uri);
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("can't open " + uri);
            }
            WadLibrary.Imported r = library.importFile(in, name);
            if (r.gameKey != null) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LAST_GAME, r.gameKey).apply();
            }
            Toast.makeText(this, r.message, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Can't import " + uri, e);
            Toast.makeText(this, "Couldn't import " + (name != null ? name : "the file"), Toast.LENGTH_LONG).show();
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

    static String readSmall(File f) {
        return readFile(f, 4096);
    }

    static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteTree(c);
            }
        }
        f.delete();
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
