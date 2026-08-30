package bili;

import com.coloryr.allmusic.server.core.AllMusic;
import com.coloryr.allmusic.server.core.IMusicApi;
import com.coloryr.allmusic.server.core.music.LyricSave;
import com.coloryr.allmusic.server.core.objs.HttpResObj;
import com.coloryr.allmusic.server.core.objs.SearchMusicObj;
import com.coloryr.allmusic.server.core.objs.music.SearchPageObj;
import com.coloryr.allmusic.server.core.objs.music.SongInfoObj;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class BiliMusicApi implements IMusicApi {

    private static final String BILI_API_BASE = "https://api.bilibili.com";
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // 必填项
    private String cacheDir = null;
    private String serveUrl = null;
    private int quality = 6;               // 默认 6，非必填

    // 可选顶层
    private String ffmpegPath = "ffmpeg";
    private int maxAudioLength = 45;       // 单位：分钟，0 表示不限制
    private int maxCacheSize = 100;        // 单位：MB，0 表示不限制

    // advanced 选项
    private int maxRetry = 3;
    private long retryDelay = 1500L;
    private String userAgent = DEFAULT_USER_AGENT;

    private volatile boolean isUpdate;
    private boolean configValid = false;
    private File configFile;

    // ========== IMusicApi 接口方法 ==========

    @Override
    public void reload(File path) {
        configFile = new File(path, "bili.json");
        configValid = false;

        boolean configBroken = false;
        if (configFile.exists()) {
            try (InputStreamReader reader = new InputStreamReader(
                    Files.newInputStream(configFile.toPath()), StandardCharsets.UTF_8);
                 BufferedReader bf = new BufferedReader(reader)) {
                JsonObject config = AllMusic.gson.fromJson(bf, JsonObject.class);

                // ---- 读取必填项 ----
                String tmpCacheDir = null;
                String tmpServeUrl = null;
                if (config.has("cacheDir")) {
                    String dir = config.get("cacheDir").getAsString();
                    if (dir != null && !dir.trim().isEmpty()) tmpCacheDir = dir.trim();
                }
                if (config.has("serveUrl")) {
                    String url = config.get("serveUrl").getAsString();
                    if (url != null && !url.trim().isEmpty()) tmpServeUrl = normalizeUrl(url.trim());
                }

                // ---- 读取 quality（非必填，缺失或无效则使用默认 6） ----
                int tmpQuality = 6;
                if (config.has("quality")) {
                    try {
                        int q = config.get("quality").getAsInt();
                        if (q >= 0 && q <= 9) {
                            tmpQuality = q;
                        } else {
                            AllMusic.log.data("<light_purple>[BiliAPI]<yellow>quality 值超出 0-9 范围（" + q + "），将使用默认值 6");
                            tmpQuality = 6;
                        }
                    } catch (NumberFormatException e) {
                        AllMusic.log.data("<light_purple>[BiliAPI]<yellow>quality 不是有效整数，将使用默认值 6");
                        tmpQuality = 6;
                    }
                } else {
                    AllMusic.log.data("<light_purple>[BiliAPI]<yellow>未配置 quality，将使用默认值 6");
                }
                quality = tmpQuality;

                // ---- 读取可选顶层 ----
                if (config.has("ffmpegPath")) {
                    String f = config.get("ffmpegPath").getAsString();
                    if (f != null && !f.trim().isEmpty()) ffmpegPath = f.trim();
                }

                // ---- 读取 maxAudioLength ----
                int tmpMaxLen = 45;
                if (config.has("maxAudioLength")) {
                    try {
                        int val = config.get("maxAudioLength").getAsInt();
                        if (val >= 0) {
                            tmpMaxLen = val;
                        } else {
                            tmpMaxLen = 45;
                            AllMusic.log.data("<light_purple>[BiliAPI]<yellow>maxAudioLength 为负数，已回退到 45 分钟");
                        }
                    } catch (NumberFormatException e) {
                        AllMusic.log.data("<light_purple>[BiliAPI]<red>maxAudioLength 不是有效整数，将使用默认 45");
                        tmpMaxLen = 45;
                    }
                } else {
                    tmpMaxLen = 45;
                }
                maxAudioLength = tmpMaxLen;

                // ---- 读取 maxCacheSize ----
                int tmpMaxCache = 100;
                if (config.has("maxCacheSize")) {
                    try {
                        int val = config.get("maxCacheSize").getAsInt();
                        if (val >= 0) {
                            tmpMaxCache = val;
                        } else {
                            tmpMaxCache = 100;
                            AllMusic.log.data("<light_purple>[BiliAPI]<yellow>maxCacheSize 为负数，已回退到 100 MB");
                        }
                    } catch (NumberFormatException e) {
                        AllMusic.log.data("<light_purple>[BiliAPI]<red>maxCacheSize 不是有效整数，将使用默认 100 MB");
                        tmpMaxCache = 100;
                    }
                } else {
                    tmpMaxCache = 100;
                }
                maxCacheSize = tmpMaxCache;

                // ---- 读取 advanced 对象 ----
                int tmpMaxRetry = 3;
                long tmpRetryDelay = 1500L;
                String tmpUserAgent = DEFAULT_USER_AGENT;

                if (config.has("advanced") && config.get("advanced").isJsonObject()) {
                    JsonObject adv = config.getAsJsonObject("advanced");

                    if (adv.has("maxRetry")) {
                        try {
                            int mr = adv.get("maxRetry").getAsInt();
                            if (mr > 0) tmpMaxRetry = mr;
                        } catch (NumberFormatException ignored) {}
                    }
                    if (adv.has("retryDelay")) {
                        try {
                            long rd = adv.get("retryDelay").getAsLong();
                            if (rd > 0) tmpRetryDelay = rd;
                        } catch (NumberFormatException ignored) {}
                    }
                    if (adv.has("userAgent")) {
                        String ua = adv.get("userAgent").getAsString();
                        if (ua != null && !ua.trim().isEmpty()) {
                            tmpUserAgent = ua.trim();
                        }
                    }
                } else {
                    AllMusic.log.data("<light_purple>[BiliAPI]<yellow>配置中无 advanced 对象，使用默认 advanced 参数");
                }

                // ---- 综合检查（仅依赖 cacheDir 和 serveUrl） ----
                if (tmpCacheDir != null && tmpServeUrl != null) {
                    cacheDir = tmpCacheDir;
                    serveUrl = tmpServeUrl;
                    maxRetry = tmpMaxRetry;
                    retryDelay = tmpRetryDelay;
                    userAgent = tmpUserAgent;

                    // 验证缓存目录是否存在
                    Path cachePath = getCachePath();
                    if (Files.exists(cachePath) && Files.isDirectory(cachePath)) {
                        // 检查 ffmpeg 可用性
                        if (checkFfmpeg()) {
                            // 缓存清理（如果启用）
                            if (maxCacheSize > 0) {
                                cleanupCache(cachePath);
                            } else {
                                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>缓存清理已禁用（maxCacheSize=0）");
                            }
                            configValid = true;
                            AllMusic.log.data("<light_purple>[BiliAPI]<yellow>缓存目录存在：" + cachePath.toAbsolutePath());
                            if (maxAudioLength > 0) {
                                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>最大音频时长限制：" + maxAudioLength + " 分钟");
                            } else {
                                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>最大音频时长限制：无限制");
                            }
                            if (maxCacheSize > 0) {
                                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>最大缓存大小：" + maxCacheSize + " MB");
                            } else {
                                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>最大缓存大小：无限制");
                            }
                        } else {
                            AllMusic.log.data("<light_purple>[BiliAPI]<red>ffmpeg 不可用，请检查 ffmpeg 路径是否正确");
                            configValid = false;
                        }
                    } else {
                        AllMusic.log.data("<light_purple>[BiliAPI]<red>缓存目录不存在或不是目录：" + cachePath.toAbsolutePath());
                        AllMusic.log.data("<light_purple>[BiliAPI]<red>请创建该目录或修改 cacheDir 配置");
                        configValid = false;
                    }
                } else {
                    AllMusic.log.data("<light_purple>[BiliAPI]<red>配置缺少必要项（cacheDir 或 serveUrl）");
                    configBroken = true;
                }

            } catch (JsonParseException e) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>配置文件 JSON 格式错误：" + e.toString());
                configBroken = true;
            } catch (Exception e) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>读取配置文件异常：" + e.toString());
                configBroken = true;
            }
        } else {
            configBroken = true;
        }

        // 如果配置文件损坏或不存在，重新生成默认配置
        if (configBroken || !configFile.exists()) {
            if (configFile.exists()) {
                try {
                    File backup = new File(configFile.getParent(), "bili.json.bak");
                    if (backup.exists()) backup.delete();
                    Files.move(configFile.toPath(), backup.toPath());
                    AllMusic.log.data("<light_purple>[BiliAPI]<yellow>已备份损坏的配置文件到：" + backup.getName());
                } catch (IOException e) {
                    AllMusic.log.data("<light_purple>[BiliAPI]<red>备份损坏配置文件失败，将直接覆盖：" + e.toString());
                }
            }

            try {
                File parent = configFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    if (!parent.mkdirs()) {
                        AllMusic.log.data("<light_purple>[BiliAPI]<red>无法创建配置目录：" + parent.getAbsolutePath());
                    }
                }
                JsonObject defaultConfig = new JsonObject();
                defaultConfig.addProperty("cacheDir", "");
                defaultConfig.addProperty("serveUrl", "");
                defaultConfig.addProperty("ffmpegPath", "ffmpeg");
                defaultConfig.addProperty("maxAudioLength", 45);
                defaultConfig.addProperty("maxCacheSize", 100);
                defaultConfig.addProperty("quality", 6);

                JsonObject adv = new JsonObject();
                adv.addProperty("maxRetry", 3);
                adv.addProperty("retryDelay", 1500);
                adv.addProperty("userAgent", DEFAULT_USER_AGENT);
                defaultConfig.add("advanced", adv);

                try (FileWriter writer = new FileWriter(configFile)) {
                    writer.write(AllMusic.gson.toJson(defaultConfig));
                }
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>已重新生成 bili.json，请填写 cacheDir 和 serveUrl 后重载");
            } catch (Exception e) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>重新生成 bili.json 失败：" + e.toString());
            }
            configValid = false;
        }

        if (configValid) {
            AllMusic.log.data("<light_purple>[BiliAPI]<yellow>B站API已加载");
            AllMusic.log.data("<light_purple>[BiliAPI]<yellow>缓存目录：" + getCachePath().toAbsolutePath());
            AllMusic.log.data("<light_purple>[BiliAPI]<yellow>serveUrl：" + serveUrl);
            AllMusic.log.data("<light_purple>[BiliAPI]<yellow>音质参数 (quality)：" + quality);
            if (maxAudioLength > 0) {
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>最大音频时长限制：" + maxAudioLength + " 分钟");
            } else {
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>最大音频时长限制：无限制");
            }
            if (maxCacheSize > 0) {
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>最大缓存大小：" + maxCacheSize + " MB");
            } else {
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>最大缓存大小：无限制");
            }
        } else {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>B站API加载失败：请确保 cacheDir 和 serveUrl 已正确配置，且 ffmpeg 可执行");
        }
    }

    /**
     * 获取缓存目录的 Path 对象，自动处理相对/绝对路径。
     * 前提：cacheDir 不为 null 且非空。
     */
    private Path getCachePath() {
        Path path = Paths.get(cacheDir);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir")).resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    /**
     * 检查 ffmpeg 是否可用
     * @return true 如果 ffmpeg 可执行
     */
    private boolean checkFfmpeg() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>ffmpeg 检查通过：" + ffmpegPath);
                return true;
            } else {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>ffmpeg 执行失败，退出码：" + exitCode);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>ffmpeg 不可用：" + e.toString());
            return false;
        }
    }

    /**
     * 清理缓存目录（合并统计和删除为一次遍历）
     * @param cachePath 缓存目录路径
     */
    private void cleanupCache(Path cachePath) {
        try {
            List<Path> filesToDelete = new ArrayList<>();
            long totalSize = 0;
            try (Stream<Path> walk = Files.walk(cachePath)) {
                Iterator<Path> it = walk.filter(Files::isRegularFile).iterator();
                while (it.hasNext()) {
                    Path p = it.next();
                    long size = Files.size(p);
                    totalSize += size;
                    filesToDelete.add(p);
                }
            }
            long maxSizeBytes = maxCacheSize * 1024L * 1024L;
            if (totalSize > maxSizeBytes) {
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>缓存大小 " + (totalSize / 1024 / 1024) + " MB 超过限制 " + maxCacheSize + " MB，开始清理...");
                int deleted = 0;
                for (Path p : filesToDelete) {
                    try {
                        Files.delete(p);
                        deleted++;
                    } catch (IOException e) {
                        AllMusic.log.data("<light_purple>[BiliAPI]<red>删除文件失败：" + p.getFileName() + " - " + e.toString());
                    }
                }
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>缓存清理完成，共删除 " + deleted + " 个文件");
            } else {
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>缓存大小 " + (totalSize / 1024 / 1024) + " MB，未超过限制 " + maxCacheSize + " MB，无需清理");
            }
        } catch (IOException e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>缓存清理失败：" + e.toString());
        }
    }

    @Override
    public String getId() {
        return "bili";
    }

    @Override
    public boolean isBusy() {
        return isUpdate;
    }

    @Override
    public String getMusicId(String arg) {
        if (arg == null) return null;
        if (arg.contains("video/")) {
            int i = arg.indexOf("video/") + 6;
            int j = arg.indexOf('/', i);
            if (j < 0) j = arg.length();
            int q = arg.indexOf('?', i);
            if (q >= 0 && q < j) j = q;
            return arg.substring(i, j);
        }
        return arg;
    }

    @Override
    public boolean checkId(String id) {
        return id != null && id.startsWith("BV");
    }

    @Override
    public SongInfoObj getMusic(String id, String player, boolean isList) {
        if (!configValid) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>API 未正确配置，拒绝处理请求");
            if (player != null && !player.isEmpty()) {
                Object sender = AllMusic.side.getPlayer(player);
                if (sender != null) {
                    AllMusic.side.sendMessage(sender, "<light_purple>[BiliAPI]<red>B站API未正确配置，请检查 bili.json");
                }
            }
            return null;
        }
        if (!checkId(id)) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>无效BV号：" + id);
            if (player != null && !player.isEmpty()) {
                Object sender = AllMusic.side.getPlayer(player);
                if (sender != null) {
                    AllMusic.side.sendMessage(sender, "<light_purple>[BiliAPI]<red>无效的BV号：" + id);
                }
            }
            return null;
        }

        String url = BILI_API_BASE + "/x/web-interface/view?bvid=" + id;
        HttpResObj res = httpGet(url);
        if (res == null || !res.ok) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>获取视频信息失败：" + id);
            if (player != null && !player.isEmpty()) {
                Object sender = AllMusic.side.getPlayer(player);
                if (sender != null) {
                    AllMusic.side.sendMessage(sender, "<light_purple>[BiliAPI]<red>获取视频信息失败：" + id);
                }
            }
            return null;
        }

        try {
            JsonObject root = AllMusic.gson.fromJson(res.data, JsonObject.class);
            if (root.get("code").getAsInt() != 0) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>B站API返回错误：" + root.get("code").getAsInt());
                if (player != null && !player.isEmpty()) {
                    Object sender = AllMusic.side.getPlayer(player);
                    if (sender != null) {
                        AllMusic.side.sendMessage(sender, "<light_purple>[BiliAPI]<red>B站API返回错误：" + root.get("code").getAsInt());
                    }
                }
                return null;
            }
            JsonObject data = root.getAsJsonObject("data");
            String name = data.get("title").getAsString();
            long duration = data.get("duration").getAsLong(); // 单位：秒

            // 检查时长限制
            if (maxAudioLength > 0 && duration > maxAudioLength * 60L) {
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>视频时长超过限制：" + duration + " 秒（限制 " + maxAudioLength + " 分钟）");
                if (player != null && !player.isEmpty()) {
                    Object sender = AllMusic.side.getPlayer(player);
                    if (sender != null) {
                        AllMusic.side.sendMessage(sender, "<light_purple>[BiliAPI]<red>时长超过 " + maxAudioLength + " 分钟");
                    }
                }
                return null;
            }

            String pic = data.get("pic").getAsString();
            String author = data.has("owner") && !data.get("owner").isJsonNull()
                    ? data.getAsJsonObject("owner").get("name").getAsString()
                    : "";

            return new SongInfoObj(author, name, id, "BV视频", player, "B站", isList,
                    duration * 1000L, pic, false, null, getId());
        } catch (Exception e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>解析视频信息失败：" + e.toString());
            if (player != null && !player.isEmpty()) {
                Object sender = AllMusic.side.getPlayer(player);
                if (sender != null) {
                    AllMusic.side.sendMessage(sender, "<light_purple>[BiliAPI]<red>解析视频信息失败：" + e.toString());
                }
            }
            return null;
        }
    }

    @Override
    public String getPlayUrl(String id) {
        if (!configValid) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>API 未正确配置，拒绝生成播放链接");
            return null;
        }
        if (!checkId(id)) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>无效BV号：" + id);
            return null;
        }

        Path cachePath = getCachePath();
        String mp3Name = id + ".mp3";
        Path mp3File = cachePath.resolve(mp3Name);

        try {
            if (Files.exists(mp3File) && Files.size(mp3File) > 1000) {
                return serveUrl + mp3Name;
            }

            Long cid = fetchCid(id);
            if (cid == null) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>无法获取cid：" + id);
                return null;
            }

            String videoUrl = fetchVideoUrl(id, cid);
            if (videoUrl == null) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>无法获取视频播放地址：" + id);
                return null;
            }

            Path tmpMp4 = cachePath.resolve(id + ".mp4.tmp");
            Path outMp3 = cachePath.resolve(id + ".out.mp3");

            if (!downloadFile(videoUrl, tmpMp4)) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>下载MP4失败：" + id);
                return null;
            }

            if (!convertToMp3(tmpMp4, outMp3)) {
                Files.deleteIfExists(tmpMp4);
                return null;
            }

            Files.deleteIfExists(mp3File);
            Files.move(outMp3, mp3File);
            return serveUrl + mp3Name;

        } catch (IOException e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>处理音频文件时发生IO错误：" + e.toString());
            return null;
        } finally {
            try {
                Files.deleteIfExists(cachePath.resolve(id + ".mp4.tmp"));
                Files.deleteIfExists(cachePath.resolve(id + ".out.mp3"));
            } catch (IOException ignored) {}
        }
    }

    @Override
    public LyricSave getLyric(String id) {
        return new LyricSave();
    }

    @Override
    public SearchPageObj search(String[] args) {
        return search(args, false);
    }

    /**
     * 新增的 search 方法（支持 isList 参数），直接调用原有逻辑。
     * @param args 搜索关键词
     * @param isList 是否用于列表（未使用）
     * @return 搜索结果
     */
    public SearchPageObj search(String[] args, boolean isList) {
        if (!configValid) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>API 未正确配置，拒绝搜索");
            return null;
        }

        List<SearchMusicObj> resultList = new ArrayList<>();
        String keyword = String.join(" ", args).trim();
        if (keyword.isEmpty()) {
            return new SearchPageObj(resultList, 0, getId());
        }

        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        String url = BILI_API_BASE + "/x/web-interface/search/type?search_type=video&keyword=" + encoded;

        for (int attempt = 0; attempt < maxRetry; attempt++) {
            try {
                HttpResObj res = httpGet(url);
                if (res == null || !res.ok) continue;

                JsonObject root = AllMusic.gson.fromJson(res.data, JsonObject.class);
                if (root.get("code").getAsInt() != 0) continue;

                JsonObject data = root.getAsJsonObject("data");
                if (!data.has("result") || data.get("result").isJsonNull()) continue;

                JsonArray items = data.getAsJsonArray("result");
                for (JsonElement el : items) {
                    JsonObject item = el.getAsJsonObject();
                    String bvid = item.get("bvid").getAsString();
                    String title = stripHtml(item.get("title").getAsString());
                    String author = item.get("author").getAsString();
                    String duration = item.get("duration").getAsString();
                    resultList.add(new SearchMusicObj(bvid, title, author, duration));
                }

                int totalPages = Math.max(1, (resultList.size() + 9) / 10);
                return new SearchPageObj(resultList, totalPages, getId());

            } catch (Exception e) {
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>搜索请求失败 (尝试 " + (attempt + 1) + "/" + maxRetry + ")：" + e.toString());
            }

            if (attempt < maxRetry - 1) {
                try {
                    TimeUnit.MILLISECONDS.sleep(retryDelay * (attempt + 1));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        AllMusic.log.data("<light_purple>[BiliAPI]<red>搜索失败，关键词：" + keyword);
        return new SearchPageObj(resultList, 0, getId());
    }

    @Override
    public void setList(String id, Object sender) {
        if (!configValid) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>API 未正确配置，拒绝操作");
            AllMusic.side.sendMessage(sender, "<light_purple>[BiliAPI]<red>B站API未正确配置，请检查 bili.json");
            return;
        }
        AllMusic.log.data("<light_purple>[BiliAPI]<yellow>B站源暂不支持歌单");
        AllMusic.side.sendMessage(sender, "<light_purple>[BiliAPI]<red>B站暂不支持歌单功能");
    }

    @Override
    public void command(Object sender, String name, String[] args) {
        // 无自定义命令
    }

    @Override
    public List<String> tab(Object sender, String name, String[] args) {
        return new ArrayList<>();
    }

    // ========== 私有辅助方法 ==========

    private String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        return url.endsWith("/") ? url : url + "/";
    }

    private Long fetchCid(String bvid) {
        String url = BILI_API_BASE + "/x/web-interface/view?bvid=" + bvid;
        HttpResObj res = httpGet(url);
        if (res == null || !res.ok) return null;
        try {
            JsonObject root = AllMusic.gson.fromJson(res.data, JsonObject.class);
            return root.getAsJsonObject("data").get("cid").getAsLong();
        } catch (Exception e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>获取cid失败：" + e.toString());
            return null;
        }
    }

    private String fetchVideoUrl(String bvid, long cid) {
        String url = BILI_API_BASE + "/x/player/playurl?bvid=" + bvid +
                "&cid=" + cid + "&qn=16&type=mp4&platform=html5";
        HttpResObj res = httpGet(url);
        if (res == null || !res.ok) return null;
        try {
            JsonObject root = AllMusic.gson.fromJson(res.data, JsonObject.class);
            if (root.get("code").getAsInt() != 0) return null;
            JsonArray durl = root.getAsJsonObject("data").getAsJsonArray("durl");
            if (durl != null && durl.size() > 0) {
                return durl.get(0).getAsJsonObject().get("url").getAsString();
            }
        } catch (Exception e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>解析视频播放地址失败：" + e.toString());
        }
        return null;
    }

    private boolean downloadFile(String url, Path target) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);

            int code = conn.getResponseCode();
            if (code != 200 && code != 206) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>下载失败，HTTP " + code + " from " + url);
                return false;
            }

            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return Files.exists(target) && Files.size(target) > 0;
        } catch (Exception e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>下载文件失败：" + e.toString());
            return false;
        }
    }

    private boolean convertToMp3(Path input, Path output) {
        String inputPath = input.toAbsolutePath().toString();
        String outputPath = output.toAbsolutePath().toString();

        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-y", "-i", inputPath,
                "-vn", "-acodec", "libmp3lame", "-q:a", String.valueOf(quality),
                outputPath
        );
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // consume output to avoid blocking
                }
            }
            int exit = process.waitFor();
            if (exit == 0 && Files.exists(output) && Files.size(output) > 1000) {
                return true;
            } else {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>ffmpeg转码失败，退出码：" + exit);
                return false;
            }
        } catch (Exception e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>ffmpeg执行异常：" + e.toString());
            return false;
        }
    }

    private HttpResObj httpGet(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            int code = conn.getResponseCode();
            if (code != 200) {
                return new HttpResObj("", false);
            }

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                return new HttpResObj(sb.toString(), true);
            }
        } catch (Exception e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>HTTP GET 失败：" + e.toString());
            return new HttpResObj("", false);
        }
    }

    private String stripHtml(String input) {
        return input.replaceAll("<[^>]+>", "");
    }
}
