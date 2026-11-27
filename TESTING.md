# 测试与验收

## 已执行的本地测试

`protocol` 模块使用 JDK 17、JUnit 4 和本机 MockWebServer，全部夹具为人工构造的合成数据。最近一次协议测试实际执行 **32 个，32 通过，0 失败，0 错误，0 跳过**。报告路径：`protocol/build/test-results/test/`、`protocol/build/reports/tests/test/`，交付证据内保留 JUnit XML 和日志。最后一轮修改仅涉及 Android 界面/声明与交付文件；最终构建对未变更的协议测试使用 Gradle `UP-TO-DATE`，不将缓存命中写成新增执行次数。

| 范围 | 数量 | 关键断言 |
|---|---:|---|
| ProtocolCryptoTest | 2 | 固定合成摘要向量；UTF-8、空串、块边界 AES 往返 |
| TemplateParserTest | 2 | 题目/子题解密与顺序；无效业务响应和密文拒绝 |
| TemplateParserBoundariesTest | 4 | 全题组保序、重复 ID、树/JSON 深度、2,000 题边界 |
| AnswerLettersTest | 1 | 只读取可见选项文本，忽略 HTML 标签字母 |
| StudentApiTest | 7 | 同一时间戳、请求头/schema、业务码/gzip、503 不重放、307 不外传凭据、上传 URL 编码、缺学生身份拒绝 |
| ApiRobustnessTest | 6 | 终止回调恰好一次、取消后真实 Call 完成、同步构造与 Call 创建失败释放保护、JSON 深度与 gzip 解压后 8 MiB 上限 |
| TemplateTransferTest | 10 | 旧/新格式、隐私字段白名单、非法导入、UTF-8/编号限制、递归预算、两行目录、HTML 净化与全层级导出 |

```sh
python3 tools/build-android.py -- :protocol:test
```

这些测试没有连接 `www.xinjiaoyu.com` 或第三方二维码服务，也没有使用任何真实账号、题卡或作业。AES 往返不等于对服务端密文的跨实现验证。

## Android 构建与静态产物检查

已实际生成最终 Debug、独立签名且经过 R8/资源收缩的 Release，以及 Debug instrumentation 测试 APK。三个 APK 的完整静态核验均通过；Release 与 Debug 证书独立，测试 APK 与 Debug 证书相同，均为最低 API 26 / 目标 API 35。构建脚本、固定版本和复现步骤见 [BUILDING.md](BUILDING.md)。最终交付目录的构建日志及 APK 核验 JSON 是对应交付物的证据；较早日志中的失败、中断或主动取消保留用来解释修正，不代表最终结果。

`tools/verify-apks.py` 执行：

- 官方 `apksigner.jar` 验签，记录证书公开指纹与适用 API 范围。
- aapt2 检查包名、版本、min/target SDK、权限和 `debuggable`；Release 与 Debug 包名和证书不同。
- ZIP CRC 和 SHA-256；`zipalign -c -P 16 4`。
- APK 原生 `.so` 的 ELF LOAD 对齐：ARM64 / x86_64 至少 16 KiB。ZXing 本身为 Java，CameraX/Compose 仍有三组 native 库；没有本工程自己的 C++ 或 Qt 运行库。

验签或文件对齐通过不能证明 Release R8 后所有运行时路径都正确。没有实际执行的路径明确列在下方。

首轮完整 Lint 找到一个真实 API 兼容错误：API 27 的 `windowLightNavigationBar` 放在了 API 26 基础主题中。已改成 `values-v27` 资源；同时增加 Android 12+ `dataExtractionRules`，明确排除云备份和设备迁移。未关闭 NewApi 检查、未生成忽略错误的 baseline。

Navigation 2.8.9 自带的部分自定义 Lint 检查与当前 Lint API 不兼容，会报告 `ObsoleteLintCustomCheck` 并跳过对应 Navigation 检查。这是静态分析覆盖的已知限制，不影响 APK 构建；最终报告保留原始警告，不写作“全部规则覆盖”。

最终 Debug 与 Release 均为 **0 错误、4 警告、3 提示**：三个 Navigation 自定义注册器过旧警告，一个最低版本已为 26 时 `mipmap-anydpi-v26` 多余的资源目录警告；提示为三个整型状态装箱建议。最终强制刷新 Release 分析与报告后，旧备份声明警告已消失，APK 摘要保持不变。完整签名构建耗时 11 分 1 秒，末次静态报告刷新耗时 2 分 21 秒；原始日志均附于交付报告。

