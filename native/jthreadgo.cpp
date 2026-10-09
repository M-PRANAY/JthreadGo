#include <jni.h>
#include <jvmti.h>
#include "org_jthreadgo_JthreadGo.h"

#include <atomic>
#include <cstdio>

namespace {

std::atomic<jvmtiEnv*> agent_environment{nullptr};

// GetErrorName allocates with JVMTI; release its buffer with the same API.
void describe_error(jvmtiEnv* environment, jvmtiError error,
                    const char* operation, char* buffer, size_t capacity) {
    char* error_name = nullptr;
    const jvmtiError lookup = environment->GetErrorName(error, &error_name);
    std::snprintf(buffer, capacity, "%s failed: %s (%d)", operation,
                  lookup == JVMTI_ERROR_NONE && error_name != nullptr
                      ? error_name
                      : "unknown JVMTI error",
                  static_cast<int>(error));
    if (error_name != nullptr) {
        environment->Deallocate(reinterpret_cast<unsigned char*>(error_name));
    }
}

void throw_java(JNIEnv* jni, const char* class_name, const char* message) {
    if (jni->ExceptionCheck()) {
        return;
    }
    jclass exception_class = jni->FindClass(class_name);
    if (exception_class == nullptr) {
        return; // FindClass has already raised the failure.
    }
    jni->ThrowNew(exception_class, message);
    jni->DeleteLocalRef(exception_class);
}

void throw_jvmti_error(JNIEnv* jni, jvmtiEnv* environment, jvmtiError error) {
    const char* exception_class = "java/lang/IllegalStateException";
    switch (error) {
        case JVMTI_ERROR_INVALID_THREAD:
        case JVMTI_ERROR_INVALID_OBJECT:
        case JVMTI_ERROR_ILLEGAL_ARGUMENT:
            exception_class = "java/lang/IllegalArgumentException";
            break;
        case JVMTI_ERROR_NULL_POINTER:
            exception_class = "java/lang/NullPointerException";
            break;
        case JVMTI_ERROR_OUT_OF_MEMORY:
            exception_class = "java/lang/OutOfMemoryError";
            break;
        case JVMTI_ERROR_MUST_POSSESS_CAPABILITY:
        case JVMTI_ERROR_NOT_AVAILABLE:
        case JVMTI_ERROR_UNSUPPORTED_OPERATION:
        case JVMTI_ERROR_OPAQUE_FRAME:
            exception_class = "java/lang/UnsupportedOperationException";
            break;
        default:
            break;
    }
    char message[384];
    describe_error(environment, error, "JVMTI StopThread", message,
                   sizeof(message));
    throw_java(jni, exception_class, message);
}

jint startup_failure(jvmtiEnv* environment, const char* operation,
                     jvmtiError error) {
    char message[384];
    describe_error(environment, error, operation, message, sizeof(message));
    std::fprintf(stderr, "[jthreadgo] %s\n", message);
    environment->DisposeEnvironment();
    return JNI_ERR;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char*, void*) {
    jvmtiEnv* environment = nullptr;
    const jint status = vm->GetEnv(reinterpret_cast<void**>(&environment),
                                   JVMTI_VERSION_1_2);
    if (status != JNI_OK || environment == nullptr) {
        std::fprintf(stderr,
                     "[jthreadgo] Cannot obtain JVMTI 1.2 environment "
                     "(JNI status %d).\n", static_cast<int>(status));
        return JNI_ERR;
    }

    jvmtiCapabilities potential{};
    jvmtiError error = environment->GetPotentialCapabilities(&potential);
    if (error != JVMTI_ERROR_NONE) {
        return startup_failure(environment, "GetPotentialCapabilities", error);
    }
    if (!potential.can_signal_thread) {
        std::fprintf(stderr,
                     "[jthreadgo] This JVM does not offer the "
                     "can_signal_thread capability at startup.\n");
        environment->DisposeEnvironment();
        return JNI_ERR;
    }

    jvmtiCapabilities requested{};
    requested.can_signal_thread = 1;
    error = environment->AddCapabilities(&requested);
    if (error != JVMTI_ERROR_NONE) {
        return startup_failure(environment, "AddCapabilities", error);
    }

    jvmtiCapabilities acquired{};
    error = environment->GetCapabilities(&acquired);
    if (error != JVMTI_ERROR_NONE) {
        return startup_failure(environment, "GetCapabilities", error);
    }
    if (!acquired.can_signal_thread) {
        std::fprintf(stderr,
                     "[jthreadgo] JVM did not grant can_signal_thread.\n");
        environment->DisposeEnvironment();
        return JNI_ERR;
    }

    agent_environment.store(environment, std::memory_order_release);
    return JNI_OK;
}

extern "C" JNIEXPORT void JNICALL Agent_OnUnload(JavaVM*) {
    agent_environment.store(nullptr, std::memory_order_release);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_jthreadgo_JthreadGo_isAgentLoaded0(JNIEnv*, jclass) {
    return agent_environment.load(std::memory_order_acquire) != nullptr
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_jthreadgo_JthreadGo_requestStop0(JNIEnv* jni, jclass,
                                           jobject target, jthrowable signal) {
    if (target == nullptr || signal == nullptr) {
        throw_java(jni, "java/lang/NullPointerException",
                   target == nullptr ? "target thread is null"
                                     : "stop signal is null");
        return;
    }
    jvmtiEnv* environment =
        agent_environment.load(std::memory_order_acquire);
    if (environment == nullptr) {
        throw_java(jni, "java/lang/IllegalStateException",
                   "JthreadGo agent is not loaded. Start this JVM with "
                   "-agentpath:<absolute path to the JthreadGo native library>.");
        return;
    }

    // The JVM delivers the exception; this does not terminate an OS thread.
    // Successful return reports acceptance, not that the target has exited.
    const jvmtiError error = environment->StopThread(target, signal);
    if (error != JVMTI_ERROR_NONE && error != JVMTI_ERROR_THREAD_NOT_ALIVE) {
        throw_jvmti_error(jni, environment, error);
    }
}
