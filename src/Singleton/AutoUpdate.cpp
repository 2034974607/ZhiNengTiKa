#include "AutoUpdate.h"
#include "UpdateRelease.h"
#include "Network.h"
#include "../StaticClass/CallAndroidNativeComponent.h"

#include <QAbstractButton>
#include <QApplication>
#include <QClipboard>
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDesktopServices>
#include <QDialog>
#include <QDialogButtonBox>
#include <QDir>
#include <QFileInfo>
#include <QLabel>
#include <QMessageBox>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QProgressBar>
#include <QPushButton>
#include <QSaveFile>
#include <QStandardPaths>
#include <QTimer>
#include <QVBoxLayout>
#include <QVariant>
#include <utility>

namespace
{
constexpr int MetadataTimeoutMs = 20000;
constexpr int InactivityTimeoutMs = 15000;
constexpr int DownloadTimeoutMs = 10 * 60 * 1000;

QWidget *dialogParent()
{
    QWidget *widget = QApplication::activeWindow();
    while (widget && widget->parentWidget())
        widget = widget->parentWidget();
    if (widget && !qobject_cast<QDialog *>(widget))
        return widget;
    for (auto *window : QApplication::topLevelWidgets())
    {
        if (window->isVisible() && !qobject_cast<QDialog *>(window))
            return window;
    }
    return nullptr;
}

QString networkError(QNetworkReply *reply)
{
    const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    if (status == 403 || status == 429)
        return QStringLiteral("GitHub 拒绝了请求或请求次数已达限制（HTTP %1）。请稍后重试或打开发布页。").arg(status);
    if (status == 404)
        return QStringLiteral("未找到本项目的正式发布版本（HTTP 404）。");
    if (status != 0 && status != 200)
        return QStringLiteral("服务器返回 HTTP %1：%2").arg(status).arg(reply->errorString());
    if (reply->error() != QNetworkReply::NoError)
        return QStringLiteral("网络请求失败：%1").arg(reply->errorString());
    if (status != 200)
        return QStringLiteral("服务器未返回有效的 HTTP 响应。");
    return QString();
}
} // namespace

struct AutoUpdate::Download
{
    Download(const UpdateRelease::Release &info, const QString &path)
        : release(info), file(path), hash(QCryptographicHash::Sha256)
    {
    }

    UpdateRelease::Release release;
    QSaveFile file;
    QCryptographicHash hash;
    QPointer<QNetworkReply> reply;
    QPointer<QDialog> dialog;
    QPointer<QLabel> label;
    QPointer<QProgressBar> progress;
    qint64 received = 0;
    QString failure;
    bool cancelled = false;
    bool finishing = false;
};

AutoUpdate *AutoUpdate::instance = nullptr;

AutoUpdate::AutoUpdate(const QString &version, QObject *parent)
    : QObject(parent), currentVersion(version)
{
}

AutoUpdate::~AutoUpdate() = default;

void AutoUpdate::initOnce(const QString &currentVersion, QObject *parent)
{
    if (!instance)
        instance = new AutoUpdate(currentVersion, parent ? parent : QCoreApplication::instance());
}

AutoUpdate *AutoUpdate::getInstance()
{
    return instance;
}

