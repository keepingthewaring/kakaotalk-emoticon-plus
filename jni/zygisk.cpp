#include "zygisk.hpp"

#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

namespace {

constexpr char kTargetPackage[] = "com.kakao.talk";
constexpr char kEntryClass[] = "com.emoplus.kakao.Entry";


extern "C" {
extern const char _emo_dex_start[];
extern const char _emo_dex_end[];
extern const char _emo_pine_start[];
extern const char _emo_pine_end[];
}

JavaVM* g_vm = nullptr;
bool g_target = false;
std::string g_flagPath;

bool ModuleMarkedOff() {
    struct stat st {};
    return stat("/data/adb/modules/kakaotalk_emoplus/disable", &st) == 0 ||
           stat("/data/adb/modules/kakaotalk_emoplus/remove", &st) == 0;
}

bool IsTargetProcess(JNIEnv* env, jstring name) {
    if (!env || !name) return false;
    const char* utf = env->GetStringUTFChars(name, nullptr);
    if (!utf) return false;
    const bool match = strcmp(utf, kTargetPackage) == 0;
    env->ReleaseStringUTFChars(name, utf);
    return match;
}

std::string JStringToStd(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* utf = env->GetStringUTFChars(s, nullptr);
    std::string out = utf ? utf : "";
    if (utf) env->ReleaseStringUTFChars(s, utf);
    return out;
}

bool Mkdirs(const std::string& path) {
    std::string cur;
    for (size_t i = 1; i <= path.size(); ++i) {
        if (i != path.size() && path[i] != '/') continue;
        cur = path.substr(0, i);
        if (cur.empty()) continue;
        if (mkdir(cur.c_str(), 0700) != 0 && errno != EEXIST) return false;
    }
    return true;
}

std::string WritePineLib(JNIEnv* env, jobject app) {
    jclass fileCls = env->FindClass("java/io/File");
    if (!fileCls) return {};
    jmethodID getFilesDir = env->GetMethodID(
        env->GetObjectClass(app), "getFilesDir", "()Ljava/io/File;");
    jmethodID getPath = env->GetMethodID(fileCls, "getAbsolutePath", "()Ljava/lang/String;");
    if (!getFilesDir || !getPath) { env->ExceptionClear(); return {}; }
    jobject filesDir = env->CallObjectMethod(app, getFilesDir);
    if (env->ExceptionCheck() || !filesDir) { env->ExceptionClear(); return {}; }
    std::string base = JStringToStd(env, (jstring) env->CallObjectMethod(filesDir, getPath));
    env->ExceptionClear();
    if (base.empty()) return {};

    std::string dir = base + "/emoplus";
    std::string path = dir + "/libpine.so";
    const size_t size = (size_t)(_emo_pine_end - _emo_pine_start);
    if (!Mkdirs(dir)) { return {}; }

    struct stat st {};
    bool needWrite = stat(path.c_str(), &st) != 0 || (size_t) st.st_size != size;
    if (needWrite) {
        std::string tmp = path + ".tmp";
        FILE* f = fopen(tmp.c_str(), "wb");
        if (!f) { return {}; }
        size_t w = fwrite(_emo_pine_start, 1, size, f);
        fclose(f);
        if (w != size) { unlink(tmp.c_str()); return {}; }
        if (rename(tmp.c_str(), path.c_str()) != 0) { return {}; }
    }
    return path;
}

jobject WaitForApplication(JNIEnv* env) {
    jclass atCls = env->FindClass("android/app/ActivityThread");
    if (!atCls) { env->ExceptionClear(); return nullptr; }
    jmethodID curApp = env->GetStaticMethodID(
        atCls, "currentApplication", "()Landroid/app/Application;");
    if (!curApp) env->ExceptionClear();

    jmethodID curAt = nullptr, getApp = nullptr;
    if (!curApp) {
        curAt = env->GetStaticMethodID(
            atCls, "currentActivityThread", "()Landroid/app/ActivityThread;");
        if (curAt)
            getApp = env->GetMethodID(atCls, "getApplication", "()Landroid/app/Application;");
        env->ExceptionClear();
        if (!curAt || !getApp) { return nullptr; }
    }

    for (int i = 0; i < 600; ++i) {
        jobject app = nullptr;
        if (curApp) {
            app = env->CallStaticObjectMethod(atCls, curApp);
        } else {
            jobject at = env->CallStaticObjectMethod(atCls, curAt);
            if (at) { app = env->CallObjectMethod(at, getApp); env->DeleteLocalRef(at); }
        }
        if (env->ExceptionCheck()) { env->ExceptionClear(); }
        if (app) return app;
        usleep(100 * 1000);
    }
    return nullptr;
}

void Inject(JNIEnv* env, jobject app) {

    std::string pinePath = WritePineLib(env, app);
    if (pinePath.empty()) { return; }

    jobject dexBuf = env->NewDirectByteBuffer(
        (void*) _emo_dex_start,
        (jlong) (_emo_dex_end - _emo_dex_start));
    if (!dexBuf) { return; }
    jobject dexBufRef = env->NewGlobalRef(dexBuf);

    jclass imclCls = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (!imclCls) { env->ExceptionClear(); return; }
    jmethodID imclCtor = env->GetMethodID(
        imclCls, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    jobject loader = imclCtor
        ? env->NewObject(imclCls, imclCtor, dexBuf, nullptr)
        : nullptr;
    if (env->ExceptionCheck() || !loader) {
        env->ExceptionClear();
        return;
    }

    jclass clCls = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(
        clCls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring entryName = env->NewStringUTF(kEntryClass);
    jobject entryCls = loadClass
        ? env->CallObjectMethod(loader, loadClass, entryName)
        : nullptr;
    if (env->ExceptionCheck() || !entryCls) {
        env->ExceptionClear();
        return;
    }
    jmethodID init = env->GetStaticMethodID(
        (jclass) entryCls, "init", "(Landroid/content/Context;Ljava/lang/String;)V");
    if (!init) { env->ExceptionClear(); return; }

    env->CallStaticVoidMethod((jclass) entryCls, init, app, env->NewStringUTF(pinePath.c_str()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    (void) dexBufRef;
}

void* WorkerMain(void*) {
    JNIEnv* env = nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) {
        return nullptr;
    }
    jobject app = WaitForApplication(env);
    if (app) Inject(env, app);
    g_vm->DetachCurrentThread();
    return nullptr;
}

}

class EmoPlusModule final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
        env->GetJavaVM(&g_vm);
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs* args) override {
        g_target = args && IsTargetProcess(env_, args->nice_name);
        if (g_target && ModuleMarkedOff()) {
            g_target = false;
            return;
        }
        if (g_target && args->app_data_dir) {
            g_flagPath = JStringToStd(env_, args->app_data_dir) +
                "/files/emoplus/disabled";
        }
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs*) override {
        if (!g_target || !g_vm) return;

        if (!g_flagPath.empty() && access(g_flagPath.c_str(), F_OK) == 0) return;
        pthread_t tid;
        if (pthread_create(&tid, nullptr, WorkerMain, nullptr) == 0) {
            pthread_detach(tid);
        } else {
        }
    }

private:
    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
};

REGISTER_ZYGISK_MODULE(EmoPlusModule)
