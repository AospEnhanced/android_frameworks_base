/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "JavaBinder"
//#define LOG_NDEBUG 0

#include "android_os_Parcel.h"
#include "android_util_Binder.h"

#include <atomic>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <android-base/stringprintf.h>
#include <binder/IInterface.h>
#include <binder/IServiceManager.h>
#include <binder/IPCThreadState.h>
#include <binder/Parcel.h>
#include <binder/BpBinder.h>
#include <binder/ProcessState.h>
#include <cutils/atomic.h>
#include <log/log.h>
#include <utils/KeyedVector.h>
#include <utils/List.h>
#include <utils/Log.h>
#include <utils/String8.h>
#include <utils/SystemClock.h>
#include <utils/threads.h>

#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedUtfChars.h>

#include "core_jni_helpers.h"

//#undef ALOGV
//#define ALOGV(...) fprintf(stderr, __VA_ARGS__)

#define DEBUG_DEATH 0
#if DEBUG_DEATH
#define LOGDEATH ALOGD
#else
#define LOGDEATH ALOGV
#endif

using namespace android;

// ----------------------------------------------------------------------------

static struct bindernative_offsets_t
{
    // Class state.
    jclass mClass;
    jmethodID mExecTransact;

    // Object state.
    jfieldID mObject;

} gBinderOffsets;

// ----------------------------------------------------------------------------

static struct binderinternal_offsets_t
{
    // Class state.
    jclass mClass;
    jmethodID mForceGc;
    jmethodID mProxyLimitCallback;

} gBinderInternalOffsets;

static struct sparseintarray_offsets_t
{
    jclass classObject;
    jmethodID constructor;
    jmethodID put;
} gSparseIntArrayOffsets;

// ----------------------------------------------------------------------------

static struct error_offsets_t
{
    jclass mClass;
} gErrorOffsets;

// ----------------------------------------------------------------------------

static struct binderproxy_offsets_t
{
    // Class state.
    jclass mClass;
    jmethodID mGetInstance;
    jmethodID mSendDeathNotice;
    jmethodID mDumpProxyDebugInfo;

    // Object state.
    jfieldID mNativeData;  // Field holds native pointer to BinderProxyNativeData.
} gBinderProxyOffsets;

static struct class_offsets_t
{
    jmethodID mGetName;
} gClassOffsets;

// ----------------------------------------------------------------------------

static struct log_offsets_t
{
    // Class state.
    jclass mClass;
    jmethodID mLogE;
} gLogOffsets;

static struct parcel_file_descriptor_offsets_t
{
    jclass mClass;
    jmethodID mConstructor;
} gParcelFileDescriptorOffsets;

static struct strict_mode_callback_offsets_t
{
    jclass mClass;
    jmethodID mCallback;
} gStrictModeCallbackOffsets;

static struct thread_dispatch_offsets_t
{
    // Class state.
    jclass mClass;
    jmethodID mDispatchUncaughtException;
    jmethodID mCurrentThread;
} gThreadDispatchOffsets;

// ****************************************************************************
// ****************************************************************************
// ****************************************************************************

static constexpr int32_t PROXY_WARN_INTERVAL = 5000;
static constexpr uint32_t GC_INTERVAL = 1000;

// Protected by gProxyLock. We warn if this gets too large.
static int32_t gNumProxies = 0;
static int32_t gProxiesWarned = 0;

// Number of GlobalRefs held by JavaBBinders.
static std::atomic<uint32_t> gNumLocalRefsCreated(0);
static std::atomic<uint32_t> gNumLocalRefsDeleted(0);
// Number of GlobalRefs held by JavaDeathRecipients.
static std::atomic<uint32_t> gNumDeathRefsCreated(0);
static std::atomic<uint32_t> gNumDeathRefsDeleted(0);

// We collected after creating this many refs.
static std::atomic<uint32_t> gCollectedAtRefs(0);

// Garbage collect if we've allocated at least GC_INTERVAL refs since the last time.
// TODO: Consider removing this completely. We should no longer be generating GlobalRefs
// that are reclaimed as a result of GC action.
__attribute__((no_sanitize("unsigned-integer-overflow")))
static void gcIfManyNewRefs(JNIEnv* env)
{
    uint32_t totalRefs = gNumLocalRefsCreated.load(std::memory_order_relaxed)
            + gNumDeathRefsCreated.load(std::memory_order_relaxed);
    uint32_t collectedAtRefs = gCollectedAtRefs.load(memory_order_relaxed);
    // A bound on the number of threads that can have incremented gNum...RefsCreated before the
    // following check is executed. Effectively a bound on #threads. Almost any value will do.
    static constexpr uint32_t MAX_RACING = 100000;

    if (totalRefs - (collectedAtRefs + GC_INTERVAL) /* modular arithmetic! */ < MAX_RACING) {
        // Recently passed next GC interval.
        if (gCollectedAtRefs.compare_exchange_strong(collectedAtRefs,
                collectedAtRefs + GC_INTERVAL, std::memory_order_relaxed)) {
            ALOGV("Binder forcing GC at %u created refs", totalRefs);
            env->CallStaticVoidMethod(gBinderInternalOffsets.mClass,
                    gBinderInternalOffsets.mForceGc);
        }  // otherwise somebody else beat us to it.
    } else {
        ALOGV("Now have %d binder ops", totalRefs - collectedAtRefs);
    }
}

static JavaVM* jnienv_to_javavm(JNIEnv* env)
{
    JavaVM* vm;
    return env->GetJavaVM(&vm) >= 0 ? vm : NULL;
}

static JNIEnv* javavm_to_jnienv(JavaVM* vm)
{
    JNIEnv* env;
    return vm->GetEnv((void **)&env, JNI_VERSION_1_4) >= 0 ? env : NULL;
}

// Report a java.lang.Error (or subclass). This will terminate the runtime by
// calling FatalError with a message derived from the given error.
static void report_java_lang_error_fatal_error(JNIEnv* env, jthrowable error,
        const char* msg)
{
    // Report an error: reraise the exception and ask the runtime to abort.

    // Try to get the exception string. Sometimes logcat isn't available,
    // so try to add it to the abort message.
    std::string exc_msg = "(Unknown exception message)";
    {
        ScopedLocalRef<jclass> exc_class(env, env->GetObjectClass(error));
        jmethodID method_id = env->GetMethodID(exc_class.get(), "toString",
                "()Ljava/lang/String;");
        ScopedLocalRef<jstring> jstr(
                env,
                reinterpret_cast<jstring>(
                        env->CallObjectMethod(error, method_id)));
        env->ExceptionClear();  // Just for good measure.
        if (jstr.get() != nullptr) {
            ScopedUtfChars jstr_utf(env, jstr.get());
            if (jstr_utf.c_str() != nullptr) {
                exc_msg = jstr_utf.c_str();
            } else {
                env->ExceptionClear();
            }
        }
    }

    env->Throw(error);
    ALOGE("java.lang.Error thrown during binder transaction (stack trace follows) : ");
    env->ExceptionDescribe();

    std::string error_msg = base::StringPrintf(
            "java.lang.Error thrown during binder transaction: %s",
            exc_msg.c_str());
    env->FatalError(error_msg.c_str());
}

