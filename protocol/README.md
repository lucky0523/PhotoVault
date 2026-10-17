# 服务器二维码协议 v1

本目录是「服务器二维码」载荷协议在仓库内的权威说明。Web 端「服务器二维码」对话框显示的二维码由服务端生成，Android 登录页扫码解析，三端代码注释统一引用：

> 服务器二维码协议 v1，见 protocol/README.md 与 protocol/server-qr-v1.json

- `server-qr-v1.json`：共享测试向量（137 条）。服务端 pytest（`server/tests/test_server_address.py`）与 Android JVM 单测直接读取同一份文件；Web 端在验证阶段用临时脚本对照。**不要重新排版或修改其中的值**，改协议必须同时更新三端实现和向量。
- 唯一编码器：`server/app/core/server_address.py::encode_connect_payload`。其他任何地方都不得拼接 `photovault://connect` 载荷。
- 唯一解析器：Android `ui/login/ServerQrCode.kt::ServerQrCode.parse`。

## 1. 语法

```
payload   = prefix query
prefix    = "photovault://connect?"          ; 编码方必须输出全小写；解析方 ASCII 大小写不敏感
query     = param *( "&" param )
param     = key [ "=" value ]               ; key 不做百分号解码，按字符精确匹配，区分大小写
key       = *( 除 "&" "=" "#" 以外的字符 )    ; 编码方只输出 v / public / lan；解析方遇到空 key 时整段忽略
value     = *( 除 "&" "#" 以外的字符 )        ; 只有 public、lan 的 value 做严格百分号解码；v 的 value 不解码
```

| key | 出现次数 | 值 | 语义 |
|---|---|---|---|
| `v` | 恰好 1 次 | 整串匹配 `[1-9][0-9]{0,8}` | 协议版本，本版为 `1` |
| `public` | 0 或 1 次 | 百分号编码后的规范地址 | 公网地址 |
| `lan` | 0 或 1 次 | 百分号编码后的规范地址 | 局域网地址 |
| 其他（含空 key） | 任意 | 任意，不解码、不检查 | v1 解析方整段忽略（同版本内向前兼容） |

约束：

- `public` 和 `lan` 解码后至少有一个非空；两者都存在时，规范形式不能相同。
- 地址必须带显式 `http://` 或 `https://`，形如 `scheme://host[:port]`，不含路径、查询、片段和 userinfo。编码方只输出规范形式（协议小写、主机小写、默认端口省略、IPv6 按 RFC 5952 压缩）。
- 不允许出现 `#` 片段。
- 只有一个地址时仍然输出 v1 载荷，不退回普通 URL。

### 编码方规则（只有服务端 `encode_connect_payload` 实现）

- 参数顺序固定为 `v`、`public`、`lan`；缺省的地址整项省略，不输出 `public=`。
- 值用 `urllib.parse.quote(addr, safe="")` 编码：只保留 RFC 3986 unreserved 字符（`A–Z a–z 0–9 - . _ ~`），其余字符按 UTF-8 字节编码成大写 `%XX`。地址里的 `:`、`/`、`[`、`]` 都会被编码。
- 输入必须已是规范形式且两者不同，结果长度不超过 563，否则抛 `ValueError`（编程错误）。

### 解析方规则（Android `ServerQrCode.parse`）

- 只对 `public`、`lan` 的值做严格百分号解码：`%` 后必须紧跟两位十六进制数字（大小写都接受），解码后的字节必须是合法 UTF-8（不做 U+FFFD 替换），否则整串无效。`+` 按字面字符处理，不当作空格。
- `v` 的值不做百分号解码，按原始字符整串匹配 `[1-9][0-9]{0,8}` 之后才转整数。`v=%31`、`v=1\n` 都无效。
- 其他 key（包括空 key，例如 `=foo`）连同其值整段忽略，即使值里有非法的 `%` 序列。
- 版本判定先于地址解码。
- 解码后为空的值视为缺省（例如 `public=&lan=…`）。首尾空白在地址前缀检查之前去掉。
- 未编码的 `:` 和 `/` 也能被接受（宽进），编码方从不这样输出。

### 版本规则

- `v` 缺失、出现多次、不能整串匹配 `[1-9][0-9]{0,8}`（如 `abc`、`1.0`、`01`、`0`、`-1`、`1\n`、空串、只有 key 没有 `=`）时，判为内容无效。
- `v` 合法但不等于 1 时，判为「版本过新」（App 提示升级）。版本判定先于地址校验，例如 `v=2&public=%%%` 也是版本过新。
- 只有破坏兼容时才把 `v` 加 1；新增可选参数不需要升版本。

