#include "chunkup_settings_api.h"

#include <jni.h>
#include <string.h>

namespace {

jclass g_snapshotClass = nullptr;
jfieldID g_instantLoad = nullptr;
jfieldID g_gpuWorldGen = nullptr;
jfieldID g_gpuDensityBatch = nullptr;
jfieldID g_preRenderOnLoad = nullptr;
jfieldID g_preRenderBudgetPerFrame = nullptr;
jfieldID g_layeredSections = nullptr;
jfieldID g_layeredSectionsRate = nullptr;
jfieldID g_forceGpu = nullptr;
jfieldID g_gpuChunkLoadOnGenerated = nullptr;
jfieldID g_gpuChunkLoadOnLoaded = nullptr;
jfieldID g_gpuSkylightApply = nullptr;
jfieldID g_gpuChunkLoadSummaryInterval = nullptr;
jfieldID g_gpuChunkLoadBatchSize = nullptr;
jfieldID g_gpuSections = nullptr;
jfieldID g_f3Debug = nullptr;
jfieldID g_debugProbe = nullptr;
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

    g_instantLoad = env->GetFieldID(g_snapshotClass, "instantLoad", "Z");
    g_gpuWorldGen = env->GetFieldID(g_snapshotClass, "gpuWorldGen", "Z");
    g_gpuDensityBatch = env->GetFieldID(g_snapshotClass, "gpuDensityBatch", "Z");
    g_preRenderOnLoad = env->GetFieldID(g_snapshotClass, "preRenderOnLoad", "Z");
    g_preRenderBudgetPerFrame = env->GetFieldID(g_snapshotClass, "preRenderBudgetPerFrame", "I");
    g_layeredSections = env->GetFieldID(g_snapshotClass, "layeredSections", "Z");
    g_layeredSectionsRate = env->GetFieldID(g_snapshotClass, "layeredSectionsRate", "I");
    g_forceGpu = env->GetFieldID(g_snapshotClass, "forceGpu", "Z");
    g_gpuChunkLoadOnGenerated = env->GetFieldID(g_snapshotClass, "gpuChunkLoadOnGenerated", "Z");
    g_gpuChunkLoadOnLoaded = env->GetFieldID(g_snapshotClass, "gpuChunkLoadOnLoaded", "Z");
    g_gpuSkylightApply = env->GetFieldID(g_snapshotClass, "gpuSkylightApply", "Z");
    g_gpuChunkLoadSummaryInterval = env->GetFieldID(g_snapshotClass, "gpuChunkLoadSummaryInterval", "I");
    g_gpuChunkLoadBatchSize = env->GetFieldID(g_snapshotClass, "gpuChunkLoadBatchSize", "I");
    g_gpuSections = env->GetFieldID(g_snapshotClass, "gpuSections", "Z");
    g_f3Debug = env->GetFieldID(g_snapshotClass, "f3Debug", "Z");
    g_debugProbe = env->GetFieldID(g_snapshotClass, "debugProbe", "Z");
    g_nativeDir = env->GetFieldID(g_snapshotClass, "nativeDir", "Ljava/lang/String;");
    g_rustLogLevel = env->GetFieldID(g_snapshotClass, "rustLogLevel", "Ljava/lang/String;");

    return g_instantLoad && g_gpuWorldGen && g_gpuDensityBatch && g_preRenderOnLoad && g_preRenderBudgetPerFrame && g_layeredSections
        && g_layeredSectionsRate && g_forceGpu && g_gpuChunkLoadOnGenerated && g_gpuChunkLoadOnLoaded
        && g_gpuSkylightApply && g_gpuChunkLoadSummaryInterval && g_gpuChunkLoadBatchSize && g_gpuSections
        && g_f3Debug && g_debugProbe && g_nativeDir && g_rustLogLevel;
}

void readSnapshot(JNIEnv *env, jobject snapshot, ChunkupSettingsNative *native)
{
    memset(native, 0, sizeof(*native));
    native->version = 2;
    native->instant_load = env->GetBooleanField(snapshot, g_instantLoad) ? 1 : 0;
    native->gpu_world_gen = env->GetBooleanField(snapshot, g_gpuWorldGen) ? 1 : 0;
    native->gpu_density_batch = env->GetBooleanField(snapshot, g_gpuDensityBatch) ? 1 : 0;
    native->pre_render_on_load = env->GetBooleanField(snapshot, g_preRenderOnLoad) ? 1 : 0;
    native->pre_render_budget = env->GetIntField(snapshot, g_preRenderBudgetPerFrame);
    native->layered_sections = env->GetBooleanField(snapshot, g_layeredSections) ? 1 : 0;
    native->layered_sections_rate = env->GetIntField(snapshot, g_layeredSectionsRate);
    native->force_gpu = env->GetBooleanField(snapshot, g_forceGpu) ? 1 : 0;
    native->gpu_chunk_load_on_generated = env->GetBooleanField(snapshot, g_gpuChunkLoadOnGenerated) ? 1 : 0;
    native->gpu_chunk_load_on_loaded = env->GetBooleanField(snapshot, g_gpuChunkLoadOnLoaded) ? 1 : 0;
    native->gpu_skylight_apply = env->GetBooleanField(snapshot, g_gpuSkylightApply) ? 1 : 0;
    native->gpu_chunk_load_summary_interval = env->GetIntField(snapshot, g_gpuChunkLoadSummaryInterval);
    native->gpu_chunk_load_batch_size = env->GetIntField(snapshot, g_gpuChunkLoadBatchSize);
    native->gpu_sections = env->GetBooleanField(snapshot, g_gpuSections) ? 1 : 0;
    native->f3_debug = env->GetBooleanField(snapshot, g_f3Debug) ? 1 : 0;
    native->debug_probe = env->GetBooleanField(snapshot, g_debugProbe) ? 1 : 0;

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
    env->SetBooleanField(snapshot, g_instantLoad, native->instant_load != 0);
    env->SetBooleanField(snapshot, g_gpuWorldGen, native->gpu_world_gen != 0);
    env->SetBooleanField(snapshot, g_gpuDensityBatch, native->gpu_density_batch != 0);
    env->SetBooleanField(snapshot, g_preRenderOnLoad, native->pre_render_on_load != 0);
    env->SetIntField(snapshot, g_preRenderBudgetPerFrame, native->pre_render_budget);
    env->SetBooleanField(snapshot, g_layeredSections, native->layered_sections != 0);
    env->SetIntField(snapshot, g_layeredSectionsRate, native->layered_sections_rate);
    env->SetBooleanField(snapshot, g_forceGpu, native->force_gpu != 0);
    env->SetBooleanField(snapshot, g_gpuChunkLoadOnGenerated, native->gpu_chunk_load_on_generated != 0);
    env->SetBooleanField(snapshot, g_gpuChunkLoadOnLoaded, native->gpu_chunk_load_on_loaded != 0);
    env->SetBooleanField(snapshot, g_gpuSkylightApply, native->gpu_skylight_apply != 0);
    env->SetIntField(snapshot, g_gpuChunkLoadSummaryInterval, native->gpu_chunk_load_summary_interval);
    env->SetIntField(snapshot, g_gpuChunkLoadBatchSize, native->gpu_chunk_load_batch_size);
    env->SetBooleanField(snapshot, g_gpuSections, native->gpu_sections != 0);
    env->SetBooleanField(snapshot, g_f3Debug, native->f3_debug != 0);
    env->SetBooleanField(snapshot, g_debugProbe, native->debug_probe != 0);

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
