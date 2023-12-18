#include "co_elastic_jvmti_JvmtiAccessImpl.h"
#include "ElasticJvmtiAgent.h"

using elastic::jvmti_agent::ReturnCode;
using elastic::jvmti_agent::toJint;
using elastic::raiseExceptionAndReturn;


JNIEXPORT jint JNICALL Java_co_elastic_jvmti_JvmtiAccessImpl_init0(JNIEnv* env, jclass) {
    return toJint(elastic::jvmti_agent::init(env));
}

JNIEXPORT jint JNICALL Java_co_elastic_jvmti_JvmtiAccessImpl_destroy0(JNIEnv* env, jclass) {
    return toJint(elastic::jvmti_agent::destroy(env));
}

JNIEXPORT jstring JNICALL Java_co_elastic_jvmti_JvmtiAccessImpl_checkVirtualThreadMountEventSupport0(JNIEnv* env, jclass) {
    return elastic::jvmti_agent::checkVirtualThreadMountEventSupport(env);

}

JNIEXPORT jint JNICALL Java_co_elastic_jvmti_JvmtiAccessImpl_enableVirtualThreadMountEvents0(JNIEnv* env, jclass) {
    return toJint(elastic::jvmti_agent::setVirtualThreadMountCallbackEnabled(env, true));
}

JNIEXPORT jint JNICALL Java_co_elastic_jvmti_JvmtiAccessImpl_disableVirtualThreadMountEvents0(JNIEnv* env, jclass) {
    return toJint(elastic::jvmti_agent::setVirtualThreadMountCallbackEnabled(env, false));
}

