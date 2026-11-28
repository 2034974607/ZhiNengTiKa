# 测试与验收

## 已执行的本地测试

`protocol` 模块使用 JDK 17、JUnit 4 和本机 MockWebServer，全部夹具为人工构造的合成数据。最近一次协议测试实际执行 **32 个，32 通过，0 失败，0 错误，0 跳过**。报告路径：`protocol/build/test-results/test/`、`protocol/build/reports/tests/test/`，交付证据内保留 JUnit XML 和日志。2026-11-28 的末次构建仅升级 Android UI 测试依赖并补充验收工具/文档，协议源码未变；`:protocol:test` 为 Gradle `UP-TO-DATE`，不能写作本轮重新执行 32 项。

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

最终 Debug 与 Release 均为 **0 错误、4 警告、3 提示**：三个 Navigation 自定义注册器过旧警告，一个最低版本已为 26 时 `mipmap-anydpi-v26` 多余的资源目录警告；提示为三个整型状态装箱建议。早前 API/备份声明修正、11 分 1 秒签名构建及 2 分 21 秒静态报告刷新保留为历史记录。2026-11-28 升级测试依赖后的完整签名构建成功，用时 **4 分 27 秒**，135 项任务中 29 项执行、106 项 `UP-TO-DATE`，日志为交付报告 `build-logs/20261128T043932.019568Z.log`。Debug 摘要不变，Release 因版本控制元数据变化而摘要改变，测试 APK 因依赖更新而变化；三个最终 APK 已重新静态核验，不声称逐字节可重复构建。

## Instrumentation 测试（真机已执行）

2026-11-28 在 OPPO PHY120、Android 17 / API 37、arm64-v8a、Android user0 上实际执行 `AppNavigationTest`。手机另有 user999（MultiApp），三个应用包均不在该用户安装；本轮使用用户重新明确授权的手机工具与 Shizuku shell（uid0），每次应用操作明确限定 user0。

| 确切测试方法 | 断言范围 | 最终结果 |
|---|---|---|
| `homeShowsQueryAndAccountNavigationOpensLoginForm` | 空编号查询禁用；账号导航显示登录表单和密码标签 | 通过 |
| `offlineSyntheticCardNeverOffersSubmission` | 载入内置合成题卡、打开只读详情、没有“作业”入口、有“全部”页 | 通过 |

最终测试 APK 使用 Espresso 3.7.0、runner 1.7.0、JUnit 扩展 1.3.0。两个方法各有状态码 `1` 开始与 `0` 成功，最终 `OK (2 tests)`、`INSTRUMENTATION_CODE: -1`，**2 开始、2 通过、0 失败、0 跳过**，JUnit 报告耗时 6.206 秒。完整输出与摘要存于交付报告的 `evidence/device-20261128/instrumentation-final-stdout.txt` 和 `session-final.json`；APK 摘要见[真机记录](docs/device-results-20261128.md)。这仅覆盖 Debug 的两个场景，不能记为 Release instrumentation 或全部验收通过。

