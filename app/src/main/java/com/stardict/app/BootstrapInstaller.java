package com.stardict.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;
import android.util.Log;

import com.stardict.BuildConfig;
import com.stardict.shared.file.FileUtils;
import com.stardict.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Extracts a user-provided bootstrap.tar from app assets on first launch.
 * Uses plain tar (not compressed) since the APK itself is already a ZIP archive.
 *
 * Archive contents are extracted to the app's private files directory:
 *   files/usr/bin/   — binaries
 *   files/usr/lib/   — libraries
 *   files/home/      — data
 *   files/startup.sh — launch script
 */
public final class BootstrapInstaller {

    private static final String LOG_TAG = "BootstrapInstaller";
    private static final String PREFS_NAME = "bootstrap_installer";
    private static final String KEY_BOOTSTRAP_EXTRACTED = "bootstrap_extracted";
    private static final String BOOTSTRAP_ASSET = "bootstrap.tar";

    // Prevent redundant work across multiple Activity lifecycles in the same process
    private static volatile boolean sCheckedInProcess = false;

    // Marker file names (all in /data/data/com.stardict/files/)
    private static final String FILE_JUST_INSTALLED = ".just_installed";
    private static final String FILE_NEED_UPDATE    = ".need_update";
    private static final String FILE_VERSION        = ".version";
    private static final String FILE_BOOTSTRAP_TAR  = "bootstrap.tar";

    private BootstrapInstaller() {}

    public static boolean ensureBootstrapExtracted(Context context) {
        if (sCheckedInProcess) {
            Log.d(LOG_TAG, "Already checked in this process.");
            return true;
        }
        sCheckedInProcess = true;

        // First install
        if (!isAlreadyExtracted(context)) {
            if (!hasAsset(context, BOOTSTRAP_ASSET)) {
                Log.e(LOG_TAG, BOOTSTRAP_ASSET + " not found in assets.");
                return false;
            }
            boolean ok = extract(context);
            if (ok) {
                touchFile(context, FILE_JUST_INSTALLED);
                writeVersionFile(context);
            }
            return ok;
        }

        // Upgrade detection
        int currentCode = BuildConfig.VERSION_CODE;
        int oldCode = readVersionCode(context);

        if (currentCode > oldCode) {
            Log.i(LOG_TAG, "Upgrade detected: " + oldCode + " -> " + currentCode);
            copyAssetToFiles(context, BOOTSTRAP_ASSET, FILE_BOOTSTRAP_TAR);
            touchFile(context, FILE_NEED_UPDATE);
            writeVersionFile(context);
        } else {
            Log.d(LOG_TAG, "Version unchanged: " + currentCode);
        }

        return true;
    }

