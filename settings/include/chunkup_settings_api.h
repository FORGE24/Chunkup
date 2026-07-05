#pragma once

#include <stdint.h>

#ifdef _WIN32
#  ifdef CHUNKUP_SETTINGS_EXPORT_BUILD
#    define CHUNKUP_SETTINGS_API __declspec(dllexport)
#  else
#    define CHUNKUP_SETTINGS_API __declspec(dllimport)
#  endif
#else
#  define CHUNKUP_SETTINGS_API __attribute__((visibility("default")))
#endif

#define CHUNKUP_SETTINGS_PATH_MAX 512

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ChunkupSettingsNative {
    int32_t version;
    int32_t gpu_chunk_load_on_loaded;
    int32_t gpu_skylight_apply;
    int32_t gpu_chunk_load_summary_interval;
    int32_t gpu_chunk_load_batch_size;
    int32_t gpu_sections;
    char native_dir[CHUNKUP_SETTINGS_PATH_MAX];
    char rust_log_level[128];
} ChunkupSettingsNative;

/** 1 = Qt Widgets 可用 */
CHUNKUP_SETTINGS_API int32_t chunkup_settings_is_available(void);

/**
 * 模态设置对话框。inout 传入当前值，用户确认后写回并持久化 JSON。
 * 返回 1=已保存, 0=取消, -1=错误
 */
CHUNKUP_SETTINGS_API int32_t chunkup_settings_show_dialog(ChunkupSettingsNative *inout);

CHUNKUP_SETTINGS_API int32_t chunkup_settings_load(ChunkupSettingsNative *out);
CHUNKUP_SETTINGS_API int32_t chunkup_settings_save(const ChunkupSettingsNative *settings);

#ifdef __cplusplus
}
#endif
