#include "ConfigManager.h"

#include <QDir>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QStandardPaths>

namespace {

QString envOrEmpty(const char *name)
{
    const QByteArray value = qgetenv(name);
    return value.isEmpty() ? QString() : QString::fromUtf8(value);
}

} // namespace

ChunkupSettings ChunkupSettings::defaults()
{
    return ChunkupSettings{};
}

QJsonObject ChunkupSettings::toJson() const
{
    QJsonObject object;
    object.insert(QStringLiteral("version"), version);
    object.insert(QStringLiteral("gpuChunkLoadOnLoaded"), gpuChunkLoadOnLoaded);
    object.insert(QStringLiteral("gpuSkylightApply"), gpuSkylightApply);
    object.insert(QStringLiteral("gpuChunkLoadSummaryInterval"), gpuChunkLoadSummaryInterval);
    object.insert(QStringLiteral("gpuChunkLoadBatchSize"), gpuChunkLoadBatchSize);
    object.insert(QStringLiteral("gpuSections"), gpuSections);
    object.insert(QStringLiteral("nativeDir"), nativeDir);
    object.insert(QStringLiteral("rustLogLevel"), rustLogLevel);
    return object;
}

ChunkupSettings ChunkupSettings::fromJson(const QJsonObject &object)
{
    ChunkupSettings settings = ChunkupSettings::defaults();
    settings.version = object.value(QStringLiteral("version")).toInt(1);
    settings.gpuChunkLoadOnLoaded = object.value(QStringLiteral("gpuChunkLoadOnLoaded")).toBool(false);
    settings.gpuSkylightApply = object.value(QStringLiteral("gpuSkylightApply")).toBool(false);
    settings.gpuChunkLoadSummaryInterval = object.value(QStringLiteral("gpuChunkLoadSummaryInterval")).toInt(256);
    settings.gpuChunkLoadBatchSize = object.value(QStringLiteral("gpuChunkLoadBatchSize")).toInt(32);
    settings.gpuSections = object.value(QStringLiteral("gpuSections")).toBool(true);
    settings.nativeDir = object.value(QStringLiteral("nativeDir")).toString().trimmed();
    settings.rustLogLevel = object.value(QStringLiteral("rustLogLevel"))
                                .toString(QStringLiteral("warn,chunkup_core=warn"))
                                .trimmed();
    return settings;
}

QString ConfigManager::settingsDirectory()
{
    const QString appData = envOrEmpty("APPDATA");
    if (!appData.isEmpty()) {
        return QDir(appData).filePath(QStringLiteral("Chunkup"));
    }

    const QString configHome = QStandardPaths::writableLocation(QStandardPaths::ConfigLocation);
    return QDir(configHome).filePath(QStringLiteral("chunkup"));
}

QString ConfigManager::settingsFilePath()
{
    return QDir(settingsDirectory()).filePath(QStringLiteral("settings.json"));
}

ChunkupSettings ConfigManager::load()
{
    const QString path = settingsFilePath();
    QFile file(path);
    if (!file.exists() || !file.open(QIODevice::ReadOnly)) {
        return ChunkupSettings::defaults();
    }

    const QJsonDocument document = QJsonDocument::fromJson(file.readAll());
    if (!document.isObject()) {
        return ChunkupSettings::defaults();
    }

    return ChunkupSettings::fromJson(document.object());
}

bool ConfigManager::save(const ChunkupSettings &settings, QString *errorMessage)
{
    const QString directory = settingsDirectory();
    QDir().mkpath(directory);

    const QString path = settingsFilePath();
    QFile file(path);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        if (errorMessage) {
            *errorMessage = QStringLiteral("无法写入 %1").arg(path);
        }
        return false;
    }

    const QJsonDocument document(settings.toJson());
    file.write(document.toJson(QJsonDocument::Indented));
    file.close();

    if (errorMessage) {
        errorMessage->clear();
    }
    return true;
}

QString ConfigManager::buildJvmArguments(const ChunkupSettings &settings)
{
    QStringList args;
    args << QStringLiteral("-Dchunkup.gpuChunkLoad.loaded=%1").arg(settings.gpuChunkLoadOnLoaded ? QStringLiteral("true") : QStringLiteral("false"));
    args << QStringLiteral("-Dchunkup.gpuSkylightApply=%1").arg(settings.gpuSkylightApply ? QStringLiteral("true") : QStringLiteral("false"));
    args << QStringLiteral("-Dchunkup.gpuChunkLoad.summaryInterval=%1").arg(settings.gpuChunkLoadSummaryInterval);
    args << QStringLiteral("-Dchunkup.gpuChunkLoad.batchSize=%1").arg(settings.gpuChunkLoadBatchSize);
    args << QStringLiteral("-Dchunkup.gpuSections=%1").arg(settings.gpuSections ? QStringLiteral("true") : QStringLiteral("false"));

    if (!settings.nativeDir.isEmpty()) {
        const QString nativeDir = QDir::toNativeSeparators(settings.nativeDir);
        args << QStringLiteral("-Dchunkup.native.dir=%1").arg(nativeDir);
        args << QStringLiteral("-Djava.library.path=%1").arg(nativeDir);
    }

    if (!settings.rustLogLevel.trimmed().isEmpty()) {
        args << QStringLiteral("-DRUST_LOG=%1").arg(settings.rustLogLevel.trimmed());
    }

    return args.join(QStringLiteral("\n"));
}
