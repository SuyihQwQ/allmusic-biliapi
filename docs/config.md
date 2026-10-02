# 配置说明

配置文件：`plugins/allmusic/api/bili.json`。修改后执行 `/music reload` 或重启服务器。

```json
{
  "cacheDir": "music_cache",
  "serveUrl": "http://your-public-host:8090/",
  "cleanupInterval": 60,
  "ffmpegPath": "ffmpeg",
  "maxAudioLength": 45,
  "maxCacheSize": 512,
  "quality": 4,
  "advanced": {
    "configVersion": 4,
    "debug": false,
    "http": {
      "enabled": true,
      "port": 8090,
      "listenAddresses": ["0.0.0.0"]
    },
    "preserveMinutes": 5,
    "streamMode": "dash",
    "transcodeMinTimeoutSeconds": 60,
    "transcodeDurationMultiplier": 0.75,
    "maxRetry": 3,
    "retryDelay": 1500
  }
}
```

## 基本配置

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `serveUrl` | 空 | 客户端访问音频的根地址，必须以 `/` 结尾。未设置时 API 不启用。 |
| `cacheDir` | `music_cache` | MP3 缓存目录，可使用绝对路径。 |
| `ffmpegPath` | `ffmpeg` | ffmpeg 可执行文件或绝对路径。 |
| `maxAudioLength` | `45` | 单曲时长上限，单位分钟；`0` 表示不限。 |
| `maxCacheSize` | `512` | 缓存上限，单位 MB；`0` 表示不限。 |
| `quality` | `4` | ffmpeg MP3 质量参数，范围 `0–9`；数值越小音质越高。 |
| `cleanupInterval` | `60` | 缓存清理间隔，单位分钟，必须大于 `0`。启动和 reload 时也会立即清理。 |

## `advanced` 配置

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `configVersion` | `4` | 配置结构版本，由插件维护，不要手动修改。缺失或不匹配时会补齐缺少项，并保留已有值。 |
| `debug` | `false` | 是否输出详细调试日志。 |
| `advanced.http.enabled` | `true` | 是否启动内置 HTTP 文件服务。设为 `false` 时需自行提供 `cacheDir` 中的 MP3 文件，并让 `serveUrl` 指向外部服务。 |
| `advanced.http.port` | `8090` | 内置 HTTP 服务端口，范围 `1–65535`；无效值回退为 `8090`。 |
| `advanced.http.listenAddresses` | `["0.0.0.0"]` | HTTP 服务监听地址列表。所有地址共用 `advanced.http.port`。 |
| `preserveMinutes` | `5` | 清理时保留最近修改的 MP3 文件时间，单位分钟。 |
| `streamMode` | `dash` | `dash` 下载 DASH 音频轨道；`mp4` 使用原有混合 MP4 流程。无效值回退为 `dash`。 |
| `transcodeMinTimeoutSeconds` | `60` | 转码超时公式中的固定秒数，必须大于 `0`。 |
| `transcodeDurationMultiplier` | `0.75` | 超时公式中的视频时长倍数，必须为有限正数。 |
| `maxRetry` | `3` | B 站搜索最大尝试次数，必须大于 `0`。 |
| `retryDelay` | `1500` | 搜索重试间隔，单位毫秒，必须大于 `0`。 |
| `userAgent` | Chrome UA | 请求 B 站时使用的 User-Agent。 |

转码超时为：

```text
transcodeMinTimeoutSeconds + 视频时长秒数 × transcodeDurationMultiplier
```

超时后会终止 ffmpeg 并删除临时输入、未完成的 MP3。此计时只覆盖转码，不含网络请求和下载。

## HTTP 服务

音频 URL 格式为 `http://主机:端口/BVxxxx.mp3`。`serveUrl` 应填写客户端可访问的根地址，例如：

```text
https://music.example.com/
```

默认由插件内置 HTTP 服务提供缓存文件，可将 Cloudflare Tunnel 等外部隧道转发到插件监听端口。若 `advanced.http.enabled` 设为 `false`，插件不会监听端口；需自行将 `cacheDir` 中生成的 MP3 发布到 `serveUrl` 对应地址。插件自身不提供 HTTPS、反向代理或目录浏览。

仅支持根路径 MP3：

- 非 MP3 或带额外路径的请求：`403`
- 不存在的 MP3：`404`
- 不支持的 HTTP 方法：`405`（允许 `GET`、`HEAD`）

## 常见问题

- **API 未启用**：检查 `serveUrl`、ffmpeg、缓存目录权限和端口占用。
- **访问 403**：使用 `/BVxxxx.mp3` 格式，不要添加路径前缀。
- **搜索失败**：检查服务端到 B 站的网络；可开启 `advanced.debug` 查看请求日志。
- **Windows 路径**：建议使用正斜杠，例如 `D:/Minecraft/music_cache`、`C:/ffmpeg/bin/ffmpeg.exe`。
