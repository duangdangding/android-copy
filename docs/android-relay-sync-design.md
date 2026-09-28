# ClipDitto 安卓端 中继服务器同步 设计文档

> 状态：草案（待评审）。
> 上游依据：PC 端《中继服务器 + 局域网双通道同步 设计文档》（copy-pc/docs/relay-sync-design.md，
> M1–M5 已全部实现）。本文档定义安卓端（ClipDitto）接入同一中继服务器的客户端方案与设置页面。
> 原则与 PC 端一致：**不改动现有 LAN 同步行为**，Relay 作为独立通道新增，双通道双发、统一入库去重。

## 1. 背景与目标

现状：ClipDitto 的同步完全走局域网（`sync/` 模块：NSD 发现 + 直连 HTTP 8765），
手机离开局域网（切蜂窝、外出）后即为孤岛。PC 端已实现中继服务器（WSS 长连接 +
分组转发 + 离线暂存 + 端到端加密），本文档让安卓端接入同一张中继网络：

- 与 PC 端**同协议、同分组、同密钥体系**：手机、电脑连同一台中继服务器、填同一个
  分组 ID / 分组密钥，即可互相同步，跨网段、跨运营商可用。
- LAN 与 Relay **同时存在、各走各的**：本机剪贴板变化同时往两条通道发；收到的条目
  统一走现有入库去重逻辑，先到先存，晚到自动判重。LAN 通道代码零改动。
- 首期范围对齐 PC 端 M5：文本同步 + 鉴权握手 + 离线补拉 + 端到端加密 + 图片可选中继。
  文件互传（FileShare）不走中继，与 PC 端一致。

## 2. 总体架构

```
                 ┌── LAN 通道（sync/ 现状）── NSD + 直连 HTTP ──> 局域网设备
 本机剪贴板事件 ──┤
                 └── Relay 通道（新增 sync/relay/）── WSS ──> 中继服务器 ──> 远端设备（PC/手机）
                                                                  │
                                                            离线暂存队列（服务器侧，TTL 7 天）
```

- **双发，不做优先级选择**（与 PC 端 §2 一致）：文本条目几 KB，双发开销可忽略；
  图片默认仅 LAN，可选开关放行（见 §8.3）。
- 两条通道互为冗余：服务器宕机不影响局域网；离开局域网不影响中继。

## 3. 复用现有机制（已核对本仓库代码）

### 3.1 去重：内容查重，天然幂等

`LanSyncManager.importClip()`（LanSyncManager.kt:498）的入库路径：

- 文字：`repo.findDuplicateText(content)` 命中 → 只 `repo.touch()` 顶时间戳；
  未命中 → `repo.insertRemote()`。
- 媒体：下载后 `repo.findDuplicateMedia(type, file)` 查重，重复则丢弃文件。

Relay 收到的条目**复用同一条入库路径**（把 `importClip` 的核心抽成可复用方法，
或由 RelaySyncManager 调用同样的 repo 查重/插入组合），双通道到达的重复条目自动兜住，
无需新增任何去重逻辑。

### 3.2 环回防护：按来源设备 ID

现有逻辑：`remoteDeviceId == settings.deviceId` → 拒收（LanSyncManager.kt:505）。
Relay 侧同样遵守，且设备身份**直接复用 `LanSettings.deviceId`**（首次启动生成后固定不变），
与 PC 端「两通道同一 device_id 即合并键」的语义对齐——PC 端设备卡合并显示
「局域网+云端」时，手机端就是那同一张卡。

### 3.3 铁律（与 PC 端 §3.3 相同）

**只有本机剪贴板变化才触发外发；入库的远程条目绝不再次外发。**
Relay 发送队列的唯一条目来源是 ClipboardService 的本地新条目回调，
`importClip` / Relay 收条路径绝不触碰发送队列，否则双通道 + 传递同步会放大成回环风暴。

## 4. 新增模块