// Report a java.lang.Error (or subclass). This will terminate the runtime, either by
// the uncaught exception handler, or explicitly by calling
// report_java_lang_error_fatal_error.
static void report_java_lang_error(JNIEnv* env, jthrowable error, const char* msg)
{
    // Try to run the uncaught exception machinery.
    jobject thread = env->CallStaticObjectMethod(gThreadDispatchOffsets.mClass,
            gThreadDispatchOffsets.mCurrentThread);
    if (thread != nullptr) {
        env->CallVoidMethod(thread, gThreadDispatchOffsets.mDispatchUncaughtException,
                error);
        // Should not return here, unless more errors occured.
    }
    // Some error occurred that meant that either dispatchUncaughtException could not be
    // called or that it had an error itself (as this should be unreachable under normal
    // conditions). As the binder code cannot handle Errors, attempt to log the error and
    // abort.
    env->ExceptionClear();
    report_java_lang_error_fatal_error(env, error, msg);
}

static void report_exception(JNIEnv* env, jthrowable excep, const char* msg)
{
    env->ExceptionClear();

    ScopedLocalRef<jstring> tagstr(env, env->NewStringUTF(LOG_TAG));
    ScopedLocalRef<jstring> msgstr(env);
    if (tagstr != nullptr) {
        msgstr.reset(env->NewStringUTF(msg));
    }

    if ((tagstr != nullptr) && (msgstr != nullptr)) {
        env->CallStaticIntMethod(gLogOffsets.mClass, gLogOffsets.mLogE,
                tagstr.get(), msgstr.get(), excep);
        if (env->ExceptionCheck()) {
            // Attempting to log the failure has failed.
            ALOGW("Failed trying to log exception, msg='%s'\n", msg);
            env->ExceptionClear();
        }
    } else {
        env->ExceptionClear();      /* assume exception (OOM?) was thrown */
        ALOGE("Unable to call Log.e()\n");
        ALOGE("%s", msg);
    }

    if (env->IsInstanceOf(excep, gErrorOffsets.mClass)) {
        report_java_lang_error(env, excep, msg);
    }
}

class JavaBBinderHolder;

class JavaBBinder : public BBinder
{
public:
    JavaBBinder(JNIEnv* env, jobject /* Java Binder */ object)
        : mVM(jnienv_to_javavm(env)), mObject(env->NewGlobalRef(object))
    {
        ALOGV("Creating JavaBBinder %p\n", this);
        gNumLocalRefsCreated.fetch_add(1, std::memory_order_relaxed);
        gcIfManyNewRefs(env);
    }

    bool    checkSubclass(const void* subclassID) const
    {
        return subclassID == &gBinderOffsets;
    }

    jobject object() const
    {
        return mObject;
    }

protected:
    virtual ~JavaBBinder()
    {
        ALOGV("Destroying JavaBBinder %p\n", this);
        gNumLocalRefsDeleted.fetch_add(1, memory_order_relaxed);
        JNIEnv* env = javavm_to_jnienv(mVM);
        env->DeleteGlobalRef(mObject);
    }

    virtual status_t onTransact(
        uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags = 0)
    {
        JNIEnv* env = javavm_to_jnienv(mVM);

        ALOGV("onTransact() on %p calling object %p in env %p vm %p\n", this, mObject, env, mVM);

        IPCThreadState* thread_state = IPCThreadState::self();
        const int32_t strict_policy_before = thread_state->getStrictModePolicy();

        //printf("Transact from %p to Java code sending: ", this);
        //data.print();
        //printf("\n");
        jboolean res = env->CallBooleanMethod(mObject, gBinderOffsets.mExecTransact,
            code, reinterpret_cast<jlong>(&data), reinterpret_cast<jlong>(reply), flags);

        if (env->ExceptionCheck()) {
            ScopedLocalRef<jthrowable> excep(env, env->ExceptionOccurred());
            report_exception(env, excep.get(),
                "*** Uncaught remote exception!  "
                "(Exceptions are not yet supported across processes.)");
            res = JNI_FALSE;
        }

        // Check if the strict mode state changed while processing the
        // call.  The Binder state will be restored by the underlying
        // Binder system in IPCThreadState, however we need to take care
        // of the parallel Java state as well.
        if (thread_state->getStrictModePolicy() != strict_policy_before) {
            set_dalvik_blockguard_policy(env, strict_policy_before);
        }

        if (env->ExceptionCheck()) {
            ScopedLocalRef<jthrowable> excep(env, env->ExceptionOccurred());
            report_exception(env, excep.get(),
                "*** Uncaught exception in onBinderStrictModePolicyChange");
        }

        // Need to always call through the native implementation of
        // SYSPROPS_TRANSACTION.
        if (code == SYSPROPS_TRANSACTION) {
            BBinder::onTransact(code, data, reply, flags);
        }

        //aout << "onTransact to Java code; result=" << res << endl
        //    << "Transact from " << this << " to Java code returning "
        //    << reply << ": " << *reply << endl;
        return res != JNI_FALSE ? NO_ERROR : UNKNOWN_TRANSACTION;
    }

    virtual status_t dump(int fd, const Vector<String16>& args)
    {
        return 0;
    }

private:
    JavaVM* const   mVM;
    jobject const   mObject;  // GlobalRef to Java Binder
};

// ----------------------------------------------------------------------------

class JavaBBinderHolder
{
public:
    sp<JavaBBinder> get(JNIEnv* env, jobject obj)
    {
        AutoMutex _l(mLock);
        sp<JavaBBinder> b = mBinder.promote();
        if (b == NULL) {
            b = new JavaBBinder(env, obj);
            mBinder = b;
            ALOGV("Creating JavaBinder %p (refs %p) for Object %p, weakCount=%" PRId32 "\n",
                 b.get(), b->getWeakRefs(), obj, b->getWeakRefs()->getWeakCount());
        }

        return b;
    }

    sp<JavaBBinder> getExisting()
    {
        AutoMutex _l(mLock);
        return mBinder.promote();
    }

private:
    Mutex           mLock;
    wp<JavaBBinder> mBinder;
};

// ----------------------------------------------------------------------------

// Per-IBinder death recipient bookkeeping.  This is how we reconcile local jobject
// death recipient references passed in through JNI with the permanent corresponding
// JavaDeathRecipient objects.

class JavaDeathRecipient;

