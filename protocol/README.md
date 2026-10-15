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
