package com.sally.cpplauncher;

public final class LauncherNative {
    static {
        System.loadLibrary("cpplauncher");
    }

    private LauncherNative() {}

    /**
     * @return 0 jika JVM mulai di background, negatif jika gagal.
     *  -1 dlopen gagal, -2 JNI_CreateJavaVM tidak ada, -3 game sudah jalan.
     */
    public static native int launch(String jvmLib,
                                    String gameDir,
                                    String mainClass,
                                    String classpath,
                                    String[] jvmArgs,
                                    String[] gameArgs);
}