class DeathRecipientList : public RefBase {
    List< sp<JavaDeathRecipient> > mList;
    Mutex mLock;

public:
    DeathRecipientList();
    ~DeathRecipientList();

    void add(const sp<JavaDeathRecipient>& recipient);
    void remove(const sp<JavaDeathRecipient>& recipient);
    sp<JavaDeathRecipient> find(jobject recipient);

    Mutex& lock();  // Use with care; specifically for mutual exclusion during binder death
};

// ----------------------------------------------------------------------------

class JavaDeathRecipient : public IBinder::DeathRecipient
{
public:
    JavaDeathRecipient(JNIEnv* env, jobject object, const sp<DeathRecipientList>& list)
        : mVM(jnienv_to_javavm(env)), mObject(env->NewGlobalRef(object)),
          mObjectWeak(NULL), mList(list)
    {
        // These objects manage their own lifetimes so are responsible for final bookkeeping.
        // The list holds a strong reference to this object.
        LOGDEATH("Adding JDR %p to DRL %p", this, list.get());
        list->add(this);

        gNumDeathRefsCreated.fetch_add(1, std::memory_order_relaxed);
        gcIfManyNewRefs(env);
    }

    void binderDied(const wp<IBinder>& who)
    {
        LOGDEATH("Receiving binderDied() on JavaDeathRecipient %p\n", this);
        if (mObject != NULL) {
            JNIEnv* env = javavm_to_jnienv(mVM);

            env->CallStaticVoidMethod(gBinderProxyOffsets.mClass,
                    gBinderProxyOffsets.mSendDeathNotice, mObject);
            if (env->ExceptionCheck()) {
                jthrowable excep = env->ExceptionOccurred();
                report_exception(env, excep,
                        "*** Uncaught exception returned from death notification!");
            }

            // Serialize with our containing DeathRecipientList so that we can't
            // delete the global ref on mObject while the list is being iterated.
            sp<DeathRecipientList> list = mList.promote();
            if (list != NULL) {
                AutoMutex _l(list->lock());

                // Demote from strong ref to weak after binderDied() has been delivered,
                // to allow the DeathRecipient and BinderProxy to be GC'd if no longer needed.
                mObjectWeak = env->NewWeakGlobalRef(mObject);
                env->DeleteGlobalRef(mObject);
                mObject = NULL;
            }
        }
    }

    void clearReference()
    {
        sp<DeathRecipientList> list = mList.promote();
        if (list != NULL) {
            LOGDEATH("Removing JDR %p from DRL %p", this, list.get());
            list->remove(this);
        } else {
            LOGDEATH("clearReference() on JDR %p but DRL wp purged", this);
        }
    }

    bool matches(jobject obj) {
        bool result;
        JNIEnv* env = javavm_to_jnienv(mVM);

        if (mObject != NULL) {
            result = env->IsSameObject(obj, mObject);
        } else {
            ScopedLocalRef<jobject> me(env, env->NewLocalRef(mObjectWeak));
            result = env->IsSameObject(obj, me.get());
        }
        return result;
    }

    void warnIfStillLive() {
        if (mObject != NULL) {
            // Okay, something is wrong -- we have a hard reference to a live death
            // recipient on the VM side, but the list is being torn down.
            JNIEnv* env = javavm_to_jnienv(mVM);
            ScopedLocalRef<jclass> objClassRef(env, env->GetObjectClass(mObject));
            ScopedLocalRef<jstring> nameRef(env,
                    (jstring) env->CallObjectMethod(objClassRef.get(), gClassOffsets.mGetName));
            ScopedUtfChars nameUtf(env, nameRef.get());
            if (nameUtf.c_str() != NULL) {
                ALOGW("BinderProxy is being destroyed but the application did not call "
                        "unlinkToDeath to unlink all of its death recipients beforehand.  "
                        "Releasing leaked death recipient: %s", nameUtf.c_str());
            } else {
                ALOGW("BinderProxy being destroyed; unable to get DR object name");
                env->ExceptionClear();
            }
        }
    }

protected:
    virtual ~JavaDeathRecipient()
    {
        //ALOGI("Removing death ref: recipient=%p\n", mObject);
        gNumDeathRefsDeleted.fetch_add(1, std::memory_order_relaxed);
        JNIEnv* env = javavm_to_jnienv(mVM);
        if (mObject != NULL) {
            env->DeleteGlobalRef(mObject);
        } else {
            env->DeleteWeakGlobalRef(mObjectWeak);
        }
    }

private:
    JavaVM* const mVM;
    jobject mObject;  // Initial strong ref to Java-side DeathRecipient. Cleared on binderDied().
    jweak mObjectWeak; // Weak ref to the same Java-side DeathRecipient after binderDied().
    wp<DeathRecipientList> mList;
};

// ----------------------------------------------------------------------------

DeathRecipientList::DeathRecipientList() {
    LOGDEATH("New DRL @ %p", this);
}

DeathRecipientList::~DeathRecipientList() {
    LOGDEATH("Destroy DRL @ %p", this);
    AutoMutex _l(mLock);

    // Should never happen -- the JavaDeathRecipient objects that have added themselves
    // to the list are holding references on the list object.  Only when they are torn
    // down can the list header be destroyed.
    if (mList.size() > 0) {
        List< sp<JavaDeathRecipient> >::iterator iter;
        for (iter = mList.begin(); iter != mList.end(); iter++) {
            (*iter)->warnIfStillLive();
        }
    }
}

void DeathRecipientList::add(const sp<JavaDeathRecipient>& recipient) {
    AutoMutex _l(mLock);

    LOGDEATH("DRL @ %p : add JDR %p", this, recipient.get());
    mList.push_back(recipient);
}

void DeathRecipientList::remove(const sp<JavaDeathRecipient>& recipient) {
    AutoMutex _l(mLock);

    List< sp<JavaDeathRecipient> >::iterator iter;
    for (iter = mList.begin(); iter != mList.end(); iter++) {
        if (*iter == recipient) {
            LOGDEATH("DRL @ %p : remove JDR %p", this, recipient.get());
            mList.erase(iter);
            return;
        }
    }
}

sp<JavaDeathRecipient> DeathRecipientList::find(jobject recipient) {
    AutoMutex _l(mLock);

    List< sp<JavaDeathRecipient> >::iterator iter;
    for (iter = mList.begin(); iter != mList.end(); iter++) {
        if ((*iter)->matches(recipient)) {
            return *iter;
        }
    }
    return NULL;
}

Mutex& DeathRecipientList::lock() {
    return mLock;
}

// ----------------------------------------------------------------------------

namespace android {

// We aggregate native pointer fields for BinderProxy in a single object to allow
// management with a single NativeAllocationRegistry, and to reduce the number of JNI
// Java field accesses. This costs us some extra indirections here.
struct BinderProxyNativeData {
    // Both fields are constant and not null once javaObjectForIBinder returns this as
    // part of a BinderProxy.

