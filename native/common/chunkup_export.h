#pragma once

/**
 * 跨平台 DLL/so 导出宏。构建 GPU 后端时定义 CHUNKUP_EXPORT_BUILD。
 */
#if defined(_WIN32) || defined(__CYGWIN__)
#  ifdef CHUNKUP_EXPORT_BUILD
#    define CHUNKUP_API __declspec(dllexport)
#  else
#    define CHUNKUP_API __declspec(dllimport)
#  endif
#else
#  define CHUNKUP_API __attribute__((visibility("default")))
#endif
