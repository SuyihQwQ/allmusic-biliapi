# AllMusic BiliAPI

为 AllMusic 提供 B 站搜索、点歌和播放支持。默认通过 DASH 获取音频，也可配置使用原有 MP4 流程；两种方式都会生成并缓存 MP3。

## 要求

- Java 21
- 兼容的 AllMusic 服务端
- 服务端可执行 ffmpeg

## 构建与安装

项目包含编译用的 AllMusic API：`libs/server-4.2.0-all.jar`。构建产物可在兼容的 AllMusic 服务端运行，不要求运行时宿主 API 版本与编译版本相同。

```bash
./gradlew clean build
```

Windows 使用 `gradlew.bat clean build`。将 `build/libs/bili-api.jar` 放入：

```text
<server>/plugins/allmusic/api/
```

启动一次服务器生成配置，编辑 `plugins/allmusic/api/bili.json`，至少设置客户端可访问的 `serveUrl`。详细选项见 [配置说明](docs/config.md)。

在 `plugins/allmusic/config.json` 中将默认音乐源设为：

```json
{
  "defaultApi": "bili"
}
```

重启后即可使用 `/music searchapi bili 歌名` 搜索。首次播放会下载并转码，后续播放使用缓存。

## 音频访问

内置 HTTP 服务默认监听 `0.0.0.0:8090`，音频地址格式为：

```text
http://主机:8090/BVxxxx.mp3
```

`serveUrl` 填写客户端实际访问的根地址并以 `/` 结尾。服务只提供缓存根目录中的 MP3，不提供反向代理或 HTTPS；使用 Cloudflare Tunnel 时，将 Tunnel 转发到插件监听端口，并将 `serveUrl` 设为 Tunnel 的公网根地址。

## 配置提示

- 缓存上限默认 `512 MB`，每 `60` 分钟清理一次；启动和 `/music reload` 时也会清理。
- `advanced.streamMode` 默认 `dash`，可设为 `mp4`。
- 转码超时为 `transcodeMinTimeoutSeconds + 视频时长秒数 × transcodeDurationMultiplier`，默认 `60 + 时长 × 0.75` 秒。
- `advanced.configVersion` 由插件维护。版本缺失或不匹配时会补齐缺少的选项，不覆盖已有设置。

## 使用限制

- 不提供歌词和歌单。
- 首次播放需要下载和转码，速度取决于网络和 CPU。
- B 站接口可能限流或无法访问。

## 相关项目

- [AllMusic](https://github.com/Coloryr/AllMusic)
- [netapi](https://github.com/Coloryr/netapi)