    // The native IBinder proxied by this BinderProxy.
    sp<IBinder> mObject;

    // Death recipients for mObject. Reference counted only because DeathRecipients
    // hold a weak reference that can be temporarily promoted.
    sp<DeathRecipientList> mOrgue;  // Death recipients for mObject.
};

BinderProxyNativeData* getBPNativeData(JNIEnv* env, jobject obj) {
    return (BinderProxyNativeData *) env->GetLongField(obj, gBinderProxyOffsets.mNativeData);
}

static Mutex gProxyLock;

// We may cache a single BinderProxyNativeData node to avoid repeat allocation.
// All fields are null. Protected by gProxyLock.
static BinderProxyNativeData *gNativeDataCache;

// If the argument is a JavaBBinder, return the Java object that was used to create it.
// Otherwise return a BinderProxy for the IBinder. If a previous call was passed the
// same IBinder, and the original BinderProxy is still alive, return the same BinderProxy.
jobject javaObjectForIBinder(JNIEnv* env, const sp<IBinder>& val)
{
    if (val == NULL) return NULL;

    if (val->checkSubclass(&gBinderOffsets)) {
        // It's a JavaBBinder created by ibinderForJavaObject. Already has Java object.
        jobject object = static_cast<JavaBBinder*>(val.get())->object();
        LOGDEATH("objectForBinder %p: it's our own %p!\n", val.get(), object);
        return object;
    }

    // For the rest of the function we will hold this lock, to serialize
    // looking/creation/destruction of Java proxies for native Binder proxies.
    AutoMutex _l(gProxyLock);

    BinderProxyNativeData* nativeData = gNativeDataCache;
    if (nativeData == nullptr) {
        nativeData = new BinderProxyNativeData();
    }
    // gNativeDataCache is now logically empty.
    jobject object = env->CallStaticObjectMethod(gBinderProxyOffsets.mClass,
            gBinderProxyOffsets.mGetInstance, (jlong) nativeData, (jlong) val.get());
    if (env->ExceptionCheck()) {
        // In the exception case, getInstance still took ownership of nativeData.
        gNativeDataCache = nullptr;
        return NULL;
    }
    BinderProxyNativeData* actualNativeData = getBPNativeData(env, object);
    if (actualNativeData == nativeData) {
        // New BinderProxy; we still have exclusive access.
        nativeData->mOrgue = new DeathRecipientList;
        nativeData->mObject = val;
        gNativeDataCache = nullptr;
        ++gNumProxies;
        if (gNumProxies >= gProxiesWarned + PROXY_WARN_INTERVAL) {
            ALOGW("Unexpectedly many live BinderProxies: %d\n", gNumProxies);
            gProxiesWarned = gNumProxies;
        }
    } else {
        // nativeData wasn't used. Reuse it the next time.
        gNativeDataCache = nativeData;
    }

    return object;
}

sp<IBinder> ibinderForJavaObject(JNIEnv* env, jobject obj)
{
    if (obj == NULL) return NULL;

    // Instance of Binder?
    if (env->IsInstanceOf(obj, gBinderOffsets.mClass)) {
        JavaBBinderHolder* jbh = (JavaBBinderHolder*)
            env->GetLongField(obj, gBinderOffsets.mObject);
        return jbh->get(env, obj);
    }

    // Instance of BinderProxy?
    if (env->IsInstanceOf(obj, gBinderProxyOffsets.mClass)) {
        return getBPNativeData(env, obj)->mObject;
    }

    ALOGW("ibinderForJavaObject: %p is not a Binder object", obj);
    return NULL;
}

jobject newParcelFileDescriptor(JNIEnv* env, jobject fileDesc)
{
    return env->NewObject(
            gParcelFileDescriptorOffsets.mClass, gParcelFileDescriptorOffsets.mConstructor, fileDesc);
}

void set_dalvik_blockguard_policy(JNIEnv* env, jint strict_policy)
{
    // Call back into android.os.StrictMode#onBinderStrictModePolicyChange
    // to sync our state back to it.  See the comments in StrictMode.java.
    env->CallStaticVoidMethod(gStrictModeCallbackOffsets.mClass,
                              gStrictModeCallbackOffsets.mCallback,
                              strict_policy);
}

void signalExceptionForError(JNIEnv* env, jobject obj, status_t err,
        bool canThrowRemoteException, int parcelSize)
{
    switch (err) {
        case UNKNOWN_ERROR:
            jniThrowException(env, "java/lang/RuntimeException", "Unknown error");
            break;
        case NO_MEMORY:
            jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
            break;
        case INVALID_OPERATION:
            jniThrowException(env, "java/lang/UnsupportedOperationException", NULL);
            break;
        case BAD_VALUE:
            jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
            break;
        case BAD_INDEX:
            jniThrowException(env, "java/lang/IndexOutOfBoundsException", NULL);
            break;
        case BAD_TYPE:
            jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
            break;
        case NAME_NOT_FOUND:
            jniThrowException(env, "java/util/NoSuchElementException", NULL);
            break;
        case PERMISSION_DENIED:
            jniThrowException(env, "java/lang/SecurityException", NULL);
            break;
        case NOT_ENOUGH_DATA:
            jniThrowException(env, "android/os/ParcelFormatException", "Not enough data");
            break;
        case NO_INIT:
            jniThrowException(env, "java/lang/RuntimeException", "Not initialized");
            break;
        case ALREADY_EXISTS:
            jniThrowException(env, "java/lang/RuntimeException", "Item already exists");
            break;
        case DEAD_OBJECT:
            // DeadObjectException is a checked exception, only throw from certain methods.
            jniThrowException(env, canThrowRemoteException
                    ? "android/os/DeadObjectException"
                            : "java/lang/RuntimeException", NULL);
            break;
        case UNKNOWN_TRANSACTION:
            jniThrowException(env, "java/lang/RuntimeException", "Unknown transaction code");
            break;
        case FAILED_TRANSACTION: {
            ALOGE("!!! FAILED BINDER TRANSACTION !!!  (parcel size = %d)", parcelSize);
            const char* exceptionToThrow;
            char msg[128];
            // TransactionTooLargeException is a checked exception, only throw from certain methods.
            // FIXME: Transaction too large is the most common reason for FAILED_TRANSACTION
            //        but it is not the only one.  The Binder driver can return BR_FAILED_REPLY
            //        for other reasons also, such as if the transaction is malformed or
            //        refers to an FD that has been closed.  We should change the driver
            //        to enable us to distinguish these cases in the future.
            if (canThrowRemoteException && parcelSize > 200*1024) {
                // bona fide large payload
                exceptionToThrow = "android/os/TransactionTooLargeException";
                snprintf(msg, sizeof(msg)-1, "data parcel size %d bytes", parcelSize);
            } else {
                // Heuristic: a payload smaller than this threshold "shouldn't" be too
                // big, so it's probably some other, more subtle problem.  In practice
                // it seems to always mean that the remote process died while the binder
                // transaction was already in flight.
                exceptionToThrow = (canThrowRemoteException)
                        ? "android/os/DeadObjectException"
                        : "java/lang/RuntimeException";
                snprintf(msg, sizeof(msg)-1,
                        "Transaction failed on small parcel; remote process probably died");
            }
            jniThrowException(env, exceptionToThrow, msg);
        } break;
        case FDS_NOT_ALLOWED:
            jniThrowException(env, "java/lang/RuntimeException",
                    "Not allowed to write file descriptors here");
            break;
        case UNEXPECTED_NULL:
            jniThrowNullPointerException(env, NULL);
            break;
        case -EBADF:
            jniThrowException(env, "java/lang/RuntimeException",
                    "Bad file descriptor");
            break;
        case -ENFILE:
            jniThrowException(env, "java/lang/RuntimeException",
                    "File table overflow");
            break;
        case -EMFILE:
            jniThrowException(env, "java/lang/RuntimeException",
                    "Too many open files");
            break;
        case -EFBIG:
            jniThrowException(env, "java/lang/RuntimeException",
                    "File too large");
            break;
        case -ENOSPC:
            jniThrowException(env, "java/lang/RuntimeException",
                    "No space left on device");
            break;
        case -ESPIPE:
            jniThrowException(env, "java/lang/RuntimeException",
                    "Illegal seek");
            break;
        case -EROFS:
            jniThrowException(env, "java/lang/RuntimeException",
                    "Read-only file system");
            break;
        case -EMLINK:
            jniThrowException(env, "java/lang/RuntimeException",
                    "Too many links");
            break;
        default:
            ALOGE("Unknown binder error code. 0x%" PRIx32, err);
            String8 msg;
            msg.appendFormat("Unknown binder error code. 0x%" PRIx32, err);
            // RemoteException is a checked exception, only throw from certain methods.
            jniThrowException(env, canThrowRemoteException
                    ? "android/os/RemoteException" : "java/lang/RuntimeException", msg.string());
            break;
    }
}

}

