#include <jni.h>
#include <string>
#include <cstdlib>
#include <cstring>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include "node.h"

// تحويل stdout/stderr الخاصة بـ Node إلى logcat لتسهيل التشخيص
static int pfd[2];
static pthread_t thr;
static const char *tag = "NODEJS-MOBILE";

static void *thread_func(void *) {
    ssize_t rdsz;
    char buf[2048];
    while ((rdsz = read(pfd[0], buf, sizeof buf - 1)) > 0) {
        if (buf[rdsz - 1] == '\n') --rdsz;
        buf[rdsz] = 0;
        __android_log_write(ANDROID_LOG_DEBUG, tag, buf);
    }
    return nullptr;
}

static int start_redirecting_stdout_stderr() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    if (pipe(pfd) != 0) return -1;
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);
    if (pthread_create(&thr, nullptr, thread_func, nullptr) != 0) return -1;
    pthread_detach(thr);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_wadl_NodeBridge_startNodeWithArguments(JNIEnv *env, jobject /* this */,
                                                        jobjectArray arguments) {
    jsize argument_count = env->GetArrayLength(arguments);

    // libuv يتطلب أن تكون الوسائط في ذاكرة متجاورة
    int c_arguments_size = 0;
    for (int i = 0; i < argument_count; i++) {
        auto s = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *c = env->GetStringUTFChars(s, nullptr);
        c_arguments_size += strlen(c) + 1;
        env->ReleaseStringUTFChars(s, c);
    }
    char *args_buffer = (char *) calloc(c_arguments_size, sizeof(char));
    char *argv[argument_count];
    char *current = args_buffer;
    for (int i = 0; i < argument_count; i++) {
        auto s = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *c = env->GetStringUTFChars(s, nullptr);
        strncpy(current, c, strlen(c));
        argv[i] = current;
        current += strlen(current) + 1;
        env->ReleaseStringUTFChars(s, c);
    }

    start_redirecting_stdout_stderr();
    return jint(node::Start(argument_count, argv));
}
