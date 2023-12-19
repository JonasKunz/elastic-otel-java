#include "co_elastic_otel_JvmtiAccessImpl.h"
#include "ElasticJvmtiAgent.h"
#include <array>

using elastic::jvmti_agent::ReturnCode;
using elastic::jvmti_agent::toJint;

JNIEXPORT jint JNICALL Java_co_elastic_otel_JvmtiAccessImpl_init0(JNIEnv* env, jclass) {
    return toJint(elastic::jvmti_agent::init(env));
}

JNIEXPORT jint JNICALL Java_co_elastic_otel_JvmtiAccessImpl_destroy0(JNIEnv* env, jclass) {
    return toJint(elastic::jvmti_agent::destroy(env));
}

JNIEXPORT void JNICALL Java_co_elastic_otel_JvmtiAccessImpl_setThreadProfilingCorrelationBuffer0(JNIEnv* env, jclass, jobject bytebuffer) {
    elastic::jvmti_agent::setThreadProfilingCorrelationBuffer(env, bytebuffer);
}

JNIEXPORT void JNICALL Java_co_elastic_otel_JvmtiAccessImpl_setProcessProfilingCorrelationBuffer0(JNIEnv* env, jclass, jobject bytebuffer) {
    elastic::jvmti_agent::setProcessProfilingCorrelationBuffer(env, bytebuffer);
}

JNIEXPORT jobject JNICALL Java_co_elastic_otel_JvmtiAccessImpl_createThreadProfilingCorrelationBufferAlias(JNIEnv* env, jclass, jlong capacity) {
    return elastic::jvmti_agent::createThreadProfilingCorrelationBufferAlias(env, capacity);
}

JNIEXPORT jobject JNICALL Java_co_elastic_otel_JvmtiAccessImpl_createProcessProfilingCorrelationBufferAlias(JNIEnv* env, jclass, jlong capacity) {
    return elastic::jvmti_agent::createProcessProfilingCorrelationBufferAlias(env, capacity);
}

JNIEXPORT jint JNICALL Java_co_elastic_otel_JvmtiAccessImpl_startProfilerReturnChannelSocket0(JNIEnv* env, jclass, jstring filename) {
    return toJint(elastic::jvmti_agent::createProfilerSocket(env, filename));
}

JNIEXPORT jint JNICALL Java_co_elastic_otel_JvmtiAccessImpl_stopProfilerReturnChannelSocket0(JNIEnv* env, jclass) {
    return toJint(elastic::jvmti_agent::closeProfilerSocket(env));
}

JNIEXPORT jint JNICALL Java_co_elastic_otel_JvmtiAccessImpl_readProfilerReturnChannelSocketMessage0(JNIEnv* env, jclass, jobject byteBuffer) {
    return elastic::jvmti_agent::readProfilerSocketMessage(env, byteBuffer);
}

JNIEXPORT jint JNICALL Java_co_elastic_otel_JvmtiAccessImpl_sendToProfilerReturnChannelSocket0(JNIEnv* env, jclass, jbyteArray message) {
    return toJint(elastic::jvmti_agent::writeProfilerSocketMessage(env, message));
}

JNIEXPORT jint JNICALL Java_co_elastic_otel_JvmtiAccessImpl_getStackTrace0(JNIEnv * env, jclass, jint skipFrames, jint maxFrames, jlongArray resultBuffer) {
    jint numFramesCollected;
    auto resultCode = elastic::jvmti_agent::getStackTrace(env, skipFrames,maxFrames, resultBuffer, numFramesCollected);
    if(resultCode != elastic::jvmti_agent::ReturnCode::SUCCESS) {
        return toJint(resultCode);
    } else {
        return numFramesCollected;
    }
}


JNIEXPORT jclass JNICALL Java_co_elastic_otel_JvmtiAccessImpl_getDeclaringClass0(JNIEnv * env, jclass, jlong methodId) {
    return elastic::jvmti_agent::getDeclaringClass(env, methodId);
}

JNIEXPORT jstring JNICALL Java_co_elastic_otel_JvmtiAccessImpl_getMethodName0(JNIEnv * env, jclass, jlong methodId, jboolean appendSignature) {
    return elastic::jvmti_agent::getMethodName(env, methodId, appendSignature);
}

