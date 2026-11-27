#include <QtTest>

#include <QByteArray>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonValue>
#include <QStringList>
#include <QUrl>
#include <QVersionNumber>

#include "../src/Singleton/UpdateRelease.h"

class UpdateReleaseTest final : public QObject
{
    Q_OBJECT

private:
    static QString assetNameForTag(const QString &tag)
    {
        const QString version = tag.startsWith(QLatin1Char('v')) ? tag.mid(1) : tag;
        return QStringLiteral("ZhiNengTiKa-%1-arm64-v8a.apk").arg(version);
    }

    static QString assetUrl(const QString &tag, const QString &name)
    {
        return QStringLiteral("https://github.com/2034974607/ZhiNengTiKa/releases/download/%1/%2")
            .arg(tag, name);
    }

    static QJsonObject makeAsset(const QString &tag,
                                 const QString &name = QString(),
                                 const QJsonValue &size = QJsonValue(12345),
                                 const QJsonValue &digest = QJsonValue(
                                     QStringLiteral("sha256:") + QString(64, QLatin1Char('a'))),
                                 const QString &url = QString())
    {
        const QString chosenName = name.isEmpty() ? assetNameForTag(tag) : name;
        QJsonObject object;
        object.insert(QStringLiteral("name"), chosenName);
        object.insert(QStringLiteral("browser_download_url"),
                      url.isEmpty() ? assetUrl(tag, chosenName) : url);
        object.insert(QStringLiteral("size"), size);
        object.insert(QStringLiteral("digest"), digest);
        return object;
    }

    static QByteArray releaseJson(const QString &tag,
                                  const QJsonArray &assets,
                                  bool draft = false,
                                  bool prerelease = false,
                                  const QString &body = QStringLiteral("Release notes"))
    {
        const QJsonObject object{
            {QStringLiteral("tag_name"), tag},
            {QStringLiteral("name"), QStringLiteral("ZhiNengTiKa %1").arg(tag)},
            {QStringLiteral("body"), body},
            {QStringLiteral("draft"), draft},
            {QStringLiteral("prerelease"), prerelease},
            {QStringLiteral("assets"), assets}
        };
        return QJsonDocument(object).toJson(QJsonDocument::Compact);
    }

    static QByteArray validRelease(const QString &tag = QStringLiteral("v1.9.0"))
    {
        QJsonArray assets;
        // A differently-architected artifact must not be selected instead of arm64-v8a.
        assets.append(makeAsset(tag, QStringLiteral("ZhiNengTiKa-1.9.0-x86_64.apk")));
        assets.append(makeAsset(tag));
        return releaseJson(tag, assets);
    }

private slots:
    void parsesStrictThreePartVersions()
    {
        QVersionNumber parsed;
        QString normalized;

        QVERIFY(UpdateRelease::parseVersion(QStringLiteral("1.2.3"), &parsed, &normalized));
        QCOMPARE(parsed.toString(), QStringLiteral("1.2.3"));
        QCOMPARE(normalized, QStringLiteral("1.2.3"));

        QVERIFY(UpdateRelease::parseVersion(QStringLiteral("v10.20.30"), &parsed, &normalized));
        QCOMPARE(parsed.toString(), QStringLiteral("10.20.30"));
        QCOMPARE(normalized, QStringLiteral("10.20.30"));

        QVERIFY(UpdateRelease::parseVersion(QStringLiteral("0.0.0"), &parsed, &normalized));
        QCOMPARE(parsed.toString(), QStringLiteral("0.0.0"));
    }

    void rejectsMalformedVersions()
    {
        const QStringList invalidVersions{
            QString(), QStringLiteral("v"), QStringLiteral("1"), QStringLiteral("1.2"),
            QStringLiteral("1.2.3.4"), QStringLiteral("01.2.3"), QStringLiteral("1.02.3"),
            QStringLiteral("1.2.03"), QStringLiteral("1.2.3-rc.1"), QStringLiteral("1.2.3+build"),
            QStringLiteral(" 1.2.3"), QStringLiteral("1.2.3 "), QStringLiteral("V1.2.3"),
            QStringLiteral("+1.2.3"), QStringLiteral("2147483648.0.0")
        };
        for (const QString &version : invalidVersions)
        {
            QVersionNumber parsed;
            QString normalized;
            QVERIFY2(!UpdateRelease::parseVersion(version, &parsed, &normalized),
                     qPrintable(QStringLiteral("Unexpectedly accepted version: [%1]").arg(version)));
        }
    }

