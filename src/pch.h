#ifndef PCH_H
#define PCH_H

#include <QApplication>
#include <QtWidgets>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonValue>
#include <QByteArray>
#include <QString>
#include <QStringList>
#include <QRegularExpression>
#include <QFileDialog>
#include <QUrl>
#include <QCryptographicHash>
#include <QtNetwork/QNetworkReply>
#include <QtNetwork/QNetworkRequest>
#include <QtNetwork/QNetworkAccessManager>
#include <QHttpMultiPart>
#ifdef Q_OS_ANDROID
#include <QtCore/private/qandroidextras_p.h>
#include <QJniEnvironment>
#include <QJniObject>
#endif // Q_OS_ANDROID

#include "qaesencryption.h"
#ifdef Q_OS_ANDROID
#include <QtWebView/QtWebView>
#include <QQuickWindow>
#include <QSGRendererInterface>
#include "GUI/AndroidWebView.h"
#else
#include "D:/Softwares/scr/WebView/WebView.h"
#endif

#endif // PCH_H