## Instrumentation 测试（已编译，尚未执行）

`AppNavigationTest` 包含两个 Compose 测试：主页查询按钮状态及账号表单导航；离线合成示例进入详情后始终只读且没有“作业”入口。测试 APK 编译通过不代表测试运行通过。

连接自己控制的测试设备后：

```sh
./gradlew :app:connectedDebugAndroidTest
```

或安装对应 Debug 与测试 APK，再执行：

```sh
adb shell am instrument -w \
  app.zhinengtika.nativeapp.debug.test/androidx.test.runner.AndroidJUnitRunner
```

不要使用已存真实账号或作业数据的设备做未经审查的测试。现有两个测试只操作新 App 的界面与明确标注的合成示例。

## 验收脚本的主机合成检查

[设备验收脚本](tools/acceptance-device.py)默认只生成计划；[方案与前置条件](docs/device-acceptance.md)说明安装、两个 UI 测试及 Release 启动/PSS 采集的范围。只有显式 `--run`、实际 serial 和符合条件的已授权测试设备才执行设备步骤；脚本拒绝覆盖已有包或保留数据，遇到失败或结果未知就停止。

```sh
python3 tools/check-device-acceptance.py
```

[合成检查脚本](tools/check-device-acceptance.py)使用人工输出及模拟进程验证 instrumentation 状态/测试身份、启动组件与测量分类、包存在性和用户检查顺序，以及超时、Ctrl-C、SIGTERM、IO 错误后停止。交付证据 `device-runner-selfcheck.json` 保留本次实际运行的检查明细；它报告 ADB 进程数为 0、设备测试未执行。这组检查不属于上述 32 个 JVM 测试，也不证明 Android 设备行为。

本次收尾只修改验收工具、交付脚本与文档，未修改 Android / protocol 模块和 Gradle 构建输入；复用已经核验的三个 APK，摘要与上一份交付一致，没有把本次打包记作重新构建。

## 本次设备控制限制

本机构建工具包中的 ARM64 adb 能执行 `version`，不代表已连接 Android。此前对手机授权 shell 的一次只读调用返回 `shizukuStopped`（`started:false`，没有命令执行），恢复设备通道的交接也被停止。之后最近一次读屏返回“无障碍服务未连接”；Android shell 的只读身份/API 探测返回 `cancelled`、`started:false`，没有命令执行。没有改写重试或切换通道重放，本次打包也未重新探测设备。因此尚未安装或启动本版 App，没有取得真实触控、截图、logcat、相机或系统权限结果。交付证据另附 `device-validation.json`，记录这一区别。

## 真机验收清单（待执行）

| 场景 | 期望行为 | 本次状态 |
|---|---|---|
| Release 安装、首次启动、系统返回 | 正常进入首页，可与旧版/Debug 共存 | 未执行 |
| 无账号离线合成示例 | 全部/逐题/答案/解析/原题可读；无作业、无网络题图 | 未执行 |
| JSON/目录导入与 HTML 导出 | 系统选择器工作；取消可恢复；拒绝过大/非法文件；原文件不变 | 未执行 |
| 浅色/深色、字体系统缩放、横竖屏 | 无遮挡、按钮可点击，布局可滚动 | 未执行 |
| 扫码拒绝相机/再次授权/切镜头/相册取消 | 错误清楚，资源释放，可继续手输编号 | 未执行 |
| 多账号、离线缓存、快速切换与登出 | 数据隔离、过期请求不更新当前界面 | 未执行 |
| 草稿快速返回、后台、相册 URI | 最后作答正确保存，无法保存有明确提示，持久读取权限有效 | 未执行 |
| 确认提交、取消、超时/断网 | 确认快照一致；一次请求；未知状态持续锁定 | 仅本地协议分支测试 |
| 清在线缓存/离线库、逐条删除 | 清除范围准确，确认后执行，不改原文件/草稿/未知锁 | 未执行 |

真实学生账号登录、图片上传、不可逆答卷提交需要单独的获授权测试账号与测试作业。本交付没有执行这些操作。

## 性能

已执行一次主机 JVM 的合成数据路径测量，详见 [PERFORMANCE.md](PERFORMANCE.md) 与原始 JSON。没有 Android 启动/帧率/内存/功耗测试，没有同条件 Qt 对比，不声称性能提升。
