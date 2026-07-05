#include "chunkup_settings_api.h"

#include <jni.h>
#include <string.h>

namespace {

jclass g_snapshotClass = nullptr;
jfieldID g_gpuChunkLoadOnLoaded = nullptr;
jfieldID g_gpuSkylightApply = nullptr;
jfieldID g_gpuChunkLoadSummaryInterval = nullptr;
jfieldID g_gpuChunkLoadBatchSize = nullptr;
jfieldID g_gpuSections = nullptr;
jfieldID g_nativeDir = nullptr;
jfieldID g_rustLogLevel = nullptr;

bool ensureFieldIds(JNIEnv *env)
{
    if (g_snapshotClass != nullptr) {
        return true;
    }

    jclass localClass = env->FindClass("cn/sanrolnet/chunkup/config/ChunkupSettingsSnapshot");
    if (localClass == nullptr) {
        return false;
    }

    g_snapshotClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    if (g_snapshotClass == nullptr) {
        return false;
    }

    g_gpuChunkLoadOnLoaded = env->GetFieldID(g_snapshotClass, "gpuChunkLoadOnLoaded", "Z");
    g_gpuSkylightApply = env->GetFieldID(g_snapshotClass, "gpuSkylightApply", "Z");
    g_gpuChunkLoadSummaryInterval = env->GetFieldID(g_snapshotClass, "gpuChunkLoadSummaryInterval", "I");
    g_gpuChunkLoadBatchSize = env->GetFieldID(g_snapshotClass, "gpuChunkLoadBatchSize", "I");
    g_gpuSections = env->GetFieldID(g_snapshotClass, "gpuSections", "Z");
    g_nativeDir = env->GetFieldID(g_snapshotClass, "nativeDir", "Ljava/lang/String;");
    g_rustLogLevel = env->GetFieldID(g_snapshotClass, "rustLogLevel", "Ljava/lang/String;");

    return g_gpuChunkLoadOnLoaded && g_gpuSkylightApply && g_gpuChunkLoadSummaryInterval
        && g_gpuChunkLoadBatchSize && g_gpuSections && g_nativeDir && g_rustLogLevel;
}

void readSnapshot(JNIEnv *env, jobject snapshot, ChunkupSettingsNative *native)
{
    memset(native, 0, sizeof(*native));
    native->version = 1;
    native->gpu_chunk_load_on_loaded = env->GetBooleanField(snapshot, g_gpuChunkLoadOnLoaded) ? 1 : 0;
    native->gpu_skylight_apply = env->GetBooleanField(snapshot, g_gpuSkylightApply) ? 1 : 0;
    native->gpu_chunk_load_summary_interval = env->GetIntField(snapshot, g_gpuChunkLoadSummaryInterval);
    native->gpu_chunk_load_batch_size = env->GetIntField(snapshot, g_gpuChunkLoadBatchSize);
    native->gpu_sections = env->GetBooleanField(snapshot, g_gpuSections) ? 1 : 0;

    auto readString = [&](jfieldID fieldId, char *dest, size_t destSize) {
        const auto jvalue = reinterpret_cast<jstring>(env->GetObjectField(snapshot, fieldId));
        if (jvalue == nullptr) {
            dest[0] = '\0';
            return;
        }
        const char *utf = env->GetStringUTFChars(jvalue, nullptr);
        if (utf != nullptr) {
            strncpy(dest, utf, destSize - 1);
            dest[destSize - 1] = '\0';
            env->ReleaseStringUTFChars(jvalue, utf);
        }
        env->DeleteLocalRef(jvalue);
    };

    readString(g_nativeDir, native->native_dir, CHUNKUP_SETTINGS_PATH_MAX);
    readString(g_rustLogLevel, native->rust_log_level, sizeof(native->rust_log_level));
}

void writeSnapshot(JNIEnv *env, jobject snapshot, const ChunkupSettingsNative *native)
{
    env->SetBooleanField(snapshot, g_gpuChunkLoadOnLoaded, native->gpu_chunk_load_on_loaded != 0);
    env->SetBooleanField(snapshot, g_gpuSkylightApply, native->gpu_skylight_apply != 0);
    env->SetIntField(snapshot, g_gpuChunkLoadSummaryInterval, native->gpu_chunk_load_summary_interval);
    env->SetIntField(snapshot, g_gpuChunkLoadBatchSize, native->gpu_chunk_load_batch_size);
    env->SetBooleanField(snapshot, g_gpuSections, native->gpu_sections != 0);

    const jstring nativeDir = env->NewStringUTF(native->native_dir);
    const jstring rustLog = env->NewStringUTF(native->rust_log_level);
    env->SetObjectField(snapshot, g_nativeDir, nativeDir);
    env->SetObjectField(snapshot, g_rustLogLevel, rustLog);
    env->DeleteLocalRef(nativeDir);
    env->DeleteLocalRef(rustLog);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_cn_sanrolnet_chunkup_client_settings_SettingsNative_nativeIsAvailable(
    JNIEnv * /*env*/,
    jclass /*clazz*/)
{
    return chunkup_settings_is_available();
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_sanrolnet_chunkup_client_settings_SettingsNative_nativeShowSettingsDialog(
    JNIEnv *env,
    jclass /*clazz*/,
    jobject snapshot)
{
    if (!ensureFieldIds(env) || snapshot == nullptr) {
        return -1;
    }

    ChunkupSettingsNative native{};
    readSnapshot(env, snapshot, &native);

    const int32_t result = chunkup_settings_show_dialog(&native);
    if (result == 1) {
        writeSnapshot(env, snapshot, &native);
    }
    return result;
}
