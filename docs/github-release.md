# 原生版 1.0.3 GitHub 发布说明

本次将已确认的设置页设计与此前离线库修复发布到现有的 [2034974607/ZhiNengTiKa](https://github.com/2034974607/ZhiNengTiKa) 仓库。默认分支继续使用 `master`，保留旧 Qt、原生 1.0.0 与后续原生开发历史，不强制推送。

## 版本与安装

[native-v1.0.3](https://github.com/2034974607/ZhiNengTiKa/releases/tag/native-v1.0.3) 的 Android 版本为 `1.0.3` / `10003`。Release 包名 `app.zhinengtika.nativeapp`，最低 Android 8.0（API 26），沿用原生 1.0.0–1.0.2 的 Release 签名，可保留数据覆盖升级；**不要先卸载**，卸载会删除私有数据与 Keystore 密钥。签名证书 SHA-256 为 `9138c44081d7ebab74f4d16df26e92d1cde9ffadb6dcc28364210c9d8d1f0b88`。

原生版与旧 Qt 版的包名、签名和版本序列独立。Qt 用户需单独安装原生版，旧 Qt 私有数据不会自动迁移。旧 [Qt v1.8.1](https://github.com/2034974607/ZhiNengTiKa/releases/tag/v1.8.1) 标签、安装包及通用 `releases/latest` 入口保留，原生版不接管旧 Qt 更新器。原生版尚无应用内更新检查与下载。

## 本次变化

- 设置页采用大标题、圆角分组与统一图标间距；三位贡献人员直接展示本地圆形头像、身份与 GitHub 主页，项目及上游仓库提供整行入口。
- 包含 1.0.1 的导入结果单次提示、六科分类、顶部目录入口、逐卡加密存储和旧 100 张库迁移；容量为 10,000 张 / 512 MiB。
- 题库内容不随公共源码或 APK 分发；用户自行导入有权使用的资料。

## APK 与源码的对应关系

三个 APK 原样沿用已签名、已核验的 1.0.3 文件。本次发布仅整理公开文档，不修改构建输入，不重新构建安装包。

- 已确认 APK 的构建输入提交：`786ffd1fc1fe411702b09c63f512c2e7fd12cd3e`。
- 发布前公开 `master`：`af002a39e3f95c27528f21d0f8760381bf841040`。
- 最终发布提交与源码树以 `native-v1.0.3` 标签、源码 ZIP 和 `github-release-manifest.json` 为准。

发布清单逐项比较 `app/`、`protocol/`、`gradle/`、根 Gradle 配置、wrapper 和 `tools/build-android.py`，要求与已确认构建输入完全一致。Release APK 的 SHA-256 为：

```text
99f89cfecf25320f011a737e93c2a0fbc7c058ee72e9f4c6104717024521c5ef
```

[Release APK 下载](https://github.com/2034974607/ZhiNengTiKa/releases/download/native-v1.0.3/ZhiNengTiKa-1.0.3-release.apk)。完整源码、Debug / UI 测试 APK、静态报告、构建来源清单及 `SHA256SUMS.txt` 在[同一发布页](https://github.com/2034974607/ZhiNengTiKa/releases/tag/native-v1.0.3)。不上传签名私钥、密码、账号资料或真实题库。

## 报告时间与验证范围

1.0.3 实际完成 Debug / Release / AndroidTest 构建、三个 APK 静态验签和资源核验；Release Lint 为 **0 错误、14 警告、3 提示**。构建耗时 6 分 7 秒，124 项任务中 55 项执行、69 项沿用结果。**本轮未重跑单元测试、未安装到手机，也未执行真机界面或 instrumentation 测试。** 编译成功不能代替设置页在不同设备和字体缩放下的视觉验收。

1.0.1 的 60 项 JVM 测试及 519 张库迁移/重复导入验证是历史回归；1.0.0 的两个 Compose 真机测试、41 项主机验收工具检查与 API 37 user0 有限设备记录同样保留各自版本范围，详见[测试报告](../TESTING.md)与[已知问题](known-issues.md)。原始构建、Lint 与 APK 资源证据原样保留于本次报告 ZIP；本地交付快照单独标为发布前记录，其中绝对路径只描述采集环境。
