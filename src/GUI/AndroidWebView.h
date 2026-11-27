#ifndef ANDROIDWEBVIEW_H
#define ANDROIDWEBVIEW_H

#include <QString>
#include <QWidget>

class QQuickView;

// Compatibility interface for the original, unpublished WebView dependency.
class WebView : public QWidget
{
public:
    explicit WebView(QWidget *parent = nullptr);
    void setHtml(const QString &html);
    QString getHtml() const;

private:
    void updateHtml();

    QQuickView *view;
    QString html;
};

#endif // ANDROIDWEBVIEW_H