// ----------------------------------------------------------------------------

static jint android_os_Binder_getCallingPid(JNIEnv* env, jobject clazz)
{
    return IPCThreadState::self()->getCallingPid();
}

static jint android_os_Binder_getCallingUid(JNIEnv* env, jobject clazz)
{
    return IPCThreadState::self()->getCallingUid();
}

static jlong android_os_Binder_clearCallingIdentity(JNIEnv* env, jobject clazz)
{
    return IPCThreadState::self()->clearCallingIdentity();
}

static void android_os_Binder_restoreCallingIdentity(JNIEnv* env, jobject clazz, jlong token)
{
    // XXX temporary sanity check to debug crashes.
    int uid = (int)(token>>32);
    if (uid > 0 && uid < 999) {
        // In Android currently there are no uids in this range.
        char buf[128];
        sprintf(buf, "Restoring bad calling ident: 0x%" PRIx64, token);
        jniThrowException(env, "java/lang/IllegalStateException", buf);
        return;
    }
    IPCThreadState::self()->restoreCallingIdentity(token);
}

static void android_os_Binder_setThreadStrictModePolicy(JNIEnv* env, jobject clazz, jint policyMask)
{
    IPCThreadState::self()->setStrictModePolicy(policyMask);
}

static jint android_os_Binder_getThreadStrictModePolicy(JNIEnv* env, jobject clazz)
{
    return IPCThreadState::self()->getStrictModePolicy();
}

static void android_os_Binder_flushPendingCommands(JNIEnv* env, jobject clazz)
{
    IPCThreadState::self()->flushCommands();
}

static jlong android_os_Binder_getNativeBBinderHolder(JNIEnv* env, jobject clazz)
{
    JavaBBinderHolder* jbh = new JavaBBinderHolder();
    return (jlong) jbh;
}

static void Binder_destroy(void* rawJbh)
{
    JavaBBinderHolder* jbh = (JavaBBinderHolder*) rawJbh;
    ALOGV("Java Binder: deleting holder %p", jbh);
    delete jbh;
}

JNIEXPORT jlong JNICALL android_os_Binder_getNativeFinalizer(JNIEnv*, jclass) {
    return (jlong) Binder_destroy;
}

