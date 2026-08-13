// Hand-written JNI facade for the C++ greeter.
//
// This file is the direct counterpart to the 581 lines of C that gobind
// GENERATES for the Go side (mobile_android.c + seq_android.c). Everything
// here — refcounting the callback, converting strings, attaching the worker
// thread to the JVM, translating an error into a thrown exception — is work
// gomobile does for you. That is the trade the two engines illustrate.

#include <jni.h>

#include <android/log.h>

#include <chrono>
#include <string>
#include <thread>
#include <vector>

#include "greeter.h"

#define LOG_TAG "greeter_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Cached at load time. A thread created by C++ has no JNIEnv of its own, so the
// only way to call back into Java from it is to attach via the JavaVM.
JavaVM* g_vm = nullptr;

// One shared instance, mirroring the single Mobile.newGreeter() the Go engine
// holds. The Greeter is stateless and const-qualified, so this is safe to use
// from several threads at once.
const greeter::Greeter& Instance() {
    static const greeter::Greeter instance;
    return instance;
}

std::string ToStdString(JNIEnv* env, jstring s) {
    if (s == nullptr) return "";
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

jbyteArray ToByteArray(JNIEnv* env, const std::string& s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr == nullptr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()),
                            reinterpret_cast<const jbyte*>(s.data()));
    return arr;
}

// The manual equivalent of go_seq_maybe_throw_exception().
void ThrowJavaException(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("java/lang/Exception");
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

// Callbacks can leave a pending Java exception. Left unchecked, the next JNI
// call aborts the process — a failure mode gobind's generated code handles and
// a hand-written bridge has to remember.
void ClearPendingException(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_sh_locus_goandroid_CppEngine_nativeVersion(JNIEnv* env, jclass /*clazz*/) {
    const std::string v = "cppcore 0.1.0 (" + greeter::RuntimeVersion() + ")";
    return env->NewStringUTF(v.c_str());
}

JNIEXPORT jstring JNICALL
Java_sh_locus_goandroid_CppEngine_nativeLanguages(JNIEnv* env, jclass /*clazz*/) {
    // Same compromise as the Go facade: a comma-joined string rather than a
    // list, so both engines expose an identical Java signature.
    std::string joined;
    for (const auto& code : Instance().Languages()) {
        if (!joined.empty()) joined += ",";
        joined += code;
    }
    return env->NewStringUTF(joined.c_str());
}

JNIEXPORT jbyteArray JNICALL
Java_sh_locus_goandroid_CppEngine_nativeGreetJSON(JNIEnv* env,
                                                 jclass /*clazz*/,
                                                 jstring name,
                                                 jstring lang) {
    greeter::Greeting greeting;
    std::string err;

    if (!Instance().Greet(ToStdString(env, name), ToStdString(env, lang), &greeting, &err)) {
        ThrowJavaException(env, err);
        return nullptr;
    }

    // Returning bytes rather than a String sidesteps JNI's modified-UTF-8
    // encoding entirely — the JSON travels as raw UTF-8, same as Go's []byte.
    return ToByteArray(env, greeter::ToJSON(greeting));
}

JNIEXPORT jbyteArray JNICALL
Java_sh_locus_goandroid_CppEngine_nativeBenchmarkJSON(JNIEnv* env,
                                                     jclass /*clazz*/,
                                                     jlong iterations) {
    return ToByteArray(env, greeter::RunBenchmarkJSON(static_cast<std::int64_t>(iterations)));
}

JNIEXPORT void JNICALL
Java_sh_locus_goandroid_CppEngine_nativeGreetAllAsync(JNIEnv* env,
                                                      jclass /*clazz*/,
                                                      jstring name,
                                                      jobject callback) {
    // The local ref dies when this function returns, so promote it to a global
    // ref for the worker thread. Go's equivalent is Seq's refnum table.
    jobject cb = env->NewGlobalRef(callback);
    const std::string owned_name = ToStdString(env, name);

    std::thread([cb, owned_name]() {
        JNIEnv* tenv = nullptr;
        if (g_vm->AttachCurrentThread(&tenv, nullptr) != JNI_OK) {
            LOGE("AttachCurrentThread failed; leaking one global ref");
            return;
        }

        jclass cls = tenv->GetObjectClass(cb);
        const jmethodID on_progress = tenv->GetMethodID(cls, "onProgress", "(I)V");
        const jmethodID on_complete = tenv->GetMethodID(cls, "onComplete", "(Ljava/lang/String;)V");
        const jmethodID on_error = tenv->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");

        const auto langs = Instance().Languages();
        std::vector<greeter::Greeting> results;
        results.reserve(langs.size());

        for (size_t i = 0; i < langs.size(); ++i) {
            greeter::Greeting g;
            std::string err;
            if (!Instance().Greet(owned_name, langs[i], &g, &err)) {
                jstring jerr = tenv->NewStringUTF(err.c_str());
                tenv->CallVoidMethod(cb, on_error, jerr);
                ClearPendingException(tenv);
                tenv->DeleteLocalRef(jerr);
                tenv->DeleteGlobalRef(cb);
                g_vm->DetachCurrentThread();
                return;
            }
            results.push_back(g);

            // Same stand-in delay as the Go engine, so the progress bar behaves
            // identically and the timings are comparable.
            std::this_thread::sleep_for(std::chrono::milliseconds(250));

            const int percent = static_cast<int>(
                static_cast<double>(i + 1) / static_cast<double>(langs.size()) * 100.0);
            tenv->CallVoidMethod(cb, on_progress, static_cast<jint>(percent));
            ClearPendingException(tenv);
        }

        jstring payload = tenv->NewStringUTF(greeter::ToJSONArray(results).c_str());
        tenv->CallVoidMethod(cb, on_complete, payload);
        ClearPendingException(tenv);
        tenv->DeleteLocalRef(payload);

        // Both of these are mandatory. Forgetting DetachCurrentThread leaks a
        // JVM thread structure; forgetting DeleteGlobalRef pins the callback
        // object — and its Activity — forever.
        tenv->DeleteGlobalRef(cb);
        g_vm->DetachCurrentThread();
    }).detach();
}

}  // extern "C"
