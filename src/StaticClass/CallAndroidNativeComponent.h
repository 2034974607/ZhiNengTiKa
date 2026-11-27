#pragma once

#ifdef Q_OS_ANDROID

namespace CallAndroidNativeComponent
{
QImage takePhoto();
// true only means the Android UI request was accepted for execution. The
// browser/installer can still fail asynchronously; installation is not complete.
// installApk may instead open unknown-source settings: return and retry afterward.
bool openUrl(const QString &url);
bool installApk(const QString &filePath);
QString getCacheDir();
};

#endif // Q_OS_ANDROID