    void selectsTheExactArm64AssetAndParsesDigest()
    {
        const auto result = UpdateRelease::parseLatest(validRelease(), QStringLiteral("1.8.0"));
        QVERIFY2(result.ok(), qPrintable(result.error));
        QVERIFY(result.newer);
        QCOMPARE(result.release.version, QStringLiteral("1.9.0"));
        QCOMPARE(result.release.tag, QStringLiteral("v1.9.0"));
        QCOMPARE(result.release.notes, QStringLiteral("Release notes"));
        QCOMPARE(result.release.assetName, QStringLiteral("ZhiNengTiKa-1.9.0-arm64-v8a.apk"));
        QCOMPARE(result.release.downloadUrl.toString(), assetUrl(
                     QStringLiteral("v1.9.0"), QStringLiteral("ZhiNengTiKa-1.9.0-arm64-v8a.apk")));
        QCOMPARE(result.release.size, qint64(12345));
        QCOMPARE(result.release.sha256, QByteArray(32, char(0xaa)));
        QVERIFY(result.release.downloadUnavailableReason.isEmpty());
        QVERIFY(result.release.canDownload());
    }

    void onlyStrictlyNewerReleaseTriggersUpdate()
    {
        const QStringList tags{QStringLiteral("v1.8.0"), QStringLiteral("v1.7.9")};
        for (const QString &tag : tags)
        {
            // Equal/older releases need no APK metadata and must not become a downgrade prompt.
            const auto result = UpdateRelease::parseLatest(releaseJson(tag, QJsonArray()),
                                                           QStringLiteral("1.8.0"));
            QVERIFY2(result.ok(), qPrintable(result.error));
            QVERIFY(!result.newer);
            QCOMPARE(result.release.version, tag.mid(1));
            QVERIFY(!result.release.canDownload());
        }

        const auto newer = UpdateRelease::parseLatest(validRelease(), QStringLiteral("1.8.0"));
        QVERIFY(newer.ok());
        QVERIFY(newer.newer);
    }

    void rejectsInvalidCurrentAndReleaseVersions()
    {
        const auto badCurrent = UpdateRelease::parseLatest(validRelease(), QStringLiteral("1.8"));
        QVERIFY(!badCurrent.ok());
        QVERIFY(!badCurrent.error.isEmpty());

        const auto badTag = UpdateRelease::parseLatest(releaseJson(QStringLiteral("v01.9.0"), QJsonArray()),
                                                       QStringLiteral("1.8.0"));
        QVERIFY(!badTag.ok());
        QVERIFY(!badTag.error.isEmpty());
    }

    void rejectsDraftAndPrerelease()
    {
        QJsonArray assets;
        assets.append(makeAsset(QStringLiteral("v1.9.0")));

        const auto draft = UpdateRelease::parseLatest(
            releaseJson(QStringLiteral("v1.9.0"), assets, true, false), QStringLiteral("1.8.0"));
        QVERIFY(!draft.ok());

        const auto prerelease = UpdateRelease::parseLatest(
            releaseJson(QStringLiteral("v1.9.0"), assets, false, true), QStringLiteral("1.8.0"));
        QVERIFY(!prerelease.ok());

        QJsonObject missingFlags{{QStringLiteral("tag_name"), QStringLiteral("v1.9.0")},
                                 {QStringLiteral("assets"), assets}};
        const auto missingFlagsResult = UpdateRelease::parseLatest(
            QJsonDocument(missingFlags).toJson(QJsonDocument::Compact), QStringLiteral("1.8.0"));
        QVERIFY(!missingFlagsResult.ok());
    }

    void rejectsMalformedJsonAndHtmlErrorPages()
    {
        const QList<QByteArray> invalidPayloads{
            QByteArray(), QByteArrayLiteral("<html><body>502 Bad Gateway</body></html>"),
            QByteArrayLiteral("{\"tag_name\":\"v1.9.0\""), QByteArrayLiteral("[]"),
            QByteArrayLiteral("\"not an object\"")
        };
        for (const QByteArray &payload : invalidPayloads)
        {
            const auto result = UpdateRelease::parseLatest(payload, QStringLiteral("1.8.0"));
            QVERIFY2(!result.ok(), qPrintable(QString::fromUtf8(payload.left(80))));
            QVERIFY(!result.error.isEmpty());
        }

        const QByteArray oversized(UpdateRelease::MaximumMetadataBytes + 1, ' ');
        const auto oversizedResult = UpdateRelease::parseLatest(oversized, QStringLiteral("1.8.0"));
        QVERIFY(!oversizedResult.ok());
    }