void AutoUpdate::checkUpdate(bool showWidget)
{
    if (isRunning())
        return;
    if (updateDialog)
    {
        updateDialog->close();
        updateDialog.clear();
    }
    if (resultDialog)
    {
        resultDialog->close();
        resultDialog.clear();
    }
    showWidgetWhenDoesntHaveUpdate = showWidget;
    running = true;
    hasNewVersion = false;
    release = UpdateRelease::Release();
    releaseError.clear();

    QNetworkRequest request(UpdateRelease::apiUrl());
    request.setRawHeader("Accept", "application/vnd.github+json");
    request.setRawHeader("X-GitHub-Api-Version", "2022-11-28");
    request.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("ZhiNengTiKa/%1").arg(currentVersion));
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
    request.setTransferTimeout(InactivityTimeoutMs);
    auto *reply = Network::getInstance()->networkAccessManager.get(request);
    newestVersionReply = reply;
    reply->setReadBufferSize(UpdateRelease::MaximumMetadataBytes + 1);

    auto *deadline = new QTimer(reply);
    deadline->setSingleShot(true);
    connect(deadline, &QTimer::timeout, this, [reply]
    {
        reply->setProperty("updateError", QStringLiteral("检查更新超时。请检查网络后重试，或打开发布页。"));
        reply->abort();
    });
    connect(reply, &QNetworkReply::readyRead, this, [reply]
    {
        if (reply->bytesAvailable() > UpdateRelease::MaximumMetadataBytes)
        {
            reply->setProperty("updateError", QStringLiteral("发布信息超过允许的大小。"));
            reply->abort();
        }
    });
    connect(reply, &QNetworkReply::finished, this, [this, reply, deadline]
    {
        deadline->stop();
        newestVersionReplyFinished(reply);
    });
    deadline->start(MetadataTimeoutMs);
}

void AutoUpdate::newestVersionReplyFinished(QNetworkReply *reply)
{
    if (newestVersionReply != reply)
    {
        reply->deleteLater();
        return;
    }
    QString error = reply->property("updateError").toString();
    if (error.isEmpty())
        error = networkError(reply);
    const auto data = reply->readAll();
    newestVersionReply.clear();
    reply->deleteLater();
    if (!error.isEmpty())
    {
        finishCheck(error);
        return;
    }
    const auto parsed = UpdateRelease::parseLatest(data, currentVersion);
    if (!parsed.ok())
    {
        finishCheck(parsed.error);
        return;
    }
    release = parsed.release;
    hasNewVersion = parsed.newer;
    finishCheck();
}

void AutoUpdate::finishCheck(const QString &error)
{
    releaseError = error;
    running = false;
    emit finished();
    if (!error.isEmpty())
    {
        if (showWidgetWhenDoesntHaveUpdate)
            showResultWidget();
    }
    else if (hasNewVersion)
        showUpdateWidget();
    else if (showWidgetWhenDoesntHaveUpdate)
        showResultWidget();
}

void AutoUpdate::showUpdateWidget()
{
    if (!hasNewVersion)
        return;
    if (updateDialog)
    {
        updateDialog->raise();
        updateDialog->activateWindow();
        return;
    }
    auto *box = new QMessageBox(QMessageBox::Information, QStringLiteral("发现新版本"),
                               QString(), QMessageBox::NoButton, dialogParent());
    box->setAttribute(Qt::WA_DeleteOnClose);
    box->setTextFormat(Qt::PlainText);
    QString text = QStringLiteral("当前版本：%1\n最新版本：%2\n\n可下载并安装，或在浏览器中打开本项目发布页。")
                       .arg(currentVersion, release.version);
    if (!release.notes.isEmpty())
        box->setDetailedText(release.notes);
    if (!release.canDownload())
        text += QStringLiteral("\n\n") + release.downloadUnavailableReason;
    box->setText(text);
    auto *downloadButton = box->addButton(QStringLiteral("下载并安装"), QMessageBox::AcceptRole);
    downloadButton->setEnabled(release.canDownload());
    auto *browserButton = box->addButton(QStringLiteral("浏览器下载"), QMessageBox::ActionRole);
    auto *copyButton = box->addButton(QStringLiteral("复制发布页"), QMessageBox::ActionRole);
    auto *cancelButton = box->addButton(QStringLiteral("取消"), QMessageBox::RejectRole);
    box->setDefaultButton(browserButton);
    box->setEscapeButton(cancelButton);
    updateDialog = box;
    connect(box, &QMessageBox::buttonClicked, this,
            [this, downloadButton, browserButton, copyButton](QAbstractButton *button)
    {
        if (button == downloadButton)
            QTimer::singleShot(0, this, &AutoUpdate::startDownload);
        else if (button == browserButton)
            QTimer::singleShot(0, this, &AutoUpdate::openReleasePage);
        else if (button == copyButton)
            QApplication::clipboard()->setText(UpdateRelease::releasesUrl().toString());
    });
    box->open();
}

