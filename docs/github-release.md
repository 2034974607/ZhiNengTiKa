# 原生版 GitHub 发布说明

本次把已完成的原生 Android 工程发布到现有的 [2034974607/ZhiNengTiKa](https://github.com/2034974607/ZhiNengTiKa) 仓库，不创建新的 GitHub 项目。默认分支继续使用 `master`，目录内容替换为 Kotlin / Jetpack Compose / Material 3 工程。

## 代码与历史

发布提交保留旧 Qt `master` 与原生工程两段历史，不通过强制推送删除旧提交。旧版固定入口为 [Qt v1.8.1](https://github.com/2034974607/ZhiNengTiKa/releases/tag/v1.8.1)，其源码基线为 `ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c`。

原生版 [native-v1.0.0](https://github.com/2034974607/ZhiNengTiKa/releases/tag/native-v1.0.0) 使用独立版本序列；它与旧 Qt `v1.8.1` 的数字不能作为同一 Android 应用的升级关系。原生 Release 包名为 `app.zhinengtika.nativeapp`，使用独立签名，需要单独安装，不自动迁移旧版私有数据。

旧 Qt 版本内置更新器读取通用 `releases/latest`，并要求旧式版本标签和特定 APK 名称。因此本次原生版不接管该入口，README 与此文提供原生版的精确标签下载地址。旧发布与标签保留，原生版尚无应用内更新检查与下载。

## APK 与源码的对应关系

三个 APK 沿用已签名、已核验并对应实际设备记录的最终文件；本次发布只修改仓库说明、许可联系入口、发布文档与打包脚本中的历史表述，不修改应用、协议、Gradle 配置或构建入口，不重建安装包。

- APK 构建输入提交：`f5db1b1692d50aff1ca249af5a10da5c9cc0dbd6`。
- 发布前完整交付快照：`10d7c3e62bceae5b27fe0dc3367d20cf561af1a4`。
- 当前发布源码：以 `native-v1.0.0` 标签、源码 ZIP 和 `github-release-manifest.json` 为准；清单记录发布提交、源码树、构建输入比较范围及附件摘要。

发布清单逐项比较 `app/`、`protocol/`、`gradle/`、根 Gradle 配置、wrapper 和 `tools/build-android.py`，要求与 APK 构建输入提交一致。Release APK 的 SHA-256 为：

```text
0727a8e6d92c8947b6166e5e7b9c6a5220d3340d310ae2040642d10bcf36f305
```

[Release 下载](https://github.com/2034974607/ZhiNengTiKa/releases/download/native-v1.0.0/ZhiNengTiKa-1.0.0-release.apk)；完整源码、Debug、UI 测试 APK、报告与 `SHA256SUMS.txt` 在[同一发布页](https://github.com/2034974607/ZhiNengTiKa/releases/tag/native-v1.0.0)。私钥与签名密码不上传 GitHub。

## 报告时间与验证边界

公开报告中的源码说明更新为本次发布状态，构建日志、JVM/Lint、APK 核验与真机原始证据保持原样。`reports/prepublication-snapshot/` 中的清单和交付说明保留 2026-11-28T05:30Z 的本地快照事实；其中“无远端推送”“4 个提交”“无 remotes”等描述只指那个快照，不描述本次发布后的仓库。该目录附有说明，原始交付文件的相对路径仍以原完整交付目录为根。

32 项 JVM 合成测试已通过，末次构建沿用已有结果；两个 Compose 真机测试已通过；41 项验收工具检查属于主机合成测试。API 37 user0 上的 Release 验证仅覆盖离线示例、持久恢复、系统文件选择器取消和有限启动/PSS 采样。真实账号、线上服务、相机、完整文件交换及复杂生命周期仍待验收，见[测试报告](../TESTING.md)、[设备记录](device-results-20261128.md)与[已知问题](known-issues.md)。
