# 数据备份与恢复

设置 › 隐私与数据 › 备份与恢复。把对话、记忆、个人技能、知识补充、自动化配置、站点登记和设置(含服务商 Key / 登录凭据)导出成一个加密文件;换机或重装后导回来。

相关代码:

- `core/src/main/kotlin/com/wangzan101/mika/core/backup/`:格式、口令派生、AES-GCM 分块加解密、zip 安全校验、合并决策、报告模型。
- `app/src/main/java/com/wangzan101/mika/backup/`:收集数据、解密/回写密钥、快照、逐分类合并、SAF 读写。
- `app/src/main/java/com/wangzan101/mika/ui/BackupScreen.kt`:设置页与导出/导入界面。

## 文件格式(format 1)

```text
Mika-backup-<yyyyMMdd-HHmm>.zip
├── manifest.json     明文,导入前先读;含格式版本、App 版本、导出时间、分类、计数、KDF 参数
└── payload.bin       AES-256-GCM 密文,明文是一个内层 zip;导出时边收集边加密,明文内层包不落盘
```

`manifest.json` 里的 `payload.sha256` 是密文的 SHA-256;导入时先核对,再做解密。AAD 由 `format`、`appVersion`、`exportedAt` 和排序后的 `sections` 拼成,manifest 里这几项被改动会导致解密失败。

口令经 PBKDF2-HMAC-SHA256(`BackupFormat.PBKDF2`;16 字节 salt,默认 21 万次)派生 256 位 AES 密钥;每个包一个随机 12 字节 IV。迭代次数写在 manifest 里,导入时限制在 10 万–200 万次之间。当前只有口令模式(没有设备绑定模式):Keystore 密钥卸载即失效,搬它没有意义。

内层 zip 的条目名只允许 `data/**` 和根上的 `secrets.json`;导入时拒绝绝对路径、`..`、反斜杠、重复条目,并限制条目数、单条/总解压大小和压缩比。

`counts` 统一按条目数记录(设置文件、服务商 Key、对话、记忆、画像、技能、知识、定时任务/窗口、路线、站点、工作区文件/字节)。导出时对规范化后的 counts 做 HMAC-SHA256,写进 `manifest.countsMac`;导入时用包内实际条目数逐项对账,不一致或 MAC 不对会写进报告。预览页的数字来自 manifest,恢复时会核对。

## 分类

默认包含:

- `settings`:设置 JSON、`memory_settings.json`、`sandbox-settings.json`、SharedPreferences 白名单。
- `secrets`:服务商 Key/订阅登录、MCP token、中继凭据、网络代理口令、设备访问凭据、豆包语音凭据。导出时用各自的 Keystore 别名解密,放进加密包;导入时用目标设备的 Keystore 重新加密。
- `conversations`:对话 `.json`、`.title`、`.pin`、`.page`。`current`(当前打开的对话指针)不导出,跨设备恢复旧指针没有意义。
- `memory`:解密后的记忆条目与用户画像 JSON。
- `skills`:个人技能、待确认草稿、归档、改动日志。
- `knowledge`:App 知识补充和用户手写的 App/站点笔记。
- `automation`:定时任务定义与时段账本、路线、审批、App 访问清单。
- `web`:站点登记表。

可选:`workspace`(`sandbox/work` 下的附件、用户屏幕、任务写的文件)。不包含:根文件系统 `sandbox/rootfs`、运行记录 `runs/`、`runs-v3.json`、`children-v3.json`、`outbox-v3.json`、`usage-v1.json`、剪贴板留底 `mika-clip-backup`。恢复旧的运行状态会和当前任务队列打架。

## 恢复规则

恢复是**逐项合并**,不是整目录替换:

- 对话按 id;目标没有就新增,导入的 mtime 更新才替换。
- 记忆按 id、`updatedAt`;画像按 id、`atMs`。
- 个人技能按名字、`meta.json` mtime;草稿/归档只补缺失。
- 服务商和模型配置按 id,导入的胜出;`uses`/`roles` 只补目标没有的用途。服务商 Key 如果目标已有且值不同,保留目标现有的,避免旧备份盖掉后来轮换的 Key;报告里会把保留数记在 `secrets` 一栏。
- 定时任务按 id,同 id 保留现有;导入的一次性任务已过期就跳过;重复任务从当前时间重排。时段账本(`windows`)一并导入,避免当天已经跑过的任务恢复后又跑一次。
- 路线按 id,只补缺失。
- 审批取「更常询问」的一边;App 访问的禁止清单取并集,分享白名单取交集。
- 站点登记按 host 合并,`loggedIn` 一律写 false(Cookie 不随包走)。

写入前把 `settings/`、`conversations/`、`memory/`、`skills/`、`knowledge/`、`personal/`、`routes/`、相关 JSON 和 SharedPreferences 白名单复制到 `files/pre-restore/<时间>/`。任何一步失败就用快照回滚;成功后保留最近两份,可以在报告页「撤销这次恢复」。

恢复只改文件,不重建进程里的单例(`ProviderStore`、`MemoryRuntime`、`McpRuntime` 等)。所以完成后必须从最近任务里划掉 Mika 再打开;界面会明确提示。

## 限制与未做

- 不做云同步、自动备份、WebDAV。
- 不导出 Cookie,恢复后网站需要重新登录。
- 不导出 rootfs 和运行记录。
- 工作区可能很大;导出/导入需要约等于数据量 2–3 倍的临时空间,空间不足会明确报「空间不够」而不是「包坏了」。
- 导出/导入都可以在进度页取消;取消后清理临时文件,不写入正式数据。
- 导入时如果有任务在跑会被拒绝。导出允许在任务进行中做:每个对话 `.json` 先解析再打包,坏文件跳过并写进报告,不会把半截 JSON 带进包里。
