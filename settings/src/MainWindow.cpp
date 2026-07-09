#include "MainWindow.h"

#include <QDesktopServices>
#include <QDialogButtonBox>
#include <QFileDialog>
#include <QMessageBox>
#include <QUrl>

MainWindow::MainWindow(QWidget *parent)
    : QDialog(parent)
{
    setWindowTitle(QStringLiteral("Chunkup 设置"));
    resize(680, 820);
    setModal(true);
    buildUi();
    refreshJvmPreview();
}

void MainWindow::buildUi()
{
    auto *layout = new QVBoxLayout(this);

    auto *sodiumGroup = new QGroupBox(QStringLiteral("Sodium 协同（默认游玩路径）"), this);
    auto *sodiumForm = new QFormLayout(sodiumGroup);

	m_instantLoad = new QCheckBox(QStringLiteral("极速加载（跳过 GPU 生成）"), sodiumGroup);
    m_gpuWorldGen = new QCheckBox(QStringLiteral("GPU 世界生成（CUDA→OpenCL→CPU）"), sodiumGroup);
    m_preRenderOnLoad = new QCheckBox(QStringLiteral("加载时预渲染（距离优先）"), sodiumGroup);
    m_preRenderBudget = new QSpinBox(sodiumGroup);
    m_preRenderBudget->setRange(1, 64);
    m_layeredSections = new QCheckBox(QStringLiteral("分层 section mesh（地表先出）"), sodiumGroup);
    m_layeredSectionsRate = new QSpinBox(sodiumGroup);
    m_layeredSectionsRate->setRange(1, 16);

    sodiumForm->addRow(m_gpuWorldGen);
    sodiumForm->addRow(m_instantLoad);
    sodiumForm->addRow(m_preRenderOnLoad);
    sodiumForm->addRow(QStringLiteral("预渲染 budget / 帧"), m_preRenderBudget);
    sodiumForm->addRow(m_layeredSections);
    sodiumForm->addRow(QStringLiteral("分层速率 / tick"), m_layeredSectionsRate);

    auto *observeGroup = new QGroupBox(QStringLiteral("观测"), this);
    auto *observeForm = new QFormLayout(observeGroup);
    m_f3Debug = new QCheckBox(QStringLiteral("F3 调试面板显示 Chunkup 详情"), observeGroup);
    m_debugProbe = new QCheckBox(QStringLiteral("性能探针日志（density.read / gpu.batch）"), observeGroup);
    observeForm->addRow(m_f3Debug);
    observeForm->addRow(m_debugProbe);

    auto *gpuGroup = new QGroupBox(QStringLiteral("高级 / GPU 实验"), this);
    auto *gpuForm = new QFormLayout(gpuGroup);

    m_gpuChunkLoadOnGenerated = new QCheckBox(QStringLiteral("GENERATED 阶段 GPU"), gpuGroup);
    m_gpuChunkLoadOnLoaded = new QCheckBox(QStringLiteral("LOADED 阶段 GPU（磁盘/传送加载）"), gpuGroup);
    m_gpuSkylightApply = new QCheckBox(QStringLiteral("将 GPU 天空光写回 Minecraft"), gpuGroup);
    m_forceGpu = new QCheckBox(QStringLiteral("强制 GPU（禁用 CPU 回退）"), gpuGroup);
    m_gpuChunkLoadBatchSize = new QSpinBox(gpuGroup);
    m_gpuChunkLoadBatchSize->setRange(1, 128);
    m_gpuChunkLoadSummaryInterval = new QSpinBox(gpuGroup);
    m_gpuChunkLoadSummaryInterval->setRange(1, 100000);
    m_gpuSections = new QCheckBox(QStringLiteral("启用 GPU Section Mesh（需 Sodium）"), gpuGroup);

    gpuForm->addRow(m_gpuChunkLoadOnGenerated);
    gpuForm->addRow(m_gpuChunkLoadOnLoaded);
    gpuForm->addRow(m_gpuSkylightApply);
    gpuForm->addRow(m_forceGpu);
    gpuForm->addRow(QStringLiteral("攒批大小"), m_gpuChunkLoadBatchSize);
    gpuForm->addRow(QStringLiteral("汇总日志间隔"), m_gpuChunkLoadSummaryInterval);
    gpuForm->addRow(m_gpuSections);

    auto *nativeGroup = new QGroupBox(QStringLiteral("Native 库"), this);
    auto *nativeForm = new QFormLayout(nativeGroup);
    auto *nativeRow = new QWidget(nativeGroup);
    auto *nativeRowLayout = new QHBoxLayout(nativeRow);
    nativeRowLayout->setContentsMargins(0, 0, 0, 0);
    m_nativeDir = new QLineEdit(nativeRow);
    auto *browseButton = new QPushButton(QStringLiteral("浏览…"), nativeRow);
    nativeRowLayout->addWidget(m_nativeDir, 1);
    nativeRowLayout->addWidget(browseButton);
    nativeForm->addRow(QStringLiteral("Native 目录"), nativeRow);

    auto *advancedGroup = new QGroupBox(QStringLiteral("高级"), this);
    auto *advancedForm = new QFormLayout(advancedGroup);
    m_rustLogLevel = new QLineEdit(advancedGroup);
    m_rustLogLevel->setPlaceholderText(QStringLiteral("warn,chunkup_core=warn"));
    advancedForm->addRow(QStringLiteral("Rust 日志 (RUST_LOG)"), m_rustLogLevel);

    auto *previewGroup = new QGroupBox(QStringLiteral("JVM 参数预览"), this);
    auto *previewLayout = new QVBoxLayout(previewGroup);
    m_jvmPreview = new QPlainTextEdit(previewGroup);
    m_jvmPreview->setReadOnly(true);
    m_jvmPreview->setMaximumBlockCount(64);
    previewLayout->addWidget(m_jvmPreview);

    m_configPathLabel = new QLabel(this);
    m_configPathLabel->setWordWrap(true);
    m_configPathLabel->setTextInteractionFlags(Qt::TextSelectableByMouse);
    m_configPathLabel->setText(QStringLiteral("配置文件：%1").arg(ConfigManager::settingsFilePath()));

    m_statusLabel = new QLabel(QStringLiteral("Sodium 协同选项即时生效；GPU 实验建议重进世界"), this);

    auto *utilityRow = new QHBoxLayout();
    auto *defaultsButton = new QPushButton(QStringLiteral("恢复默认"), this);
    auto *openDirButton = new QPushButton(QStringLiteral("打开配置目录"), this);
    utilityRow->addWidget(defaultsButton);
    utilityRow->addWidget(openDirButton);
    utilityRow->addStretch(1);

    auto *buttonBox = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel, this);
    connect(buttonBox, &QDialogButtonBox::accepted, this, &MainWindow::acceptDialog);
    connect(buttonBox, &QDialogButtonBox::rejected, this, &QDialog::reject);

    layout->addWidget(sodiumGroup);
    layout->addWidget(observeGroup);
    layout->addWidget(gpuGroup);
    layout->addWidget(nativeGroup);
    layout->addWidget(advancedGroup);
    layout->addWidget(previewGroup, 1);
    layout->addWidget(m_configPathLabel);
    layout->addWidget(m_statusLabel);
    layout->addLayout(utilityRow);
    layout->addWidget(buttonBox);

    connect(browseButton, &QPushButton::clicked, this, &MainWindow::browseNativeDir);
    connect(defaultsButton, &QPushButton::clicked, this, &MainWindow::restoreDefaults);
    connect(openDirButton, &QPushButton::clicked, this, &MainWindow::openConfigDirectory);

    const auto refreshPreview = [this]() { refreshJvmPreview(); };
    connect(m_gpuWorldGen, &QCheckBox::toggled, this, refreshPreview);
    connect(m_instantLoad, &QCheckBox::toggled, this, refreshPreview);
    connect(m_preRenderOnLoad, &QCheckBox::toggled, this, refreshPreview);
    connect(m_preRenderBudget, qOverload<int>(&QSpinBox::valueChanged), this, refreshPreview);
    connect(m_layeredSections, &QCheckBox::toggled, this, refreshPreview);
    connect(m_layeredSectionsRate, qOverload<int>(&QSpinBox::valueChanged), this, refreshPreview);
    connect(m_f3Debug, &QCheckBox::toggled, this, refreshPreview);
    connect(m_debugProbe, &QCheckBox::toggled, this, refreshPreview);
    connect(m_gpuChunkLoadOnGenerated, &QCheckBox::toggled, this, refreshPreview);
    connect(m_gpuChunkLoadOnLoaded, &QCheckBox::toggled, this, refreshPreview);
    connect(m_gpuSkylightApply, &QCheckBox::toggled, this, refreshPreview);
    connect(m_forceGpu, &QCheckBox::toggled, this, refreshPreview);
    connect(m_gpuChunkLoadBatchSize, qOverload<int>(&QSpinBox::valueChanged), this, refreshPreview);
    connect(m_gpuChunkLoadSummaryInterval, qOverload<int>(&QSpinBox::valueChanged), this, refreshPreview);
    connect(m_gpuSections, &QCheckBox::toggled, this, refreshPreview);
    connect(m_nativeDir, &QLineEdit::textChanged, this, refreshPreview);
    connect(m_rustLogLevel, &QLineEdit::textChanged, this, refreshPreview);
}

