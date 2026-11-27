#include "Network.h"

#include <QEventLoop>
#include <QPointer>
#include <QTimer>

Network *Network::instance = nullptr;

Network::Network()
{
    // A transfer is aborted after 15 seconds without receiving/sending bytes.
    networkAccessManager.setTransferTimeout(15000);
}

void Network::initOnce()
{
    if (!Network::instance)
        Network::instance = new Network;
}

Network *Network::getInstance()
{
    return Network::instance;
}

QNetworkReply *Network::requestAndWaitForFinished(const QNetworkRequest &requestInfo)
{
    return Network::waitForFinished(Network::getInstance()->networkAccessManager.get(requestInfo));
}

QNetworkReply *Network::postAndWaitForFinished(const QNetworkRequest &requestInfo, const QByteArray &data)
{
    return Network::waitForFinished(Network::getInstance()->networkAccessManager.post(requestInfo, data));
}

QNetworkReply *Network::waitForFinished(QNetworkReply *reply, int timeoutMs)
{
    if (!reply || reply->isFinished())
        return reply;

    QPointer<QNetworkReply> guardedReply(reply);
    QEventLoop eventLoop;
    QTimer deadline;
    deadline.setSingleShot(true);

    QObject::connect(reply, &QNetworkReply::finished, &eventLoop, &QEventLoop::quit);
    QObject::connect(reply, &QObject::destroyed, &eventLoop, &QEventLoop::quit);
    QObject::connect(&deadline, &QTimer::timeout, &eventLoop,
                     [&eventLoop, guardedReply]
    {
        if (guardedReply && !guardedReply->isFinished())
            guardedReply->abort();
        eventLoop.quit();
    });

    // Recheck after installing the connection so a reply that finished at
    // the boundary never enters a nested loop unnecessarily.
    if (guardedReply && !guardedReply->isFinished())
    {
        deadline.start(qMax(0, timeoutMs));
        if (guardedReply && !guardedReply->isFinished())
            eventLoop.exec();
    }
    deadline.stop();
    return guardedReply.data();
}

QByteArray Network::replyReadAll(QNetworkReply *reply)
{
    if (!reply)
        return QByteArray();
    const auto data{reply->readAll()};
    reply->deleteLater();
    return data;
}

QByteArray Network::getReplyData(QNetworkReply *reply)
{
    return replyReadAll(waitForFinished(reply));
}

QByteArray Network::getData(const QNetworkRequest &requestInfo)
{
    return replyReadAll(requestAndWaitForFinished(requestInfo));
}

QByteArray Network::postData(const QNetworkRequest &requestInfo, const QByteArray &data)
{
    return replyReadAll(postAndWaitForFinished(requestInfo, data));
}
