#include "ElasticJvmtiAgent.h"
#include "ProfilerSocket.h"
#include <array>
#include <cstring>
#include <memory>

// These two global variables have symbol names which will be recognized by
// the elastic universal profiling host-agent. The host-agent will be able
// to read their values and the memory they point to
JNIEXPORT thread_local void* elastic_apm_profiling_correlation_tls_v1 = nullptr;
JNIEXPORT void* elastic_apm_profiling_correlation_process_storage_v1 = nullptr;

namespace elastic
{
    namespace jvmti_agent
    {
        namespace {

            template<typename T>
            jlong toJlong(T value) {
                static_assert(sizeof(T) <= sizeof(jlong));
                jlong result = 0;
                std::memcpy(&result, &value, sizeof(T));
                return result;
            }

            template<typename T>
            T fromJlong(jlong value) {
                static_assert(sizeof(T) <= sizeof(jlong));
                T result;
                std::memcpy(&result, &value, sizeof(T));
                return result;
            }
        }

        static ProfilerSocket profilerSocket;
        static jvmtiEnv* jvmti;


        ReturnCode init(JNIEnv* jniEnv) {
            if(jvmti != nullptr) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Elastic JVMTI Agent is already initialized!");
            }

            JavaVM* vm;
            auto vmError = jniEnv->GetJavaVM(&vm);
            if(vmError != JNI_OK) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jniEnv->GetJavaVM() failed, return code is ", vmError);
            }

