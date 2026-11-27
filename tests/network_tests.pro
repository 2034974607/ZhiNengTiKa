TEMPLATE = app
TARGET = test_network
QT = core network testlib
CONFIG += console testcase c++17
CONFIG -= app_bundle

SOURCES += \
    tst_network.cpp \
    ../src/Singleton/Network.cpp

HEADERS += \
    ../src/Singleton/Network.h

INCLUDEPATH += ../src/Singleton