## 2. 示例载荷

两个地址都有：
```
photovault://connect?v=1&public=https%3A%2F%2Fphotos.example.com&lan=http%3A%2F%2F192.168.1.10%3A8000
```
只有公网地址：
```
photovault://connect?v=1&public=https%3A%2F%2Fphotos.example.com
```
只有局域网地址：
```
photovault://connect?v=1&lan=http%3A%2F%2F192.168.1.10%3A8000
```
带端口的公网地址，加上 IPv6 局域网地址：
```
photovault://connect?v=1&public=https%3A%2F%2Fphotos.example.com%3A8443&lan=http%3A%2F%2F%5Bfd00%3A%3A1%5D%3A8000
```

## 3. 长度上限与纠错等级

- 规范地址最长 255 个字符（规范化之后再检查一次）。
- 每个地址编码后最多变长 8 个字符（`://` → `%3A%2F%2F` 多 6 个，端口前的 `:` → `%3A` 多 2 个）。
- 固定开销 `photovault://connect?v=1`（24）+ `&public=`（8）+ `&lan=`（5）= 37。
- 载荷上限 = 37 + 2 × 263 = **563 字节**。
- 纠错等级 **M**（Web 端显式传 `errorCorrectionLevel: 'M'`），尺寸 `width: 240, margin: 2`。典型载荷（约 101 字节）为 QR 版本 6；上限 563 字节为版本 19。

## 4. 主机范围判定

输入规范地址，输出 `public`、`lan`、`loopback` 三类之一：

- `loopback`：IPv4 `127.0.0.0/8`、`0.0.0.0/8`；IPv6 `::1`、`::`；主机名 `localhost` 与 `*.localhost`。
- `lan`：IPv4 `10/8`、`172.16/12`、`192.168/16`、`169.254/16`；IPv6 `fe80::/10`、`fc00::/7`；以 `.local` 结尾的主机名。
- `public`：其他所有情况，包括 `100.64/10`、`172.32.0.1`、`[fec0::1]`、单标签主机名（如 `local`、`nas`）、`nas.local.example.com`。

使用方：

- Android 扫到旧版单 URL 二维码：`lan` 或 `loopback` 写进局域网框，`public` 写进公网框，另一个框清空。
- 服务端用浏览器 origin 回退时：只有 `public` 能作为公网候选，只有 `lan` 能作为局域网候选，`loopback` 两边都不用。

## 5. 已知不兼容：旧版 Android 扫新二维码

| 旧版本 | 表现 | 是否发出请求 | 是否崩溃 |
|---|---|---|---|
| 双地址版（提交 `2e82d38` 及之后、本协议之前） | 整串写进公网框；点「登录」或「测试连接」时公网框下显示「仅支持 http:// 或 https://」 | 否，校验在发请求之前 | 否 |
| 单地址版（`8340269` 之前） | 整串写进服务器地址框；点登录或测试时 Retrofit `baseUrl()` 抛「must end in /」被捕获，显示连接失败类文案，失败地址不会被保存 | 否，异常发生在 Retrofit 构建阶段 | 否 |

规避办法：先通过 Web 端「下载手机 App」对话框升级 App。不为旧客户端额外生成旧格式二维码。iOS 目前没有扫码功能，将来实现时必须遵守本协议和共享向量。

---

# 备份查重接口

服务端实现：`server/app/api/backup.py`（请求/响应模型即下表），查重逻辑在 `server/app/services/deduplication_service.py`，上传完成的去向在 `server/app/services/upload_service.py`。

## 1. 适用范围

- `POST /api/v1/backup/check`、`POST /api/v1/backup/init`、`POST /api/v1/backup/complete`，都需要 `Authorization: Bearer <access_token>`。
- 查重只在当前用户自己的记录里进行，只比较文件的 SHA-256 与服务端 `file_records` 中的记录；`file_path` 和 `device_name` 不参与查重。
- **服务端保留的历史版本不参与查重。** 同一个文件被替换下来的旧内容会作为历史版本保留 30 天，但 check/init 从不读取它们：哈希只与某个历史版本相同的文件，按 `not_found` 处理，客户端应正常上传。

## 2. `POST /api/v1/backup/check`

请求：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `file_hash` | string | 是 | 文件内容的 SHA-256（小写十六进制） |
| `file_path` | string | 是 | 文件在手机上的原始路径（Android 为 MediaStore URI） |
| `device_name` | string | 是 | 设备名 |

响应：

