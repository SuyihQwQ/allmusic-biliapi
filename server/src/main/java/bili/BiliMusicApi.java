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
    private static final String DEFAULT_CACHE_DIR = "music_cache";

    // 必填项
    private String cacheDir = DEFAULT_CACHE_DIR;   // 有默认值
    private String serveUrl = null;                // 无默认值，必须配置
    private int quality = 6;

    // 可选顶层
    private String ffmpegPath = "ffmpeg";
    private int maxAudioLength = 45;
    private int maxCacheSize = 100;

    // advanced 选项
    private int maxRetry = 3;
    private long retryDelay = 1500L;
    private String userAgent = DEFAULT_USER_AGENT;

    private volatile boolean isUpdate;
    private boolean configValid = false;
    private File configFile;
    private Path effectiveCachePath;   // 实际使用的缓存目录（绝对路径，仅当可用时设置）

    // ========== 工具方法：清洗路径字符串 ==========

    private static String cleanPath(String input) {
        if (input == null) return null;
        return input.replaceAll("[\\p{C}\\u200B\\u200C\\u200D\\u200E\\u200F\\uFEFF]", "").trim();
    }

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
                    if (dir != null) {
                        dir = cleanPath(dir);
                        if (!dir.isEmpty()) tmpCacheDir = dir;
                    }
                }
                if (config.has("serveUrl")) {
                    String url = config.get("serveUrl").getAsString();
                    if (url != null) {
                        url = cleanPath(url);
                        if (!url.isEmpty()) tmpServeUrl = normalizeUrl(url);
                    }
                }

                // ---- 读取 quality ----
                int tmpQuality = 6;
                if (config.has("quality")) {
                    try {
                        int q = config.get("quality").getAsInt();
                        if (q >= 0 && q <= 9) {
                            tmpQuality = q;
                        } else {
                            AllMusic.log.data("<light_purple>[BiliAPI]<yellow>quality 值超出 0-9 范围（" + q + "），将使用默认值 6");
                        }
                    } catch (NumberFormatException e) {
                        AllMusic.log.data("<light_purple>[BiliAPI]<yellow>quality 不是有效整数，将使用默认值 6");
                    }
                } else {
                    AllMusic.log.data("<light_purple>[BiliAPI]<yellow>未配置 quality，将使用默认值 6");
                }
                quality = tmpQuality;

                // ---- 读取可选顶层 ----
                if (config.has("ffmpegPath")) {
                    String f = config.get("ffmpegPath").getAsString();
                    if (f != null) {
                        f = cleanPath(f);
                        if (!f.isEmpty()) ffmpegPath = f;
                    }
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
                        if (ua != null) {
                            ua = cleanPath(ua);
                            if (!ua.isEmpty()) tmpUserAgent = ua;
                        }
                    }
                } else {
                    AllMusic.log.data("<light_purple>[BiliAPI]<yellow>配置中无 advanced 对象，使用默认 advanced 参数");
                }

                // ---- 保存配置到成员变量 ----
                if (tmpCacheDir != null) {
                    cacheDir = tmpCacheDir;
                } else {
                    cacheDir = DEFAULT_CACHE_DIR;
                }
                if (tmpServeUrl != null) {
                    serveUrl = tmpServeUrl;
                }
                maxRetry = tmpMaxRetry;
                retryDelay = tmpRetryDelay;
                userAgent = tmpUserAgent;

                // ---- 检查 ffmpeg ----
                boolean ffmpegOk = checkFfmpeg();

                // ---- 检查并准备缓存目录（强制要求可用） ----
                boolean dirOk = ensureCacheDirectory();

                // ---- 综合判断：serveUrl、ffmpeg、缓存目录三者都 OK ----
                if (serveUrl != null && ffmpegOk && dirOk) {
                    if (maxCacheSize > 0 && effectiveCachePath != null) {
                        cleanupCache(effectiveCachePath);
                    }
                    configValid = true;
                    AllMusic.log.data("<light_purple>[BiliAPI]<yellow>B站API已加载");
                    AllMusic.log.data("<light_purple>[BiliAPI]<yellow>缓存目录：" + effectiveCachePath.toAbsolutePath());
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
                    configValid = false;
                    if (serveUrl == null) {
                        AllMusic.log.data("<light_purple>[BiliAPI]<red>serveUrl 未配置，API 禁用");
                    }
                    if (!ffmpegOk) {
                        AllMusic.log.data("<light_purple>[BiliAPI]<red>ffmpeg 不可用，API 禁用");
                    }
                    if (!dirOk) {
                        AllMusic.log.data("<light_purple>[BiliAPI]<red>缓存目录不可用，API 禁用");
                    }
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
                defaultConfig.addProperty("cacheDir", DEFAULT_CACHE_DIR);
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
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>已重新生成 bili.json，请填写 serveUrl 后重载");
            } catch (Exception e) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>重新生成 bili.json 失败：" + e.toString());
            }
            configValid = false;
        }

        if (!configValid && !configBroken) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>B站API加载失败：请检查日志中的错误原因");
        }
    }

    /**
     * 确保缓存目录可用（存在、是目录、可写）。
     * @return true 如果目录可用
     */
    private boolean ensureCacheDirectory() {
        // 1. 确定最终使用的路径
        String finalDir = (cacheDir != null && !cacheDir.isEmpty()) ? cacheDir : DEFAULT_CACHE_DIR;
        Path target = resolveCachePath(finalDir);

        // 2. 检查目录是否存在
        if (Files.exists(target)) {
            // 存在，检查是否为目录
            if (!Files.isDirectory(target)) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>缓存路径不是目录：" + target.toAbsolutePath());
                return false;
            }
            // 检查是否可写
            if (!Files.isWritable(target)) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>缓存目录没有写入权限：" + target.toAbsolutePath());
                return false;
            }
            AllMusic.log.data("<light_purple>[BiliAPI]<yellow>缓存目录已存在且可写：" + target.toAbsolutePath());
            effectiveCachePath = target;
            return true;
        } else {
            // 目录不存在，尝试创建
            try {
                Files.createDirectories(target);
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>创建缓存目录：" + target.toAbsolutePath());
                // 创建后再次检查写入权限（实际上创建成功即有权限，但保险起见）
                if (!Files.isWritable(target)) {
                    AllMusic.log.data("<light_purple>[BiliAPI]<red>创建后目录仍不可写（权限问题），请检查：" + target.toAbsolutePath());
                    return false;
                }
                effectiveCachePath = target;
                return true;
            } catch (IOException e) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>创建缓存目录失败：" + target.toAbsolutePath() + " - " + e.toString());
                // 尝试回退到默认值（如果当前不是默认值）
                if (!finalDir.equals(DEFAULT_CACHE_DIR)) {
                    AllMusic.log.data("<light_purple>[BiliAPI]<yellow>尝试回退到默认目录：" + DEFAULT_CACHE_DIR);
                    Path fallback = resolveCachePath(DEFAULT_CACHE_DIR);
                    try {
                        if (!Files.exists(fallback)) {
                            Files.createDirectories(fallback);
                            AllMusic.log.data("<light_purple>[BiliAPI]<yellow>创建默认缓存目录：" + fallback.toAbsolutePath());
                        }
                        if (Files.isDirectory(fallback) && Files.isWritable(fallback)) {
                            effectiveCachePath = fallback;
                            cacheDir = DEFAULT_CACHE_DIR;
                            AllMusic.log.data("<light_purple>[BiliAPI]<yellow>成功回退到默认缓存目录：" + fallback.toAbsolutePath());
                            return true;
                        } else {
                            AllMusic.log.data("<light_purple>[BiliAPI]<red>默认缓存目录也不可用：" + fallback.toAbsolutePath() + "（可能不可写或不是目录）");
                            return false;
                        }
                    } catch (IOException ex) {
                        AllMusic.log.data("<light_purple>[BiliAPI]<red>回退创建默认缓存目录失败：" + ex.toString());
                        return false;
                    }
                } else {
                    return false;
                }
            }
        }
    }

    /**
     * 根据给定的路径字符串解析为绝对路径（相对于 user.dir）。
     */
    private Path resolveCachePath(String dir) {
        String cleaned = cleanPath(dir);
        if (cleaned == null || cleaned.isEmpty()) cleaned = DEFAULT_CACHE_DIR;
        Path path = Paths.get(cleaned);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir")).resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    /**
     * 获取当前有效的缓存目录路径（仅当已初始化且可用时调用）。
     */
    private Path getCachePath() {
        if (effectiveCachePath == null) {
            // 安全防护：尝试重新初始化（但正常不会走到这里）
            if (!ensureCacheDirectory()) {
                // 若仍失败，使用默认并记录错误（但后续操作会失败）
                AllMusic.log.data("<light_purple>[BiliAPI]<red>缓存目录未初始化，将使用临时根目录，可能导致错误");
                effectiveCachePath = Paths.get(System.getProperty("user.dir"));
            }
        }
        return effectiveCachePath;
    }

    /**
     * 检查 ffmpeg 是否可用（默认 "ffmpeg" 会尝试系统 PATH）
     */
    private boolean checkFfmpeg() {
        String cleaned = cleanPath(ffmpegPath);
        if (cleaned == null || cleaned.isEmpty()) cleaned = "ffmpeg";
        ffmpegPath = cleaned;

        Path ffmpegFile = Paths.get(ffmpegPath);
        if (ffmpegFile.isAbsolute() && !Files.exists(ffmpegFile)) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>ffmpeg 文件不存在：" + ffmpegPath);
            return trySystemFfmpeg();
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-version");
            pb.redirectErrorStream(true);
            AllMusic.log.data("<light_purple>[BiliAPI]<yellow>执行 ffmpeg 检查命令：" + String.join(" ", pb.command()));

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();

            AllMusic.log.data("<light_purple>[BiliAPI]<yellow>ffmpeg 输出：\n" + output.toString());

            if (exitCode == 0) {
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>ffmpeg 检查通过：" + ffmpegPath);
                return true;
            } else {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>ffmpeg 执行失败，退出码：" + exitCode);
                return trySystemFfmpeg();
            }
        } catch (IOException | InterruptedException e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>ffmpeg 执行异常：" + e.toString());
            return trySystemFfmpeg();
        }
    }

    private boolean trySystemFfmpeg() {
        AllMusic.log.data("<light_purple>[BiliAPI]<yellow>尝试使用系统 PATH 中的 ffmpeg...");
        try {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>系统 ffmpeg 可用，将使用它（请考虑在配置中明确指定路径）");
                this.ffmpegPath = "ffmpeg";
                return true;
            } else {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>系统 ffmpeg 也不可用，退出码：" + exitCode);
                return false;
            }
        } catch (Exception e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>系统 ffmpeg 调用失败：" + e.toString());
            return false;
        }
    }

    /**
     * 清理缓存目录（仅当 maxCacheSize > 0 且目录可用）
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

    // ========== IMusicApi 接口方法 ==========

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
            long duration = data.get("duration").getAsLong();

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
                    // consume output
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
