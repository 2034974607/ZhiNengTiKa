#pragma once

#include <QByteArray>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonParseError>
#include <QJsonValue>
#include <QRegularExpression>
#include <QString>
#include <QUrl>
#include <QVersionNumber>
#include <QtGlobal>
#include <cmath>

// Pure QtCore helpers shared by the updater and its standalone tests.
namespace UpdateRelease
{
constexpr qsizetype MaximumMetadataBytes = 1024 * 1024;
constexpr qint64 MaximumApkBytes = 2LL * 1024 * 1024 * 1024;

inline QUrl apiUrl()
{
    return QUrl(QStringLiteral("https://api.github.com/repos/2034974607/ZhiNengTiKa/releases/latest"));
}

inline QUrl releasesUrl()
{
    return QUrl(QStringLiteral("https://github.com/2034974607/ZhiNengTiKa/releases/latest"));
}

struct Release
{
    QString version;
    QString tag;
    QString notes;
    QString assetName;
    QUrl downloadUrl;
    qint64 size = 0;
    QByteArray sha256; // The 32 digest bytes, not their hexadecimal representation.
    QString downloadUnavailableReason;

    bool canDownload() const;
};

struct Result
{
    Release release;
    bool newer = false;
    QString error;

    bool ok() const { return error.isEmpty(); }
};

inline bool parseVersion(const QString &text, QVersionNumber *out = nullptr,
                         QString *normalized = nullptr)
{
    static const QRegularExpression pattern(QStringLiteral(
        "\\Av?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\z"));
    if (!pattern.match(text).hasMatch())
        return false;

    const QString number = text.startsWith(QLatin1Char('v')) ? text.mid(1) : text;
    const auto version = QVersionNumber::fromString(number);
    // Also reject values outside QVersionNumber's integer range.
    if (version.segmentCount() != 3 || version.toString() != number)
        return false;
    if (out)
        *out = version;
    if (normalized)
        *normalized = number;
    return true;
}

inline bool isSecureHttps(const QUrl &url)
{
    return url.isValid() && !url.isRelative()
        && url.scheme() == QStringLiteral("https") && !url.host().isEmpty()
        && url.userInfo().isEmpty() && url.fragment().isEmpty()
        && (url.port(-1) == -1 || url.port(-1) == 443);
}

inline bool isTrustedAssetUrl(const QUrl &url, const QString &tag,
                              const QString &assetName)
{
    QString version;
    if (!parseVersion(tag, nullptr, &version)
        || assetName != QStringLiteral("ZhiNengTiKa-%1-arm64-v8a.apk").arg(version))
        return false;
    const QString expectedPath = QStringLiteral("/2034974607/ZhiNengTiKa/releases/download/%1/%2")
                                     .arg(tag, assetName);
    return isSecureHttps(url) && url.host() == QStringLiteral("github.com")
        && url.query().isEmpty() && url.path() == expectedPath;
}

inline bool isTrustedRedirectUrl(const QUrl &url)
{
    if (!isSecureHttps(url))
        return false;
    const auto host = url.host();
    return host == QStringLiteral("github.com")
        || host == QStringLiteral("release-assets.githubusercontent.com")
        || host == QStringLiteral("objects.githubusercontent.com")
        || host == QStringLiteral("github-releases.githubusercontent.com");
}

inline bool Release::canDownload() const
{
    return downloadUnavailableReason.isEmpty() && size > 0 && size <= MaximumApkBytes
        && sha256.size() == 32 && isTrustedAssetUrl(downloadUrl, tag, assetName);
}

inline Result parseLatest(const QByteArray &json, const QString &currentVersion)
{
    Result result;
    QVersionNumber current;
    if (!parseVersion(currentVersion, &current))
    {
        result.error = QStringLiteral("当前应用版本格式无效，应为三段数字版本。");
        return result;
    }
    if (json.size() > MaximumMetadataBytes)
    {
        result.error = QStringLiteral("发布信息超过允许的大小。");
        return result;
    }
    QJsonParseError parseError;
    const auto document = QJsonDocument::fromJson(json, &parseError);
    if (parseError.error != QJsonParseError::NoError || !document.isObject())
    {
        result.error = QStringLiteral("GitHub 返回的发布信息不是有效的 JSON 对象。");
        return result;
    }
    const auto object = document.object();
    if (!object.value(QStringLiteral("draft")).isBool()
        || !object.value(QStringLiteral("prerelease")).isBool()
        || object.value(QStringLiteral("draft")).toBool()
        || object.value(QStringLiteral("prerelease")).toBool())
    {
        result.error = QStringLiteral("更新源未返回正式发布版本。");
        return result;
    }
    result.release.tag = object.value(QStringLiteral("tag_name")).toString();
    QVersionNumber latest;
    if (!parseVersion(result.release.tag, &latest, &result.release.version))
    {
        result.error = QStringLiteral("发布版本格式无效，应为 v1.2.3 或 1.2.3。");
        return result;
    }
    result.release.notes = object.value(QStringLiteral("body")).toString().left(6000);
    result.newer = QVersionNumber::compare(latest, current) > 0;
    // Equal/older releases cannot cause an update prompt or a downgrade.
    if (!result.newer)
        return result;

    result.release.assetName = QStringLiteral("ZhiNengTiKa-%1-arm64-v8a.apk")
                                   .arg(result.release.version);
    QJsonObject asset;
    int matches = 0;
    for (const auto &value : object.value(QStringLiteral("assets")).toArray())
    {
        const auto candidate = value.toObject();
        if (candidate.value(QStringLiteral("name")).toString() == result.release.assetName)
        {
            asset = candidate;
            ++matches;
        }
    }
    if (matches != 1)
    {
        result.error = QStringLiteral("该发布未提供唯一匹配的 arm64-v8a APK：%1")
                           .arg(result.release.assetName);
        return result;
    }
    const auto urlValue = asset.value(QStringLiteral("browser_download_url"));
    result.release.downloadUrl = QUrl(urlValue.toString(), QUrl::StrictMode);
    if (!urlValue.isString()
        || !isTrustedAssetUrl(result.release.downloadUrl, result.release.tag,
                              result.release.assetName))
    {
        result.error = QStringLiteral("安装包地址不是本项目可信的 HTTPS GitHub Release 地址。");
        return result;
    }
    const auto sizeValue = asset.value(QStringLiteral("size"));
    const double size = sizeValue.toDouble(-1);
    if (!sizeValue.isDouble() || !std::isfinite(size) || size <= 0
        || size > double(MaximumApkBytes) || std::floor(size) != size)
    {
        result.error = QStringLiteral("发布信息中的安装包大小无效。");
        return result;
    }
    result.release.size = qint64(size);

    static const QRegularExpression digestPattern(
        QStringLiteral("\\Asha256:([0-9a-fA-F]{64})\\z"));
    const auto digestMatch = digestPattern.match(asset.value(QStringLiteral("digest")).toString());
    if (digestMatch.hasMatch())
        result.release.sha256 = QByteArray::fromHex(digestMatch.captured(1).toLatin1());
    else
        result.release.downloadUnavailableReason = QStringLiteral(
            "发布信息未提供有效的 SHA-256 校验值，应用内下载已停用。请使用浏览器下载。");
    return result;
}

inline QString verifyDownload(const Release &release, qint64 actualSize,
                              const QByteArray &actualSha256)
{
    if (!release.canDownload())
        return QStringLiteral("缺少可信的安装包信息或 SHA-256 校验值。");
    if (actualSize != release.size)
        return QStringLiteral("下载大小不匹配（收到 %1 字节，预期 %2 字节）。")
            .arg(actualSize).arg(release.size);
    if (actualSha256.size() != 32 || actualSha256 != release.sha256)
        return QStringLiteral("安装包 SHA-256 校验失败，已丢弃本次下载。");
    return QString();
}
} // namespace UpdateRelease
