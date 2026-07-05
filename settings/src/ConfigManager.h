#pragma once

#include <QJsonObject>
#include <QString>

struct ChunkupSettings {
    int version = 1;
    bool forceGpu = true;
    bool gpuChunkLoadOnLoaded = true;
    bool gpuSkylightApply = true;
    int gpuChunkLoadSummaryInterval = 256;
    int gpuChunkLoadBatchSize = 64;
    bool gpuSections = true;
    bool f3Debug = true;
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
