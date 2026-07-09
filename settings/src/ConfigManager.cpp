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
    object.insert(QStringLiteral("instantLoad"), instantLoad);
    object.insert(QStringLiteral("gpuWorldGen"), gpuWorldGen);
    object.insert(QStringLiteral("gpuDensityBatch"), gpuDensityBatch);
    object.insert(QStringLiteral("preRenderOnLoad"), preRenderOnLoad);
    object.insert(QStringLiteral("preRenderBudgetPerFrame"), preRenderBudgetPerFrame);
    object.insert(QStringLiteral("layeredSections"), layeredSections);
    object.insert(QStringLiteral("layeredSectionsRate"), layeredSectionsRate);
    object.insert(QStringLiteral("forceGpu"), forceGpu);
    object.insert(QStringLiteral("gpuChunkLoadOnGenerated"), gpuChunkLoadOnGenerated);
    object.insert(QStringLiteral("gpuChunkLoadOnLoaded"), gpuChunkLoadOnLoaded);
    object.insert(QStringLiteral("gpuSkylightApply"), gpuSkylightApply);
    object.insert(QStringLiteral("gpuChunkLoadSummaryInterval"), gpuChunkLoadSummaryInterval);
    object.insert(QStringLiteral("gpuChunkLoadBatchSize"), gpuChunkLoadBatchSize);
    object.insert(QStringLiteral("gpuSections"), gpuSections);
    object.insert(QStringLiteral("f3Debug"), f3Debug);
    object.insert(QStringLiteral("debugProbe"), debugProbe);
    object.insert(QStringLiteral("nativeDir"), nativeDir);
    object.insert(QStringLiteral("rustLogLevel"), rustLogLevel);
    return object;
}

ChunkupSettings ChunkupSettings::fromJson(const QJsonObject &object)
{
    ChunkupSettings settings = ChunkupSettings::defaults();
    settings.version = object.value(QStringLiteral("version")).toInt(2);
    settings.instantLoad = object.value(QStringLiteral("instantLoad")).toBool(false);
    settings.gpuWorldGen = object.value(QStringLiteral("gpuWorldGen")).toBool(true);
    settings.gpuDensityBatch = object.value(QStringLiteral("gpuDensityBatch")).toBool(true);
    settings.preRenderOnLoad = object.value(QStringLiteral("preRenderOnLoad")).toBool(true);
    settings.preRenderBudgetPerFrame = object.value(QStringLiteral("preRenderBudgetPerFrame")).toInt(8);
    settings.layeredSections = object.value(QStringLiteral("layeredSections")).toBool(true);
    settings.layeredSectionsRate = object.value(QStringLiteral("layeredSectionsRate")).toInt(3);
    settings.forceGpu = object.value(QStringLiteral("forceGpu")).toBool(false);
    settings.gpuChunkLoadOnGenerated = object.value(QStringLiteral("gpuChunkLoadOnGenerated")).toBool(false);
    settings.gpuChunkLoadOnLoaded = object.value(QStringLiteral("gpuChunkLoadOnLoaded")).toBool(false);
    settings.gpuSkylightApply = object.value(QStringLiteral("gpuSkylightApply")).toBool(false);
    settings.gpuChunkLoadSummaryInterval = object.value(QStringLiteral("gpuChunkLoadSummaryInterval")).toInt(256);
    settings.gpuChunkLoadBatchSize = object.value(QStringLiteral("gpuChunkLoadBatchSize")).toInt(64);
    settings.gpuSections = object.value(QStringLiteral("gpuSections")).toBool(false);
    settings.f3Debug = object.value(QStringLiteral("f3Debug")).toBool(true);
    settings.debugProbe = object.value(QStringLiteral("debugProbe")).toBool(false);
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
    args << QStringLiteral("-Dchunkup.instantLoad=%1").arg(settings.instantLoad ? QStringLiteral("true") : QStringLiteral("false"));
    args << QStringLiteral("-Dchunkup.gpuWorldGen=%1").arg(settings.gpuWorldGen ? QStringLiteral("true") : QStringLiteral("false"));
    args << QStringLiteral("-Dchunkup.gpuDensityBatch=%1").arg(settings.gpuDensityBatch ? QStringLiteral("true") : QStringLiteral("false"));
    args << QStringLiteral("-Dchunkup.preRenderOnLoad=%1").arg(settings.preRenderOnLoad ? QStringLiteral("true") : QStringLiteral("false"));
    args << QStringLiteral("-Dchunkup.preRender.budget=%1").arg(settings.preRenderBudgetPerFrame);
    args << QStringLiteral("-Dchunkup.layeredSections=%1").arg(settings.layeredSections ? QStringLiteral("true") : QStringLiteral("false"));
    args << QStringLiteral("-Dchunkup.layeredSections.rate=%1").arg(settings.layeredSectionsRate);
    args << QStringLiteral("-Dchunkup.forceGpu=%1").arg(settings.forceGpu ? QStringLiteral("true") : QStringLiteral("false"));
    args << QStringLiteral("-Dchunkup.gpuChunkLoad.generated=%1").arg(settings.gpuChunkLoadOnGenerated ? QStringLiteral("true") : QStringLiteral("false"));
    args << QStringLiteral("-Dchunkup.gpuChunkLoad.loaded=%1").arg(settings.gpuChunkLoadOnLoaded ? QStringLiteral("true") : QStringLiteral("false"));
    args << QStringLiteral("-Dchunkup.gpuSkylightApply=%1").arg(settings.gpuSkylightApply ? QStringLiteral("true") : QStringLiteral("false"));
    args << QStringLiteral("-Dchunkup.gpuChunkLoad.summaryInterval=%1").arg(settings.gpuChunkLoadSummaryInterval);
    args << QStringLiteral("-Dchunkup.gpuChunkLoad.batchSize=%1").arg(settings.gpuChunkLoadBatchSize);
    args << QStringLiteral("-Dchunkup.gpuSections=%1").arg(settings.gpuSections ? QStringLiteral("true") : QStringLiteral("false"));
    args << QStringLiteral("-Dchunkup.f3Debug=%1").arg(settings.f3Debug ? QStringLiteral("true") : QStringLiteral("false"));
    args << QStringLiteral("-Dchunkup.debug.probe=%1").arg(settings.debugProbe ? QStringLiteral("true") : QStringLiteral("false"));

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