    void rejectsMissingOrDuplicateAbiAsset()
    {
        QJsonArray otherAbiOnly;
        otherAbiOnly.append(makeAsset(QStringLiteral("v1.9.0"),
                                     QStringLiteral("ZhiNengTiKa-1.9.0-x86_64.apk")));
        const auto noArm64 = UpdateRelease::parseLatest(
            releaseJson(QStringLiteral("v1.9.0"), otherAbiOnly), QStringLiteral("1.8.0"));
        QVERIFY(!noArm64.ok());

        QJsonArray duplicateArm64;
        duplicateArm64.append(makeAsset(QStringLiteral("v1.9.0")));
        duplicateArm64.append(makeAsset(QStringLiteral("v1.9.0")));
        const auto duplicate = UpdateRelease::parseLatest(
            releaseJson(QStringLiteral("v1.9.0"), duplicateArm64), QStringLiteral("1.8.0"));
        QVERIFY(!duplicate.ok());
    }

    void absentOrInvalidDigestDisablesInAppDownload()
    {
        const QString tag = QStringLiteral("v1.9.0");
        const QList<QJsonValue> invalidDigests{
            QJsonValue(), QJsonValue(QStringLiteral("")),
            QJsonValue(QStringLiteral("sha1:") + QString(64, QLatin1Char('a'))),
            QJsonValue(QStringLiteral("sha256:") + QString(63, QLatin1Char('a'))),
            QJsonValue(QStringLiteral("sha256:") + QString(63, QLatin1Char('a')) + QLatin1Char('g')),
            QJsonValue(123)
        };
        for (const QJsonValue &digest : invalidDigests)
        {
            QJsonArray assets;
            assets.append(makeAsset(tag, QString(), QJsonValue(12345), digest));
            const auto result = UpdateRelease::parseLatest(
                releaseJson(tag, assets), QStringLiteral("1.8.0"));
            QVERIFY2(result.ok(), qPrintable(result.error));
            QVERIFY(!result.release.canDownload());
            QVERIFY(!result.release.downloadUnavailableReason.isEmpty());
            QVERIFY(!UpdateRelease::verifyDownload(result.release, 12345, QByteArray(32, char(0xaa))).isEmpty());
        }
    }

    void rejectsInvalidAssetSizes()
    {
        const QString tag = QStringLiteral("v1.9.0");
        struct InvalidSize
        {
            const char *label;
            QJsonValue value;
            bool include;
        };
        const QList<InvalidSize> cases{
            {"missing", QJsonValue(), false}, {"zero", QJsonValue(0), true},
            {"negative", QJsonValue(-1), true}, {"fractional", QJsonValue(1.5), true},
            {"string", QJsonValue(QStringLiteral("12345")), true},
            {"too large", QJsonValue(double(UpdateRelease::MaximumApkBytes) + 1.0), true}
        };
        for (const InvalidSize &testCase : cases)
        {
            QJsonArray assets;
            QJsonObject asset = makeAsset(tag);
            if (testCase.include)
                asset.insert(QStringLiteral("size"), testCase.value);
            else
                asset.remove(QStringLiteral("size"));
            assets.append(asset);
            const auto result = UpdateRelease::parseLatest(
                releaseJson(tag, assets), QStringLiteral("1.8.0"));
            QVERIFY2(!result.ok(), testCase.label);
        }

        QJsonArray boundaryAsset;
        boundaryAsset.append(makeAsset(tag, QString(), QJsonValue(double(UpdateRelease::MaximumApkBytes))));
        const auto atLimit = UpdateRelease::parseLatest(
            releaseJson(tag, boundaryAsset), QStringLiteral("1.8.0"));
        QVERIFY(atLimit.ok());
        QCOMPARE(atLimit.release.size, UpdateRelease::MaximumApkBytes);
        QVERIFY(atLimit.release.canDownload());
    }

