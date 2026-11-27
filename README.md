# ZhiNengTiKa / xinjiaoyu

原项目作者：LFWQSP2641。本 fork 保留项目原有代码和版权声明，并补充 Android APK 构建及发布配置。项目许可证见 [LICENSE](LICENSE)。

## Android 构建

构建目标为 `arm64-v8a`，沿用应用版本 `1.8.0`。工程使用 Qt 6.4.3、Android SDK / Build Tools 31、NDK r23b（23.1.7779620）和 JDK 17。Qt 需安装 Android arm64-v8a、相同版本的桌面主机工具以及 `qtwebview`、`qtshadertools` 模块。

原工程引用的自定义 WebView 没有包含在仓库中。Android 现在使用仓库内的 `AndroidWebView` 兼容封装，保留 `setHtml` / `getHtml` 接口，底层使用 Qt 官方 WebView。Windows 仍保留原项目的外部 WebView 依赖配置；本工作流只构建 Android。

先获取固定版本依赖：

```sh
git clone https://github.com/bricke/Qt-AES.git third_party/Qt-AES
git -C third_party/Qt-AES checkout fe02771df612ca80fdb12b1d48b6e089e1933e6b
git clone https://github.com/KDAB/android_openssl.git third_party/android_openssl
git -C third_party/android_openssl checkout b71f1470962019bd89534a2919f5925f93bc5779
```

设置 `ANDROID_SDK_ROOT`、`ANDROID_NDK_ROOT`、`JAVA_HOME` 和指向同版本桌面 Qt 安装目录的 `QT_HOST_PATH`（例如 `/opt/Qt/6.4.3/gcc_64`），将目标 Android Qt 的 `bin` 加入 `PATH`，然后构建：

```sh
mkdir -p build
cd build
qmake ../xinjiaoyu.pro CONFIG+=release ANDROID_ABIS=arm64-v8a ANDROID_PLATFORM=android-31
make -j2
make INSTALL_ROOT="$PWD/android-build" install
"$QT_HOST_PATH/bin/androiddeployqt" --input android-xinjiaoyu-deployment-settings.json --output android-build --android-platform android-31 --jdk "$JAVA_HOME" --gradle --release
```

随后使用 Android SDK 的 `zipalign`、`apksigner` 和自己的长期签名密钥对 APK 对齐、签名并验证。不要将密钥、密钥密码或已解码的密钥文件提交到仓库。

## GitHub Actions 和 Release

`Android APK` 工作流会安装依赖，编译、签名并检查 APK 的签名、包名、版本和 ABI，上传 APK 及 `SHA256SUMS.txt`。

首次运行前，在仓库 Actions secrets 中配置：

- `ANDROID_KEYSTORE_BASE64`：长期签名 keystore 的 Base64 文本。
- `ANDROID_KEYSTORE_PASSWORD`：keystore 密码。
- `ANDROID_KEY_PASSWORD`：密钥密码。

密钥别名固定为 `zhinengtika`。请安全保存 keystore 和密码；丢失后无法继续用同一签名发布更新。

默认分支的相关修改会触发构建。也可以在 Actions 中手动运行 `Android APK`；勾选 `publish` 后，工作流将在成功构建后创建与项目版本对应的 `v1.8.0` Release，附带 APK 和 SHA-256 校验文件。已存在的同名 Release 不会被覆盖。发布说明位于 `.github/RELEASE_NOTES.md`，发布后续版本时应同时更新应用版本和说明。

构建校验不代表真机功能验证。仓库原有题库、登录、扫码和自动更新等外部服务保持不变，其当前可用性需要另外测试。此 fork 的签名与原版不同，安装前请备份数据；不要直接覆盖安装使用其他签名的版本。