全部放在 `sync/relay/` 子包下，与现有 LAN 模块平级、互不引用对方内部类：

```
app/src/main/java/com/clipditto/app/sync/relay/
├── RelaySettings.kt      # 配置持久化（SharedPreferences，独立文件 "relay_sync"）
├── RelayCrypto.kt        # HKDF-SHA256 密钥派生 + AES-256-GCM 加解密 + HMAC 握手签名
├── RelayClient.kt        # OkHttp WebSocket：连接/握手/心跳/重连/收发消息
└── RelaySyncManager.kt   # 总控：开关驱动连接、push 队列、收条入库、状态流
```

其他改动点：

| 位置 | 改动 | 说明 |
|---|---|---|
| `app/build.gradle` | 新增依赖 `com.squareup.okhttp3:okhttp:4.12.0` | WebSocket 客户端；现有网络层是裸 HttpURLConnection，没有 WS 能力，这是唯一新增依赖 |
| `ClipboardService.kt` | 本地新条目回调处追加一行投递 | 除现有 LAN 逻辑外，同时 `RelaySyncManager.enqueueLocal(item)`；**仅此一处接入，其余不动** |
| `LanSyncManager.kt` | 抽出入库共用方法 | `importClip` 的文字入库分支抽为 internal 方法供 Relay 复用（或 RelaySyncManager 直接组合 `repo.findDuplicateText` / `touch` / `insertRemote`，二选一，实现时定） |
| `ui/RelaySettingsActivity.kt` + `res/layout/activity_relay_settings.xml` | 新增「云端中继」设置页 | 见 §7 |
| `ui/DevicesActivity.kt` | 设备卡合并展示 | 云端设备并入设备列表（§9）；设置入口移至 `LanSyncSettingsActivity` |
| `ui/LanSyncSettingsActivity.kt`（新增） | 同步设置页 | 局域网同步全部开关/配置 + 「云端中继」入口（实时状态） |
| `AndroidManifest.xml` | 注册新 Activity | 无需新权限（INTERNET 已有） |

## 5. 协议实现（对齐 PC 端 §4.3 / §4.5）

### 5.1 连接与握手

1. OkHttp `newWebSocket()` 连接 `wss://服务器地址`（设置页填写，支持 `ws://` 供局域网调试）。
2. 收到服务器首条 `{"op":"challenge","nonce":"..."}` 后，**5 秒内**回复 hello：

```json
{ "op": "hello", "deviceId": "<LanSettings.deviceId>", "name": "<deviceName>",
  "group": "<分组ID>", "ts": 1758...,
  "auth": "HMAC-SHA256(接入密钥, nonce+deviceId+ts)" }
```

   - HMAC 用 `javax.crypto.Mac`（"HmacSHA256"），输出小写 hex；拼接串无分隔符，
     与 PC 端逐字节一致（实现时对照 PC 端 `relay.rs` 的拼接顺序联调）。
   - 鉴权失败/超时 → 服务器断开，客户端按「鉴权错误」状态展示，**不自动重连**
     （密钥错了重连无意义，还触发服务器防爆破封禁），等用户改配置。
3. 握手成功后立即补拉：`{"op":"pull","sinceSeq":<relay_last_seq>}`。

### 5.2 消息收发

| 方向 | op | 处理 |
|---|---|---|
| 发 | `push` | clip 字段名与 PC 端 `/clips` 口径一致：`type` / `text` / `timestamp` / `remoteDeviceId` / `remoteId`（`fileName` / `fileSize` 图片中继时使用）。发送前若配了分组密钥则先加密（§6） |
| 发 | `ping` | 应用层心跳，30 秒一次；配合 OkHttp 自带的 WS ping（`pingInterval`）双保险 |
| 收 | `clip` | 推进内存 `last_seq` → 解密（若 `enc:1`）→ 环回防护 → 入库去重（§3） |
| 收 | `acked` | push 确认，推进 `last_seq` |
| 收 | `peers` | 同组在线设备列表，更新状态流供设置页展示 |
| 收 | `error` | `auth_failed` 等：置错误状态、停止自动重连 |