    void enforcesCanonicalAssetUrl()
    {
        const QString tag = QStringLiteral("v1.9.0");
        const QString name = assetNameForTag(tag);
        const QString good = assetUrl(tag, name);
        QVERIFY(UpdateRelease::isTrustedAssetUrl(QUrl(good), tag, name));

        const QStringList badUrls{
            QStringLiteral("http://github.com/2034974607/ZhiNengTiKa/releases/download/%1/%2").arg(tag, name),
            QStringLiteral("https://github.com.evil.example/2034974607/ZhiNengTiKa/releases/download/%1/%2").arg(tag, name),
            QStringLiteral("https://evil.example/2034974607/ZhiNengTiKa/releases/download/%1/%2").arg(tag, name),
            QStringLiteral("https://github.com/2034974607/Other/releases/download/%1/%2").arg(tag, name),
            QStringLiteral("https://github.com/2034974607/ZhiNengTiKa/releases/download/v1.8.0/%1").arg(name),
            good + QStringLiteral("?download=1"), good + QStringLiteral("#fragment"),
            QStringLiteral("https://user@github.com/2034974607/ZhiNengTiKa/releases/download/%1/%2").arg(tag, name),
            QStringLiteral("https://github.com:444/2034974607/ZhiNengTiKa/releases/download/%1/%2").arg(tag, name),
            assetUrl(tag, QStringLiteral("other.apk"))
        };
        for (const QString &url : badUrls)
            QVERIFY2(!UpdateRelease::isTrustedAssetUrl(QUrl(url), tag, name), qPrintable(url));

        QJsonArray assets;
        assets.append(makeAsset(tag, name, QJsonValue(12345),
                                QJsonValue(QStringLiteral("sha256:") + QString(64, QLatin1Char('a'))),
                                QStringLiteral("https://attacker.example/file.apk")));
        const auto parsed = UpdateRelease::parseLatest(releaseJson(tag, assets), QStringLiteral("1.8.0"));
        QVERIFY(!parsed.ok());
    }

    void restrictsRedirectsToKnownHttpsHosts()
    {
        QVERIFY(UpdateRelease::isTrustedRedirectUrl(QUrl(QStringLiteral("https://github.com/"))));
        QVERIFY(UpdateRelease::isTrustedRedirectUrl(
            QUrl(QStringLiteral("https://release-assets.githubusercontent.com/file"))));
        QVERIFY(UpdateRelease::isTrustedRedirectUrl(
            QUrl(QStringLiteral("https://objects.githubusercontent.com/file"))));
        QVERIFY(UpdateRelease::isTrustedRedirectUrl(
            QUrl(QStringLiteral("https://github-releases.githubusercontent.com/file"))));

        QVERIFY(!UpdateRelease::isTrustedRedirectUrl(QUrl(QStringLiteral("http://github.com/"))));
        QVERIFY(!UpdateRelease::isTrustedRedirectUrl(QUrl(QStringLiteral("https://github.com.evil.example/"))));
        QVERIFY(!UpdateRelease::isTrustedRedirectUrl(QUrl(QStringLiteral("https://evil.example/"))));
        QVERIFY(!UpdateRelease::isTrustedRedirectUrl(QUrl(QStringLiteral("https://user@github.com/"))));
        QVERIFY(!UpdateRelease::isTrustedRedirectUrl(QUrl(QStringLiteral("https://github.com:444/"))));
    }

    void verifiesDownloadedSizeAndSha256()
    {
        const auto parsed = UpdateRelease::parseLatest(validRelease(), QStringLiteral("1.8.0"));
        QVERIFY(parsed.ok());
        QVERIFY(parsed.release.canDownload());
        const QByteArray digest(32, char(0xaa));

        QVERIFY(UpdateRelease::verifyDownload(parsed.release, 12345, digest).isEmpty());
        QVERIFY(!UpdateRelease::verifyDownload(parsed.release, 12344, digest).isEmpty());
        QVERIFY(!UpdateRelease::verifyDownload(parsed.release, 12345, QByteArray(32, char(0xbb))).isEmpty());
        QVERIFY(!UpdateRelease::verifyDownload(parsed.release, 12345, QByteArray(31, char(0xaa))).isEmpty());

        auto incomplete = parsed.release;
        incomplete.downloadUnavailableReason = QStringLiteral("digest unavailable");
        QVERIFY(!UpdateRelease::verifyDownload(incomplete, 12345, digest).isEmpty());
    }

    void exposesOfficialReleasePages()
    {
        QCOMPARE(UpdateRelease::apiUrl().toString(),
                 QStringLiteral("https://api.github.com/repos/2034974607/ZhiNengTiKa/releases/latest"));
        QCOMPARE(UpdateRelease::releasesUrl().toString(),
                 QStringLiteral("https://github.com/2034974607/ZhiNengTiKa/releases/latest"));
    }
};

QTEST_APPLESS_MAIN(UpdateReleaseTest)
#include "tst_updaterelease.moc"
