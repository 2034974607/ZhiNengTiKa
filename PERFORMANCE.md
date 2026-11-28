# 性能测量与范围

## 已测：主机 JVM 的合成数据路径

原始结果为交付证据中的 `synthetic-benchmark.json`；生成入口：

```sh
python3 tools/build-android.py -- :protocol:benchmarkSynthetic
```

时间：2026-11-27 20:52:04 UTC（北京时间 2026-11-28 04:52:04）。环境：Android 手机上的 Ubuntu / proot，Linux aarch64、8 个逻辑 CPU，OpenJDK 17.0.21.1。夹具完全合成，200 道题，UTF-8 JSON 48,342 字节；每组测量预热 20 次。报告保留 JVM 版本、时间、迭代数和原始汇总值。

| 数据路径 | 测量次数 | 中位数 ms | P95 ms | 最大值 ms |
|---|---:|---:|---:|---:|
| 导入 200 道合成题 | 80 | 5.343 | 6.676 | 11.284 |
| 导出 200 道题为 HTML | 80 | 23.509 | 57.750 | 729.112 |
| 4 KiB UTF-8 AES 加解密往返 | 200 | 0.403 | 1.713 | 17.198 |
| 一批 1,000 次请求摘要 | 80 批 | 11.285 | 28.151 | 37.077 |

HTML 导出出现 729 ms 的最大值，明显高于中位数。当前证据不能区分 GC、调度、JIT 和主机后台负载的贡献，不能忽略异常值或宣称稳定低延迟。请求摘要一行是每批一千次的耗时，不是单个请求的网络延迟。

这些是运行在手机 Linux 环境里的 **JVM 算法测量**，不属于 Android App 真机 UI benchmark；没有采集置信区间、CPU 温度或系统调度轨迹，不用于承诺实际用户时延。

## 源码中采取的措施

- HTTP 使用可取消的 OkHttp 异步调用；磁盘、Keystore、导入/导出和照片处理放到 IO dispatcher。
- 在线响应按解压后的 8 MiB 限制读取，JSON 深度限制 64、题树深度 12、题目总数 2,000，防止无界解析。
- 首页、离线目录、ALL 题目页使用 LazyColumn，按可见区域创建题目内容；不一次生成全部原生 TextView。
- 富文本解析使用 remember 复用；二维码帧约每 180 ms 解码一次，使用单线程和 KEEP_ONLY_LATEST。
- 相册扫码与提交图片最长边降采样到不超过 2,048；提交图片重编码为 JPEG 质量 85，去除 EXIF。
- 离线资料库限制 100 张卡、总题卡 JSON 16 MiB、目录 10,000 条；写入时在锁内再次检查。
- Release 启用 R8 和资源收缩；APK 实际大小及 native 库对齐以 `apk-verification.json` 的生成记录为准。

以上只说明机制存在，不等于已测得启动、帧率、内存或功耗改善。离线资料库仍是一个整体加密 blob，每次改动会重写，接近总容量上限时仍可能较慢。

## 已测：最终签名 APK 的文件体积

最终 APK 的 ZIP CRC、SHA-256、签名、版本与 SDK 已核验，详细记录保存在交付证据的 `apk-verification.json`。1 MiB = 1,048,576 字节。

| 产物 | 字节 | MiB |
|---|---:|---:|
| 独立签名 Release（R8 / 资源收缩） | 2,708,694 | 2.583 |
| Debug | 22,115,366 | 21.091 |
| Debug instrumentation 测试 APK | 1,053,614 | 1.005 |

Debug 与前次交付的字节和摘要相同；Release 体积相同，但版本控制元数据变化使摘要改变；测试 APK 随 Espresso/runner 升级而变化。最终摘要及真机运行对应关系见[真机记录](docs/device-results-20261128.md)。文件体积不代表安装后存储占用，也没有同条件 Qt 包体积比较。

## 已测：最终 Release 的受限真机采样

