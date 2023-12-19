#include "ElasticJvmtiAgent.h"
#include <memory>
#include <cstring>
#include <array>
#include <mutex>
#include <sys/socket.h>
#include <sys/un.h>
#include <fcntl.h>
#include <unistd.h>
#include <iostream>
#include "MethodRef.h"

namespace elastic
{
    namespace jvmti_agent
    {

        static jvmtiEnv* jvmti;
        static bool virtualThreadsCapabilitySupported;        

        static GlobalMethodRef threadMountCallback;
        static GlobalMethodRef threadUnmountCallback;
        static bool threadMountCallbacksEnabled = false;        


        namespace {

            bool isExpectedMountEvent(jvmtiExtensionEventInfo& eventInfo) {
                if(strcmp(eventInfo.id, "com.sun.hotspot.events.VirtualThreadMount") != 0) {
                    return false;
                }
                if(eventInfo.param_count != 2) {
                    return false;
                }
                if(eventInfo.params[0].base_type != JVMTI_TYPE_JNIENV) {
                    return false;
                }
                if(eventInfo.params[0].kind != JVMTI_KIND_IN_PTR) {
                    return false;
                }
                if(eventInfo.params[0].null_ok) {
                    return false;
                }
                if(eventInfo.params[1].base_type != JVMTI_TYPE_JTHREAD) {
                    return false;
                }
                if(eventInfo.params[1].kind != JVMTI_KIND_IN) {
                    return false;
                }
                if(eventInfo.params[1].null_ok) {
                    return false;
                }
                return true;
            }

            bool isExpectedUnmountEvent(jvmtiExtensionEventInfo& eventInfo) {
                if(strcmp(eventInfo.id, "com.sun.hotspot.events.VirtualThreadUnmount") != 0) {
                    return false;
                }
                if(eventInfo.param_count != 2) {
                    return false;
                }
                if(eventInfo.params[0].base_type != JVMTI_TYPE_JNIENV) {
                    return false;
                }
                if(eventInfo.params[0].kind != JVMTI_KIND_IN_PTR) {
                    return false;
                }
                if(eventInfo.params[0].null_ok) {
                    return false;
                }
                if(eventInfo.params[1].base_type != JVMTI_TYPE_JTHREAD) {
                    return false;
                }
                if(eventInfo.params[1].kind != JVMTI_KIND_IN) {
                    return false;
                }
                if(eventInfo.params[1].null_ok) {
                    return false;
                }
                return true;
            }

            void JNICALL vtMountHandler(jvmtiEnv* jvmtiEnv, ...) {
                va_list args;
                va_start(args, jvmtiEnv);
                JNIEnv* jniEnv = va_arg(args, JNIEnv*);
                jthread argThread = va_arg(args, jthread);
                va_end(args);

                if(!threadMountCallback.isEmpty()) {
                    threadMountCallback.invokeStaticVoid(jniEnv, argThread);
                }
            }

            void JNICALL vtUnmountHandler(jvmtiEnv* jvmtiEnv, ...) {
                va_list args;
                va_start(args, jvmtiEnv);
                JNIEnv* jniEnv = va_arg(args, JNIEnv*);
                jthread argThread = va_arg(args, jthread);
                va_end(args);

                if(!threadUnmountCallback.isEmpty()) {
                    threadUnmountCallback.invokeStaticVoid(jniEnv, argThread);
                }
            }

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

        ReturnCode init(JNIEnv* jniEnv) {

            if(jvmti != nullptr) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Elastic JVMTI Agent is already initialized!");
            }

            threadMountCallback = GlobalMethodRef(jniEnv, "co/elastic/jvmti/JvmtiAccessImpl", true, "onThreadMount", "(Ljava/lang/Thread;)V");
            threadUnmountCallback = GlobalMethodRef(jniEnv, "co/elastic/jvmti/JvmtiAccessImpl", true, "onThreadUnmount", "(Ljava/lang/Thread;)V");
            if(threadMountCallback.isEmpty() || threadUnmountCallback.isEmpty()) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to lookup JVMTIAgentAccess callback methods ");
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


            jvmtiCapabilities supportedCapabilities;
            auto supErr =jvmti->GetPotentialCapabilities(&supportedCapabilities);
            if(supErr != JVMTI_ERROR_NONE) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to get JVMTI supported capability", supErr);
            }

            virtualThreadsCapabilitySupported = supportedCapabilities.can_support_virtual_threads != 0;
            if (virtualThreadsCapabilitySupported) {
                jvmtiCapabilities caps = {};
                caps.can_support_virtual_threads = 1;
                auto capErr = jvmti->AddCapabilities(&caps);
                if(capErr != JVMTI_ERROR_NONE) {
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to add virtual threads capability", capErr);
                }
            }

            return ReturnCode::SUCCESS;
        }

