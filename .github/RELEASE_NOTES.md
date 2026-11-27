基于原项目 1.8.0 代码的 Android 构建版，保留原作者 LFWQSP2641 的版权声明。

### 本 fork 的发布准备

- 修复仅适用于原作者电脑的 Android 构建依赖路径。
- 使用 Qt 官方 WebView 补齐原仓库未包含的 Android WebView 封装。
- 配置 Qt 6.4.3、固定版本依赖和可重复使用的 APK 签名。
- 在成功编译、签名及校验 APK 后提供安装包和 SHA-256 校验值。

### 原项目 1.8.0 更新

以下条目来自原项目 ChangeLog：

- 更新 Qt 框架至 6.4.3。
- 优化设置相关功能。
- 修复一处潜在的内存泄漏。

### 下载与使用

- `ZhiNengTiKa-1.8.0-arm64-v8a.apk`：64 位 ARM Android 手机安装包。
- `SHA256SUMS.txt`：安装包完整性校验值。
- 这是本 fork 的独立签名，与原作者签名不同，不能直接覆盖安装原作者版本。如已安装原版，请先备份数据；卸载会清除应用数据。
- 编译和签名校验不能替代真机功能测试。登录、题库服务、页面显示、扫码及更新等功能尚未经本次发布的真机验证；项目沿用的外部服务可能已发生变化。
- 原有启动逻辑会查询最低版本服务；服务不可达或返回内容异常时，可能提示“请更新版本或检查网络连接”并导致主界面不可用。此构建保留该业务逻辑。

### 源码和依赖

- 本项目许可证：GNU AGPL v3，见仓库 `LICENSE`。
- Qt 6.4.3：<https://download.qt.io/archive/qt/6.4/6.4.3/>。
- Qt-AES 源码及其许可证：<https://github.com/bricke/Qt-AES/tree/fe02771df612ca80fdb12b1d48b6e089e1933e6b>。
- Android OpenSSL 二进制、构建说明及许可证：<https://github.com/KDAB/android_openssl/tree/b71f1470962019bd89534a2919f5925f93bc5779>。
- 本次对应的应用源码可通过本 Release 下方的源码归档下载；完整构建步骤见仓库 README。
