package com.chronokairo.kroot;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * BinaryInstaller - Unified binary management using /data/local/xbin/ as the single pattern.
 *
 * Responsibilities:
 *  - Embed su/krootd/krootctl/mali_kroot in res/raw
 *  - Copy them to /data/local/xbin/ on first run
 *  - Run Mali UAF exploit to obtain UID 0
 *  - Install binaries to /data/local/xbin/ with root ownership
 *  - Start krootd as root daemon
 *  - All paths use /data/local/xbin/ exclusively
 */
public class BinaryInstaller {

    private static final String TAG = "BinaryInstaller";

    // Single pattern: /data/local/xbin/ for everything
    public static final String XBIN_DIR = "/data/local/xbin";
    public static final String SU_PATH = XBIN_DIR + "/su";
    public static final String KROOTD_PATH = XBIN_DIR + "/krootd";
    public static final String KROOTCTL_PATH = XBIN_DIR + "/krootctl";
    public static final String MALI_PATH = XBIN_DIR + "/mali_kroot";

    private final Context context;
    private final RootState state;
    private final LogManager logManager;

    public BinaryInstaller(Context context, RootState state, LogManager logManager) {
        this.context = context;
        this.state = state;
        this.logManager = logManager;
    }

    /**
     * Full install-and-use flow. Call from a background thread.
     * Returns true if root is available and krootd is online.
     */
    public boolean installAndUse() {
        logManager.i(TAG, "Starting install-and-use flow (pattern: " + XBIN_DIR + ")");

        // Step 1: Copy embedded binaries to /data/local/xbin/
        if (!copyEmbeddedBinaries()) {
            logManager.e(TAG, "Failed to copy embedded binaries to " + XBIN_DIR);
            return false;
        }

        // Step 2: Run Mali UAF exploit to get root
        if (!runMaliUaf()) {
            logManager.e(TAG, "Mali UAF failed - cannot obtain UID 0");
            state.setLastMessage("Mali UAF failed - no root");
            return false;
        }

        logManager.i(TAG, "UID 0 obtained via Mali UAF");

        // Step 3: Install binaries to /data/local/xbin/ with root ownership
        if (!installBinariesAsRoot()) {
            logManager.e(TAG, "Failed to install binaries to " + XBIN_DIR);
            return false;
        }

        // Step 4: Start krootd as root daemon
        if (!startKrootdAsRoot()) {
            logManager.e(TAG, "Failed to start krootd");
            return false;
        }

        logManager.i(TAG, "krootd online - root available");
        state.setLastMessage("Root active - krootd online");
        return true;
    }

    /**
     * Copy embedded binaries from res/raw to /data/local/xbin/
     */
    private boolean copyEmbeddedBinaries() {
        File xbinDir = new File(XBIN_DIR);
        if (!xbinDir.exists()) {
            if (!xbinDir.mkdirs()) {
                logManager.e(TAG, "Cannot create " + XBIN_DIR);
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

        logManager.i(TAG, "Binaries copied to " + XBIN_DIR);
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
     * Install binaries to /data/local/xbin/ with root ownership using su.
     */
    private boolean installBinariesAsRoot() {
        if (!hasRoot()) {
            logManager.e(TAG, "No root - cannot install binaries as root");
            return false;
        }

        logManager.i(TAG, "Installing binaries to " + XBIN_DIR + " as root");
        try {
            String cmd = "cp " + MALI_PATH + " " + XBIN_DIR + "/mali_kroot && " +
                         "cp " + SU_PATH + " " + XBIN_DIR + "/su && " +
                         "cp " + KROOTD_PATH + " " + XBIN_DIR + "/krootd && " +
                         "cp " + KROOTCTL_PATH + " " + XBIN_DIR + "/krootctl && " +
                         "chown root:root " + XBIN_DIR + "/mali_kroot " +
                                     XBIN_DIR + "/su " +
                                     XBIN_DIR + "/krootd " +
                                     XBIN_DIR + "/krootctl && " +
                         "chmod 0755 " + XBIN_DIR + "/mali_kroot " +
                                     XBIN_DIR + "/su " +
                                     XBIN_DIR + "/krootd " +
                                     XBIN_DIR + "/krootctl";

            Process p = new ProcessBuilder(SU_PATH, "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();

            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                logManager.d(TAG, "install: " + line);
            }

            logManager.i(TAG, "Binaries installed to " + XBIN_DIR + " as root");
            return true;
        } catch (Exception e) {
            logManager.e(TAG, "Error installing binaries as root", e);
            return false;
        }
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
            Process p = new ProcessBuilder(SU_PATH, "-c", KROOTD_PATH + " --daemon")
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
            Process p = new ProcessBuilder(SU_PATH, "-c", "id -u")
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
            Process p = new ProcessBuilder(SU_PATH, "--status")
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