static void android_os_Binder_blockUntilThreadAvailable(JNIEnv* env, jobject clazz)
{
    return IPCThreadState::self()->blockUntilThreadAvailable();
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gBinderMethods[] = {
     /* name, signature, funcPtr */
    { "getCallingPid", "()I", (void*)android_os_Binder_getCallingPid },
    { "getCallingUid", "()I", (void*)android_os_Binder_getCallingUid },
    { "clearCallingIdentity", "()J", (void*)android_os_Binder_clearCallingIdentity },
    { "restoreCallingIdentity", "(J)V", (void*)android_os_Binder_restoreCallingIdentity },
    { "setThreadStrictModePolicy", "(I)V", (void*)android_os_Binder_setThreadStrictModePolicy },
    { "getThreadStrictModePolicy", "()I", (void*)android_os_Binder_getThreadStrictModePolicy },
    { "flushPendingCommands", "()V", (void*)android_os_Binder_flushPendingCommands },
    { "getNativeBBinderHolder", "()J", (void*)android_os_Binder_getNativeBBinderHolder },
    { "getNativeFinalizer", "()J", (void*)android_os_Binder_getNativeFinalizer },
    { "blockUntilThreadAvailable", "()V", (void*)android_os_Binder_blockUntilThreadAvailable }
};

const char* const kBinderPathName = "android/os/Binder";

static int int_register_android_os_Binder(JNIEnv* env)
{
    jclass clazz = FindClassOrDie(env, kBinderPathName);

    gBinderOffsets.mClass = MakeGlobalRefOrDie(env, clazz);
    gBinderOffsets.mExecTransact = GetMethodIDOrDie(env, clazz, "execTransact", "(IJJI)Z");
    gBinderOffsets.mObject = GetFieldIDOrDie(env, clazz, "mObject", "J");

    return RegisterMethodsOrDie(
        env, kBinderPathName,
        gBinderMethods, NELEM(gBinderMethods));
}

// ****************************************************************************
// ****************************************************************************
// ****************************************************************************

namespace android {

jint android_os_Debug_getLocalObjectCount(JNIEnv* env, jobject clazz)
{
    return gNumLocalRefsCreated - gNumLocalRefsDeleted;
}

jint android_os_Debug_getProxyObjectCount(JNIEnv* env, jobject clazz)
{
    AutoMutex _l(gProxyLock);
    return gNumProxies;
}

jint android_os_Debug_getDeathObjectCount(JNIEnv* env, jobject clazz)
{
    return gNumDeathRefsCreated - gNumDeathRefsDeleted;
}

}

// ****************************************************************************
// ****************************************************************************
// ****************************************************************************

static jobject android_os_BinderInternal_getContextObject(JNIEnv* env, jobject clazz)
{
    sp<IBinder> b = ProcessState::self()->getContextObject(NULL);
    return javaObjectForIBinder(env, b);
}

static void android_os_BinderInternal_joinThreadPool(JNIEnv* env, jobject clazz)
{
    sp<IBinder> b = ProcessState::self()->getContextObject(NULL);
    android::IPCThreadState::self()->joinThreadPool();
}

static void android_os_BinderInternal_disableBackgroundScheduling(JNIEnv* env,
        jobject clazz, jboolean disable)
{
    IPCThreadState::disableBackgroundScheduling(disable ? true : false);
}

static void android_os_BinderInternal_setMaxThreads(JNIEnv* env,
        jobject clazz, jint maxThreads)
{
    ProcessState::self()->setThreadPoolMaxThreadCount(maxThreads);
}

static void android_os_BinderInternal_handleGc(JNIEnv* env, jobject clazz)
{
    ALOGV("Gc has executed, updating Refs count at GC");
    gCollectedAtRefs = gNumLocalRefsCreated + gNumDeathRefsCreated;
}

static void android_os_BinderInternal_proxyLimitcallback(int uid)
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    {
        // Calls into BinderProxy must be serialized
        AutoMutex _l(gProxyLock);
        env->CallStaticVoidMethod(gBinderProxyOffsets.mClass,
                                  gBinderProxyOffsets.mDumpProxyDebugInfo);
    }
    if (env->ExceptionCheck()) {
        ScopedLocalRef<jthrowable> excep(env, env->ExceptionOccurred());
        report_exception(env, excep.get(),
            "*** Uncaught exception in dumpProxyDebugInfo");
    }

    env->CallStaticVoidMethod(gBinderInternalOffsets.mClass,
                              gBinderInternalOffsets.mProxyLimitCallback,
                              uid);

    if (env->ExceptionCheck()) {
        ScopedLocalRef<jthrowable> excep(env, env->ExceptionOccurred());
        report_exception(env, excep.get(),
            "*** Uncaught exception in binderProxyLimitCallbackFromNative");
    }
}

static void android_os_BinderInternal_setBinderProxyCountEnabled(JNIEnv* env, jobject clazz,
                                                                 jboolean enable)
{
    BpBinder::setCountByUidEnabled((bool) enable);
}

static jobject android_os_BinderInternal_getBinderProxyPerUidCounts(JNIEnv* env, jclass clazz)
{
    Vector<uint32_t> uids, counts;
    BpBinder::getCountByUid(uids, counts);
    jobject sparseIntArray = env->NewObject(gSparseIntArrayOffsets.classObject,
                                            gSparseIntArrayOffsets.constructor);
    for (size_t i = 0; i < uids.size(); i++) {
        env->CallVoidMethod(sparseIntArray, gSparseIntArrayOffsets.put,
                            static_cast<jint>(uids[i]), static_cast<jint>(counts[i]));
    }
    return sparseIntArray;
}

static jint android_os_BinderInternal_getBinderProxyCount(JNIEnv* env, jobject clazz, jint uid) {
    return static_cast<jint>(BpBinder::getBinderProxyCount(static_cast<uint32_t>(uid)));
}

