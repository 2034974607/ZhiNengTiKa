#pragma once

#include <QByteArray>
#include <QObject>
#include <QPointer>
#include <QString>
#include <QUrl>
#include <memory>

#include "UpdateRelease.h"

class QDialog;
class QMessageBox;
class QNetworkReply;

class AutoUpdate : public QObject
{
    Q_OBJECT
public:
    static void initOnce(const QString &currentVersion, QObject *parent = nullptr);
    static AutoUpdate *getInstance();
    ~AutoUpdate() override;

    void checkUpdate(bool showWidget);
    void setCurrentVersion(const QString &version) { currentVersion = version; }
    bool isRunning() const { return running || bool(download); }
    bool isFinished() const { return !isRunning(); }

public slots:
    void showUpdateWidget();
    void showResultWidget();

signals:
    void finished();

private:
    struct Download;
    static AutoUpdate *instance;
    explicit AutoUpdate(const QString &currentVersion, QObject *parent = nullptr);

    void newestVersionReplyFinished(QNetworkReply *reply);
    void finishCheck(const QString &error = QString());
    void showReleaseMessage(const QString &text, bool error);
    void openReleasePage();
    void startDownload();
    void writeDownloadData(QNetworkReply *reply);
    void failDownload(const QString &error);
    void downloadFinished(QNetworkReply *reply);
    void showInstallDialog(const QString &filePath, const QString &version);

    QString currentVersion;
    QString releaseError;
    UpdateRelease::Release release;
    bool showWidgetWhenDoesntHaveUpdate = false;
    bool running = false;
    bool hasNewVersion = false;
    QPointer<QNetworkReply> newestVersionReply;
    QPointer<QMessageBox> updateDialog;
    QPointer<QMessageBox> resultDialog;
    QPointer<QDialog> installDialog;
    std::unique_ptr<Download> download;
};