    private static boolean isAlreadyExtracted(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_BOOTSTRAP_EXTRACTED, false)) return false;
        File bin = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "bin");
        return bin.exists() && bin.isDirectory() && bin.list() != null && bin.list().length > 0;
    }

    private static boolean hasAsset(Context ctx, String name) {
        try {
            String[] list = ctx.getAssets().list("");
            if (list == null) return false;
            for (String s : list) if (name.equals(s)) return true;
        } catch (IOException e) { Log.e(LOG_TAG, "Cannot list assets", e); }
        return false;
    }

    private static int readVersionCode(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE_VERSION);
        if (!f.exists()) return 0;
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = fis.read(b);
            String content = new String(b, 0, n, "UTF-8");
            int start = content.lastIndexOf('(');
            int end = content.lastIndexOf(')');
            if (start != -1 && end != -1 && end > start) {
                return Integer.parseInt(content.substring(start + 1, end));
            }
        } catch (Exception e) { Log.w(LOG_TAG, "Failed to read version file", e); }
        return 0;
    }

    private static void writeVersionFile(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), FILE_VERSION);
            String content = String.format("v%s(%d)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(content.getBytes("UTF-8"));
            }
            Os.chmod(f.getAbsolutePath(), 0600);
        } catch (Exception e) { Log.e(LOG_TAG, "Failed to write version file", e); }
    }

    private static void touchFile(Context ctx, String name) {
        try {
            File f = new File(ctx.getFilesDir(), name);
            if (!f.exists()) f.createNewFile();
        } catch (Exception e) { Log.e(LOG_TAG, "Failed to touch " + name, e); }
    }

    private static void copyAssetToFiles(Context ctx, String assetName, String destName) {
        File dest = new File(ctx.getFilesDir(), destName);
        try (InputStream is = ctx.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(dest)) {
            copyStream(is, fos);
            Log.i(LOG_TAG, "Copied asset to " + dest.getAbsolutePath());
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to copy asset " + assetName, e);
        }
    }

    private static boolean extract(Context ctx) {
        Log.i(LOG_TAG, "Extracting " + BOOTSTRAP_ASSET + " ...");
        File filesDir = ctx.getFilesDir();
        File prefixDir = new File(filesDir, "usr");

        try {
            FileUtils.createDirectoryFile(prefixDir.getAbsolutePath());
            FileUtils.createDirectoryFile(new File(filesDir, "home").getAbsolutePath());
            FileUtils.createDirectoryFile(new File(filesDir, "tmp").getAbsolutePath());

            File tmp = new File(ctx.getCacheDir(), BOOTSTRAP_ASSET);
            try (InputStream is = ctx.getAssets().open(BOOTSTRAP_ASSET);
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                copyStream(is, fos);
            }

            boolean ok = trySystemTar(tmp, filesDir.getAbsolutePath());
            if (!ok) ok = extractJava(tmp, filesDir.getAbsolutePath());

            tmp.delete();
            if (!ok) { Log.e(LOG_TAG, "Extraction failed."); return false; }

            setExecPermissions(prefixDir);
            installFont(ctx);
            installColors(ctx);
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_BOOTSTRAP_EXTRACTED, true).apply();
            Log.i(LOG_TAG, "Done.");
            return true;
        } catch (Exception e) {
            Log.e(LOG_TAG, "Extract error", e);
            return false;
        }
    }

    // --- system tar ---

    private static boolean trySystemTar(File archive, String dest) {
        try {
            ProcessBuilder pb = new ProcessBuilder("tar", "-xf", archive.getAbsolutePath(), "-C", dest);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) {}
            }
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    // --- Java tar parser ---

    private static boolean extractJava(File archive, String dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(archive)) {
            return parseTar(fis, dest);
        }
    }

    private static boolean parseTar(InputStream in, String dest) throws IOException {
        byte[] buf = new byte[8192];
        byte[] hdr = new byte[512];

        while (true) {
            int n = readFully(in, hdr);
            if (n < 512 || isNullBlock(hdr)) {
                byte[] nx = new byte[512];
                if (readFully(in, nx) == 0 || isNullBlock(nx)) break;
            }

            String name = parseStr(hdr, 0, 100);
            long size = parseOct(hdr, 124, 12);
            int type = hdr[156] & 0xFF;
            if (type == 0) type = '0';
            int mode = (int) parseOct(hdr, 100, 8);
            if (mode == 0) mode = 0644;

            if ("././@LongLink".equals(name)) {
                byte[] ln = new byte[(int) size];
                readFully(in, ln); skipAlign(in, size);
                name = new String(ln, 0, (int) size, "UTF-8").trim();
                readFully(in, hdr);
                size = parseOct(hdr, 124, 12);
                type = hdr[156] & 0xFF; if (type == 0) type = '0';
            }

            if (name.isEmpty()) { skipData(in, size); continue; }
            File out = new File(dest, name);

            if (type == '5' || name.endsWith("/")) {
                FileUtils.createDirectoryFile(out.getAbsolutePath());
                continue;
            }
            if (type == '2') {
                try { Os.symlink(parseStr(hdr, 157, 100), out.getAbsolutePath()); } catch (Exception ignored) {}
                skipData(in, size); continue;
            }

            FileUtils.createDirectoryFile(out.getParentFile().getAbsolutePath());
            try (FileOutputStream fos = new FileOutputStream(out)) {
                long rem = size;
                while (rem > 0) {
                    int c = (int) Math.min(rem, buf.length);
                    int r = in.read(buf, 0, c);
                    if (r < 0) break;
                    fos.write(buf, 0, r); rem -= r;
                }
            }
            try { Os.chmod(out.getAbsolutePath(), mode); } catch (Exception ignored) {}
            skipAlign(in, size);
        }
        return true;
    }

    // --- helpers ---

    private static int readFully(InputStream is, byte[] b) throws IOException {
        int t = 0;
        while (t < b.length) { int r = is.read(b, t, b.length - t); if (r < 0) break; t += r; }
        return t;
    }
    private static boolean isNullBlock(byte[] b) { for (byte x : b) if (x != 0) return false; return true; }
    private static String parseStr(byte[] b, int o, int l) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < l && b[o + i] != 0; i++) s.append((char) (b[o + i] & 0xFF));
        return s.toString();
    }
    private static long parseOct(byte[] b, int o, int l) {
        long v = 0; int i = o, e = o + l;
        while (i < e && (b[i] == ' ' || b[i] == 0)) i++;
        while (i < e) { byte c = b[i]; if (c == 0 || c == ' ' || c < '0' || c > '7') break; v = (v << 3) + (c - '0'); i++; }
        return v;
    }
    private static void skipAlign(InputStream is, long sz) throws IOException {
        long pad = (512 - (sz % 512)) % 512; while (pad > 0) pad -= is.skip(pad);
    }
    private static void skipData(InputStream is, long sz) throws IOException {
        long t = sz + ((512 - (sz % 512)) % 512); while (t > 0) t -= is.skip(Math.min(t, 8192));
    }
    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }
    private static void setExecPermissions(File prefix) {
        File bin = new File(prefix, "bin");
        if (bin.isDirectory()) for (File f : bin.listFiles()) if (f.isFile()) try { Os.chmod(f.getAbsolutePath(), 0700); } catch (Exception ignored) {}
        File lib = new File(prefix, "lib");
        if (lib.isDirectory()) for (File f : lib.listFiles()) if (f.isFile()) try { Os.chmod(f.getAbsolutePath(), 0755); } catch (Exception ignored) {}
    }
    private static void installFont(Context ctx) {
        try {
            File dir = TermuxConstants.TERMUX_FONT_FILE.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            if (TermuxConstants.TERMUX_FONT_FILE.exists()) return;
            String[] a = ctx.getAssets().list("");
            boolean has = false; if (a != null) for (String s : a) if ("font.ttf".equals(s)) has = true;
            if (!has) return;
            try (InputStream is = ctx.getAssets().open("font.ttf");
                 FileOutputStream fos = new FileOutputStream(TermuxConstants.TERMUX_FONT_FILE)) {
                copyStream(is, fos);
            }
            Log.i(LOG_TAG, "Font installed.");
        } catch (Exception e) { Log.w(LOG_TAG, "Font install failed", e); }
    }
    private static void installColors(Context ctx) {
        try {
            File dir = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            if (TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE.exists()) return;
            String[] a = ctx.getAssets().list("");
            boolean has = false; if (a != null) for (String s : a) if ("colors.properties".equals(s)) has = true;
            if (!has) return;
            try (InputStream is = ctx.getAssets().open("colors.properties");
                 FileOutputStream fos = new FileOutputStream(TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE)) {
                copyStream(is, fos);
            }
            Log.i(LOG_TAG, "Color theme installed.");
        } catch (Exception e) { Log.w(LOG_TAG, "Color install failed", e); }
    }
    public static void reset(Context ctx) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_BOOTSTRAP_EXTRACTED, false).apply();
    }
}