ChunkupSettings MainWindow::collectSettings() const
{
    ChunkupSettings settings;
    settings.instantLoad = m_instantLoad->isChecked();
    settings.gpuWorldGen = m_gpuWorldGen->isChecked();
    settings.preRenderOnLoad = m_preRenderOnLoad->isChecked();
    settings.preRenderBudgetPerFrame = m_preRenderBudget->value();
    settings.layeredSections = m_layeredSections->isChecked();
    settings.layeredSectionsRate = m_layeredSectionsRate->value();
    settings.f3Debug = m_f3Debug->isChecked();
    settings.debugProbe = m_debugProbe->isChecked();
    settings.forceGpu = m_forceGpu->isChecked();
    settings.gpuChunkLoadOnGenerated = m_gpuChunkLoadOnGenerated->isChecked();
    settings.gpuChunkLoadOnLoaded = m_gpuChunkLoadOnLoaded->isChecked();
    settings.gpuSkylightApply = m_gpuSkylightApply->isChecked();
    settings.gpuChunkLoadSummaryInterval = m_gpuChunkLoadSummaryInterval->value();
    settings.gpuChunkLoadBatchSize = m_gpuChunkLoadBatchSize->value();
    settings.gpuSections = m_gpuSections->isChecked();
    settings.nativeDir = m_nativeDir->text().trimmed();
    settings.rustLogLevel = m_rustLogLevel->text().trimmed();
    return settings;
}