### 5.3 游标持久化

- `relay_last_seq`（Long）存 SharedPreferences；内存中实时推进，**断开连接时统一落盘**，
  另加 30 秒节流定期落盘兜底（手机进程可能被系统杀死，丢游标的代价只是下次多补拉
  一批 + 哈希判重，无脏数据——PC 端 §9 已确认该语义）。
- 补拉与实时推送撞车的重复，由入库内容查重兜住（§3.1）。

### 5.4 重连策略

- 指数退避：1s → 2s → 4s … 封顶 60s，连接成功后重置。
- 监听网络变化（复用 `LanSyncManager.watchNetwork()` 同款 `ConnectivityManager` 回调）：
  网络恢复时立即触发一次重连，不等退避计时。
- 仅「开关开 + 配置完整（地址/接入密钥/分组 ID 非空）+ 非鉴权错误」时才保持重连循环。
- 心跳超时（两次 ping 未收到任何服务器消息）主动断开走重连。
- 服务生命周期：Relay 连接挂在 `ClipboardService` 的前台服务进程内（该服务本来就常驻），
  不新增独立 Service；ClipboardService 停止时 Relay 随之断开。

## 6. 端到端加密（对齐 PC 端 §5）

- 配置项 `relay.group_key`（可空）：留空 = 明文模式，与同组未加密设备兼容；
  同组所有设备填相同密钥即进入加密模式。
- 密钥派生：`HKDF-SHA256(分组密钥, salt="lscopy-relay-e2e", info=分组ID)` → AES-256 密钥。
  HKDF 用 `javax.crypto.Mac` 手工实现 extract+expand（Android 无内置 HKDF，约 40 行，
  常量 salt/info 与 PC 端逐字节一致）。
- 加密粒度：仅 clip 文本内容。`AES-256-GCM`（`Cipher.getInstance("AES/GCM/NoPadding")`，
  minSdk 26 原生支持），每条随机 12 字节 nonce，密文 `base64(nonce ‖ ciphertext)`
  放入 `clip.data`，并带 `enc: 1`。
- **AAD 绑定**：`remoteDeviceId` / `remoteId` / `timestamp` / `type` 按固定顺序拼接作 AAD
  （拼接格式与 PC 端逐字节一致，实现时对照联调），篡改元数据 → 解密失败 → 跳过该条。
- 接收侧：本机未配分组密钥或解密失败 → 跳过，不入库、不报错弹窗（记日志）。
- 发送侧加密失败 → **静默丢弃该条，不降级发明文**。

## 7. 设置页（「云端中继」页面）

### 7.1 入口与布局

- 入口：「设备同步」页（DevicesActivity）右上角「设置」→ 同步设置页
  （LanSyncSettingsActivity）里的「云端中继」条目，实时显示连接状态摘要
  （未启用 / 已连接 / 连接中 / 鉴权失败），点击进 `RelaySettingsActivity`。
- 布局 `activity_relay_settings.xml`：沿用现有设置页风格（Material 开关 + 输入框 +
  状态文本），分区如下：

```
┌─────────────────────────────────────┐
│ 云端中继                              │
│                                     │
│ [开关] 启用云端中继同步               │
│                                     │
│ ── 服务器 ──                         │
│ 服务器地址  [wss://example.com:8780] │
│ 接入密钥    [____________________]   │  ← 密码样式输入（inputType=textPassword）
│                                     │
│ ── 分组 ──                           │
│ 分组 ID     [____________________]   │
│ 分组密钥    [____________________]   │  ← 密码样式；下方一行小字说明：
│            留空为明文模式；同组设备须填相同密钥  │
│                                     │
│ ── 选项 ──                           │
│ [开关] 图片也走云端中继（≤4MB，更耗流量）│  ← 默认关，对齐 PC 端 relay.sync_image
│                                     │
│ ── 状态 ──                           │
│ 连接状态：已连接 · 延迟 123ms         │
│ 同组在线设备：                        │
│   · 小火箭-4821（本机）               │
│   · 拯救者 Y700 — 云端               │
│ 上次同步游标：seq 457                │
│ [重新连接]  [清除游标并全量补拉]       │
└─────────────────────────────────────┘
```