时间为 2026-11-28，北京时间 12:51:55 完成性能采集。设备 OPPO PHY120、Android 17 / API 37、arm64-v8a、user0，另有 user999；本版不在 user999 安装。屏幕 1440×3168、密度 640 dpi、字体缩放 1.0。采样经明确授权的 Shizuku shell（uid0）执行并核对应用进程 UID 10598；手机同时运行 DSH/proot、Shizuku 等，属于实际使用环境，未隔离后台负载。

本表仅对应最终 Release SHA-256 `0727a8e6d92c8947b6166e5e7b9c6a5220d3340d310ae2040642d10bcf36f305`。交付报告保留 `evidence/device-20261128/session-final.json`、`verified-final-cold-launches.txt` 与 `verified-final-foreground-returns.txt`。

| 指标 | 五个原始样本 ms | 中位数 ms | 最小 / 最大 ms |
|---|---|---:|---:|
| 进程冷启动 `TotalTime` | 365, 382, 362, 359, 357 | 362 | 357 / 382 |
| 进程冷启动 `WaitTime` | 380, 387, 372, 364, 369 | 372 | 364 / 387 |
| 同进程任务返回 `WaitTime` | 61, 45, 40, 43, 47 | 45 | 40 / 61 |
| 同进程任务返回 `TotalTime` | 五次均未提供 | 无数据 | 无数据 |

每次冷启动先对 user0 的 Release 执行 force-stop、确认无进程，再 `am start --user 0 -W`；系统均报告预期组件、`Status: ok`、`LaunchState: COLD`，之后为新 PID。它只测进程冷启动，不清磁盘缓存，不是安装后首次启动或 Macrobenchmark 的 fully drawn 指标。

返回前台的五次样本前后 PID 均为 32085、UID 均为 10598，系统表示当前任务被带回前台、`LaunchState: UNKNOWN (0)`，且没有 `TotalTime`。因此只汇总实际存在的 `WaitTime`，它是命令/系统等待记录，不能写成“温启动 45 ms”或 Compose 渲染耗时；缺失值不填成 0。`am start -W` 本身也不能证明内容已经可交互。

最终单次 `dumpsys meminfo` 的 **PSS 为 27,687 KiB**，不是峰值或稳定态分布。初始页面及任务返回期间的 `gfxinfo` 报告 **20 帧、2 个 janky frames**；仅保留这段记录，不作为 ALL 列表滚动 benchmark、长期掉帧率或帧率承诺。原始文件为 `verified-final-release-meminfo.txt`、`verified-final-release-framestats.txt`。

采样前电池记录为 14%、Battery Service 温度 39.1°C，采样后为 13%、39.5°C，均未接 AC/USB/无线电源；这是两个系统上下文快照。手机同时承担 DSH/proot、Shizuku、测试和操控等工作，不能把电量或温度差归因于本 App，也不能称作耗电或温升测量。

先前旧摘要 Release 的冷启动中位数 238 ms、PSS 26,463 KiB 保留在 `session-before-test-fix.json`，不混入最终样本，也不以两批差异推导性能变化。另有一次直接将 Binder 输出重定向到共享存储的批次失败，包装未在首错停止，有效样本为 0；核实后使用管道捕获并校验状态/PID，才得到上述记录。失败批次不参与汇总，也未把意外全进程列表放入交付。

## 尚未测量的项目

尚未测量首次安装启动、磁盘缓存冷启动、fully drawn、ALL 长列表滚动、峰值 PSS、持续扫码功耗、App 可归因温升及真实上传耗时。没有同设备、同题卡、同网络条件下的旧 Qt 测量，因此没有“快多少”“省多少电”的对比结论。

[设备验收方案](docs/device-acceptance.md)区分通用干净单用户脚本与本轮单独受限的 user0 验收。本轮没有执行该脚本或旧 48 条计划；主机 **41 项合成检查**只验证记录/判断逻辑和缺失字段处理，ADB 进程数为 0，不计作性能样本。后续更广测量应在授权范围内记录设备和负载条件，使用合成或获授权脱敏内容，结合 Macrobenchmark / Perfetto 采集；不能用真实不可逆答卷提交做压测。
