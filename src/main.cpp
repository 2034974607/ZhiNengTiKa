#include "GUI/MainWidget.h"
#include "Singleton/AutoUpdate.h"
#include "StaticClass/Global.h"
#include "StaticClass/Setting.h"
#include "Singleton/Network.h"
#include <QTimer>
#include "StaticClass/QRCodeScanner.h"

int main(int argc, char *argv[])
{
#ifdef USE_QTWEBVIEW
#ifndef Q_OS_WINDOWS
    QtWebView::initialize();
    QCoreApplication::setAttribute(Qt::AA_ShareOpenGLContexts);
    QQuickWindow::setGraphicsApi(QSGRendererInterface::OpenGLRhi);
#endif // Q_OS_WINDOWS
#endif // USE_QTWEBVIEW
    QApplication a(argc, argv);

    Network::initOnce();

    Global::initOnce();

    a.setWindowIcon(QIcon(QStringLiteral(":/ico/xinjiaoyuico.png")));

    Setting::loadFromFile();
    AutoUpdate::initOnce(APP_VERSION, &a);
    QRCodeScanner::initialize(Setting::jsonObjectApiQRCodeScanner);
    UserData::initPublicUserData();

    QFont appFont;
    if(Setting::fontPointSize < 1 || Setting::font.isEmpty())
    {
        Setting::fontPointSize = a.font().pointSize();
#ifdef Q_OS_ANDROID
        Setting::smallFontPointSize = Setting::fontPointSize / 2;
#else // Q_OS_ANDROID
        Setting::smallFontPointSize = Setting::fontPointSize;
#endif // Q_OS_ANDROID
        Setting::font = a.font().family();
        Setting::saveToFile();
    }
    else
    {
        appFont.setFamily(Setting::font);
        appFont.setPointSize(Setting::fontPointSize);
        a.setFont(appFont);
    }

    MainWidget w;
    w.setAttribute(Qt::WA_QuitOnClose);
    w.show();

#ifdef Q_OS_ANDROID
    // Updates are optional and start only after the main event loop is running.
    // A failed network request must never disable the application's controls.
    QTimer::singleShot(0, AutoUpdate::getInstance(), []
    {
        AutoUpdate::getInstance()->checkUpdate(false);
    });
#endif // Q_OS_ANDROID

    return a.exec();
}
