# BiliMusicApi 配置说明

配置文件位置：

```text
<server>/plugins/allmusic/api/bili.json
```

构建产物不绑定特定的 AllMusic 服务端版本，可放入任意兼容的 AllMusic 服务端运行。项目 `libs/` 中的宿主 API JAR 仅用于编译，运行时由目标 AllMusic 服务端提供实际宿主环境。

修改配置后执行 `/music reload`；也可以重启 Paper。配置文件不存在或 JSON 格式错误时，API 会备份损坏文件并生成默认配置。升级旧版本时，如果检测到缺少新版配置项，API 会自动补齐并写回 `bili.json`，不会覆盖已有配置值。

## 推荐配置

```json
{
  "cacheDir": "music_cache",
  "serveUrl": "http://your-public-ip:8090/",
  "port": 8090,
  "cleanupInterval": 60,
  "ffmpegPath": "ffmpeg",
  "maxAudioLength": 45,
  "maxCacheSize": 512,
  "quality": 6,
  "advanced": {
    "configVersion": 2,
    "debug": false,
    "listenAddresses": ["0.0.0.0"],
    "preserveMinutes": 5,
    "maxRetry": 3,
    "retryDelay": 1500,
    "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
  }
}
```

## 配置项

### 必填项

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `serveUrl` | 字符串 | 无 | 客户端访问音频文件的地址，必须以 `/` 结尾。例如 `http://your-public-ip:8090/`。为空时 API 不会启用。项目不提供反向代理或 HTTPS。 |

`advanced.configVersion` 是由插件自动维护的配置结构版本，不应手动修改。字段缺失、格式错误或版本不匹配时，插件会检查默认配置中的所有字段，将缺失项补齐并写回配置文件，同时保留用户已有值。

### 常用选项

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `cacheDir` | 字符串 | `music_cache` | MP3 缓存目录。相对路径以服务端工作目录为基准；目录不存在时会自动创建。 |
| `port` | 整数 | `8090` | 插件内置 HTTP 音乐文件服务监听端口，监听地址为 `0.0.0.0`。端口必须在 `1` 到 `65535` 之间；无效值回退为 `8090`。 |
| `cleanupInterval` | 整数 | `60` | 缓存清理周期，单位为分钟。必须为正数；空值、`0` 或负数回退为 `60`。 |
| `ffmpegPath` | 字符串 | `ffmpeg` | ffmpeg 可执行文件路径。可填写绝对路径；配置路径不可用时会尝试使用系统 PATH 中的 `ffmpeg`。 |
| `maxAudioLength` | 整数 | `45` | 最大视频时长，单位为分钟。设为 `0` 表示不限制；负数回退为 `45`。 |
| `maxCacheSize` | 整数 | `512` | 缓存目录上限，单位为 MB。设为 `0` 表示不限制；负数回退为 `512`。后台清理任务按 `cleanupInterval` 执行。 |
| `quality` | 整数 | `6` | ffmpeg 的 `-q:a` 参数，范围 `0` 到 `9`。数值越小音质越高、文件越大；超出范围回退为 `6`。 |

### `advanced` 高级选项

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `configVersion` | 整数 | `2` | 配置结构版本，由插件自动维护。缺失、非数字或不等于当前版本时会触发完整配置检查，不应手动修改。 |
| `debug` | 布尔值 | `false` | 输出详细调试日志。排查搜索、HTTP、下载、缓存或转码问题时临时开启，正常运行建议关闭。 |
| `listenAddresses` | 字符串数组 | `["0.0.0.0"]` | 内置 HTTP 服务监听的地址列表，所有地址使用同一个 `port`。可以填写多个地址，例如 `["127.0.0.1", "192.168.1.10"]`；为空或未填写时回退为 `["0.0.0.0"]`。 |
| `preserveMinutes` | 整数 | `5` | 缓存清理时保留最近修改的 MP3 文件，单位为分钟。必须为非负数；空值或负数回退为 `5`。 |
| `maxRetry` | 整数 | `3` | B 站搜索请求的最大尝试次数。当前配置为正数时生效；非正数回退为 `3`。 |
| `retryDelay` | 整数 | `1500` | 重试间隔，单位为毫秒。当前配置为正数时生效；非正数回退为 `1500`。 |
| `userAgent` | 字符串 | Chrome UA | 请求 B 站 API 和视频地址时使用的 User-Agent。一般无需修改。 |

## 启用条件

API 只有在以下条件全部满足时才会启用：

1. `serveUrl` 已配置且非空。
2. ffmpeg 可以执行，且 `ffmpeg -version` 返回成功。
3. `cacheDir` 是目录并且可写；目录不存在时 API 会尝试创建。
4. `port` 未被其他程序占用，插件可以启动内置 HTTP 文件服务。

如果同时配置 `0.0.0.0`（或 `::`）和其他具体地址，操作系统可能因为地址重复绑定而启动失败，通常只选择一种监听方式即可。

插件内置 HTTP 文件服务只提供缓存目录根目录中的 MP3 文件，不提供目录列表，也不会允许访问缓存目录之外的路径。访问格式必须是 `http://地址:端口/BVxxxx.mp3`，不支持额外的路径前缀。项目不提供反向代理或 HTTPS，客户端必须直接访问插件监听端口。

Windows 用户建议为 `cacheDir` 和 `ffmpegPath` 使用绝对路径，例如：

```json
{
  "cacheDir": "D:/Minecraft/music_cache",
  "port": 8090,
  "ffmpegPath": "C:/ffmpeg/bin/ffmpeg.exe"
}
```

也可以使用双反斜杠：

```json
{
  "cacheDir": "D:\\Minecraft\\music_cache",
  "ffmpegPath": "C:\\ffmpeg\\bin\\ffmpeg.exe"
}
```

## 调试日志

开启：

```json
{
  "advanced": {
    "debug": true
  }
}
```

调试日志使用统一标记：

```text
[BiliAPI][DEBUG] 搜索完成：关键词=晴天，结果数=10
[BiliAPI][DEBUG] 命中缓存：BVxxxxxxxx.mp3
[BiliAPI][DEBUG] 音频生成完成：/path/to/music_cache/BVxxxxxxxx.mp3
```

调试信息包含请求状态、搜索结果数量、缓存命中、下载结果和 ffmpeg 检查结果。不会输出完整的视频下载 URL。问题排查完成后建议改回 `false` 并重新 reload。

## 常见问题

### API 未加载

检查日志中的具体原因：

- `serveUrl 未配置`：填写外部音频地址，并确认地址以 `/` 结尾。
- `ffmpeg 不可用`：安装 ffmpeg，或配置 `ffmpegPath`。
- `缓存目录不可用`：检查路径、父目录权限以及服务进程的写权限。

### 播放首次较慢

首次点歌需要请求 B 站视频、下载 MP4 并使用 ffmpeg 转换为 MP3。转换完成后会写入 `cacheDir`，再次播放同一 BV 号会直接使用缓存。

### 缓存没有自动清理

缓存清理会在 API 加载和 `/music reload` 后按 `cleanupInterval` 持续后台执行。确认 `maxCacheSize` 大于 `0`；如果为 `0`，表示不限制缓存大小：

```text
/music reload
```

### 搜索失败或被限流

可临时提高 `maxRetry` 或 `retryDelay`，并打开 `advanced.debug` 查看请求过程。不要设置过短的重试间隔，以免加重 B 站限流。
