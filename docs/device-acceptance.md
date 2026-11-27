# 设备验收执行方案

## 当前状态

这是尚未在设备上执行的方案。三个 APK 已静态核验，两个 Compose instrumentation 测试已编译；截至本次交付，没有设备安装、启动、UI 测试或 Android 性能结果。此前手机 shell 探测在启动前被取消，之后没有重试、改写或切换后端执行该命令。

此前主机只读检查发现 `127.0.0.1:5037` 与 `127.0.0.1:5555` 均无监听，本工程未安装模拟器。这是当时的本地连接情况，不是本次重新探测的结果，也不能证明不存在其他可连接设备。

[验收脚本](../tools/acceptance-device.py)把安装、两个合成 UI 测试和基础 Release 性能采集串起来。默认只核对交付目录内三个 APK 的 SHA-256、静态核验标记和 API 范围，然后生成命令计划；不会启动 ADB、安装应用或操作屏幕。生成计划不算设备验证。

[脚本合成检查](../tools/check-device-acceptance.py)在主机上使用人工构造的输出和模拟进程验证解析、执行顺序及异常停止；结果保存在交付报告的 `evidence/device-runner-selfcheck.json`。它没有执行 ADB，也不增加设备测试通过数。交付报告还包含 `evidence/device-acceptance-plan.json`，其中 `DEVICE_SERIAL` 是占位符；换机器或移动交付目录后应重新生成计划。

## 准备条件

- 自己控制的 Android 8.0 / API 26 及以上测试设备，屏幕已解锁，当前 Android 用户为 0 且只有这个用户；工作资料、访客或其他用户设备由脚本拒绝，避免跨用户安装/运行差异。
- 已通过正常开发者流程授权该主机的 ADB；自行确定设备 serial。脚本不负责配对、连接、重连或恢复 DSH 手机操控。
- 设备内没有本版 Release、Debug 和测试包，包括卸载后保留的数据。三个安装前查询全部通过后才开始安装；脚本不覆盖已有包、不卸载、不清数据。已安装的包由维护者自行选择保留或改用另一台干净测试设备。
- 已解压的完整交付目录，包含三个 APK 和静态核验报告。仅有源码 ZIP 不包含 APK。
- 运行设备操作前再次确认当前工具通道可用并取得授权。先前取消的操作不能因为脚本已经准备好而被自动重放。

## 先生成可审查的计划

在源码根目录运行，替换为实际交付目录。计划模式允许不指定 serial：

```sh
python3 tools/acceptance-device.py \
  --delivery /path/to/unpacked-delivery \
  --output /path/to/new-acceptance-plan.json
```

输出文件必须不存在；脚本不覆盖原记录。生成结果的 `kind` 是 `device-acceptance-plan`，`status` 为 `plan-only`，`deviceTestsExecuted` 为 `false`，`commands` 是空数组；待执行命令在 `plan` 数组。检查设备、三个安装包路径、摘要及每一条命令。

在已授权且符合上述条件的测试设备上显式执行：

```sh
python3 tools/acceptance-device.py \
  --delivery /path/to/unpacked-delivery \
  --serial YOUR_DEVICE_SERIAL \
  --adb /path/to/host-compatible/adb \
  --run \
  --output /path/to/new-device-result.jsonl
```

不指定 `--adb` 时优先使用 PATH 中的 adb；ARM64 本机构建环境可使用已有的静态工具。不同宿主架构需选择匹配的工具，脚本不会下载或主动启动替代服务器；执行 adb 本身可能启动其正常的本地服务。默认每组启动采样五次，可用 `--samples 3` 到 `--samples 20` 调整。

运行记录为追加式 JSONL，每个非空行是当时的完整快照，不是单个 JSON 文档；读取最后一个可解析的完整对象来确定最新状态。命令启动前先落盘 `in-progress`，完成或异常后再写快照。进程被强杀或存储写入失败时，最后状态可能停留在 `in-progress`，应按结果未知处理。

## 脚本实际执行范围

1. 检查指定 serial 返回 `device`；确认当前用户为 0 且无其他用户；读取厂家、型号、Android 版本/API 和电池上下文。
2. 在主机私有临时目录复制并重新核对三个 APK 的摘要，避免共享存储上的文件在检查后变化。查询三个包均不存在（含保留数据）后，逐个一次性安装 Debug、Release、测试 APK；安装不带 `-r` 或 `-g`，明确选用用户 0。
3. 使用 AndroidJUnitRunner 运行 `AppNavigationTest`：主页/账号导航；合成离线题卡只读且无作业入口。检查两个确切方法的开始/成功状态、测试类、计数及最终成功标记；不能仅靠 adb 退出码判断成功。
4. 对 Release 默认采集五次进程冷启动：每次先 `am force-stop`，再 `am start -W`。随后五次按 Home 返回前台，前后检查 PID 连续性，分别记录 `TotalTime` 和 `WaitTime`；若系统明确返回 `LaunchState: COLD`，不能计为同进程返回。
5. 采集 Release 的单次 `dumpsys meminfo` PSS 和初始页面的 `dumpsys gfxinfo ... framestats` 原文。

非零退出、无成功标志、已有包、测试失败或关键数据不完整都会停止后续步骤。超时、Ctrl-C 或 SIGTERM 记为结果未知并停止，不重试、不重连、不切换通道；已经开始的设备动作可能继续，必须先人工核实现状。已成功安装的包和合成示例会保留，脚本不清理它们。主机私有 APK 快照也保留在运行记录所示路径，便于核对。

脚本状态 `scripted-checks-complete` 只表示自动步骤完成。`deviceTestsExecuted` 表示观测到至少一个预期测试开始，并不等于全部测试成功；非零退出或超时会保留已观察到的测试计数，同时强制标记 `instrumentation.complete: false`。若结果未知而没有取得开始状态，`deviceTestsExecuted: false` 也不能证明设备端没有执行，应结合原始输出和 `commandResultUnknown` 人工核实。应同时检查 `instrumentation.complete`、命令状态和最终脚本状态；它们仍不能代替下面的人工验收。

## 测量的解释与人工验收

进程冷启动不清磁盘缓存，也不是安装后首次启动或 Macrobenchmark 的 fully drawn 指标；返回前台属于已有任务恢复，不标为温启动。`am start -W` 记录系统启动时序，不能单独证明 Compose 内容完全渲染或可交互。PSS 是单次快照；初始页面帧记录不能当作 ALL 列表滚动性能。电池上下文不能推导耗电或温升。

自动脚本不操作真实账号、不触发登录/取题/图片上传/答卷提交，也不会完成下面的人工场景：

- Release 首次启动及系统返回、浅深色和字体缩放、横竖屏。
- SAF 导入/导出、取消与异常恢复，原文件不变。
- 相机授权拒绝/恢复、切镜头及合成二维码识别。
- 草稿快速返回、后台/进程重建、URI 权限恢复、相册重选提示。
- 多账号隔离、清理范围、提交确认与未知状态保护。

完整待验收表见[测试报告](../TESTING.md)，性能口径见[性能报告](../PERFORMANCE.md)。真实服务互操作须另有获授权的测试账号、题卡和作业；不可拿真实不可撤回答卷提交做压测。记录中可能含设备型号、serial 和本机路径，外发前应人工检查。