        ReturnCode destroy(JNIEnv* jniEnv) {
            if(jvmti != nullptr) {

                if(threadMountCallbacksEnabled ) {
                    auto ret = setVirtualThreadMountCallbackEnabled(jniEnv, false);
                    if(ret != ReturnCode::SUCCESS) {
                        return ret;
                    }
                }
                auto error = jvmti->DisposeEnvironment();
                jvmti = nullptr;
                if(error != JVMTI_ERROR_NONE) {
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "jvmti->DisposeEnvironment() failed, return code is: ", error);
                }

                threadMountCallback.clear(jniEnv);
                threadUnmountCallback.clear(jniEnv);

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


        jstring checkVirtualThreadMountEventSupport(JNIEnv* jniEnv) {
            if(jvmti == nullptr) {
                raiseException(jniEnv, "Elastic JVMTI Agent has not been initialized");
                return nullptr;
            }
            if(!virtualThreadsCapabilitySupported) {
                return formatJString(jniEnv, "JVMTI environment does not support virtual threads");
            }
            jint extensionCount;
            jvmtiExtensionEventInfo* extensionInfos;
            auto error = jvmti->GetExtensionEvents(&extensionCount, &extensionInfos);
            if (error != JVMTI_ERROR_NONE) {
                return formatJString(jniEnv, "Failed to get extension events, return code is ", error);
            }
            bool mountEventFound = false;
            bool unmountEventFound = false;
            for(int i=0; i<extensionCount; i++) {
                mountEventFound = mountEventFound || isExpectedMountEvent(extensionInfos[i]);
                unmountEventFound = unmountEventFound || isExpectedUnmountEvent(extensionInfos[i]);
            }
            jvmti->Deallocate((unsigned char*) extensionInfos);
            if(!mountEventFound) {
                return formatJString(jniEnv, "mount event not found");
            }
            if(!unmountEventFound) {
                return formatJString(jniEnv, "unmount event not found");
            }
            return nullptr;
        }

        ReturnCode setVirtualThreadMountCallbackEnabled(JNIEnv* jniEnv, jboolean enabled) {
            if(jvmti == nullptr) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Elastic JVMTI Agent has not been initialized");
            }
            if (!virtualThreadsCapabilitySupported) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "JVMTI environment does not support virtual threads");
            }
            jint mountEvIdx=-1;
            jint unmountEvIdx=-1;
            jint extensionCount;
            jvmtiExtensionEventInfo* extensionInfos;
            auto error = jvmti->GetExtensionEvents(&extensionCount, &extensionInfos);
            if (error != JVMTI_ERROR_NONE) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to get extension events, return code is ", error);
            }

            for(int i=0; i<extensionCount; i++) {
                if(isExpectedMountEvent(extensionInfos[i])) {
                    mountEvIdx = extensionInfos[i].extension_event_index;
                }
                if(isExpectedUnmountEvent(extensionInfos[i])) {
                    unmountEvIdx = extensionInfos[i].extension_event_index;
                }
            }
            jvmti->Deallocate((unsigned char*) extensionInfos);
            if(mountEvIdx == -1) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Mount event not found");
            }
            if(unmountEvIdx == -1) {
                return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Unmount event not found");
            }
            if (enabled) {
                error = jvmti->SetExtensionEventCallback(mountEvIdx, (jvmtiExtensionEvent) &vtMountHandler);
                if (error != JVMTI_ERROR_NONE) {
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to to set mount event handler, error code is  ", error);
                }
                error = jvmti->SetExtensionEventCallback(unmountEvIdx, (jvmtiExtensionEvent) &vtUnmountHandler);
                if (error != JVMTI_ERROR_NONE) {
                    jvmti->SetExtensionEventCallback(mountEvIdx, nullptr);
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to to set unmount event handler, error code is  ", error);
                }
                error = jvmti->SetEventNotificationMode(JVMTI_ENABLE, static_cast<jvmtiEvent>(mountEvIdx), nullptr);
                if (error != JVMTI_ERROR_NONE) {
                    jvmti->SetExtensionEventCallback(unmountEvIdx, nullptr);
                    jvmti->SetExtensionEventCallback(mountEvIdx, nullptr);
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to to set mount event enabled, error code is  ", error);
                }
                error = jvmti->SetEventNotificationMode(JVMTI_ENABLE, static_cast<jvmtiEvent>(unmountEvIdx), nullptr);
                if (error != JVMTI_ERROR_NONE) {
                    jvmti->SetEventNotificationMode(JVMTI_DISABLE, static_cast<jvmtiEvent>(mountEvIdx), nullptr);
                    jvmti->SetExtensionEventCallback(unmountEvIdx, nullptr);
                    jvmti->SetExtensionEventCallback(mountEvIdx, nullptr);
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to to set unmount event enabled, error code is  ", error);
                }


            } else {
                auto err1 = jvmti->SetEventNotificationMode(JVMTI_DISABLE, static_cast<jvmtiEvent>(mountEvIdx), nullptr);
                auto err2 = jvmti->SetEventNotificationMode(JVMTI_DISABLE, static_cast<jvmtiEvent>(unmountEvIdx), nullptr);
                auto err3 = jvmti->SetExtensionEventCallback(mountEvIdx, nullptr);
                auto err4 = jvmti->SetExtensionEventCallback(unmountEvIdx, nullptr);
                if (err1 != JVMTI_ERROR_NONE) {
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to to set mount event mode to disabled, error code is  ", err1);
                }
                if (err2 != JVMTI_ERROR_NONE) {
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to to set unmount event mode to disabled, error code is  ", err2);
                }
                if (err3 != JVMTI_ERROR_NONE) {
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to to unset mount event handler, error code is  ", err3);
                }
                if (err4 != JVMTI_ERROR_NONE) {
                    return raiseExceptionAndReturn(jniEnv, ReturnCode::ERROR, "Failed to to unset unmount event handler, error code is  ", err4);
                }
            }
            threadMountCallbacksEnabled = enabled;
            return ReturnCode::SUCCESS;
        }

    } // namespace jvmti_agent
    
} // namespace elastic