void AutoUpdate::showResultWidget()
{
    if (!releaseError.isEmpty())
        showReleaseMessage(QStringLiteral("检查更新失败\n\n") + releaseError, true);
    else if (hasNewVersion)
        showUpdateWidget();
    else
        showReleaseMessage(QStringLiteral("当前版本：%1\n未发现更高的正式版本。").arg(currentVersion), false);
}

void AutoUpdate::showReleaseMessage(const QString &text, bool error)
{
    if (resultDialog)
    {
        resultDialog->close();
        resultDialog.clear();
    }
    auto *box = new QMessageBox(error ? QMessageBox::Warning : QMessageBox::Information,
                               QStringLiteral("应用更新"), QString(), QMessageBox::NoButton,
                               dialogParent());
    box->setAttribute(Qt::WA_DeleteOnClose);
    box->setTextFormat(Qt::PlainText);
    box->setText(text + QStringLiteral("\n\n发布页：") + UpdateRelease::releasesUrl().toString());
    auto *browserButton = box->addButton(QStringLiteral("打开发布页"), QMessageBox::ActionRole);
    auto *copyButton = box->addButton(QStringLiteral("复制发布页"), QMessageBox::ActionRole);
    auto *closeButton = box->addButton(QStringLiteral("关闭"), QMessageBox::RejectRole);
    box->setEscapeButton(closeButton);
    resultDialog = box;
    connect(box, &QMessageBox::buttonClicked, this,
            [this, browserButton, copyButton](QAbstractButton *button)
    {
        if (button == browserButton)
            QTimer::singleShot(0, this, &AutoUpdate::openReleasePage);
        else if (button == copyButton)
            QApplication::clipboard()->setText(UpdateRelease::releasesUrl().toString());
    });
    box->open();
}

void AutoUpdate::openReleasePage()
{
#ifdef Q_OS_ANDROID
    const bool queued = CallAndroidNativeComponent::openUrl(UpdateRelease::releasesUrl().toString());
#else
    const bool queued = QDesktopServices::openUrl(UpdateRelease::releasesUrl());
#endif
    if (!queued)
        showReleaseMessage(QStringLiteral("无法打开浏览器。请复制下面的发布页地址，手动打开。"), true);
}

