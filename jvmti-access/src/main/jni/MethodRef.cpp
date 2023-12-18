#include "MethodRef.h"

namespace elastic {


    LocalMethodRef::LocalMethodRef(JNIEnv* env, const char* className, bool isStatic, const char* name, const char* signature) : MethodRef(NULL, NULL){
        jclass resolvedClass = env->FindClass(className);
        if(resolvedClass == NULL) {
            return;
        }
        if(isStatic) {
            m_method = env->GetStaticMethodID(resolvedClass, name, signature);
        } else {
            m_method = env->GetMethodID(resolvedClass, name, signature);
        }
        if(m_method != NULL) {
            m_class = resolvedClass;
        }
    }

    void LocalMethodRef::set(JNIEnv* env, const MethodRef& copy) {
        if(copy.isEmpty()) {
            clear();
        } else {
            m_class = (jclass) env->NewLocalRef(copy.clazz());
            m_method = copy.method();
        }
    }


    void GlobalMethodRef::clear(JNIEnv* env) {
        if(!isEmpty()) {
            env->DeleteGlobalRef(m_class);
            doClear();
        }
    }

    void GlobalMethodRef::set(JNIEnv* env, const MethodRef& copy) {
        clear(env);
        if (!copy.isEmpty()) {
            m_class = (jclass) env->NewGlobalRef(copy.clazz());
            m_method = copy.method();
        } 
    }

   


}