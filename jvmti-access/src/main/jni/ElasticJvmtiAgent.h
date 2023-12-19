#ifndef ELASTICJVMTIAGENT_H_
#define ELASTICJVMTIAGENT_H_

#include <jvmti.h>
#include <sstream>

namespace elastic {
    namespace jvmti_agent {

        enum class ReturnCode {
            SUCCESS = 0,
            ERROR_NOT_INITIALIZED = -1,
            ERROR = -2,
        };

        constexpr jint toJint(ReturnCode rc) noexcept {
            return static_cast<jint>(rc);
        }

        ReturnCode init(JNIEnv* jniEnv);
        ReturnCode destroy(JNIEnv* jniEnv);

        // when fetching a stacktrace with less or equal this number of frames, no malloc will be performed
        // instead only stack memory will be used
        const jint MAX_ALLOCATION_FREE_FRAMES = 32;
        ReturnCode getStackTrace(JNIEnv* jniEnv, jint skipFrames, jint maxCollectFrames, jlongArray resultBuffer, jint& resultNumFrames);
        jclass getDeclaringClass(JNIEnv* jniEnv, jlong methodId);
        jstring getMethodName(JNIEnv* jniEnv, jlong methodId, bool appendSignature);

        jstring checkVirtualThreadMountEventSupport(JNIEnv* jniEnv);
        ReturnCode setVirtualThreadMountCallbackEnabled(JNIEnv* jniEnv, jboolean enable);

    }

    template< class... Args >
    void raiseExceptionType(JNIEnv* env, const char* exceptionClass, Args&&... messageParts) {
        jclass clazz = env->FindClass(exceptionClass);
        if(clazz != NULL) {
            std::stringstream fmt;
            ([&]{ fmt << messageParts; }(), ...);
            env->ThrowNew(clazz, fmt.str().c_str());
        }
    }

    template< class... Args >
    jstring formatJString(JNIEnv* env,  Args&&... messageParts) {
        std::stringstream fmt;
        ([&]{ fmt << messageParts; }(), ...);
        return env->NewStringUTF(fmt.str().c_str());
    }


    template< class... Args >
    void raiseException(JNIEnv* env, Args&&... messageParts) {
        return raiseExceptionType(env, "java/lang/RuntimeException", messageParts...);
    }    

    template<typename Ret, class... Args >
    [[nodiscard]] Ret raiseExceptionAndReturn(JNIEnv* env, Ret retVal, Args&&... messageParts) {
        raiseExceptionType(env, "java/lang/RuntimeException", messageParts...);
        return retVal;
    }  
}

#endif
