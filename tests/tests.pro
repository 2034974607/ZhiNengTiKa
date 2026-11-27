QT = core testlib

CONFIG += console testcase c++17
TEMPLATE = app
TARGET = test_updaterelease

INCLUDEPATH += ../src/Singleton
HEADERS += ../src/Singleton/UpdateRelease.h
SOURCES += tst_updaterelease.cpp
