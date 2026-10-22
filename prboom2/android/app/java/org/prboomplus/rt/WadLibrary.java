package org.prboomplus.rt;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The games and mods the app knows about, in its internal storage:
 *   files/            bundled IWADs (doom.wad if bundled, freedoom1/2.wad)
 *   files/iwads/      imported IWADs (doom2.wad, plutonia.wad, ...)
 *   files/mods/NAME/  an imported mod: its .wad / .deh / .bex files
 */
final class WadLibrary {

    enum Kind { IWAD, PWAD, OTHER }

    /** Which base game a mod's levels are made for. */
    enum Family { DOOM1, DOOM2, ANY }

    static final class Game {
        final String title;
        final String iwad;          // absolute path
        final String modDir;        // null for a plain game
        Game(String title, String iwad, String modDir) {
            this.title = title;
            this.iwad = iwad;
            this.modDir = modDir;
        }
        String key() {
            return modDir != null ? "mod:" + new File(modDir).getName() : "iwad:" + new File(iwad).getName();
        }
    }

    private final File home;

    WadLibrary(File home) {
        this.home = home;
    }

    File iwadDir() { return new File(home, "iwads"); }
    File modsDir() { return new File(home, "mods"); }

    // --- WAD inspection

    static Kind kindOf(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] id = new byte[4];
            if (r.read(id) != 4) return Kind.OTHER;
            String s = new String(id, "US-ASCII");
            return s.equals("IWAD") ? Kind.IWAD : s.equals("PWAD") ? Kind.PWAD : Kind.OTHER;
        } catch (IOException e) {
            return Kind.OTHER;
        }
    }

    /** Lump names of a WAD (empty if it can't be read). */
    static List<String> lumps(File f) {
        List<String> out = new ArrayList<>();
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] head = new byte[12];
            r.readFully(head);
            ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            b.position(4);
            int count = b.getInt();
            int offset = b.getInt();
            if (count < 0 || count > 200000 || offset < 0 || offset > r.length()) return out;
            byte[] dir = new byte[count * 16];
            r.seek(offset);
            r.readFully(dir);
            for (int i = 0; i < count; i++) {
                int n = 0;
                while (n < 8 && dir[i * 16 + 8 + n] != 0) n++;
                out.add(new String(dir, i * 16 + 8, n, "US-ASCII").toUpperCase(Locale.ROOT));
            }
        } catch (IOException e) {
            // not a WAD
        }
        return out;
    }

    static Family familyOf(List<File> wads) {
        boolean doom1 = false, doom2 = false;
        for (File w : wads) {
            for (String l : lumps(w)) {
                if (l.matches("MAP[0-9][0-9]")) doom2 = true;
                else if (l.matches("E[1-9]M[1-9]")) doom1 = true;
            }
        }
        return doom2 ? Family.DOOM2 : doom1 ? Family.DOOM1 : Family.ANY;
    }

    // --- base games

    private File bundledDefault() {
        // doom.wad bundled at build time, see build_apk.sh / default_iwad.txt
        File txt = new File(home, "default_iwad.txt");
        String name = LauncherActivity.readSmall(txt);
        if (name != null && !name.trim().isEmpty()) {
            File f = new File(home, new File(name.trim()).getName());
            if (f.isFile()) return f;
        }
        return null;
    }

    private static String titleOf(String file) {
        switch (file.toLowerCase(Locale.ROOT)) {
            case "doom.wad": case "doomu.wad": return "Doom";
            case "doom1.wad": return "Doom (shareware)";
            case "doom2.wad": return "Doom II";
            case "plutonia.wad": return "Final Doom: Plutonia";
            case "tnt.wad": return "Final Doom: TNT";
            case "freedoom1.wad": return "Freedoom: Phase 1";
            case "freedoom2.wad": return "Freedoom: Phase 2";
            default: return file;
        }
    }

    /** IWADs, best first. */
    List<File> iwads(File extDir) {
        List<File> out = new ArrayList<>();
        File def = bundledDefault();
        if (def != null) out.add(def);
        File[] imported = iwadDir().listFiles();
        if (imported != null) {
            Arrays.sort(imported);
            for (File f : imported) if (f.isFile()) out.add(f);
        }
        if (extDir != null) {
            File[] ext = extDir.listFiles();
            if (ext != null) {
                Arrays.sort(ext);
                for (File f : ext) {
                    if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".wad") && kindOf(f) == Kind.IWAD) {
                        out.add(f);
                    }
                }
            }
        }
        for (String b : new String[] { "freedoom1.wad", "freedoom2.wad" }) {
            File f = new File(home, b);
            if (f.isFile()) out.add(f);
        }
        return out;
    }

    private File pickIwad(List<File> iwads, Family family) {
        for (File f : iwads) {
            boolean d2 = isDoom2(f.getName());
            if (family == Family.ANY || (family == Family.DOOM2) == d2) return f;
        }
        return iwads.isEmpty() ? null : iwads.get(0);
    }

    private static boolean isDoom2(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("doom2.wad") || n.equals("plutonia.wad") || n.equals("tnt.wad")
            || n.equals("freedoom2.wad") || n.startsWith("doom2");
    }

    // --- mods

    static List<File> modFiles(File modDir, String... exts) {
        List<File> out = new ArrayList<>();
        File[] files = modDir.listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            for (String e : exts) {
                if (n.endsWith(e)) {
                    out.add(f);
                    break;
                }
            }
        }
        return out;
    }

    /** Everything the picker shows: plain games, then mods. */
    List<Game> games(File extDir) {
        List<Game> out = new ArrayList<>();
        List<File> iwads = iwads(extDir);
        List<String> seen = new ArrayList<>();
        for (File f : iwads) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (seen.contains(n)) continue;
            seen.add(n);
            out.add(new Game(titleOf(f.getName()), f.getAbsolutePath(), null));
        }
        File[] mods = modsDir().listFiles();
        if (mods != null) {
            Arrays.sort(mods);
            for (File m : mods) {
                if (!m.isDirectory()) continue;
                List<File> wads = modFiles(m, ".wad");
                if (wads.isEmpty() && modFiles(m, ".deh", ".bex").isEmpty()) continue;
                File iwad = pickIwad(iwads, familyOf(wads));
                if (iwad == null) continue;
                out.add(new Game(m.getName() + "  (" + titleOf(iwad.getName()) + ")", iwad.getAbsolutePath(), m.getAbsolutePath()));
            }
        }
        return out;
    }

    // --- import

    /** A result for the user. */
    static final class Imported {
        final String message;
        final String gameKey;      // to select it, or null
        Imported(String message, String gameKey) {
            this.message = message;
            this.gameKey = gameKey;
        }
    }

    private static String safeName(String name) {
        String n = name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return n.isEmpty() ? "mod" : n;
    }

    private static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** Imports a .wad, .deh/.bex or .zip given as a stream with its display name. */
    Imported importFile(InputStream in, String displayName) throws IOException {
        String name = displayName == null ? "imported.wad" : new File(displayName).getName();
        String lower = name.toLowerCase(Locale.ROOT);

        File tmp = new File(home, "import.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            LauncherActivity.copy(in, out);
        }

        try {
            if (lower.endsWith(".zip") || isZip(tmp)) {
                return importZip(tmp, stripExt(name));
            }
            if (lower.endsWith(".deh") || lower.endsWith(".bex")) {
                File dir = new File(modsDir(), safeName(stripExt(name)));
                mkdirs(dir);
                move(tmp, new File(dir, safeName(lower)));
                return new Imported("Added " + dir.getName(), "mod:" + dir.getName());
            }
            switch (kindOf(tmp)) {
                case IWAD: {
                    // the engine detects the game by file name (doom2.wad, tnt.wad...)
                    String file = lower.endsWith(".wad") ? lower : "imported.wad";
                    mkdirs(iwadDir());
                    File dst = new File(iwadDir(), safeName(file));
                    move(tmp, dst);
                    return new Imported("Added " + titleOf(dst.getName()), "iwad:" + dst.getName());
                }
                case PWAD: {
                    File dir = new File(modsDir(), safeName(stripExt(name)));
                    mkdirs(dir);
                    move(tmp, new File(dir, safeName(lower.endsWith(".wad") ? lower : lower + ".wad")));
                    return new Imported("Added " + dir.getName(), "mod:" + dir.getName());
                }
                default:
                    return new Imported(name + " isn't a WAD, DEH or ZIP file", null);
            }
        } finally {
            tmp.delete();
        }
    }

    private static boolean isZip(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            return r.length() > 4 && r.readInt() == 0x504B0304;
        } catch (IOException e) {
            return false;
        }
    }

    private Imported importZip(File zip, String baseName) throws IOException {
        File dir = new File(modsDir(), safeName(baseName));
        File staging = new File(home, "import.dir");
        LauncherActivity.deleteTree(staging);
        mkdirs(staging);

        int count = 0;
        String iwadKey = null;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = new File(e.getName()).getName().toLowerCase(Locale.ROOT);
                if (!(n.endsWith(".wad") || n.endsWith(".deh") || n.endsWith(".bex"))) continue;
                File out = new File(staging, safeName(n));
                try (OutputStream o = new FileOutputStream(out)) {
                    LauncherActivity.copy(z, o);
                }
                if (n.endsWith(".wad") && kindOf(out) == Kind.IWAD) {
                    mkdirs(iwadDir());
                    move(out, new File(iwadDir(), safeName(n)));
                    iwadKey = "iwad:" + safeName(n);
                } else {
                    count++;
                }
            }
        }

        if (count == 0) {
            LauncherActivity.deleteTree(staging);
            return iwadKey != null ? new Imported("Added " + titleOf(iwadKey.substring(5)), iwadKey)
                                   : new Imported("No .wad or .deh files in " + baseName + ".zip", null);
        }
        LauncherActivity.deleteTree(dir);
        mkdirs(modsDir());
        if (!staging.renameTo(dir)) {
            throw new IOException("can't create " + dir);
        }
        return new Imported("Added " + dir.getName(), "mod:" + dir.getName());
    }

    private static void mkdirs(File d) throws IOException {
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("can't create " + d);
    }

    private static void move(File from, File to) throws IOException {
        to.delete();
        if (!from.renameTo(to)) throw new IOException("can't move to " + to);
    }
}
