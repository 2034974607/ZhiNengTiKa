import QtQuick
import QtWebView as NativeWeb

Item {
    property string html: ""

    onHtmlChanged: browser.loadHtml(html, "about:blank")

    NativeWeb.WebView {
        id: browser
        anchors.fill: parent
    }
}
