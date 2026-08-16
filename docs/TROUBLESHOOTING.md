# 踩坑记录（Troubleshooting）

本文档记录开发这个项目时遇到的所有坑，供后来者参考。按"症状 → 根因 → 解决"组织。

---

## 1. 网易云源（netapi）在云服务器上完全不可用

**症状**：AllMusic 加载 netapi 成功，但 `music test` 返回"测试解析失败"。

**根因**：网易云 **weapi 接口对数据中心 IP 风控**——返回 HTTP 200 但内容为空。这不是代码 bug，用 pycryptodome 精确实现官方 weapi 算法从服务器发请求也是空。公开 API（`/api/search/get`）正常，但 weapi 必空。

**验证**：`curl -X POST https://music.163.com/weapi/search/get -d 'params=x&encSecKey=y'` → 200 但 `size_download=0`。

**结论**：网易云系方案在腾讯云等云服务器上绕不过（出口 IP 就这台机器）。

---

## 2. B 站 DASH 纯音频接口同样被 IP 限流

**症状**：想拿 B 站纯音频（DASH），但 `playurl?fnval=16&fnver=0` 返回的 JSON 里**没有 dash 字段**。

**根因**：B 站对数据中心 IP 的 DASH 接口限流。试过 `fnval=16/80/4048`、`qn=64`、带 `buvid3` cookie、完整 UA/Referer、长间隔重试，全部拿不到 dash，只稳定返回 durl 混合 MP4。

**注意**：偶尔能成功一次（碰运气），但不可靠，别依赖。

---

## 3. AllMusic 客户端解不了 B 站混合 MP4

**症状**：点歌后 `[AllMusic Client]不支持这样的文件播放`。

**根因**：B 站 durl MP4 是**视频+音频混合**（`stsd` 里有 `avc1` + `mp4a`），AllMusic 客户端（按纯音频 M4A 设计）解这种文件：
- 格式判断只认 `00 00 00 1c` 的 M4A box（28 字节），B 站 ftyp box 是 32+ 字节 → 被误判为 OGG
- 即使强制用 M4ADecoder，视频/音频帧交错导致 AAC 解码错位（`invalid huffman codebook: 12`）

**解决**：服务端 ffmpeg 转成纯 MP3。

---

## 4. 客户端下载音乐流被截断（received: 7542）

**症状**：`ConnectionClosedException: Premature end of Content-Length delimited message body (expected: 8809098; received: 7542)`。

**根因**：B 站 CDN 对**无 Referer** 的请求只返回前 8KB 就断连。AllMusic 客户端 `AllMusicPlayer.connect()` 下载时只设了 Range 头，没带 Referer/UA。

**解决**：给 `connect()` 加 `User-Agent` + `Referer: https://www.bilibili.com/`。

**注意**：本地测试下载完整（curl/python/独立 Java 都行），只有 Minecraft 进程内断——别被"本地能下"迷惑，是客户端缺请求头。

---

## 5. 客户端下载被 Clash 代理截断（同 7542 但加了 Referer 仍断）

**症状**：加 Referer 后还是断在 7542 字节。用同 jar 独立 Java 测试下载完整，只有 Minecraft 进程内断。

**根因**：**系统环境变量 `HTTP_PROXY=http://127.0.0.1:7897`**（Clash）。Apache HttpClient 5 默认读系统代理，AllMusic 客户端下载时走了 Clash，而 Clash 对大文件流（>1MB）连接中断。

**解决**：`AllMusicCore.init()` 里给 HTTP client 禁用系统代理：
```java
.setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
```
注意类路径是 `org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner`（有 `impl.`）。

**诊断技巧**：写独立 Java 测试（同 jar、同 relocated 包）跑一遍下载，如果本地完整但游戏内断 → 环境/代理问题。

---

## 6. skip 大跳重建连接导致断流

**症状**：M4A 解码器播放时 `AllMusicPlayer.skip()` 触发 `connect()` 重建连接，`expected: 4821635; received: 286070`。

**根因**：`skip()` 大跳（>2048 字节）时 `local += n; connect()` 用 Range 重定位重建连接，B 站 CDN 对重建的 Range 连接中断。

**解决**：`skip()` 改成**纯内存读取丢弃**（4KB 缓冲循环），不重建连接。慢一点但稳定。

---

## 7. seek 重连导致播放失败

**症状**：点歌播放时 `setLocal()` → `streamClose()+connect()` 重连，`expected: 2694403; received: 947`。

**根因**：`Mp3Decoder.set(time)` 触发 seek，`AllMusicPlayer.setLocal()` 关闭旧连接重建，重建后连接被截断。

**解决**：`setLocal()` 改成**纯内存跳过**（`content.read` 丢弃），不重建连接。

---

## 8. ffmpeg 转码失败：无法推断输出格式

**症状**：ffmpeg 返回 `Unable to choose an output format for 'xxx.mp3.tmp'`。

**根因**：ffmpeg 按**扩展名**推断输出格式，`.tmp` 结尾它不认识。

**解决**：输出文件用 `.out.mp3`（正确扩展名），转完再 `renameTo` 到正式名。

---

## 9. `/music test` 卡主线程触发 Paper watchdog

**症状**：RCON 跑 `music test` 后服务器 `The server has not responded for 10 seconds!`。

**根因**：AllMusic 的 `CommandTest` 在**服务器主线程**同步调 `getPlayUrl`，转码（下载+ffmpeg）要 12 秒，主线程阻塞触发 watchdog。

**解决**：真实播放走 `allMusic_play` 异步线程，不卡主线程。测试用独立 Java 程序（见下）。

**独立测试方法**：
```bash
java -cp "AllMusic_Server.jar:bili-api.jar:gson.jar" -e '...'
```
（编译一个小 main 直接调 `api.getPlayUrl("BV1xxx")`）

---

## 10. B 站搜索间歇性失败（无法搜索歌曲）

**症状**：连续搜索时部分返回"无法搜索歌曲"。

**根因**：B 站搜索 API 有**频率限制**，连续请求会返回空。

**解决**：`search()` 内置 4 次重试 + 1.5s 间隔。

---

## 11. 腾讯云安全组 / 端口放行

- 新增端口要**两层都放行**：腾讯云安全组 + 服务器 UFW
- **NAT 环回不支持**：服务器内用公网 IP 访问自己端口超时，排障用 `127.0.0.1`
- 最省事：**不新增端口**，用 Caddy `handle_path /music/*` 反代到本地端口，复用已放行的 443
