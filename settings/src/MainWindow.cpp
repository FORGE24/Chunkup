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
    resize(640, 720);
    setModal(true);
    buildUi();
    refreshJvmPreview();
}

void MainWindow::buildUi()
{
    auto *layout = new QVBoxLayout(this);

    auto *gpuLoadGroup = new QGroupBox(QStringLiteral("GPU 区块加载"), this);
    auto *gpuLoadForm = new QFormLayout(gpuLoadGroup);

    m_gpuChunkLoadOnLoaded = new QCheckBox(QStringLiteral("在 LOADED 阶段启用 GPU（磁盘/传送加载）"), gpuLoadGroup);
    m_gpuSkylightApply = new QCheckBox(QStringLiteral("将 GPU 天空光写回 Minecraft"), gpuLoadGroup);
    m_forceGpu = new QCheckBox(QStringLiteral("强制 GPU（失败时不回退 CPU）"), gpuLoadGroup);
    m_gpuChunkLoadBatchSize = new QSpinBox(gpuLoadGroup);
    m_gpuChunkLoadBatchSize->setRange(1, 128);
    m_gpuChunkLoadSummaryInterval = new QSpinBox(gpuLoadGroup);
    m_gpuChunkLoadSummaryInterval->setRange(1, 100000);

    gpuLoadForm->addRow(m_gpuChunkLoadOnLoaded);
    gpuLoadForm->addRow(m_gpuSkylightApply);
    gpuLoadForm->addRow(m_forceGpu);
    gpuLoadForm->addRow(QStringLiteral("攒批大小"), m_gpuChunkLoadBatchSize);
    gpuLoadForm->addRow(QStringLiteral("汇总日志间隔"), m_gpuChunkLoadSummaryInterval);

    auto *renderGroup = new QGroupBox(QStringLiteral("客户端渲染"), this);
    auto *renderForm = new QFormLayout(renderGroup);
    m_gpuSections = new QCheckBox(QStringLiteral("启用 GPU Section Mesh（需 Sodium）"), renderGroup);
    m_f3Debug = new QCheckBox(QStringLiteral("F3 调试面板显示 Chunkup 详情"), renderGroup);
    renderForm->addRow(m_gpuSections);
    renderForm->addRow(m_f3Debug);

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

    m_statusLabel = new QLabel(QStringLiteral("部分选项需重启世界后生效"), this);

    auto *utilityRow = new QHBoxLayout();
    auto *defaultsButton = new QPushButton(QStringLiteral("恢复默认"), this);
    auto *openDirButton = new QPushButton(QStringLiteral("打开配置目录"), this);
    utilityRow->addWidget(defaultsButton);
    utilityRow->addWidget(openDirButton);
    utilityRow->addStretch(1);

    auto *buttonBox = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel, this);
    connect(buttonBox, &QDialogButtonBox::accepted, this, &MainWindow::acceptDialog);
    connect(buttonBox, &QDialogButtonBox::rejected, this, &QDialog::reject);

    layout->addWidget(gpuLoadGroup);
    layout->addWidget(renderGroup);
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
    connect(m_gpuChunkLoadOnLoaded, &QCheckBox::toggled, this, refreshPreview);
    connect(m_gpuSkylightApply, &QCheckBox::toggled, this, refreshPreview);
    connect(m_forceGpu, &QCheckBox::toggled, this, refreshPreview);
    connect(m_gpuChunkLoadBatchSize, qOverload<int>(&QSpinBox::valueChanged), this, refreshPreview);
    connect(m_gpuChunkLoadSummaryInterval, qOverload<int>(&QSpinBox::valueChanged), this, refreshPreview);
    connect(m_gpuSections, &QCheckBox::toggled, this, refreshPreview);
    connect(m_f3Debug, &QCheckBox::toggled, this, refreshPreview);
    connect(m_nativeDir, &QLineEdit::textChanged, this, refreshPreview);
    connect(m_rustLogLevel, &QLineEdit::textChanged, this, refreshPreview);
}

ChunkupSettings MainWindow::collectSettings() const
{
    ChunkupSettings settings;
    settings.forceGpu = m_forceGpu->isChecked();
    settings.gpuChunkLoadOnLoaded = m_gpuChunkLoadOnLoaded->isChecked();
    settings.gpuSkylightApply = m_gpuSkylightApply->isChecked();
    settings.gpuChunkLoadSummaryInterval = m_gpuChunkLoadSummaryInterval->value();
    settings.gpuChunkLoadBatchSize = m_gpuChunkLoadBatchSize->value();
    settings.gpuSections = m_gpuSections->isChecked();
    settings.f3Debug = m_f3Debug->isChecked();
    settings.nativeDir = m_nativeDir->text().trimmed();
    settings.rustLogLevel = m_rustLogLevel->text().trimmed();
    return settings;
}

void MainWindow::applySettings(const ChunkupSettings &settings)
{
    m_forceGpu->setChecked(settings.forceGpu);
    m_gpuChunkLoadOnLoaded->setChecked(settings.gpuChunkLoadOnLoaded);
    m_gpuSkylightApply->setChecked(settings.gpuSkylightApply);
    m_gpuChunkLoadSummaryInterval->setValue(settings.gpuChunkLoadSummaryInterval);
    m_gpuChunkLoadBatchSize->setValue(settings.gpuChunkLoadBatchSize);
    m_gpuSections->setChecked(settings.gpuSections);
    m_f3Debug->setChecked(settings.f3Debug);
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
