#include "AndroidWebView.h"

#include <QDebug>
#include <QQmlError>
#include <QQuickItem>
#include <QQuickView>
#include <QVBoxLayout>

WebView::WebView(QWidget *parent)
    : QWidget(parent), view(new QQuickView)
{
    view->setResizeMode(QQuickView::SizeRootObjectToView);
    auto *container = QWidget::createWindowContainer(view, this);
    container->setFocusPolicy(Qt::StrongFocus);
    auto *layout = new QVBoxLayout(this);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->addWidget(container);

    connect(view, &QQuickView::statusChanged, this, [this](QQuickView::Status status)
    {
        if (status == QQuickView::Ready)
        {
            updateHtml();
        }
        else if (status == QQuickView::Error)
        {
            for (const auto &error : view->errors())
            {
                qCritical().noquote() << error.toString();
            }
        }
    });
    view->setSource(QUrl(QStringLiteral("qrc:/webview/AndroidWebView.qml")));
}

void WebView::setHtml(const QString &value)
{
    html = value;
    updateHtml();
}

QString WebView::getHtml() const
{
    return html;
}

void WebView::updateHtml()
{
    if (view->status() == QQuickView::Ready && view->rootObject())
    {
        view->rootObject()->setProperty("html", html);
    }
}