此前 Espresso 3.5.0 的两个测试实际开始并均失败，原因是测试基础设施初始化时反射不存在的 `InputManager.getInstance()`；**旧结果为 0 通过、2 失败**，失败摘录保留且明确不是完整原始 stdout。官方[修复提交](https://github.com/android/android-test/commit/fddcda4adc8eaba515fbf2d790cf8dfe5c1b722d)在现代 Android 上改用 `Context.getSystemService(InputManager.class)`。本轮只升级测试依赖和相应锁/许可清单，业务实现、Compose 版本与 Release 运行依赖未变，没有降低断言或跳过测试。

测试前单独观察 Debug 稳定显示未登录、无已保存账号、无最近访问、离线库/目录均为空且没有存储错误。应用启动会读取自己的加密账号存储；测试没有输入凭据或点击登录。第二项测试会在 Debug 私有离线库创建或刷新内置合成示例，**测试存在本地写入**，结束后示例保留。Release 与 Debug 私有存储分开；离线详情不接收账号、不读写在线草稿/提交标记。没有触发线上业务操作，也没有抓包，不能据此宣称验证了零网络。

## 安装、设备通道与异常记录

Release/Debug 起初已经安装，先核对其摘要精确匹配旧交付，未重新安装 Debug；新装测试包后，因测试依赖修复而在保留数据的前提下更新 Release 与测试包，并再次核对最终安装摘要。全程没有卸载或清数据。旧版[验收脚本](tools/acceptance-device.py)要求干净单用户设备，本设备既有 user999 又有现存包，**该脚本和旧 48 条命令计划从未执行**。本次是独立、明确限定 user0 的受限验收，不证明 user999 或跨用户隔离。

初次安装命令因 `pm` 对 `-` 的解析返回 255；只读确认包仍不存在后修正为 `-- -` 才完成安装。初次 `am` 启动因 shell 缺少 `BOOTCLASSPATH` / `DEX2OATBOOTCLASSPATH` 在 Java VM 创建前中止（134），未观察到测试开始；确认没有活动 instrumentation 后，仅为当前命令从 zygote 导入两个标准变量，没有更改系统配置。另一批性能命令因 Binder 输出直接定向共享存储而失败，包装未在首错停止；这批有效样本为 0，未计入结果。只读诊断后以管道捕获并校验状态/PID，得到后续有效记录。各类失败与最终成功分别保留，不以 shell 退出码单独判断业务成功。

早前 `shizukuStopped`、无障碍不可用和取消调用保留为历史；本次是在新的明确授权下恢复通道后执行，并非自动重放取消操作。[设备验收方案](docs/device-acceptance.md)说明后续授权与状态核实要求。

## 验收脚本的主机合成检查

```sh
python3 tools/check-device-acceptance.py
```

[合成检查脚本](tools/check-device-acceptance.py)本轮 **41 项通过、0 个 ADB 进程**，使用人工输出及模拟进程验证测试身份、异常停止、用户/包守卫，以及返回前台仅有 `WaitTime` 时保留缺失 `TotalTime`。时间汇总分别记录两种字段，空值不填成 0。这组检查不是上述 32 个 JVM 测试或两个真机测试，也不是性能测量；结果保存在交付报告 `evidence/device-runner-selfcheck.json`。默认计划仍不执行设备命令。

## 真机验收清单

| 场景 | 本次实际覆盖 | 剩余范围 |
|---|---|---|
| Release 安装/启动/返回 | 现有包摘要核验、保留数据更新、首页未登录/空查询禁用、进程冷启动及任务返回 | 从未安装状态首次安装/首次启动、其他 Android/API/ABI |
| 无账号离线合成示例 | Release 题干/选项/父子层级截图、只读无作业；先前版本的答案/解析/原题及切题操作；更新和冷启动后仍有一张示例 | 真实/复杂题卡排版、长列表和异常容量 |
| 系统文件选择器 | 最终 Release 导入选择器打开后取消并回库；导出选择器打开后取消，显示取消消息并返回详情 | 实际文件读写、JSON/目录导入、成功导出、过大/非法文件及提供方异常 |
| 浅深色/字体/横竖屏 | 当前系统字体缩放 1.0 的基础页面 | 主题切换、大字体、旋转均待执行 |
| 扫码/权限/相册 | 未执行 | 拒绝/再次授权、切镜头、合成 QR、相册恢复 |
| 多账号/缓存/登出 | 未使用真实账号 | 数据隔离、过期请求、跨用户行为 |
| 草稿/后台/URI | 内置离线示例保留至更新和冷启动后 | 在线作答快速返回、进程重建、URI 权限恢复 |
| 提交确认/断网/UNKNOWN | 仅既有本地协议分支测试 | 设备持久保护与获授权测试作业 |
| 清理/逐条删除 | 未执行 | 清理范围与确认行为 |

真实学生登录、取题、图片上传和不可逆答卷提交均未执行，需要单独获授权的测试账号、题卡和作业。没有同条件 Qt 对比，不声称全产品已通过验收。

## 性能

除既有主机 JVM 合成测量外，本轮对最终 Release 实际采集五次进程冷启动、五次同 PID 返回前台、一次 PSS 和初始页面帧记录。原始值、缺失字段与测量局限见[性能报告](PERFORMANCE.md)及[真机记录](docs/device-results-20261128.md)；未测量滚动性能、功耗、温升、峰值内存或真实上传耗时。