            auto getEnvErr = vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2);
            if(getEnvErr != JNI_OK) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "JavaVM->GetEnv() failed, return code is ", getEnvErr);
            }
            return ReturnCode::SUCCESS;
        }

        ReturnCode destroy(JNIEnv* jniEnv) {
            if(jvmti != nullptr) {
                elastic_apm_profiling_correlation_process_storage_v1 = nullptr;
                profilerSocket.destroy();

                auto error = jvmti->DisposeEnvironment();
                jvmti = nullptr;
                if(error != JVMTI_ERROR_NONE) {
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jvmti->DisposeEnvironment() failed, return code is: ", error);
                }

                return ReturnCode::SUCCESS;
            } else {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR_NOT_INITIALIZED, "Elastic JVMTI Agent has not been initialized!");
            }
        }


        ReturnCode getStackTrace(JNIEnv* jniEnv, jint skipFrames, jint maxCollectFrames, jlongArray resultBuffer, jint& resultNumFrames) {
            static_assert(sizeof(jmethodID) == sizeof(jlong));
            static_assert(sizeof(jlocation) == sizeof(jlong));

            if(jvmti == nullptr) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR_NOT_INITIALIZED, "Elastic JVMTI Agent has not been initialized");
            }

            jvmtiFrameInfo* buffer;
            std::array<jvmtiFrameInfo, MAX_ALLOCATION_FREE_FRAMES> stackBuffer;
            std::unique_ptr<jvmtiFrameInfo[]> heapBuffer;
            if (maxCollectFrames <= MAX_ALLOCATION_FREE_FRAMES) {
                buffer = stackBuffer.data();
            } else {
                heapBuffer = std::make_unique<jvmtiFrameInfo[]>(maxCollectFrames);
                buffer = heapBuffer.get();
            }

            auto error = jvmti->GetStackTrace(NULL, skipFrames, maxCollectFrames, buffer, &resultNumFrames);
            if (error != JVMTI_ERROR_NONE) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jvmti->GetStackTrace() failed, return code is ", error);
            }

            jlong* resultPtr = static_cast<jlong*>(jniEnv->GetPrimitiveArrayCritical(resultBuffer, nullptr));
            if(resultPtr == nullptr) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Could not access result array buffer");
            }

            for (jint i=0; i<resultNumFrames; i++) {
                resultPtr[i] = toJlong(buffer[i].method);
            }

            jniEnv->ReleasePrimitiveArrayCritical(resultBuffer, resultPtr, 0);
            return ReturnCode::SUCCESS;
        }


        jclass getDeclaringClass(JNIEnv* jniEnv, jlong methodId) {
            if(jvmti == nullptr) {
                raiseException(jniEnv, "Elastic JVMTI Agent has not been initialized");
                return nullptr;
            }
            jclass returnValue;
            auto error = jvmti->GetMethodDeclaringClass(fromJlong<jmethodID>(methodId), &returnValue);
            if (error != JVMTI_ERROR_NONE) {
                return nullptr;
            }
            return returnValue;
        }

        jstring getMethodName(JNIEnv* jniEnv, jlong methodId, bool appendSignature) {
            if(jvmti == nullptr) {
                raiseException(jniEnv, "Elastic JVMTI Agent has not been initialized");
                return nullptr;
            }
            char* namePtr = nullptr;
            char* signaturePtr = nullptr;
            auto error = jvmti->GetMethodName(fromJlong<jmethodID>(methodId), &namePtr, appendSignature ? &signaturePtr : nullptr, nullptr);
            if (error != JVMTI_ERROR_NONE) {
                return nullptr;
            }

            std::array<char, 1024> stackBuffer;
            std::unique_ptr<char[]> heapBuffer;

            char* result;
            if (!appendSignature) {
                result = namePtr;
            } else {
                auto nameLen = std::strlen(namePtr);
                auto signatureLen = std::strlen(signaturePtr);
                auto minBufferLen = nameLen + signatureLen + 1; // +2 because of slash and zero termination
                if(minBufferLen <= stackBuffer.size()) {
                    result = stackBuffer.data();
                } else {
                    heapBuffer = std::make_unique<char[]>(minBufferLen);
                    result = heapBuffer.get();
                }
                std::memcpy(result, namePtr, nameLen);
                std::memcpy(result + nameLen, signaturePtr, signatureLen);
                result[nameLen + signatureLen] = 0;
            }

            jstring resultStr = jniEnv->NewStringUTF(result);

            jvmti->Deallocate(reinterpret_cast<unsigned char*>(namePtr));
            if(appendSignature) {
                jvmti->Deallocate(reinterpret_cast<unsigned char*>(signaturePtr));
            }

            return resultStr;
        }

        void setThreadProfilingCorrelationBuffer(JNIEnv* jniEnv, jobject bytebuffer) {
            if(bytebuffer == nullptr) {
                elastic_apm_profiling_correlation_tls_v1 = nullptr;
            } else {
                elastic_apm_profiling_correlation_tls_v1 = jniEnv->GetDirectBufferAddress(bytebuffer);
            }
        }

        void setProcessProfilingCorrelationBuffer(JNIEnv* jniEnv, jobject bytebuffer) {
            if(bytebuffer == nullptr) {
                elastic_apm_profiling_correlation_process_storage_v1 = nullptr;
            } else {
                elastic_apm_profiling_correlation_process_storage_v1 = jniEnv->GetDirectBufferAddress(bytebuffer);
            }
        }

        //ONLY FOR TESTING!
        jobject createThreadProfilingCorrelationBufferAlias(JNIEnv* jniEnv, jlong capacity) {
            if(elastic_apm_profiling_correlation_tls_v1 == nullptr) {
                return nullptr;
            } else {
                return jniEnv->NewDirectByteBuffer(elastic_apm_profiling_correlation_tls_v1, capacity);
            }
        }

        //ONLY FOR TESTING!
        jobject createProcessProfilingCorrelationBufferAlias(JNIEnv* jniEnv, jlong capacity) {
            if(elastic_apm_profiling_correlation_process_storage_v1 == nullptr) {
                return nullptr;
            } else {
                return jniEnv->NewDirectByteBuffer(elastic_apm_profiling_correlation_process_storage_v1, capacity);
            }
        }

        ReturnCode createProfilerSocket(JNIEnv* jniEnv, jstring filepath) {
            return profilerSocket.openSocket(jniEnv, filepath);
        }

        ReturnCode closeProfilerSocket(JNIEnv* jniEnv) {
            return profilerSocket.closeSocket(jniEnv);
        }

        jint readProfilerSocketMessage(JNIEnv* jniEnv, jobject outputBuffer) {
            return profilerSocket.readMessage(jniEnv, outputBuffer);
        }

        //ONLY FOR TESTING!
        ReturnCode writeProfilerSocketMessage(JNIEnv* jniEnv, jbyteArray message) {
            return profilerSocket.writeMessage(jniEnv, message);
        }

    } // namespace jvmti_agent
    
} // namespace elastic
