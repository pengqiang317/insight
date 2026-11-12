#include <jni.h>

JNIEXPORT void JNICALL
Java_com_bigtree_insight_sample_CrashTrigger_sigsegv(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    *(volatile int *) 0 = 1;
}
