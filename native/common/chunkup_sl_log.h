#pragma once

#include <stdio.h>
#include <time.h>

#ifdef __cplusplus
extern "C" {
#endif

/** SL Studio Log Standard — English structured line to stderr. */
void chunkup_sl_log_write(
    int level_id,
    const char* level_name,
    const char* module,
    const char* actor,
    const char* content,
    const char* params
);

#define CHUNKUP_SL_PROJECT "[Multi-Lang-Chunkup]"

#define CHUNKUP_SL_INFO_INIT(module, content, params) \
    chunkup_sl_log_write(4, "INFO_INIT", module, "Service:chunkup_native", content, params)

#define CHUNKUP_SL_INFO_START(module, content, params) \
    chunkup_sl_log_write(5, "INFO_START", module, "Service:chunkup_native", content, params)

#define CHUNKUP_SL_INFO_COMPLETE(module, content, params) \
    chunkup_sl_log_write(7, "INFO_COMPLETE", module, "Service:chunkup_native", content, params)

#define CHUNKUP_SL_INFO_STATUS(module, content, params) \
    chunkup_sl_log_write(8, "INFO_STATUS", module, "Service:chunkup_native", content, params)

#define CHUNKUP_SL_DEBUG_FUNC(module, content, params) \
    chunkup_sl_log_write(3, "DEBUG_FUNC", module, "Service:chunkup_native", content, params)

#ifdef __cplusplus
}
#endif