void AutoUpdate::startDownload()
{
    if (isRunning())
        return;
    if (!release.canDownload())
    {
        showReleaseMessage(QStringLiteral("无法进行应用内下载。请使用浏览器下载。\n")
                               + release.downloadUnavailableReason, true);
        return;
    }
#ifdef Q_OS_ANDROID
    const QString cache = CallAndroidNativeComponent::getCacheDir();
#else
    const QString cache = QStandardPaths::writableLocation(QStandardPaths::CacheLocation);
#endif
    const QString directory = cache + QStringLiteral("/updates");
    if (cache.isEmpty() || !QDir().mkpath(directory))
    {
        showReleaseMessage(QStringLiteral("无法创建更新缓存目录。请检查可用存储空间，或使用浏览器下载。"), true);
        return;
    }
    auto state = std::make_unique<Download>(release, directory + QLatin1Char('/') + release.assetName);
    state->file.setDirectWriteFallback(false);
    if (!state->file.open(QIODevice::WriteOnly))
    {
        showReleaseMessage(QStringLiteral("无法写入安装包：%1").arg(state->file.errorString()), true);
        return;
    }
    download = std::move(state);

    auto *dialog = new QDialog(dialogParent());
    dialog->setAttribute(Qt::WA_DeleteOnClose);
    dialog->setWindowTitle(QStringLiteral("下载更新"));
    auto *layout = new QVBoxLayout(dialog);
    auto *label = new QLabel(QStringLiteral("正在连接下载服务器…"), dialog);
    auto *progress = new QProgressBar(dialog);
    progress->setRange(0, 1000);
    auto *buttons = new QDialogButtonBox(QDialogButtonBox::Cancel, dialog);
    layout->addWidget(label);
    layout->addWidget(progress);
    layout->addWidget(buttons);
    download->dialog = dialog;
    download->label = label;
    download->progress = progress;
    connect(buttons, &QDialogButtonBox::rejected, dialog, &QDialog::reject);

    QNetworkRequest request(download->release.downloadUrl);
    request.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("ZhiNengTiKa/%1").arg(currentVersion));
    request.setRawHeader("Accept", "application/octet-stream");
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::UserVerifiedRedirectPolicy);
    request.setTransferTimeout(InactivityTimeoutMs);
    auto *reply = Network::getInstance()->networkAccessManager.get(request);
    reply->setReadBufferSize(256 * 1024);
    download->reply = reply;

    auto *deadline = new QTimer(reply);
    auto *inactivity = new QTimer(reply);
    deadline->setSingleShot(true);
    inactivity->setSingleShot(true);
    inactivity->setInterval(InactivityTimeoutMs);
    connect(deadline, &QTimer::timeout, this, [this, reply]
    {
        if (download && download->reply == reply)
            failDownload(QStringLiteral("下载超过总时限，已取消。请重试或使用浏览器下载。"));
    });
    connect(inactivity, &QTimer::timeout, this, [this, reply]
    {
        if (download && download->reply == reply)
            failDownload(QStringLiteral("下载长时间未收到数据，已取消。请检查网络后重试。"));
    });
    connect(reply, &QNetworkReply::redirected, this, [this, reply, inactivity](const QUrl &url)
    {
        if (!download || download->reply != reply)
            return;
        if (!UpdateRelease::isTrustedRedirectUrl(url))
        {
            failDownload(QStringLiteral("下载被重定向到不受信任的地址，已取消。"));
            return;
        }
        inactivity->start();
        reply->redirectAllowed();
    });
    connect(reply, &QNetworkReply::readyRead, this, [this, reply, inactivity]
    {
        inactivity->start();
        writeDownloadData(reply);
    });
    connect(reply, &QNetworkReply::finished, this, [this, reply, deadline, inactivity]
    {
        deadline->stop();
        inactivity->stop();
        downloadFinished(reply);
    });
    auto cancel = [this, reply]
    {
        if (download && download->reply == reply && !download->finishing)
        {
            download->cancelled = true;
            if (!reply->isFinished())
                reply->abort();
        }
    };
    connect(dialog, &QDialog::rejected, this, cancel);
    connect(dialog, &QObject::destroyed, this, cancel);
    deadline->start(DownloadTimeoutMs);
    inactivity->start();
    dialog->open();
}

void AutoUpdate::writeDownloadData(QNetworkReply *reply)
{
    if (!download || download->reply != reply || download->cancelled || !download->failure.isEmpty())
        return;
    const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    if (status >= 300 && status < 400)
    {
        reply->readAll();
        return;
    }
    if (status != 200 || !UpdateRelease::isTrustedRedirectUrl(reply->url()))
    {
        failDownload(QStringLiteral("下载服务器返回无效响应或不受信任的地址。"));
        return;
    }
    while (reply->bytesAvailable() > 0)
    {
        const QByteArray chunk = reply->read(64 * 1024);
        if (chunk.isEmpty())
            break;
        if (chunk.size() > download->release.size - download->received)
        {
            failDownload(QStringLiteral("安装包超过发布信息声明的大小，已取消。"));
            return;
        }
        if (download->file.write(chunk) != chunk.size())
        {
            failDownload(QStringLiteral("写入安装包失败：%1").arg(download->file.errorString()));
            return;
        }
        download->hash.addData(chunk);
        download->received += chunk.size();
    }
    if (download->label)
        download->label->setText(QStringLiteral("已下载 %1 / %2 MiB")
                                     .arg(double(download->received) / (1024 * 1024), 0, 'f', 1)
                                     .arg(double(download->release.size) / (1024 * 1024), 0, 'f', 1));
    if (download->progress)
        download->progress->setValue(int(download->received * 1000 / download->release.size));
}

