package com.bigtree.insight;

public final class NativeBridge {
    static volatile boolean loaded;

    private NativeBridge() {
    }

    public static void install(String dir, String abi) {
        if (!loaded) {
            System.loadLibrary("insight-native");
            loaded = true;
        }
        installNative(dir, abi);
    }

    public static void markJavaCrash() {
        if (loaded) {
            markJavaCrashNative();
        }
    }

    private static native void installNative(String dir, String abi);

    private static native void markJavaCrashNative();
}
