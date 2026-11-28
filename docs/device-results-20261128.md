# 2026-11-28 受限真机验收记录

## 结果与设备

最终 Debug 的两个 Compose instrumentation 测试实际通过；最终 Release 已完成离线合成题卡与系统文件选择器取消返回的手工检查，以及五次进程冷启动、五次同进程任务返回和一次 PSS/初屏帧记录。此轮不代表全产品验收通过，真实账号与服务互操作仍未执行。

设备为 OPPO PHY120，Android 17 / API 37、arm64-v8a，屏幕 1440×3168、密度 640 dpi、系统字体缩放 1.0。当前 Android 用户为 0，另有 user999（MultiApp）；本版 Release、Debug 和测试包均不在 user999 安装。经用户重新明确授权，使用手机工具与 Shizuku shell（uid0）执行受限验收，应用操作明确限定 user0。手机同时运行 DSH/proot、Shizuku 和其他进程，未隔离后台负载。

通用[干净单用户验收脚本](../tools/acceptance-device.py)及旧 48 条命令计划**从未执行**：当前手机既有多用户又有 Release/Debug 现存包，不满足该脚本守卫。本轮采用独立审查后的 user0 操作，不是关闭脚本守卫后执行旧计划。先前停止/取消的手机操作保留为历史；本轮依新的明确授权进行。

## 包身份、构建与安装

| 包 | 最终字节 | 最终 SHA-256 | 本轮设备变更 |
|---|---:|---|---|
| Release `app.zhinengtika.nativeapp` | 2,708,694 | `0727a8e6d92c8947b6166e5e7b9c6a5220d3340d310ae2040642d10bcf36f305` | 现有安装先核验旧摘要，再保留数据更新并核验最终摘要 |
| Debug `app.zhinengtika.nativeapp.debug` | 22,115,366 | `a6439d413b0ec8fc03e2a5526ef536d77cd84f1aae7a562b06a781a1b9af7498` | 现有安装核验，APK 未变 |
| UI 测试 `app.zhinengtika.nativeapp.debug.test` | 1,053,614 | `1ad250eed1a6e55c82032768811a04c5d4204388d7d8470ba230e9e67644614e` | 先新装旧测试包，再保留数据更新为修复后测试包 |

全程没有卸载或清数据。起初 Release 的旧摘要为 `909eaf5d56d6566a6cecd3f0f9925961d6e33b5d5b437e7e098f54896a95042c`，旧测试包为 `01e95ff6b01cf799d0e0b15673aeeebae9b2df5978ffef2e008dbf9de6fe1372`。旧摘要对应的测量只保留为历史，不作为最终包的结果。

代码变更限于 Android UI 测试依赖：JUnit 扩展 1.3.0、runner 1.7.0、显式 Espresso core 3.7.0，及相应锁和测试许可记录。业务实现、Compose 和 Release 运行依赖未变，compile/target/min SDK 仍为 35/35/26。末次完整签名构建成功日志为交付报告 `build-logs/20261128T043932.019568Z.log`，耗时 4 分 27 秒，135 项任务中 29 执行、106 `UP-TO-DATE`；32 项协议测试沿用既有实际通过结果，不能写成本轮重跑。

三个最终 APK 已重新通过静态签名、包名/版本、SDK、ZIP 和对齐核验。Debug 摘要不变；Release 体积相同但版本控制元数据变化导致摘要改变；测试包随依赖更新而变化。不声称位级可重复构建。Debug/Release Lint 均为 0 错误、4 警告、3 提示，Navigation 自定义规则覆盖缺口仍保留。

## 两项 Debug 测试

测试前观察到 Debug 稳定显示未登录、已保存账号/最近访问均为 0、离线题卡/目录均为 0，且没有存储错误。应用启动会读取自己的加密账号数据；代理没有读取凭据正文，测试也没有输入账号密码或点击登录。

| 测试方法 | 开始状态 | 最终状态 |
|---|---|---|
| `offlineSyntheticCardNeverOffersSubmission` | `INSTRUMENTATION_STATUS_CODE: 1` | `0`，通过 |
| `homeShowsQueryAndAccountNavigationOpensLoginForm` | `INSTRUMENTATION_STATUS_CODE: 1` | `0`，通过 |

最终输出包括确切测试类、`numtests=2`、`OK (2 tests)` 和 `INSTRUMENTATION_CODE: -1`；命令退出码 0，**2 开始、2 通过、0 失败、0 跳过**，报告耗时 6.206 秒。本次成功 instrumentation 于北京时间 12:48:43 开始。

第一项验证合成离线示例只读且无作业入口；载入示例会在 Debug 私有离线库创建或刷新 `demo-builtin-001`，重写题卡加密记录并保留示例，不能把测试称作不写数据。第二项验证空编号查询禁用、账号导航与登录表单标签，不测试登录业务。这两项不是 Release instrumentation，也不覆盖相机、文件成功读写、多账号、user999、草稿、提交保护或完整生命周期。没有触发线上业务操作，没有抓包，不能声称实测零网络。

