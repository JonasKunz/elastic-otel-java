#ifndef METHODREF_H_
#define METHODREF_H_

#include <jvmti.h>
#include <utility>

namespace elastic {


    class MethodRef {
    private:
        template<typename Ret, typename... Params>
        Ret invokeAndClearExceptions(Ret (JNIEnv::*function)(jclass, jmethodID, ...), JNIEnv* env, Params... args) {
            Ret result = (env->*function)(m_class,m_method, args...);
            if(env->ExceptionCheck() == JNI_FALSE) {
                env->ExceptionClear();
            }
            return result;
        }
        template<typename... Params>
        void invokeAndClearExceptions(void (JNIEnv::*function)(jclass, jmethodID, ...), JNIEnv* env, Params... args) {
            (env->*function)(m_class,m_method, args...);
            if(env->ExceptionCheck() == JNI_FALSE) {
                env->ExceptionClear();
            }
        }
    protected:
        jclass m_class;
        jmethodID m_method;

        MethodRef(jclass clazz, jmethodID method) : m_class(clazz), m_method(method) {};
        MethodRef(const MethodRef&) = delete;
        MethodRef(MethodRef&&) = delete;
        MethodRef& operator=(const MethodRef&) = delete;
        MethodRef& operator=(MethodRef&&) = delete;

        void doClear() {
            m_method = NULL;
            m_class = NULL;
        }

    public:

        bool isEmpty() const {
            return m_method == NULL;
        }


        jclass clazz() const {
            return m_class;
        }

        jmethodID method() const {
            return m_method;
        }

        template<typename... Params>
        void invokeStaticVoid(JNIEnv* env, Params... args) {
            invokeAndClearExceptions(&JNIEnv::CallStaticVoidMethod, env, args...);
        }
    };


    class LocalMethodRef : public MethodRef {
    public:
        LocalMethodRef() : MethodRef(NULL, NULL){}

        LocalMethodRef(JNIEnv* env, const char* className, bool isStatic, const char* name, const char* signature);

        LocalMethodRef(const LocalMethodRef& copy) : MethodRef(copy.clazz(), copy.method()) {}
        LocalMethodRef& operator=(const LocalMethodRef& copy) {
            this->m_method = copy.m_method;
            this->m_class = copy.m_class;
            return *this;
        }

        LocalMethodRef(JNIEnv* env, const MethodRef& copy) : MethodRef(NULL, NULL) {
            set(env, copy);
        }

        void set(JNIEnv* env, const MethodRef& copy);

        void clear() {
            doClear();
        }
    };


    class GlobalMethodRef : public MethodRef {
    public:
        GlobalMethodRef() : MethodRef(NULL, NULL){}
        GlobalMethodRef(JNIEnv* env, const MethodRef& copy) : MethodRef(NULL, NULL) {
            set(env, copy);
        }

        GlobalMethodRef(JNIEnv* env, const char* className, bool isStatic,  const char* name, const char* signature) :
             GlobalMethodRef(env, LocalMethodRef(env, className, isStatic, name, signature)) {}

        GlobalMethodRef(const GlobalMethodRef& copy)  = delete;
        GlobalMethodRef& operator=(const GlobalMethodRef& copy)  = delete;

        GlobalMethodRef(GlobalMethodRef&& other) : MethodRef(NULL, NULL) {
            *this = std::move(other);
        }
        GlobalMethodRef& operator=(GlobalMethodRef&& other) {
            std::swap(this->m_class,other.m_class);
            std::swap(this->m_method, other.m_method);
            return *this;
        }

        void clear(JNIEnv* env);
        void set(JNIEnv* env, const MethodRef& copy);

        ~GlobalMethodRef() {
            if(!isEmpty()) {
                //This should never happen,it means we are leaking a reference to the class
            }
        }
    };
    
}

#endif