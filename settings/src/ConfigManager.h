#pragma once

#include <QJsonObject>
#include <QString>

struct ChunkupSettings {
    int version = 2;
    bool instantLoad = false;
    bool gpuWorldGen = true;
    bool gpuDensityBatch = true;
    bool preRenderOnLoad = true;
    int preRenderBudgetPerFrame = 8;
    bool layeredSections = true;
    int layeredSectionsRate = 3;
    bool forceGpu = false;
    bool gpuChunkLoadOnGenerated = false;
    bool gpuChunkLoadOnLoaded = false;
    bool gpuSkylightApply = false;
    int gpuChunkLoadSummaryInterval = 256;
    int gpuChunkLoadBatchSize = 64;
    bool gpuSections = false;
    bool f3Debug = true;
    bool debugProbe = false;
    QString nativeDir;
    QString rustLogLevel = QStringLiteral("warn,chunkup_core=warn");

    static ChunkupSettings defaults();
    QJsonObject toJson() const;
    static ChunkupSettings fromJson(const QJsonObject &object);
};

class ConfigManager {
public:
    static QString settingsFilePath();
    static QString settingsDirectory();

    static ChunkupSettings load();
    static bool save(const ChunkupSettings &settings, QString *errorMessage = nullptr);

    static QString buildJvmArguments(const ChunkupSettings &settings);
};