### 7.2 交互逻辑

- **启用开关**：打开时校验四项必填（地址/接入密钥/分组 ID 非空、地址以 `ws://` 或
  `wss://` 开头），不通过则弹 Toast 并弹回开关；通过后触发连接。
- 任一配置项修改后：若已连接，自动断开重连（重启生效，与 PC 端「配置改动重启生效」一致）。
- **连接状态**实时刷新：订阅 `RelaySyncManager.state: StateFlow`（
  `Disconnected / Connecting / Connected(rttMs) / AuthFailed / Error(msg)`），
  在线设备列表来自 `peers` 消息。
- **清除游标并全量补拉**：`relay_last_seq = 0` + 重连，用于换服务器/数据错乱自救；
  弹确认框说明「可能拉回大量历史条目，重复内容会自动去重」。
- 密钥输入框右侧提供「显示/隐藏」眼睛图标（Material `TextInputLayout` 自带
  `endIconMode="password_toggle"`）。

### 7.3 配置存储（RelaySettings）

SharedPreferences 文件 `relay_sync`，与 `lan_sync` 互不干扰：

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | Bool | false | 总开关 |
| `server_url` | String | "" | wss:// 地址 |
| `access_key` | String | "" | 接入密钥（服务器级） |
| `group_id` | String | "" | 分组 ID |
| `group_key` | String | "" | 分组密钥（E2E 根密钥，可空=明文） |
| `sync_image` | Bool | false | 图片经中继（≤4MB PNG，base64 内嵌） |
| `last_seq` | Long | 0 | 服务器条目游标 |

## 8. 关键行为明细

### 8.1 发送时机（唯一入口）

`ClipboardService` 本地新条目入库成功后（现有 LAN 投递点旁边）：
`RelaySyncManager.enqueueLocal(item)`。队列内做：

1. 开关/连接状态检查：未连接则**直接丢弃**（不做本地离线队列——手机剪贴板高频，
   攒离线队列意义小且增加复杂度；服务器侧暂存已覆盖「对方离线」场景）。
2. 环回防护：`item.remoteDeviceId != null`（本身同步来的）→ 不投递（§3.3 铁律的
   双保险，调用点本来就只传本地条目）。
3. 类型过滤：文本直接发；图片仅当 `sync_image` 开且 ≤4MB 时 base64 内嵌发送；
   视频/文件/超限图片 → 仅 LAN，不发。
4. 加密（若配分组密钥）→ 组 `push` 消息发出。

### 8.2 接收入库

`clip` 消息 → 解密 → 构造与 LAN `/clips` 条目相同的 JsonObject 形状 →
走 §3.1 的共用入库路径（含环回防护、文字查重 touch、时间锚定）。

图片经中继到达时：base64 解码落临时文件 → `repo.findDuplicateMedia` 查重 →
落盘位置沿用现有同步逻辑（SAF 自定义目录或 Download/ClipDitto），
同样受 `syncMaxSizeMb` 上限约束。

### 8.3 大文件策略（与 PC 端 §7.3 一致）

- `sync_image` 关（默认）：仅文本走中继。
- 开：≤4MB 图片 base64 内嵌 clip（加密同样生效）；超限与文件类仍仅 LAN。
- 手机用蜂窝网络时该开关对流量敏感，设置页开关文案已注明「更耗流量」。

### 8.4 与 LAN 的到达时序

同一条目 LAN 先到（几十 ms）、Relay 后到（几百 ms）或反向，均按内容查重，
晚到的 touch 时间戳，用户无感知（PC 端 §7.1 场景的手机侧镜像）。

