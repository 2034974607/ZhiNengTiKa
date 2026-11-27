#include "../src/Singleton/Network.h"

#include <QElapsedTimer>
#include <QTimer>
#include <QtTest>
#include <cstring>

class FakeReply final : public QNetworkReply
{
public:
    explicit FakeReply(bool alreadyFinished = false, QObject *parent = nullptr)
        : QNetworkReply(parent)
    {
        const QNetworkRequest request(QUrl(QStringLiteral("https://example.invalid/test")));
        setRequest(request);
        setUrl(request.url());
        setOperation(QNetworkAccessManager::GetOperation);
        open(QIODevice::ReadOnly | QIODevice::Unbuffered);
        if (alreadyFinished)
        {
            setError(QNetworkReply::NoError, QString());
            setFinished(true);
        }
    }

    void complete(const QByteArray &data = QByteArray())
    {
        if (isFinished())
            return;
        m_data = data;
        setError(QNetworkReply::NoError, QString());
        setFinished(true);
        emit readyRead();
        emit finished();
    }

    void abort() override
    {
        if (isFinished())
            return;
        wasAborted = true;
        setError(QNetworkReply::OperationCanceledError, QStringLiteral("aborted by test"));
        setFinished(true);
        emit finished();
    }

    bool wasAborted = false;

protected:
    qint64 readData(char *data, qint64 maxSize) override
    {
        if (m_offset >= m_data.size())
            return -1;
        const auto amount = qMin<qint64>(maxSize, m_data.size() - m_offset);
        std::memcpy(data, m_data.constData() + m_offset, size_t(amount));
        m_offset += amount;
        return amount;
    }

private:
    QByteArray m_data;
    qint64 m_offset = 0;
};

class NetworkTests final : public QObject
{
    Q_OBJECT

private slots:
    void sharedManagerHasInactivityTimeout()
    {
        Network::initOnce();
        QVERIFY(Network::getInstance());
        QCOMPARE(Network::getInstance()->networkAccessManager.transferTimeout(), 15000);
    }

    void waitReturnsAfterNormalFinish()
    {
        FakeReply reply;
        QTimer::singleShot(10, &reply, [&reply]
        {
            reply.complete(QByteArrayLiteral("done"));
        });

        QElapsedTimer elapsed;
        elapsed.start();
        QVERIFY(Network::waitForFinished(&reply, 1000) == &reply);
        QVERIFY(reply.isFinished());
        QVERIFY(!reply.wasAborted);
        QCOMPARE(reply.readAll(), QByteArrayLiteral("done"));
        QVERIFY(elapsed.elapsed() < 500);
    }

    void alreadyFinishedReplyReturnsImmediately()
    {
        FakeReply reply(true);
        QElapsedTimer elapsed;
        elapsed.start();
        QVERIFY(Network::waitForFinished(&reply, 1000) == &reply);
        QVERIFY(reply.isFinished());
        QVERIFY(elapsed.elapsed() < 100);
    }

    void deadlineAbortsSlowReply()
    {
        FakeReply reply;
        QElapsedTimer elapsed;
        elapsed.start();
        QCOMPARE(Network::waitForFinished(&reply, 25), &reply);
        QVERIFY(elapsed.elapsed() >= 10);
        QVERIFY(elapsed.elapsed() < 1000);
        QVERIFY(reply.wasAborted);
        QVERIFY(reply.isFinished());
        QCOMPARE(reply.error(), QNetworkReply::OperationCanceledError);
    }

    void destroyedReplyDoesNotLeaveLoopRunning()
    {
        auto *reply = new FakeReply;
        QTimer::singleShot(10, this, [reply]
        {
            delete reply;
        });
        QElapsedTimer elapsed;
        elapsed.start();
        QVERIFY(Network::waitForFinished(reply, 1000) == nullptr);
        QVERIFY(elapsed.elapsed() < 500);
    }

    void nullReplyIsSafe()
    {
        QVERIFY(Network::waitForFinished(nullptr, 10) == nullptr);
    }
};

QTEST_GUILESS_MAIN(NetworkTests)
#include "tst_network.moc"
