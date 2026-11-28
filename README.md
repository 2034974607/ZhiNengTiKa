# 新教育智能题卡 · 原生 Android 重写

独立 Kotlin / Jetpack Compose / Material 3 客户端。新工程不依赖 Qt、C++、QML 或 WebView；使用新的应用标识、独立签名和全新 Git 历史。旧仓库仅用来审计业务与协议，未合并其历史。

最低 Android 8.0（API 26），目标 API 35。Release 为 `app.zhinengtika.nativeapp`，Debug 为 `app.zhinengtika.nativeapp.debug`，版本 `1.0.3` / `10003`。Release 沿用 1.0.0 签名，可保留数据覆盖升级；旧版最多 100 张的本机离线库会自动迁移。包名设计支持与旧 Qt 版共存，数据分别保存。

1.0.3 参考原生设置列表重排页面：大标题与副标题、浅色圆角分组、统一图标与留白。三位贡献人员在同一分组中直接展示圆形头像、身份说明和 GitHub 主页入口，不使用贡献人员弹窗；头像内置，断网可见，来源与摘要见[头像资源记录](docs/contributor-avatars.json)。项目与上游仓库保持独立整行入口；深浅主题、FAQ/隐私/许可全文和清除数据确认保留，详细清除范围收进说明入口。

1.0.2 在设置页新增“项目仓库”和“贡献人员”：可打开[项目仓库](https://github.com/2034974607/ZhiNengTiKa)与[上游仓库](https://github.com/LFWQSP2641/ZhiNengTiKa)，并展示上游仓库作者 LFWQSP2641、项目贡献者 2034974607，以及 OpenAI（GPT-6.1-Sol · AI 辅助开发），各自附可打开的 GitHub 主页链接。

1.0.1 修复批量导入重复提示、六科分类与目录入口，并改为逐卡加密存储，容量提高为 10,000 张 / 512 MiB。60 项 JVM 回归已实际通过，完整 519 张提取库也通过迁移、加密落盘、重启与重复导入验收，详见[离线资料库](docs/offline-library.md)及[测试与验收](TESTING.md)。1.0.0 在 OPPO PHY120 / Android 17（API 37）的受限设备记录仍保留在[历史真机记录](docs/device-results-20261128.md)，不能代替本修复版设备验收。真实账号、相机和复杂生命周期仍待验证。

## 使用

安装交付目录中的 Release APK。Android 首次从当前文件管理器安装时可能要求允许安装应用；由系统展示并由用户决定。Debug APK 供开发与测试，名称带“调试”。

无需登录即可进入“离线资料库”。“整库 ZIP”可直接选择已提取的题库压缩包，无需逐张导入；也可多选 JSON / 两行式目录。顶部“目录 / 题卡”切换与六科筛选常驻，内容按科目展开。每批只反馈一次结果，失败文件集中列出；同编号更新现有题卡，保留已导入的本机引用。支持按名称、编号和学科搜索，查看全部/逐题、父子题、答案、解析和原题，并导出不含历史答卷的 HTML。详情见[离线资料库说明](docs/offline-library.md)。离线资料只读，离线阅读不会自动请求远端题目图片。

在线查询需在“账号”页使用自己的学生账号登录。查询、扫码回填编号和最近访问均可从首页操作；账号之间的缓存、历史、草稿与提交状态隔离。密码不保存，二维码由 CameraX + ZXing 在本机解码。安装包不含旧版题库、公共账号或个人记录。

在线详情的“作业”页支持选择题、相册多图、参考答案与上次答卷回填。最终确认显示目标账号及未答题情况；图片会先上传，答卷另行发送。提交无法撤回，应用不自动重试。出现“结果未知”时须先到服务端核实，再考虑解除本机保护标记。

## 工程与构建

- [app](app/build.gradle.kts)：Compose 界面、CameraX、Android Keystore AES-GCM、本地存储与 SAF 导入导出。
- [protocol](protocol/build.gradle.kts)：纯 Kotlin/JVM 模型、AES/摘要、HTTP 协议、受限解析、离线格式与 HTML 导出；合成测试不连接业务服务。
- [构建脚本](tools/build-android.py)、[APK 核验](tools/verify-apks.py)与[交付打包](tools/package-delivery.py)：保留实际构建与产物证据。
- [设备验收方案](docs/device-acceptance.md)与[验收脚本](tools/acceptance-device.py)：脚本默认只生成计划，实际执行只接受干净单用户测试设备。本次多用户手机通过另行授权、明确限定 user0 的手机工具验收，未执行该脚本；结果见[真机记录](docs/device-results-20261128.md)。脚本的[主机合成检查](tools/check-device-acceptance.py)独立于设备测试。
- [结构与数据流](docs/architecture.md)、[业务对照](docs/feature-comparison.md)、[协议](docs/protocol.md)、[迁移](docs/migration.md)、[隐私](docs/privacy.md)、[第三方许可](docs/third-party-notices.md)与[已知问题](docs/known-issues.md)。

常规 Android 开发机先准备 JDK 17、官方 platform 35 与 build-tools 34.0.0，再运行：

```sh
./gradlew :protocol:test :app:assembleDebug :app:lintDebug
```

完整构建、ARM64 proot 的工具架构限制、SDK 许可、Release 签名和离线依赖说明见 [BUILDING.md](BUILDING.md)。测试证据和真机验证范围见 [TESTING.md](TESTING.md)，主机合成测量与受限 Android 采样见 [PERFORMANCE.md](PERFORMANCE.md)。

## 差异与限制

本版保留经审计的学生登录、题卡读取、图片上传与答卷提交协议，但未使用真实账号执行线上互操作测试。编译或本地 MockWebServer 测试不能证明当前生产服务可用。

未实现公告、学案资源下载、应用内更新源、独立字体/字号设置、原始答卷 JSON 手工编辑。上游学案接口使用的随机授权与固定学校编号不属于有效认证方案；原生版未采用它。没有原生版自己的发布源，不能把旧 Qt 的更新 APK 安装到本版。详情及其他差异见 [功能对照](docs/feature-comparison.md) 与 [已知问题](docs/known-issues.md)。

旧应用私有数据不能跨应用沙箱自动读取。离线导入会去除旧答卷与账号元数据，不迁移旧密码、token 或公共账号。卸载会删除本版加密私有数据；签名私钥未包含在源码或下载交付中。

## 许可与来源

工程以 GNU AGPL v3 分发，完整对应源码与 APK 一同交付，见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。依赖分别遵循原许可证，见 [第三方声明](docs/third-party-notices.md) 及应用“设置 → 开源说明”。

审计基线：fork [2034974607/ZhiNengTiKa](https://github.com/2034974607/ZhiNengTiKa) `ed34415b5fb893dc22a6b94a5cd14e3c2e7a457c`；upstream [LFWQSP2641/ZhiNengTiKa](https://github.com/LFWQSP2641/ZhiNengTiKa) `4d192e4e61a7f854c8f48be739e7cff513b6b242`。本工程是独立重写，与教育服务运营方不存在官方关联；题目、题卡及商标权益归相应权利人。
