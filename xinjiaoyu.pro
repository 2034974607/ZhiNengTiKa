QT       += core widgets gui network
VERSION = 1.8.0
android{

QT       += core-private

ANDROID_VERSION_NAME = $$VERSION

RESOURCES += Resource/Resource_Android.qrc

include(D:/Softwares/scr/AndroidSDK/android_openssl/openssl.pri)

ANDROID_PACKAGE_SOURCE_DIR = \
    $$PWD/android

}
win32{

RC_ICONS = Resource/img/xinjiaoyuico.ico

QMAKE_TARGET_PRODUCT = "xinjiaoyu"

QMAKE_TARGET_COPYRIGHT = "Copyright © 2022 - 2023 LFWQSP2641.All Rights Reserved."

RC_LANG = 0x0004

RESOURCES += Resource/Resource.qrc

}

CONFIG += webview

#DEFINES += LIMITED

DEFINES += APP_VERSION=\\\"$$VERSION\\\"

DEFINES += DATABASE_DOMAIN=\\\"https://gitee.com/LFWQSP2641/zhinengtika_database/raw/master/\\\"

DEFINES *= QT_USE_QSTRINGBUILDER

CONFIG += c++17 precompile_header

PRECOMPILED_HEADER = src/pch.h

# You can make your code fail to compile if it uses deprecated APIs.
# In order to do so, uncomment the following line.
#DEFINES += QT_DISABLE_DEPRECATED_BEFORE=0x060000    # disables all the APIs deprecated before Qt 6.0.0

# Default rules for deployment.
qnx: target.path = /tmp/$${TARGET}/bin
else: unix:!android: target.path = /opt/$${TARGET}/bin
!isEmpty(target.path): INSTALLS += target

include(D:/Softwares/scr/Qt-AES/QAESEncryption.pri)
include(D:/Softwares/scr/WebView/WebView.pri)

HEADERS += \
    src/GUI/AnswerAndAnalysisWidget.h \
    src/GUI/ClickableLabel.hpp \
    src/GUI/MainWidget.h \
    src/GUI/MultipleSubjectsTemplateListView.h \
    src/GUI/NavigationBarTabWidget.h \
    src/GUI/PixmapLabel.h \
    src/GUI/PlusSignLabel.h \
    src/GUI/QuestionWidget.h \
    src/GUI/SearchWidget.h \
    src/GUI/SelectWidget.h \
    src/GUI/SettingWidget.h \
    src/GUI/ShowFullScreenWidget.h \
    src/GUI/TemplateDetailWidget.h \
    src/GUI/TemplateListView.h \
    src/GUI/UploadChildWidget.h \
    src/GUI/UploadWidget.h \
    src/GUI/WebViewWidget.h \
    src/Logic/AnalysisWebRawData.h \
    src/Logic/AnswerDetailData.hpp \
    src/Logic/MultipleSubjectsTemplateListModelList.h \
    src/Logic/TemplateListModel.h \
    src/Logic/TemplateSearcher.h \
    src/Logic/UserData.h \
    src/Logic/UserDataList.hpp \
    src/Singleton/AutoUpdate.h \
    src/Singleton/Network.h \
    src/StaticClass/CallAndroidNativeComponent.h \
    src/StaticClass/Global.h \
    src/StaticClass/QRCodeScanner.h \
    src/StaticClass/Setting.h \
    src/StaticClass/XinjiaoyuEncryptioner.h \
    src/StaticClass/XinjiaoyuNetwork.h

SOURCES += \
    src/GUI/AnswerAndAnalysisWidget.cpp \
    src/GUI/MainWidget.cpp \
    src/GUI/MultipleSubjectsTemplateListView.cpp \
    src/GUI/NavigationBarTabWidget.cpp \
    src/GUI/PixmapLabel.cpp \
    src/GUI/PlusSignLabel.cpp \
    src/GUI/QuestionWidget.cpp \
    src/GUI/SearchWidget.cpp \
    src/GUI/SelectWidget.cpp \
    src/GUI/SettingWidget.cpp \
    src/GUI/ShowFullScreenWidget.cpp \
    src/GUI/TemplateDetailWidget.cpp \
    src/GUI/TemplateListView.cpp \
    src/GUI/UploadChildWidget.cpp \
    src/GUI/UploadWidget.cpp \
    src/GUI/WebViewWidget.cpp \
    src/Logic/AnalysisWebRawData.cpp \
    src/Logic/MultipleSubjectsTemplateListModelList.cpp \
    src/Logic/TemplateListModel.cpp \
    src/Logic/TemplateSearcher.cpp \
    src/Logic/UserData.cpp \
    src/Singleton/AutoUpdate.cpp \
    src/Singleton/Network.cpp \
    src/StaticClass/CallAndroidNativeComponent.cpp \
    src/StaticClass/Global.cpp \
    src/StaticClass/QRCodeScanner.cpp \
    src/StaticClass/Setting.cpp \
    src/StaticClass/XinjiaoyuEncryptioner.cpp \
    src/StaticClass/XinjiaoyuNetwork.cpp \
    src/main.cpp \
    src/pch.h.cpp

android{
DISTFILES += \
    android/AndroidManifest.xml \
    android/build.gradle \
    android/gradle.properties \
    android/gradle/wrapper/gradle-wrapper.jar \
    android/gradle/wrapper/gradle-wrapper.properties \
    android/gradlew \
    android/gradlew.bat \
    android/res/drawable-hdpi/icon.png \
    android/res/drawable-ldpi/icon.png \
    android/res/drawable-mdpi/icon.png \
    android/res/drawable-xhdpi/icon.png \
    android/res/drawable-xxhdpi/icon.png \
    android/res/drawable-xxxhdpi/icon.png \
    android/res/values/libs.xml \
    android/src/com/LFWQSP2641/xinjiaoyu/CallNativeComponent.java \
    android/src/com/LFWQSP2641/xinjiaoyu/IntentActivity.java \
    android/src/com/LFWQSP2641/xinjiaoyu/Util.java \
    android/res/xml/provider_paths.xml
}