## 9. 设备卡合并展示（已实现）

与 PC 端 M5 同一合并语义：两通道设备身份是同一个 `LanSettings.deviceId`，它就是合并键。

- `LanDevice` 新增 `@Transient var viaRelay`（展示用，不进配对持久化）；
  `DevicesActivity.mergedDevices()` 把 `RelaySyncManager.peers` 并入 LAN 设备列表：
  同一 `deviceId` 合并为一张卡，局域网看不到但中继在线的设备追加一张「云端」卡。
- 徽标（LAN 优先语义，与 PC 端一致）：双通道显示「局域网+云端」，仅云端显示「云端」，
  纯局域网不标注；云端在线即视为在线（绿点）。
- **仅云端可达的设备不提供局域网操作**：未配对的纯云端卡点击只弹说明 +
  「删除该设备同步来的记录」；批量同步自动跳过仅云端设备并 Toast 提示
  （中继是实时推送，无需也无法手动拉取）。已配对但暂时仅云端可达的设备，
  本地管理操作（删记录 / 移除设备）仍可用。
- **发送不受显示影响**：维持双通道双发，徽标只是展示，不引入按显示通道单发的状态机。

## 10. 实施步骤

| 步 | 内容 | 产出 |
|---|---|---|
| 1 | 加 OkHttp 依赖；`RelaySettings` + `RelayCrypto`（HKDF/AES-GCM/HMAC，含与 PC 端对齐的单元级自测 main 函数或临时 Activity 日志验证） | 加密握手可与服务器互通 |
| 2 | `RelayClient`：WS 连接、challenge/hello 握手、ping 心跳、指数退避重连 | 能连上服务器、鉴权通过 |
| 3 | `RelaySyncManager`：push 队列接入 ClipboardService；clip 收条复用入库去重；pull/last_seq 补拉 | 文本双通道同步打通 |
| 4 | `RelaySettingsActivity` + 设备页入口卡片 + 状态流 | 页面可用，状态实时 |
| 5 | 图片中继（可选开关）+ 联调 PC 端互发 | 对齐 M5 |
| 6 | 真机验证：蜂窝/Wi-Fi 切换重连、息屏保活、与 PC 双向同步、加密/明文两组互测 | 发版 |

验证方式照旧：无单元测试目录，`./gradlew assembleDebug` 编译 + 真机运行。
每台测试机连一台真实中继服务器（PC 端已有 Docker 镜像可一键起）。

## 11. 风险与注意点

- **后台限制**：Relay 长连接依赖 ClipboardService 前台服务存活；国产 ROM 杀后台时
  与现有悬浮球/监听同生共死，不额外做保活（现有文档已引导用户开自启/忽略电池优化）。
- **协议逐字节对齐**：HMAC 拼接串、HKDF salt/info、AAD 拼接顺序、base64 编码
  （是否带换行、URL-safe 与否）必须与 PC 端 `relay.rs` 完全一致，联调第一步就是
  「同一条测试文本两端加解密互认」。实现时把 PC 端这几个常量原样抄过来。
- **耗电**：WS 心跳 30s + OkHttp pingInterval，移动网络下实测待机耗电，
  异常时提供「仅 Wi-Fi 下连接」开关（列为可选增强，首期不做）。
- **明文模式**：分组密钥留空时明文经服务器，设置页文案必须写清楚，避免误解。
- **不碰的部分**：LAN 发现/配对/`/clips` 服务、互传文件、悬浮球手势逻辑、
  Android 10+ 剪贴板读取方案，全部原样。

## 12. 开放问题

- 是否需要「仅 Wi-Fi 下连接中继」开关（流量敏感用户）？
- 是否需要发送失败本地暂存（短期断网时攒几条，重连后补发）？首期丢弃，按需再加。
- 分组密钥的扫码/配对码分发（PC 端也列为后续迭代），届时两端一起对齐。