void MainWindow::applySettings(const ChunkupSettings &settings)
{
    m_instantLoad->setChecked(settings.instantLoad);
    m_gpuWorldGen->setChecked(settings.gpuWorldGen);
    m_preRenderOnLoad->setChecked(settings.preRenderOnLoad);
    m_preRenderBudget->setValue(settings.preRenderBudgetPerFrame);
    m_layeredSections->setChecked(settings.layeredSections);
    m_layeredSectionsRate->setValue(settings.layeredSectionsRate);
    m_f3Debug->setChecked(settings.f3Debug);
    m_debugProbe->setChecked(settings.debugProbe);
    m_forceGpu->setChecked(settings.forceGpu);
    m_gpuChunkLoadOnGenerated->setChecked(settings.gpuChunkLoadOnGenerated);
    m_gpuChunkLoadOnLoaded->setChecked(settings.gpuChunkLoadOnLoaded);
    m_gpuSkylightApply->setChecked(settings.gpuSkylightApply);
    m_gpuChunkLoadSummaryInterval->setValue(settings.gpuChunkLoadSummaryInterval);
    m_gpuChunkLoadBatchSize->setValue(settings.gpuChunkLoadBatchSize);
    m_gpuSections->setChecked(settings.gpuSections);
    m_nativeDir->setText(settings.nativeDir);
    m_rustLogLevel->setText(settings.rustLogLevel);
}

void MainWindow::browseNativeDir()
{
    const QString current = m_nativeDir->text().trimmed();
    const QString selected = QFileDialog::getExistingDirectory(
        this,
        QStringLiteral("选择 Native 库目录"),
        current.isEmpty() ? QDir::homePath() : current);
    if (!selected.isEmpty()) {
        m_nativeDir->setText(QDir::toNativeSeparators(selected));
    }
}

void MainWindow::restoreDefaults()
{
    applySettings(ChunkupSettings::defaults());
    refreshJvmPreview();
    m_statusLabel->setText(QStringLiteral("已恢复默认值（尚未保存）"));
}

void MainWindow::openConfigDirectory()
{
    QDesktopServices::openUrl(QUrl::fromLocalFile(ConfigManager::settingsDirectory()));
}

void MainWindow::acceptDialog()
{
    accept();
}

void MainWindow::refreshJvmPreview()
{
    m_jvmPreview->setPlainText(ConfigManager::buildJvmArguments(collectSettings()));
}