void AutoUpdate::failDownload(const QString &error)
{
    if (!download)
        return;
    if (download->failure.isEmpty())
        download->failure = error;
    if (download->reply && !download->finishing && !download->reply->isFinished())
        download->reply->abort();
}

void AutoUpdate::downloadFinished(QNetworkReply *reply)
{
    if (!download || download->reply != reply)
    {
        reply->deleteLater();
        return;
    }
    if (download->finishing)
        return;
    download->finishing = true;
    if (!download->cancelled && download->failure.isEmpty() && reply->error() == QNetworkReply::NoError)
        writeDownloadData(reply);
    auto completed = std::move(download);
    QString error = completed->failure;
    if (!completed->cancelled && error.isEmpty())
        error = networkError(reply);
    if (!completed->cancelled && error.isEmpty())
        error = UpdateRelease::verifyDownload(completed->release, completed->received, completed->hash.result());
    if (!completed->cancelled && error.isEmpty() && !completed->file.commit())
        error = QStringLiteral("保存已校验的安装包失败：%1").arg(completed->file.errorString());
    if (completed->cancelled || !error.isEmpty())
        completed->file.cancelWriting();

    if (completed->dialog)
    {
        disconnect(completed->dialog, nullptr, this, nullptr);
        completed->dialog->close();
    }
    reply->deleteLater();
    emit finished();
    if (completed->cancelled)
        return;
    if (!error.isEmpty())
    {
        showReleaseMessage(QStringLiteral("下载失败\n\n") + error, true);
        return;
    }
    showInstallDialog(completed->file.fileName(), completed->release.version);
}

void AutoUpdate::showInstallDialog(const QString &filePath, const QString &version)
{
    if (installDialog)
    {
        installDialog->close();
        installDialog.clear();
    }
    auto *dialog = new QDialog(dialogParent());
    dialog->setAttribute(Qt::WA_DeleteOnClose);
    dialog->setWindowTitle(QStringLiteral("安装更新"));
    auto *layout = new QVBoxLayout(dialog);
    auto *label = new QLabel(QStringLiteral("%1 安装包下载完成，大小和 SHA-256 校验通过。\n安装包已保存在应用缓存中。")
                                .arg(version), dialog);
    label->setWordWrap(true);
    auto *installButton = new QPushButton(QStringLiteral("安装/重试安装"), dialog);
    auto *browserButton = new QPushButton(QStringLiteral("打开发布页"), dialog);
    auto *closeButton = new QPushButton(QStringLiteral("关闭"), dialog);
    layout->addWidget(label);
    layout->addWidget(installButton);
    layout->addWidget(browserButton);
    layout->addWidget(closeButton);
    installDialog = dialog;
    connect(installButton, &QPushButton::clicked, dialog, [filePath, label]
    {
        if (!QFileInfo::exists(filePath))
        {
            label->setText(QStringLiteral("安装包已不在缓存中，请关闭此窗口并重新下载。"));
            return;
        }
#ifdef Q_OS_ANDROID
        const bool queued = CallAndroidNativeComponent::installApk(filePath);
        label->setText(queued
            ? QStringLiteral("安装请求已排队，仍需在 Android 系统界面确认。\n若先进入“允许此来源”设置，请授权后返回并点击“安装/重试安装”。")
            : QStringLiteral("无法启动安装。可点击“安装/重试安装”再次尝试，或打开发布页下载。"));
#else
        label->setText(QStringLiteral("这是 Android arm64-v8a 安装包，请在对应设备上安装。\n%1").arg(filePath));
#endif
    });
    connect(browserButton, &QPushButton::clicked, this, &AutoUpdate::openReleasePage);
    connect(closeButton, &QPushButton::clicked, dialog, &QDialog::reject);
    dialog->open();
#ifdef Q_OS_ANDROID
    QTimer::singleShot(0, installButton, &QPushButton::click);
#endif
}
