# 原生版协议实现说明（脱敏）

## 证据边界

- 本文只描述**源码中已实现的协议处理**，依据旧项目静态审计（`ZhiNengTiKa-native-audit/
  protocol.md`）和本仓库 `protocol` 模块代码；**未对真实服务发起任何请求**，未使用任何真实
  账号凭据。服务端当前可用性、业务码、字段细节均未获线上验证。
- 文中不含 AES 密钥、真实 token/session、账号名、学生/学校 ID、历史答卷或任何真实用户数据。
  客户端内置对称密钥本身不构成安全边界，不在文档、测试夹具、日志或崩溃报告中复现。
- JVM 中的 MockWebServer 往返与算法测试**不是服务端互操作验证**。任何“通过”仅表示本地
  合成夹具下代码按预期工作。

## 端点（静态来源，不代表线上验证）

| 流程 | 方法与路径 | 本仓库实现位置 |
|---|---|---|
| 登录 | `POST https://www.xinjiaoyu.com/api/v3/server_system/auth/login` | `StudentApi.login` |
| 题卡 | `GET https://www.xinjiaoyu.com/api/v3/server_homework/homework/template/question/list`（`templateCode`、`studentId`、`isEncrypted=true`） | `StudentApi.getTemplate` |
| 答案图片上传 | `POST https://www.xinjiaoyu.com/api/v3/server_homework/homework/upload`（multipart，字段 `file`） | `StudentApi.uploadImage` |
| 答卷提交 | `POST https://www.xinjiaoyu.com/api/v3/server_homework/student/homework/answer` | `StudentApi.submitAnswers` |

## 认证头与摘要

- 头字段：`app: student`、`client: android`、`Content-Type: application/json`、
  `User-Agent: okhttp/4.9.3`、`Authorization`、`accessToken`、`clientSession`、`t`、`encrypt`。
- `t` 为毫秒时间戳，`encrypt` = `MD5(UTF-8("jbyxinjiaoyu" + t + clientSession + "android"))`
  的小写十六进制（非 HMAC）。本实现一次生成时间戳并复用，避免旧版正文与头部时间窗不一致。
- 登录 `Authorization` 使用固定的 `JBY` 前缀值加服务返回的 token；登录正文中的用户名与密码
  先经协议加密再 Base64。
- OkHttp 客户端关闭自动重定向/SSL 重定向与连接失败重试，避免不可逆 POST（尤其答卷提交）
  被框架重放；TLS 证书校验保持系统默认开启，禁用明文流量（`usesCleartextTraffic=false`）。
- 响应体无论是否 gzip 都按解压后的字节计算 8 MiB 上限，再进入 JSON 解析，防止解压放大攻击。

## 加密与题卡解码

- 旧代码声明 AES-256/ECB/PKCS7，32 字节 ASCII key；JCE 用 `AES/ECB/PKCS5Padding` 对 16 字节
  分组填充等价。本仓库 `ProtocolCrypto` 实现 encrypt/decrypt 与 digest，密钥不出现在文档、
  报告或测试夹具中。算法是否仍与当前服务端兼容**未做线上验证**。
- `TemplateParser` 以 JSON 解析器读取业务 `code`、字段和嵌套结构，不再使用旧版 `mid(8,3)`
  或整段字符串相等；支持多题组、递归子题、`ordered` 编号、选项 A–G、解密 `answer` 与
  `answerExplanation`。上一次答卷字段读取 `record.answer.answer` 与 `record.answer.rawScan`
  （不是旧 UI 内部名 `userAnswer`）。
- 选项字母从可见文本提取（`AnswerLetters` 用 Jsoup），避免把 HTML 标签字母误当成选项。
- 防御上限：响应 ≤8 MiB、JSON 嵌套 ≤64、题目深度 ≤12、题数 ≤2000、编号长度与字符约束、
  重复编号拒绝、解密失败给出可解释错误。
- 富文本由 Android `TextView` 的受限 HTML 能力显示，不执行脚本、不提供链接导航，仅单独请求
  HTTPS 图片。它不支持完整浏览器 CSS、MathML 或 JavaScript 公式排版；HTML 导出另经 Jsoup 清洗。

## 主观题图片与不可逆提交

- 图片在端侧解码、按最长边 2048 采样、重编码 JPEG（质量 85）、限制 15 MB，去除 EXIF 与
  原文件元数据；上传响应中的访问 URL 必须为 HTTPS，文件名按 URL 编码替换末段路径。
- 图片上传与答卷提交是两个独立事务。最终答卷 JSON 根字段：`schoolId`、`studentId`、
  `templateCode`、`answers[]`；每项 `questionId`、`answer`、`rawScan`（多图 URL 以逗号连接）。
- 提交前：未答计数提醒 → 显示目标账号与“不可撤回”确认 → 冻结确认时的作答快照 → 原子写
  加密 UNKNOWN journal（`tryBegin`）→ 发 POST。
- 提交后：仅服务端明确成功才 `resolve`；网络异常/超时/未知业务码一律保留“结果未知”，
  **不自动重试**；进程内 IN_FLIGHT 阻止人工解锁，重启后仍需用户先在服务端核实。
- 取消请求会取消 OkHttp call，并在最终回调中释放 in-flight 标记；即便调用方协程已消失也不会
  永久卡住账户。503/307/308 不触发凭据重放。

## 本地导入不属于协议认证

- `TemplateTransfer.importJson` 仅接受用户自行提供的题卡 JSON/旧列表文本，8 MiB/UTF-8/深度/
  数量限制，白名单重建字段，清除历史作答、`rawScan`、`retrievedAtMillis` 与账号元数据；
  导入结果明确标注“只读离线，不能作为在线提交或账号身份依据”。
- `exportHtml` 导出仅含白名单字段的 UTF-8 静态 HTML，不含上次答卷等私有数据。

## 上游资源 API 的边界

上游 `ResourceFileFetcher` 使用随机生成的 Authorization/token、伪造 clientSession、硬编码
schoolId `100117`。这只能说明源码曾尝试访问相关端点，**不能证明服务端授权或可用性**，因此
原生版未实现学案资源接口，也不把它写入“已验证能力”。

## 待核验事项（不可宣称通过）

- 与当前服务端的真实登录/取题/上传/提交互操作：需用户明确授权、测试账号与测试作业后才可做。
- 业务码、字段命名、加密参数是否与线上现行版本一致：仅静态推断。
- 图片上传是否幂等、失败是否留下孤立文件：未验证；当前不对上传做自动重试。
- 真实网络下的超时、弱网、证书策略与取消行为：仅 MockWebServer 合成测试覆盖。
