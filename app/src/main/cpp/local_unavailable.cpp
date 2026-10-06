#include <jni.h>
extern "C" int harness_local_unavailable() { return 1; }

extern "C" JNIEXPORT void JNICALL Java_com_androidharness_app_local_LocalNative_evictSession(JNIEnv *, jobject) {}
