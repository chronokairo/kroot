package com.chronokairo.kroot;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Executa diagnósticos no dispositivo para verificar compatibilidade
 * e estado dos componentes necessários ao Kairos Root.
 */
public class DiagnosticEngine {

    private static final String TAG = "DiagnosticEngine";

    public static class Result {
        public boolean maliBinaryExists;
        public boolean maliBinaryExecutable;
        public boolean krootdBinaryExists;
        public boolean krootdBinaryExecutable;
        public boolean suBinaryExists;
        public boolean suBinaryExecutable;
        public boolean devMali0Exists;
        public boolean devMali0Readable;
        public boolean devMali0Writable;
        public boolean isRoot;
        public int androidUid;
        public String androidVersion;
        public String buildFingerprint;
        public String deviceModel;
        public StringBuilder log = new StringBuilder();
    }

    public Result runDiagnostics() {
        Result r = new Result();
        r.log.append("=== Diagnóstico Kairos Root ===\n");

        r.androidVersion = getProp("ro.build.version.release");
        r.buildFingerprint = getProp("ro.build.fingerprint");
        r.deviceModel = getProp("ro.product.model");

        r.log.append("Android: ").append(r.androidVersion).append("\n");
        r.log.append("Modelo: ").append(r.deviceModel).append("\n");
        r.log.append("Fingerprint: ").append(r.buildFingerprint).append("\n");

        // UID atual
        String uidStr = execSimple("id -u");
        try {
            r.androidUid = Integer.parseInt(uidStr.trim());
            r.isRoot = (r.androidUid == 0);
        } catch (NumberFormatException e) {
            r.androidUid = -1;
            r.isRoot = false;
        }
        r.log.append("UID atual: ").append(r.androidUid).append(" (root=").append(r.isRoot).append(")\n");

        // Verificar binários (caminhos via BinaryInstaller - diretório privado do app)
        String maliPath = BinaryInstaller.MALI_PATH;
        String krootdPath = BinaryInstaller.KROOTD_PATH;
        String suPath = BinaryInstaller.SU_PATH;

        r.maliBinaryExists = fileExists(maliPath != null ? maliPath : "");
        r.maliBinaryExecutable = canExecute(maliPath != null ? maliPath : "");
        r.log.append("mali_kroot existe=").append(r.maliBinaryExists)
                .append(" executável=").append(r.maliBinaryExecutable).append("\n");

        r.krootdBinaryExists = fileExists(krootdPath != null ? krootdPath : "");
        r.krootdBinaryExecutable = canExecute(krootdPath != null ? krootdPath : "");
        r.log.append("krootd existe=").append(r.krootdBinaryExists)
                .append(" executável=").append(r.krootdBinaryExecutable).append("\n");

        r.suBinaryExists = fileExists(suPath != null ? suPath : "");
        r.suBinaryExecutable = canExecute(suPath != null ? suPath : "");
        r.log.append("su existe=").append(r.suBinaryExists)
                .append(" executável=").append(r.suBinaryExecutable).append("\n");

        // Verificar /dev/mali0
        r.devMali0Exists = fileExists("/dev/mali0");
        r.devMali0Readable = canRead("/dev/mali0");
        r.devMali0Writable = canWrite("/dev/mali0");
        r.log.append("/dev/mali0 existe=").append(r.devMali0Exists)
                .append(" leitura=").append(r.devMali0Readable)
                .append(" escrita=").append(r.devMali0Writable).append("\n");

        r.log.append("=== Fim do diagnóstico ===\n");
        return r;
    }

    private String execSimple(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            return line != null ? line : "";
        } catch (Exception e) {
            return "";
        }
    }

    private boolean fileExists(String path) {
        return new java.io.File(path).exists();
    }

    private boolean canExecute(String path) {
        java.io.File f = new java.io.File(path);
        return f.exists() && f.canExecute();
    }

    private boolean canRead(String path) {
        java.io.File f = new java.io.File(path);
        return f.exists() && f.canRead();
    }

    private boolean canWrite(String path) {
        java.io.File f = new java.io.File(path);
        return f.exists() && f.canWrite();
    }

    private String getProp(String name) {
        return execSimple("getprop " + name);
    }
}