原 Espresso 3.5.0 的同两项测试实际开始但均在基础设施初始化阶段失败：`NoSuchMethodException: android.hardware.input.InputManager.getInstance []`，0 通过、2 失败。官方[修复提交](https://github.com/android/android-test/commit/fddcda4adc8eaba515fbf2d790cf8dfe5c1b722d)在现代 Android 使用 `Context.getSystemService(InputManager.class)`；升级测试依赖后原断言不变并通过。旧失败摘录清楚标注不是完整 stdout，不能被新结果抹去。

## Release 手工检查

早前旧摘要 Release 已实际显示题干、选项和父子层级，答案/解析/原题及切题操作有响应。本轮最终包更新后，再次观察：

- 首页未登录，空编号查询禁用（s18）。
- 保留数据更新和进程冷启动后，离线库仍有一张合成题卡（s19）。
- 导入系统选择器打开（s20），取消后回到资料库（s21）。
- 只读详情打开（s22）；导出系统选择器打开（s23），取消出现明确取消消息（s24），关闭后回详情（s25）。
- 实际截图 s26 显示合成题干、选项、父子题层级、只读状态与无作业入口；截图带工具元素编号覆盖。

没有选择真实或合成文件完成导入/导出，因此这里只验证选择器打开与取消返回。截图存于交付证据 `release-offline-synthetic.jpg`，SHA-256 为 `51e537cd3adb9119d581c508bce423be3a19cd3293a1ebb9587f88c1e5d7a288`。此合成截图不构成真实题卡排版等价性证据。

## 最终 Release 性能范围

北京时间 12:51:55 完成有效采集，Release 进程 UID 为 10598：

| 指标 | 原始样本 ms | 中位数 ms |
|---|---|---:|
| 进程冷启动 `TotalTime` | 365, 382, 362, 359, 357 | 362 |
| 进程冷启动 `WaitTime` | 380, 387, 372, 364, 369 | 372 |
| 同进程任务返回 `WaitTime` | 61, 45, 40, 43, 47 | 45 |
| 同进程任务返回 `TotalTime` | 五次均缺失 | 无数据 |

冷启动前明确 force-stop 并确认无进程，之后系统五次均为 COLD、新 PID、预期组件；返回前台每个样本前后 PID 均为 32085、UID 10598，系统将已有任务带回前台，`LaunchState: UNKNOWN (0)`。返回样本只提供 `WaitTime`，不能称为温启动或渲染时延，缺失 `TotalTime` 保留为空。

PSS 为单次 **27,687 KiB**；初始页面/任务返回帧记录为 **20 帧、2 janky frames**，不是峰值内存或 ALL 滚动 benchmark。采样前后 Battery Service 分别为 14% / 39.1°C、13% / 39.5°C，均未接 AC/USB/无线电源；这些上下文快照不能推导 App 功耗或温升。详细解释见[性能报告](../PERFORMANCE.md)。

旧摘要 Release 的中位数 238 ms、PSS 26,463 KiB 只留历史，不参与最终汇总，不作跨批次性能比较。首次安装、磁盘冷缓存、fully drawn、长期滚动、峰值、功耗、温升和 Qt 对比均未测量。

## 失败、诊断与记录边界

- 首次 `pm` 安装命令因 `-` 语法返回 255；只读确认测试包仍未安装后，修正 `-- -` 并成功安装。没有用替换或清数据解决。
- 首次 `am` 因两个标准 classpath 环境变量为空，在 Java VM 创建前中止（134），无测试开始输出。确认没有活动 instrumentation 后，仅在当前命令导入 zygote 的 `BOOTCLASSPATH` 与 `DEX2OATBOOTCLASSPATH`，未改系统配置。
- 旧 Espresso 两项失败是实际执行结果，独立保存；新测试包通过只代表修复后那次执行。
- 一批性能命令将 Binder stdout 直接定向共享存储而失败，包装没有在首错停止，有效样本为 0，未计入结果。只读诊断确认控制台/管道正常后，后续使用管道捕获并校验状态/PID。失败摘要保留，意外全进程列表不进入交付。
- 主机验收记录逻辑的 41 项合成检查通过、ADB 进程数 0；它们不增加真机通过数。静态 APK 报告与设备运行报告分别保留，默认计划仍为未执行。

## 证据位置与后续工作

设备证据不属于源码构建输入，保存在工作区 `artifacts/evidence/device-20261128/`，交付报告复制为 `evidence/device-20261128/`。应以 `session-final.json` 汇总最终包结果；`session-before-test-fix.json` 是旧包、旧工具和诊断的历史。完整最终 instrumentation stdout/stderr、五次冷启动/任务返回原文、meminfo、framestats、电池上下文和截图均在同目录。旧失败文件是摘录，已明确标注。交付清单记录最终源码提交和证据路径，不在本文硬编码尚未生成的提交号。

仍待验收：完整 SAF 读写/异常、主题/大字体/旋转、相机与合成二维码、在线账号与草稿生命周期、隔离/清理/UNKNOWN 保护、授权服务互操作、其他 API/ABI 与最低 API 设备，以及更广性能测量。完整状态见[测试报告](../TESTING.md)和[已知问题](known-issues.md)。这些后续操作仍限于用户实际授权范围，本次记录不扩展手机或真实业务授权。
