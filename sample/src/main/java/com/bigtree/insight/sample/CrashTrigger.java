package com.bigtree.insight.sample;

public final class CrashTrigger {
    static {
        System.loadLibrary("sample-crash");
    }

    private CrashTrigger() {
    }

    public static native void sigsegv();
}
