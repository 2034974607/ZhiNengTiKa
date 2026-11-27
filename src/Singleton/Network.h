#pragma once

#include <QByteArray>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>

class Network
{
public:
    static constexpr int DefaultWaitTimeoutMs = 30000;

    static void initOnce();
    static Network *getInstance();
    QNetworkAccessManager networkAccessManager;
    static QNetworkReply *requestAndWaitForFinished(const QNetworkRequest &requestInfo);
    static QNetworkReply *postAndWaitForFinished(const QNetworkRequest &requestInfo, const QByteArray &data);
    // Returns after completion or the bounded deadline; a timed-out reply is aborted.
    static QNetworkReply *waitForFinished(QNetworkReply *reply,
                                          int timeoutMs = DefaultWaitTimeoutMs);
    static QByteArray replyReadAll(QNetworkReply *reply);
    static QByteArray getReplyData(QNetworkReply *reply);
    static QByteArray getData(const QNetworkRequest &requestInfo);
    static QByteArray postData(const QNetworkRequest &requestInfo, const QByteArray &data);

private:
    Network();
    static Network *instance;
};