static void android_os_BinderInternal_setBinderProxyCountWatermarks(JNIEnv* env, jobject clazz,
                                                                    jint high, jint low)
{
    BpBinder::setBinderProxyCountWatermarks(high, low);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gBinderInternalMethods[] = {
     /* name, signature, funcPtr */
    { "getContextObject", "()Landroid/os/IBinder;", (void*)android_os_BinderInternal_getContextObject },
    { "joinThreadPool", "()V", (void*)android_os_BinderInternal_joinThreadPool },
    { "disableBackgroundScheduling", "(Z)V", (void*)android_os_BinderInternal_disableBackgroundScheduling },
    { "setMaxThreads", "(I)V", (void*)android_os_BinderInternal_setMaxThreads },
    { "handleGc", "()V", (void*)android_os_BinderInternal_handleGc },
    { "nSetBinderProxyCountEnabled", "(Z)V", (void*)android_os_BinderInternal_setBinderProxyCountEnabled },
    { "nGetBinderProxyPerUidCounts", "()Landroid/util/SparseIntArray;", (void*)android_os_BinderInternal_getBinderProxyPerUidCounts },
    { "nGetBinderProxyCount", "(I)I", (void*)android_os_BinderInternal_getBinderProxyCount },
    { "nSetBinderProxyCountWatermarks", "(II)V", (void*)android_os_BinderInternal_setBinderProxyCountWatermarks}
};

const char* const kBinderInternalPathName = "com/android/internal/os/BinderInternal";

static int int_register_android_os_BinderInternal(JNIEnv* env)
{
    jclass clazz = FindClassOrDie(env, kBinderInternalPathName);

    gBinderInternalOffsets.mClass = MakeGlobalRefOrDie(env, clazz);
    gBinderInternalOffsets.mForceGc = GetStaticMethodIDOrDie(env, clazz, "forceBinderGc", "()V");
    gBinderInternalOffsets.mProxyLimitCallback = GetStaticMethodIDOrDie(env, clazz, "binderProxyLimitCallbackFromNative", "(I)V");

    jclass SparseIntArrayClass = FindClassOrDie(env, "android/util/SparseIntArray");
    gSparseIntArrayOffsets.classObject = MakeGlobalRefOrDie(env, SparseIntArrayClass);
    gSparseIntArrayOffsets.constructor = GetMethodIDOrDie(env, gSparseIntArrayOffsets.classObject,
                                                           "<init>", "()V");
    gSparseIntArrayOffsets.put = GetMethodIDOrDie(env, gSparseIntArrayOffsets.classObject, "put",
                                                   "(II)V");

    BpBinder::setLimitCallback(android_os_BinderInternal_proxyLimitcallback);

    return RegisterMethodsOrDie(
        env, kBinderInternalPathName,
        gBinderInternalMethods, NELEM(gBinderInternalMethods));
}

// ****************************************************************************
// ****************************************************************************
// ****************************************************************************

static jboolean android_os_BinderProxy_pingBinder(JNIEnv* env, jobject obj)
{
    IBinder* target = getBPNativeData(env, obj)->mObject.get();
    if (target == NULL) {
        return JNI_FALSE;
    }
    status_t err = target->pingBinder();
    return err == NO_ERROR ? JNI_TRUE : JNI_FALSE;
}

static jstring android_os_BinderProxy_getInterfaceDescriptor(JNIEnv* env, jobject obj)
{
    IBinder* target = getBPNativeData(env, obj)->mObject.get();
    if (target != NULL) {
        const String16& desc = target->getInterfaceDescriptor();
        return env->NewString(reinterpret_cast<const jchar*>(desc.string()),
                              desc.size());
    }
    jniThrowException(env, "java/lang/RuntimeException",
            "No binder found for object");
    return NULL;
}

static jboolean android_os_BinderProxy_isBinderAlive(JNIEnv* env, jobject obj)
{
    IBinder* target = getBPNativeData(env, obj)->mObject.get();
    if (target == NULL) {
        return JNI_FALSE;
    }
    bool alive = target->isBinderAlive();
    return alive ? JNI_TRUE : JNI_FALSE;
}

static int getprocname(pid_t pid, char *buf, size_t len) {
    char filename[32];
    FILE *f;

    snprintf(filename, sizeof(filename), "/proc/%d/cmdline", pid);
    f = fopen(filename, "r");
    if (!f) {
        *buf = '\0';
        return 1;
    }
    if (!fgets(buf, len, f)) {
        *buf = '\0';
        fclose(f);
        return 2;
    }
    fclose(f);
    return 0;
}

static bool push_eventlog_string(char** pos, const char* end, const char* str) {
    jint len = strlen(str);
    int space_needed = 1 + sizeof(len) + len;
    if (end - *pos < space_needed) {
        ALOGW("not enough space for string. remain=%" PRIdPTR "; needed=%d",
             end - *pos, space_needed);
        return false;
    }
    **pos = EVENT_TYPE_STRING;
    (*pos)++;
    memcpy(*pos, &len, sizeof(len));
    *pos += sizeof(len);
    memcpy(*pos, str, len);
    *pos += len;
    return true;
}

static bool push_eventlog_int(char** pos, const char* end, jint val) {
    int space_needed = 1 + sizeof(val);
    if (end - *pos < space_needed) {
        ALOGW("not enough space for int.  remain=%" PRIdPTR "; needed=%d",
             end - *pos, space_needed);
        return false;
    }
    **pos = EVENT_TYPE_INT;
    (*pos)++;
    memcpy(*pos, &val, sizeof(val));
    *pos += sizeof(val);
    return true;
}

// From frameworks/base/core/java/android/content/EventLogTags.logtags:

static const bool kEnableBinderSample = false;

#define LOGTAG_BINDER_OPERATION 52004

static void conditionally_log_binder_call(int64_t start_millis,
                                          IBinder* target, jint code) {
    int duration_ms = static_cast<int>(uptimeMillis() - start_millis);

    int sample_percent;
    if (duration_ms >= 500) {
        sample_percent = 100;
    } else {
        sample_percent = 100 * duration_ms / 500;
        if (sample_percent == 0) {
            return;
        }
        if (sample_percent < (random() % 100 + 1)) {
            return;
        }
    }

    char process_name[40];
    getprocname(getpid(), process_name, sizeof(process_name));
    String8 desc(target->getInterfaceDescriptor());

    char buf[LOGGER_ENTRY_MAX_PAYLOAD];
    buf[0] = EVENT_TYPE_LIST;
    buf[1] = 5;
    char* pos = &buf[2];
    char* end = &buf[LOGGER_ENTRY_MAX_PAYLOAD - 1];  // leave room for final \n
    if (!push_eventlog_string(&pos, end, desc.string())) return;
    if (!push_eventlog_int(&pos, end, code)) return;
    if (!push_eventlog_int(&pos, end, duration_ms)) return;
    if (!push_eventlog_string(&pos, end, process_name)) return;
    if (!push_eventlog_int(&pos, end, sample_percent)) return;
    *(pos++) = '\n';   // conventional with EVENT_TYPE_LIST apparently.
    android_bWriteLog(LOGTAG_BINDER_OPERATION, buf, pos - buf);
}

// We only measure binder call durations to potentially log them if
// we're on the main thread.
static bool should_time_binder_calls() {
  return (getpid() == gettid());
}

static jboolean android_os_BinderProxy_transact(JNIEnv* env, jobject obj,
        jint code, jobject dataObj, jobject replyObj, jint flags) // throws RemoteException
{
    if (dataObj == NULL) {
        jniThrowNullPointerException(env, NULL);
        return JNI_FALSE;
    }

    Parcel* data = parcelForJavaObject(env, dataObj);
    if (data == NULL) {
        return JNI_FALSE;
    }
    Parcel* reply = parcelForJavaObject(env, replyObj);
    if (reply == NULL && replyObj != NULL) {
        return JNI_FALSE;
    }

    IBinder* target = getBPNativeData(env, obj)->mObject.get();
    if (target == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", "Binder has been finalized!");
        return JNI_FALSE;
    }

    ALOGV("Java code calling transact on %p in Java object %p with code %" PRId32 "\n",
            target, obj, code);


    bool time_binder_calls;
    int64_t start_millis;
    if (kEnableBinderSample) {
        // Only log the binder call duration for things on the Java-level main thread.
        // But if we don't
        time_binder_calls = should_time_binder_calls();

        if (time_binder_calls) {
            start_millis = uptimeMillis();
        }
    }

    //printf("Transact from Java code to %p sending: ", target); data->print();
    status_t err = target->transact(code, *data, reply, flags);
    //if (reply) printf("Transact from Java code to %p received: ", target); reply->print();

    if (kEnableBinderSample) {
        if (time_binder_calls) {
            conditionally_log_binder_call(start_millis, target, code);
        }
    }

    if (err == NO_ERROR) {
        return JNI_TRUE;
    } else if (err == UNKNOWN_TRANSACTION) {
        return JNI_FALSE;
    }

    signalExceptionForError(env, obj, err, true /*canThrowRemoteException*/, data->dataSize());
    return JNI_FALSE;
}

static void android_os_BinderProxy_linkToDeath(JNIEnv* env, jobject obj,
        jobject recipient, jint flags) // throws RemoteException
{
    if (recipient == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }

    BinderProxyNativeData *nd = getBPNativeData(env, obj);
    IBinder* target = nd->mObject.get();

    LOGDEATH("linkToDeath: binder=%p recipient=%p\n", target, recipient);

    if (!target->localBinder()) {
        DeathRecipientList* list = nd->mOrgue.get();
        sp<JavaDeathRecipient> jdr = new JavaDeathRecipient(env, recipient, list);
        status_t err = target->linkToDeath(jdr, NULL, flags);
        if (err != NO_ERROR) {
            // Failure adding the death recipient, so clear its reference
            // now.
            jdr->clearReference();
            signalExceptionForError(env, obj, err, true /*canThrowRemoteException*/);
        }
    }
}

static jboolean android_os_BinderProxy_unlinkToDeath(JNIEnv* env, jobject obj,
                                                 jobject recipient, jint flags)
{
    jboolean res = JNI_FALSE;
    if (recipient == NULL) {
        jniThrowNullPointerException(env, NULL);
        return res;
    }

    BinderProxyNativeData* nd = getBPNativeData(env, obj);
    IBinder* target = nd->mObject.get();
    if (target == NULL) {
        ALOGW("Binder has been finalized when calling linkToDeath() with recip=%p)\n", recipient);
        return JNI_FALSE;
    }

    LOGDEATH("unlinkToDeath: binder=%p recipient=%p\n", target, recipient);

    if (!target->localBinder()) {
        status_t err = NAME_NOT_FOUND;

        // If we find the matching recipient, proceed to unlink using that
        DeathRecipientList* list = nd->mOrgue.get();
        sp<JavaDeathRecipient> origJDR = list->find(recipient);
        LOGDEATH("   unlink found list %p and JDR %p", list, origJDR.get());
        if (origJDR != NULL) {
            wp<IBinder::DeathRecipient> dr;
            err = target->unlinkToDeath(origJDR, NULL, flags, &dr);
            if (err == NO_ERROR && dr != NULL) {
                sp<IBinder::DeathRecipient> sdr = dr.promote();
                JavaDeathRecipient* jdr = static_cast<JavaDeathRecipient*>(sdr.get());
                if (jdr != NULL) {
                    jdr->clearReference();
                }
            }
        }

        if (err == NO_ERROR || err == DEAD_OBJECT) {
            res = JNI_TRUE;
        } else {
            jniThrowException(env, "java/util/NoSuchElementException",
                              "Death link does not exist");
        }
    }

    return res;
}

static void BinderProxy_destroy(void* rawNativeData)
{
    // Don't race with construction/initialization
    AutoMutex _l(gProxyLock);

    BinderProxyNativeData * nativeData = (BinderProxyNativeData *) rawNativeData;
    LOGDEATH("Destroying BinderProxy: binder=%p drl=%p\n",
            nativeData->mObject.get(), nativeData->mOrgue.get());
    delete nativeData;
    IPCThreadState::self()->flushCommands();
    --gNumProxies;
}

JNIEXPORT jlong JNICALL android_os_BinderProxy_getNativeFinalizer(JNIEnv*, jclass) {
    return (jlong) BinderProxy_destroy;
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gBinderProxyMethods[] = {
     /* name, signature, funcPtr */
    {"pingBinder",          "()Z", (void*)android_os_BinderProxy_pingBinder},
    {"isBinderAlive",       "()Z", (void*)android_os_BinderProxy_isBinderAlive},
    {"getInterfaceDescriptor", "()Ljava/lang/String;", (void*)android_os_BinderProxy_getInterfaceDescriptor},
    {"transactNative",      "(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z", (void*)android_os_BinderProxy_transact},
    {"linkToDeath",         "(Landroid/os/IBinder$DeathRecipient;I)V", (void*)android_os_BinderProxy_linkToDeath},
    {"unlinkToDeath",       "(Landroid/os/IBinder$DeathRecipient;I)Z", (void*)android_os_BinderProxy_unlinkToDeath},
    {"getNativeFinalizer",  "()J", (void*)android_os_BinderProxy_getNativeFinalizer},
};

const char* const kBinderProxyPathName = "android/os/BinderProxy";

static int int_register_android_os_BinderProxy(JNIEnv* env)
{
    jclass clazz = FindClassOrDie(env, "java/lang/Error");
    gErrorOffsets.mClass = MakeGlobalRefOrDie(env, clazz);

    clazz = FindClassOrDie(env, kBinderProxyPathName);
    gBinderProxyOffsets.mClass = MakeGlobalRefOrDie(env, clazz);
    gBinderProxyOffsets.mGetInstance = GetStaticMethodIDOrDie(env, clazz, "getInstance",
            "(JJ)Landroid/os/BinderProxy;");
    gBinderProxyOffsets.mSendDeathNotice = GetStaticMethodIDOrDie(env, clazz, "sendDeathNotice",
            "(Landroid/os/IBinder$DeathRecipient;)V");
    gBinderProxyOffsets.mDumpProxyDebugInfo = GetStaticMethodIDOrDie(env, clazz, "dumpProxyDebugInfo",
            "()V");
    gBinderProxyOffsets.mNativeData = GetFieldIDOrDie(env, clazz, "mNativeData", "J");

    clazz = FindClassOrDie(env, "java/lang/Class");
    gClassOffsets.mGetName = GetMethodIDOrDie(env, clazz, "getName", "()Ljava/lang/String;");

    return RegisterMethodsOrDie(
        env, kBinderProxyPathName,
        gBinderProxyMethods, NELEM(gBinderProxyMethods));
}

// ****************************************************************************
// ****************************************************************************
// ****************************************************************************

int register_android_os_Binder(JNIEnv* env)
{
    if (int_register_android_os_Binder(env) < 0)
        return -1;
    if (int_register_android_os_BinderInternal(env) < 0)
        return -1;
    if (int_register_android_os_BinderProxy(env) < 0)
        return -1;

    jclass clazz = FindClassOrDie(env, "android/util/Log");
    gLogOffsets.mClass = MakeGlobalRefOrDie(env, clazz);
    gLogOffsets.mLogE = GetStaticMethodIDOrDie(env, clazz, "e",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I");

    clazz = FindClassOrDie(env, "android/os/ParcelFileDescriptor");
    gParcelFileDescriptorOffsets.mClass = MakeGlobalRefOrDie(env, clazz);
    gParcelFileDescriptorOffsets.mConstructor = GetMethodIDOrDie(env, clazz, "<init>",
                                                                 "(Ljava/io/FileDescriptor;)V");

    clazz = FindClassOrDie(env, "android/os/StrictMode");
    gStrictModeCallbackOffsets.mClass = MakeGlobalRefOrDie(env, clazz);
    gStrictModeCallbackOffsets.mCallback = GetStaticMethodIDOrDie(env, clazz,
            "onBinderStrictModePolicyChange", "(I)V");

    clazz = FindClassOrDie(env, "java/lang/Thread");
    gThreadDispatchOffsets.mClass = MakeGlobalRefOrDie(env, clazz);
    gThreadDispatchOffsets.mDispatchUncaughtException = GetMethodIDOrDie(env, clazz,
            "dispatchUncaughtException", "(Ljava/lang/Throwable;)V");
    gThreadDispatchOffsets.mCurrentThread = GetStaticMethodIDOrDie(env, clazz, "currentThread",
            "()Ljava/lang/Thread;");

    return 0;
}
