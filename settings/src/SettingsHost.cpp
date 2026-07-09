#include "chunkup_settings_api.h"

#include "ConfigManager.h"
#include "MainWindow.h"

#include <QApplication>
#include <QByteArray>
#include <QString>

#include <cstring>

namespace {

QApplication *g_app = nullptr;
int g_argc = 1;
char g_arg0[] = "chunkup";
char *g_argv[] = {g_arg0, nullptr};

void ensureQtApplication()
{
    if (QApplication::instance() != nullptr) {
        return;
    }
    g_app = new QApplication(g_argc, g_argv);
}

ChunkupSettings toQtSettings(const ChunkupSettingsNative &native)
{
    ChunkupSettings settings = ChunkupSettings::defaults();
    settings.instantLoad = native.instant_load != 0;
    settings.gpuWorldGen = native.gpu_world_gen != 0;
    settings.gpuDensityBatch = native.gpu_density_batch != 0;
    settings.preRenderOnLoad = native.pre_render_on_load != 0;
    settings.preRenderBudgetPerFrame = native.pre_render_budget;
    settings.layeredSections = native.layered_sections != 0;
    settings.layeredSectionsRate = native.layered_sections_rate;
    settings.forceGpu = native.force_gpu != 0;
    settings.gpuChunkLoadOnGenerated = native.gpu_chunk_load_on_generated != 0;
    settings.gpuChunkLoadOnLoaded = native.gpu_chunk_load_on_loaded != 0;
    settings.gpuSkylightApply = native.gpu_skylight_apply != 0;
    settings.gpuChunkLoadSummaryInterval = native.gpu_chunk_load_summary_interval;
    settings.gpuChunkLoadBatchSize = native.gpu_chunk_load_batch_size;
    settings.gpuSections = native.gpu_sections != 0;
    settings.f3Debug = native.f3_debug != 0;
    settings.debugProbe = native.debug_probe != 0;
    settings.nativeDir = QString::fromUtf8(native.native_dir);
    settings.rustLogLevel = QString::fromUtf8(native.rust_log_level);
    return settings;
}

void toNativeSettings(const ChunkupSettings &settings, ChunkupSettingsNative *native)
{
    if (native == nullptr) {
        return;
    }
    native->version = settings.version;
    native->instant_load = settings.instantLoad ? 1 : 0;
    native->gpu_world_gen = settings.gpuWorldGen ? 1 : 0;
    native->gpu_density_batch = settings.gpuDensityBatch ? 1 : 0;
    native->pre_render_on_load = settings.preRenderOnLoad ? 1 : 0;
    native->pre_render_budget = settings.preRenderBudgetPerFrame;
    native->layered_sections = settings.layeredSections ? 1 : 0;
    native->layered_sections_rate = settings.layeredSectionsRate;
    native->force_gpu = settings.forceGpu ? 1 : 0;
    native->gpu_chunk_load_on_generated = settings.gpuChunkLoadOnGenerated ? 1 : 0;
    native->gpu_chunk_load_on_loaded = settings.gpuChunkLoadOnLoaded ? 1 : 0;
    native->gpu_skylight_apply = settings.gpuSkylightApply ? 1 : 0;
    native->gpu_chunk_load_summary_interval = settings.gpuChunkLoadSummaryInterval;
    native->gpu_chunk_load_batch_size = settings.gpuChunkLoadBatchSize;
    native->gpu_sections = settings.gpuSections ? 1 : 0;
    native->f3_debug = settings.f3Debug ? 1 : 0;
    native->debug_probe = settings.debugProbe ? 1 : 0;

    const QByteArray nativeDir = settings.nativeDir.toUtf8();
    const QByteArray rustLog = settings.rustLogLevel.toUtf8();
    strncpy(native->native_dir, nativeDir.constData(), CHUNKUP_SETTINGS_PATH_MAX - 1);
    native->native_dir[CHUNKUP_SETTINGS_PATH_MAX - 1] = '\0';
    strncpy(native->rust_log_level, rustLog.constData(), sizeof(native->rust_log_level) - 1);
    native->rust_log_level[sizeof(native->rust_log_level) - 1] = '\0';
}

} // namespace

extern "C" CHUNKUP_SETTINGS_API int32_t chunkup_settings_is_available(void)
{
    return 1;
}

extern "C" CHUNKUP_SETTINGS_API int32_t chunkup_settings_load(ChunkupSettingsNative *out)
{
    if (out == nullptr) {
        return -1;
    }
    toNativeSettings(ConfigManager::load(), out);
    return 0;
}

extern "C" CHUNKUP_SETTINGS_API int32_t chunkup_settings_save(const ChunkupSettingsNative *settings)
{
    if (settings == nullptr) {
        return -1;
    }
    return ConfigManager::save(toQtSettings(*settings), nullptr) ? 0 : -1;
}

extern "C" CHUNKUP_SETTINGS_API int32_t chunkup_settings_show_dialog(ChunkupSettingsNative *inout)
{
    if (inout == nullptr) {
        return -1;
    }

    try {
        ensureQtApplication();

        MainWindow dialog(nullptr);
        dialog.applySettings(toQtSettings(*inout));
        const int result = dialog.exec();
        if (result != static_cast<int>(QDialog::Accepted)) {
            return 0;
        }

        const ChunkupSettings saved = dialog.collectSettings();
        QString errorMessage;
        if (!ConfigManager::save(saved, &errorMessage)) {
            return -1;
        }

        toNativeSettings(saved, inout);
        return 1;
    } catch (...) {
        return -1;
    }
}
