#pragma once

#include "chunkup_compat.h"
#include "chunkup_improved_noise.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Minecraft NormalNoise 近似：双 ImprovedNoise 混合 + 振幅表。 */
CHUNKUP_FN float chunkup_normal_noise2d(
    const ChunkupNoiseTables* tables,
    float x,
    float z,
    int first_octave,
    const float* amplitudes,
    int amp_len
) {
    float sum = 0.0f;
    float norm = 0.0f;

    for (int i = 0; i < amp_len; ++i) {
        const float amp = amplitudes[i];
        if (amp == 0.0f) {
            continue;
        }
        const int octave = first_octave + i;
        const float freq = (float)(1 << (octave < 0 ? -octave : octave));
        const float scale = octave < 0 ? 1.0f / freq : freq;

        const float n0 = chunkup_improved_noise3(tables, x * scale, 0.0f, z * scale);
        const float n1 = chunkup_improved_noise3(
            tables,
            x * scale + 17.0f,
            0.0f,
            z * scale + 17.0f
        );
        const float sample = (n0 + n1 * 1.018126964f) * 0.5f;
        sum += sample * amp;
        norm += amp;
    }

    return norm > 0.0f ? sum / norm : 0.0f;
}

CHUNKUP_FN float chunkup_normal_noise3d(
    const ChunkupNoiseTables* tables,
    float x,
    float y,
    float z,
    int first_octave,
    const float* amplitudes,
    int amp_len
) {
    float sum = 0.0f;
    float norm = 0.0f;

    for (int i = 0; i < amp_len; ++i) {
        const float amp = amplitudes[i];
        if (amp == 0.0f) {
            continue;
        }
        const int octave = first_octave + i;
        const float freq = (float)(1 << (octave < 0 ? -octave : octave));
        const float scale = octave < 0 ? 1.0f / freq : freq;

        const float n0 = chunkup_improved_noise3(tables, x * scale, y * scale, z * scale);
        const float n1 = chunkup_improved_noise3(
            tables,
            x * scale + 17.0f,
            y * scale + 17.0f,
            z * scale + 17.0f
        );
        const float sample = (n0 + n1 * 1.018126964f) * 0.5f;
        sum += sample * amp;
        norm += amp;
    }

    return norm > 0.0f ? sum / norm : 0.0f;
}

#ifdef __cplusplus
}
#endif
