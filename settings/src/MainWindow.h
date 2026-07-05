#pragma once

#include "ConfigManager.h"

#include <QCheckBox>
#include <QDialog>
#include <QFormLayout>
#include <QGroupBox>
#include <QHBoxLayout>
#include <QLabel>
#include <QLineEdit>
#include <QPlainTextEdit>
#include <QPushButton>
#include <QSpinBox>
#include <QVBoxLayout>

class MainWindow : public QDialog {
    Q_OBJECT

public:
    explicit MainWindow(QWidget *parent = nullptr);

    ChunkupSettings collectSettings() const;
    void applySettings(const ChunkupSettings &settings);

private slots:
    void browseNativeDir();
    void restoreDefaults();
    void openConfigDirectory();
    void acceptDialog();
    void refreshJvmPreview();

private:
    void buildUi();

    QCheckBox *m_gpuChunkLoadOnLoaded = nullptr;
    QCheckBox *m_gpuSkylightApply = nullptr;
    QSpinBox *m_gpuChunkLoadSummaryInterval = nullptr;
    QSpinBox *m_gpuChunkLoadBatchSize = nullptr;
    QCheckBox *m_gpuSections = nullptr;
    QLineEdit *m_nativeDir = nullptr;
    QLineEdit *m_rustLogLevel = nullptr;
    QPlainTextEdit *m_jvmPreview = nullptr;
    QLabel *m_configPathLabel = nullptr;
    QLabel *m_statusLabel = nullptr;
};
