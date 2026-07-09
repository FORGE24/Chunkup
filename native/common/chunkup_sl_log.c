#include "chunkup_sl_log.h"

#include <stdarg.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#endif

static int g_sl_event_seq = 0;

static void chunkup_sl_timestamp(char* out, size_t out_size) {
    if (!out || out_size < 24) {
        return;
    }
#ifdef _WIN32
    SYSTEMTIME st;
    GetLocalTime(&st);
    snprintf(
        out,
        out_size,
        "%04d-%02d-%02d %02d:%02d:%02d.%03d",
        (int)st.wYear,
        (int)st.wMonth,
        (int)st.wDay,
        (int)st.wHour,
        (int)st.wMinute,
        (int)st.wSecond,
        (int)st.wMilliseconds
    );
#else
    struct timespec ts;
    struct tm local_tm;
    if (clock_gettime(CLOCK_REALTIME, &ts) != 0 || !localtime_r(&ts.tv_sec, &local_tm)) {
        strncpy(out, "1970-01-01 00:00:00.000", out_size - 1);
        out[out_size - 1] = '\0';
        return;
    }
    snprintf(
        out,
        out_size,
        "%04d-%02d-%02d %02d:%02d:%02d.%03ld",
        local_tm.tm_year + 1900,
        local_tm.tm_mon + 1,
        local_tm.tm_mday,
        local_tm.tm_hour,
        local_tm.tm_min,
        local_tm.tm_sec,
        ts.tv_nsec / 1000000L
    );
#endif
}

void chunkup_sl_log_write(
    int level_id,
    const char* level_name,
    const char* module,
    const char* actor,
    const char* content,
    const char* params
) {
    char ts[32];
    char event_id[32];
    chunkup_sl_timestamp(ts, sizeof(ts));
    g_sl_event_seq = (g_sl_event_seq + 1) % 1000;
    snprintf(event_id, sizeof(event_id), "CHUP-NATIVE-%03d", g_sl_event_seq);

    fprintf(
        stderr,
        "[%d-%s] [%s] %s [%s] [%s] [%s] | Content:[%s] | Params:[%s]\n",
        level_id,
        level_name ? level_name : "INFO_STATUS",
        ts,
        CHUNKUP_SL_PROJECT,
        module ? module : "Native Module",
        actor ? actor : "Service:chunkup_native",
        event_id,
        content ? content : "",
        params ? params : ""
    );
    fflush(stderr);
}
