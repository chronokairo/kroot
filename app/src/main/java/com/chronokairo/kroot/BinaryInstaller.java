package com.chronokairo.kroot;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * BinaryInstaller - Unified binary management using app private data directory.
 *
 * Responsibilities:
 *  - Embed su/krootd/krootctl/mali_kroot in res/raw
 *  - Copy them to app private files dir on first run
 *  - Run Mali UAF exploit to obtain UID 0
 *  - Start krootd as root daemon
 *  - All binaries stored in app private data directory
 */
public class BinaryInstaller {

    private static final String TAG = "BinaryInstaller";

    // Use app private data directory
    private static BinaryInstaller instance;
    private String BIN_DIR;
    public static String SU_PATH;
    public static String KROOTD_PATH;
    public static String KROOTCTL_PATH;
    public static String MALI_PATH;

    private final Context context;
    private final RootState state;
    private final LogManager logManager;

    public static BinaryInstaller getInstance(Context context, RootState state, LogManager logManager) {
        if (instance == null) {
            instance = new BinaryInstaller(context, state, logManager);
        }
        return instance;
    }

    private BinaryInstaller(Context context, RootState state, LogManager logManager) {
        this.context = context;
        this.state = state;
        this.logManager = logManager;
        File binDir = new File(context.getFilesDir(), "bin");
        if (!binDir.exists()) {
            binDir.mkdirs();
        }
        BIN_DIR = binDir.getAbsolutePath();
        SU_PATH = BIN_DIR + "/su";
        KROOTD_PATH = BIN_DIR + "/krootd";
        KROOTCTL_PATH = BIN_DIR + "/krootctl";
        MALI_PATH = BIN_DIR + "/mali_kroot";
    }

    /**
     * Full install-and-use flow. Call from a background thread.
     * Returns true if root is available and krootd is online.
     */
    public boolean installAndUse() {
        logManager.i(TAG, "Starting install-and-use flow (private data: " + BIN_DIR + ")");

        // Step 1: Copy embedded binaries to app private dir
        if (!copyEmbeddedBinaries()) {
            logManager.e(TAG, "Failed to copy embedded binaries to " + BIN_DIR);
            return false;
        }

        // Step 2: Run Mali UAF exploit to get root
        if (!runMaliUaf()) {
            logManager.e(TAG, "Mali UAF failed - cannot obtain UID 0");
            state.setLastMessage("Mali UAF failed - no root");
            return false;
        }

        logManager.i(TAG, "UID 0 obtained via Mali UAF");

        // Step 3: Start krootd as root daemon
        if (!startKrootdAsRoot()) {
            logManager.e(TAG, "Failed to start krootd");
            return false;
        }

        logManager.i(TAG, "krootd online - root available");
        state.setLastMessage("Root active - krootd online");
        return true;
    }

    /**
     * Copy embedded binaries from res/raw to app private bin dir
     */
    private boolean copyEmbeddedBinaries() {
        File binDir = new File(BIN_DIR);
        if (!binDir.exists()) {
            if (!binDir.mkdirs()) {
                logManager.e(TAG, "Cannot create " + BIN_DIR);
                return false;
            }
        }

        boolean ok = true;
        ok &= copyRawToFile(R.raw.mali_kroot, MALI_PATH);
        ok &= copyRawToFile(R.raw.su, SU_PATH);
        ok &= copyRawToFile(R.raw.krootd, KROOTD_PATH);
        ok &= copyRawToFile(R.raw.krootctl, KROOTCTL_PATH);

        // Make all executable
        new File(MALI_PATH).setExecutable(true, false);
        new File(SU_PATH).setExecutable(true, false);
        new File(KROOTD_PATH).setExecutable(true, false);
        new File(KROOTCTL_PATH).setExecutable(true, false);

        logManager.i(TAG, "Binaries copied to " + BIN_DIR);
        return ok;
    }

    /**
     * Copy a raw resource to a file path.
     */
    private boolean copyRawToFile(int resId, String destPath) {
        try (InputStream in = context.getResources().openRawResource(resId);
             FileOutputStream out = new FileOutputStream(destPath)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
            File f = new File(destPath);
            f.setExecutable(true, false);
            logManager.d(TAG, "Copied " + resId + " -> " + destPath + " (" + f.length() + " bytes)");
            return true;
        } catch (Exception e) {
            logManager.e(TAG, "Failed to copy " + resId + " to " + destPath, e);
            return false;
        }
    }

    /**
     * Run Mali UAF exploit to obtain UID 0.
     */
    private boolean runMaliUaf() {
        File mali = new File(MALI_PATH);
        if (!mali.exists() || !mali.canExecute()) {
            logManager.e(TAG, "mali_kroot not available at " + MALI_PATH);
            return false;
        }

        logManager.i(TAG, "Running Mali UAF exploit: " + MALI_PATH);
        try {
            Process p = new ProcessBuilder(MALI_PATH)
                    .redirectErrorStream(true)
                    .start();

            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                logManager.d(TAG, "mali: " + line);
            }
            p.waitFor();
        } catch (Exception e) {
            logManager.e(TAG, "Error running mali_kroot", e);
            return false;
        }

        // Verify we got root
        return hasRoot();
    }

    /**
     * Start krootd as root daemon via su.
     */
    private boolean startKrootdAsRoot() {
        if (!hasRoot()) {
            logManager.e(TAG, "No root - cannot start krootd");
            return false;
        }

        logManager.i(TAG, "Starting krootd as root daemon");
        try {
            Process p = new ProcessBuilder(KROOTD_PATH, "--daemon")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();

            // Wait for socket to come online
            for (int i = 0; i < 20; i++) {
                if (isKrootdOnline()) {
                    logManager.i(TAG, "krootd online");
                    return true;
                }
                Thread.sleep(500);
            }

            logManager.w(TAG, "krootd did not come online");
            return false;
        } catch (Exception e) {
            logManager.e(TAG, "Error starting krootd", e);
            return false;
        }
    }

    /**
     * Check if we have UID 0.
     */
    public boolean hasRoot() {
        try {
            Process p = new ProcessBuilder("id", "-u")
                    .redirectErrorStream(true)
                    .start();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String uid = br.readLine();
            p.waitFor();
            return uid != null && uid.trim().equals("0");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if krootd socket is online.
     */
    public boolean isKrootdOnline() {
        try {
            Process p = new ProcessBuilder(KROOTD_PATH, "--status")
                    .redirectErrorStream(true)
                    .start();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            p.waitFor();
            return sb.toString().contains("online");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Getter methods for binary paths.
     */
    public String getBinDir() { return BIN_DIR; }
    public String getSuPath() { return SU_PATH; }
    public String getKrootdPath() { return KROOTD_PATH; }
    public String getKrootctlPath() { return KROOTCTL_PATH; }
    public String getMaliPath() { return MALI_PATH; }

    /**
     * Stop krootd.
     */
    public boolean stopKrootd() {
        try {
            Process p = new ProcessBuilder(SU_PATH, "-c", KROOTD_PATH + " --stop")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();
            return true;
        } catch (Exception e) {
            logManager.e(TAG, "Error stopping krootd", e);
            return false;
        }
    }
}
