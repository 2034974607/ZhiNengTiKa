基于原项目 1.8.0 的 Android 修复版，保留原作者 LFWQSP2641 的版权声明。应用版本 1.8.1，Android versionCode 10801。

### 修复内容

- 移除启动时禁用整个主窗口的最低版本门禁。更新检查在主事件循环启动后异步进行，网络失败、跳过更新或系统返回不再把主页留在禁用状态。
- 更新来源改为本 fork 的 GitHub Release，按版本号选择 arm64-v8a APK，不再使用原作者的旧更新/加密下载接口。
- 更新提示和下载流程使用非阻塞对话框，增加超时、取消、错误提示、下载大小和 SHA-256 校验；校验通过后才保存供安装的 APK。
- 修复 Android 外部浏览器 Intent，移除错误的网页 MIME 限制；打开失败时给出提示。
- 修复 APK 安装权限流程，使用缓存目录和 FileProvider。未允许安装未知来源应用时引导到系统设置，返回后可再次点击安装。

### 下载与安装

- `ZhiNengTiKa-1.8.1-arm64-v8a.apk`：64 位 ARM Android 手机安装包。
- `SHA256SUMS.txt`：安装包完整性校验值。
- 沿用本 fork v1.8.0 的签名，可以覆盖升级该版本；无需卸载或清除数据。与原作者使用其他签名的版本不能直接覆盖安装。
- 自动化测试和构建校验范围见对应 Actions 日志。发布操作不代表已完成真机逐项测试。
- 登录、题库、扫码等业务外部服务仍沿用原项目，服务是否可用需要分别验证；本次不会绕过登录或服务端权限。

### 源码和依赖

- 本项目许可证：GNU AGPL v3，见仓库 `LICENSE`。
- Qt 6.4.3：<https://download.qt.io/archive/qt/6.4/6.4.3/>。
- Qt-AES 源码及其许可证：<https://github.com/bricke/Qt-AES/tree/fe02771df612ca80fdb12b1d48b6e089e1933e6b>。
- Android OpenSSL 二进制、构建说明及许可证：<https://github.com/KDAB/android_openssl/tree/b71f1470962019bd89534a2919f5925f93bc5779>。
- 对应应用源码可通过本 Release 的源码归档下载；完整构建步骤见仓库 README。
