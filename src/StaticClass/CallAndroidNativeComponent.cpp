#include "CallAndroidNativeComponent.h"

#ifdef Q_OS_ANDROID

namespace
{
bool clearJniException(const char *operation)
{
    QJniEnvironment environment;
    if(!environment->ExceptionCheck())
        return false;

    environment->ExceptionClear();
    qWarning() << "CallAndroidNativeComponent: JNI exception during" << operation;
    return true;
}

bool queueIntent(const char *method, const QString &argument)
{
    if(clearJniException("entering intent bridge"))
        return false;

    QJniObject activity = QtAndroidPrivate::activity();
    if(clearJniException("getting activity") || !activity.isValid())
        return false;

    QJniObject value = QJniObject::fromString(argument);
    if(clearJniException("creating intent argument") || !value.isValid())
        return false;

    const jboolean accepted = QJniObject::callStaticMethod<jboolean>(
        "com/LFWQSP2641/xinjiaoyu/IntentActivity",
        method,
        "(Ljava/lang/String;Lorg/qtproject/qt/android/bindings/QtActivity;)Z",
        value.object<jstring>(),
        activity.object<jobject>());
    if(clearJniException("queuing intent"))
        return false;

    // The Java result only acknowledges UI dispatch, not a successful launch
    // or a completed installation. Java reports subsequent failures with Toast.
    return accepted == JNI_TRUE;
}
}

QImage CallAndroidNativeComponent::takePhoto()
{
    QtAndroidPrivate::requestPermission(QtAndroidPrivate::Camera).waitForFinished();
    QJniObject activity = QtAndroidPrivate::activity();
    QJniObject::callStaticMethod<void>(
        "com/LFWQSP2641/xinjiaoyu/CallNativeComponent",
        "callNativeCamera",
        "(Lorg/qtproject/qt/android/bindings/QtActivity;)V",
        activity.object<jobject>());
    // 简单粗暴好吧(*/ω＼*)
    QMessageBox::information(Q_NULLPTR, QStringLiteral("information"), QStringLiteral("简单粗暴的线程堵塞,关掉就行"));
    QFile file(CallAndroidNativeComponent::getCacheDir() + QDir::separator() + QStringLiteral("callNativeCamera_result.jpg"));
    if(file.size() < 1)
    {
        qWarning() << "CallAndroidNativeComponent::takePhoto() : file.size() < 1";
        return QImage();
    }
    file.open(QFile::ReadOnly);
    auto image{QImage::fromData(file.readAll())};
    file.close();
    QFileInfo fileInfo(file);
    fileInfo.dir().remove(fileInfo.fileName());
    if(image.isNull())
    {
        qWarning() << "CallAndroidNativeComponent::takePhoto() : image.isNull()";
    }
    return image;
}

bool CallAndroidNativeComponent::openUrl(const QString &url)
{
    return queueIntent("openUrl", url);
}

bool CallAndroidNativeComponent::installApk(const QString &filePath)
{
    return queueIntent("installApk", filePath);
}

QString CallAndroidNativeComponent::getCacheDir()
{
    if(clearJniException("entering cache directory bridge"))
        return QString();

    QJniObject activity = QtAndroidPrivate::activity();
    if(clearJniException("getting activity") || !activity.isValid())
        return QString();

    QJniObject directory = QJniObject::callStaticMethod<jstring>(
        "com/LFWQSP2641/xinjiaoyu/Util",
        "getCacheDir",
        "(Lorg/qtproject/qt/android/bindings/QtActivity;)Ljava/lang/String;",
        activity.object<jobject>());
    if(clearJniException("getting cache directory") || !directory.isValid())
        return QString();

    const QString path = directory.toString();
    if(clearJniException("reading cache directory"))
        return QString();
    return path;
}

#endif // Q_OS_ANDROID
