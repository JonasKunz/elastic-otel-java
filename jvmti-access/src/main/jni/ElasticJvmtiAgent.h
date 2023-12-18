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