| 字段 | 类型 | 说明 |
|---|---|---|
| `is_duplicate` | boolean | 为 `true` 时客户端跳过上传 |
| `file_id` | integer 或 null | 仅 `status=active` 时有值，是命中的记录 id |
| `status` | string | 取值见第 3 节 |
| `expires_at` | string 或 null | 仅 `status=trashed` 时有值：回收站到期时间（ISO 8601） |

## 3. status 取值

与现有实现一致，不新增取值。按优先级从上到下取第一个命中的：

| status | is_duplicate | 含义与客户端行为 |
|---|---|---|
| `active` | `true` | 已备份，跳过上传 |
| `trashed` | `false` | 同哈希的记录在回收站里，`expires_at` 是回收站到期时间；可以重新上传 |
| `purged` | `false` | 同哈希的记录已被彻底删除；可以重新上传 |
| `not_found` | `false` | 服务端没有这份内容（包括只存在于历史版本的情况），需要上传 |

优先级：`active` → `trashed`/`purged` → `not_found`。

## 4. `POST /api/v1/backup/init`

请求：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `file_hash` | string | 是 | SHA-256 |
| `file_name` | string | 是 | 文件名，不能包含路径分隔符 |
| `file_size` | integer | 是 | 字节数 |
| `file_path` | string | 是 | 手机上的原始路径（服务端保存为记录的 `original_path`） |
| `device_name` | string | 是 | 设备名 |
| `source_folder` | string | 是 | 来源文件夹（如 `DCIM/Camera`） |
| `storage_policy` | object | 是 | `use_custom_path`（boolean，默认 `false`）、`custom_path`（string 或 null，默认 null）、`use_year_month_layer`（boolean，默认 `false`） |
| `exif_time` | string 或 null | 否 | 拍摄时间（ISO 8601） |
| `file_modified_time` | string 或 null | 否 | MediaStore `DATE_MODIFIED`（毫秒时间戳），没有拍摄时间时作为目录日期的后备 |
| `mime_type` | string 或 null | 否 | MIME 类型 |

响应：

| 字段 | 类型 | 说明 |
|---|---|---|
| `session_id` | string | 上传会话 id |
| `total_chunks` | integer | 分块数 |
| `chunk_size` | integer | 分块大小（字节） |
| `is_duplicate` | boolean | 默认 `false` |
| `file_id` | integer 或 null | 命中 active 时的记录 id |

命中 `active` 时返回 `is_duplicate=true, session_id="", total_chunks=0, chunk_size=0, file_id=<id>`，客户端必须跳过上传；其余情况（包括哈希只存在于历史版本）都创建会话。错误：400 路径非法，507 磁盘空间不足，500 目标目录无法创建。

## 5. `POST /api/v1/backup/complete`

请求：`session_id`（string，必填）。

响应：

| 字段 | 类型 | 说明 |
|---|---|---|
| `success` | boolean | |
| `file_id` | integer 或 null | 结果对应的记录 id |
| `stored_path` | string | 服务端保存路径，默认 `""` |
| `replaced` | boolean | 新增字段，默认 `false`；为 `true` 表示原地替换了已有记录的内容 |

替换规则：上传的文件与服务端某条当前记录「同设备（`device_name` 相同）、同路径（目标路径和文件名相同）、同 `original_path`、哈希不同」时，服务端原地替换这条记录的当前内容，`file_id` 不变、不生成 `_1` 文件，`replaced=true`；被替换下来的内容作为历史版本保留 30 天。把文件还原成某个旧内容（哈希等于它的历史版本）也是这样处理。不同源（另一台设备，或同设备另一个 `original_path`）按普通新文件处理，与以前相同：同目录同名时生成 `原名_1`。

失败时的 HTTP 状态码：

| 状态码 | 含义 | 客户端处理 |
|---|---|---|
| 409 | 服务端暂时无法替换：同一路径有进行中的操作或待恢复的记录、文件锁忙、维护脚本在运行 | 会话和已上传的分块都保留，稍后重新调用 `/backup/complete` 即可 |
| 500 | 服务端 I/O 或数据库错误 | 同样可以稍后重试 |
| 404 | 会话不存在、不属于当前用户或已过期 | 重新 init |
| 422 | 合并后的完整性校验失败（会话已被清理） | 重新 init 并上传 |
| 400 | 其他错误（如分块未收齐、分块文件缺失） | 补传分块后重试 |

## 6. 兼容说明

- 客户端遇到不认识的 `status` 时应当继续调用 `/backup/init`，以 `/backup/init` 返回的 `is_duplicate` 为准。
- `replaced` 是新增字段，旧客户端忽略它即可（Android 用 Gson，未知字段不报错）。旧客户端按 check → init → 分块 → complete 的顺序调用时，得到的结果与新客户端完全相同。
