// Launcher native: memuat libjvm.so dari JRE Android, membuat JVM,
// lalu memanggil main() milik Minecraft di thread terpisah.
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>

#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#define TAG "CppLauncher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using CreateVM_t = jint (*)(JavaVM **, void **, void *);

// JVM hanya bisa dibuat sekali per proses, jadi satu game aktif per proses.
static std::atomic<bool> g_running{false};
static std::once_flag g_stdoutFlag;

struct LaunchConfig {
    CreateVM_t createVM = nullptr;
    std::string gameDir;
    std::string mainClass;   // format titik, contoh net.minecraft.Main
    std::string classpath;
    std::vector<std::string> jvmArgs;
    std::vector<std::string> gameArgs;
};

static std::string jstringToStd(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

static std::vector<std::string> jarrayToVector(JNIEnv *env, jobjectArray arr) {
    std::vector<std::string> out;
    if (!arr) return out;
    jsize n = env->GetArrayLength(arr);
    out.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        out.push_back(jstringToStd(env, s));
        env->DeleteLocalRef(s);
    }
    return out;
}

// Alihkan stdout/stderr ke pipe agar output JVM dan game muncul di logcat.
static void setupStdoutForwarder() {
    std::call_once(g_stdoutFlag, [] {
        int fds[2];
        if (pipe(fds) != 0) return;
        dup2(fds[1], STDOUT_FILENO);
        dup2(fds[1], STDERR_FILENO);
        std::thread([rd = fds[0]] {
            char buf[1024];
            std::string line;
            ssize_t n;
            while ((n = read(rd, buf, sizeof buf)) > 0) {
                for (ssize_t i = 0; i < n; ++i) {
                    if (buf[i] == '\n') {
                        __android_log_print(ANDROID_LOG_INFO, "MCGame", "%s", line.c_str());
                        line.clear();
                    } else {
                        line += buf[i];
                    }
                }
            }
        }).detach();
    });
}

static void reportException(JNIEnv *env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

static void callMain(JNIEnv *env, const LaunchConfig &cfg) {
    std::string slashed = cfg.mainClass;
    for (auto &c : slashed) {
        if (c == '.') c = '/';
    }

    jclass cls = env->FindClass(slashed.c_str());
    if (!cls) {
        LOGE("Main class tidak ditemukan: %s", cfg.mainClass.c_str());
        reportException(env);
        return;
    }

    jmethodID mid = env->GetStaticMethodID(cls, "main", "([Ljava/lang/String;)V");
    if (!mid) {
        LOGE("Method main(String[]) tidak ada di %s", cfg.mainClass.c_str());
        reportException(env);
        return;
    }

    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray args = env->NewObjectArray(static_cast<jsize>(cfg.gameArgs.size()), strCls, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(cfg.gameArgs.size()); ++i) {
        jstring js = env->NewStringUTF(cfg.gameArgs[i].c_str());
        env->SetObjectArrayElement(args, i, js);
        env->DeleteLocalRef(js);
    }

    LOGI("Memanggil %s.main() dengan %zu argumen", cfg.mainClass.c_str(), cfg.gameArgs.size());
    env->CallStaticVoidMethod(cls, mid, args);
    reportException(env);
}

static void runGame(LaunchConfig cfg) {
    std::vector<std::string> opts = {
        "-Djava.class.path=" + cfg.classpath,
        "-Duser.home=" + cfg.gameDir,
        "-Djava.library.path=" + cfg.gameDir + "/natives",
    };
    opts.insert(opts.end(), cfg.jvmArgs.begin(), cfg.jvmArgs.end());

    std::vector<JavaVMOption> vmOpts(opts.size());
    for (size_t i = 0; i < opts.size(); ++i) {
        vmOpts[i].optionString = const_cast<char *>(opts[i].c_str());
        vmOpts[i].extraInfo = nullptr;
    }

    JavaVMInitArgs vmArgs{};
    vmArgs.version = JNI_VERSION_1_8;
    vmArgs.nOptions = static_cast<jint>(vmOpts.size());
    vmArgs.options = vmOpts.data();
    vmArgs.ignoreUnrecognized = JNI_TRUE;

    JavaVM *vm = nullptr;
    JNIEnv *env = nullptr;
    jint rc = cfg.createVM(&vm, reinterpret_cast<void **>(&env), &vmArgs);
    if (rc != JNI_OK || !env) {
        LOGE("JNI_CreateJavaVM gagal, kode %d", rc);
        g_running = false;
        return;
    }

    LOGI("JVM berhasil dibuat");
    callMain(env, cfg);
    LOGI("main() selesai");

    // Jangan DestroyJavaVM: proses game biasanya keluar lewat System.exit.
    g_running = false;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sally_cpplauncher_LauncherNative_launch(JNIEnv *env, jclass,
                                                 jstring jvmLib,
                                                 jstring gameDir,
                                                 jstring mainClass,
                                                 jstring classpath,
                                                 jobjectArray jvmArgs,
                                                 jobjectArray gameArgs) {
    if (g_running.exchange(true)) {
        LOGE("Game sudah berjalan di proses ini");
        return -3;
    }

    std::string lib = jstringToStd(env, jvmLib);
    void *handle = dlopen(lib.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        LOGE("dlopen gagal: %s", dlerror());
        g_running = false;
        return -1;
    }

    auto create = reinterpret_cast<CreateVM_t>(dlsym(handle, "JNI_CreateJavaVM"));
    if (!create) {
        LOGE("Symbol JNI_CreateJavaVM tidak ditemukan di %s", lib.c_str());
        g_running = false;
        return -2;
    }

    LaunchConfig cfg;
    cfg.createVM = create;
    cfg.gameDir = jstringToStd(env, gameDir);
    cfg.mainClass = jstringToStd(env, mainClass);
    cfg.classpath = jstringToStd(env, classpath);
    cfg.jvmArgs = jarrayToVector(env, jvmArgs);
    cfg.gameArgs = jarrayToVector(env, gameArgs);

    setupStdoutForwarder();
    std::thread(runGame, std::move(cfg)).detach();
    return 0;
}
