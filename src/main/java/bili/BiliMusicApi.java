package bili;

import bili.config.BiliConfig;
import bili.http.BiliHttpServer;
import bili.stream.BiliDashStreamProvider;
import bili.stream.BiliMp4StreamProvider;
import bili.stream.BiliStreamProvider;
import com.coloryr.allmusic.libs.org.apache.hc.client5.http.classic.methods.HttpGet;
import com.coloryr.allmusic.libs.org.apache.hc.core5.http.HttpEntity;
import com.coloryr.allmusic.libs.org.apache.hc.core5.http.io.entity.EntityUtils;
import com.coloryr.allmusic.server.core.AllMusic;
import com.coloryr.allmusic.server.core.IMusicApi;
import com.coloryr.allmusic.server.core.music.LyricSave;
import com.coloryr.allmusic.server.core.music.MusicHttpClient;
import com.coloryr.allmusic.server.core.objs.HttpResObj;
import com.coloryr.allmusic.server.core.objs.SearchMusicObj;
import com.coloryr.allmusic.server.core.objs.music.SearchPageObj;
import com.coloryr.allmusic.server.core.objs.music.SongInfoObj;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

public class BiliMusicApi implements IMusicApi {

    private static final String BILI_API_BASE = "https://api.bilibili.com";
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10L;
    private static final int PLAY_LOCK_COUNT = 64;
    private record VideoMetadata(long cid, long durationSeconds) {}

    private BiliConfig config = new BiliConfig();
    private BiliStreamProvider streamProvider;

    private final Object lifecycleLock = new Object();
    private final Set<ActivePlayTask> activePlayTasks = new HashSet<>();
    private final ThreadLocal<ActivePlayTask> currentPlayTask = new ThreadLocal<>();
    private final ReentrantLock[] playLocks = createPlayLocks();
    private volatile boolean configValid = false;
    private boolean reloading;
    private Path effectiveCachePath;
    private BiliHttpServer fileServer;
    private ScheduledExecutorService cleanupExecutor;

    private static final class ActivePlayTask {
        private final Thread thread = Thread.currentThread();
        private volatile boolean cancelled;
        private HttpURLConnection connection;
        private Process process;

        private synchronized void attachConnection(HttpURLConnection connection) {
            if (cancelled) {
                connection.disconnect();
            } else {
                this.connection = connection;
            }
        }

        private synchronized void detachConnection(HttpURLConnection connection) {
            if (this.connection == connection) {
                this.connection = null;
            }
        }

        private synchronized void attachProcess(Process process) {
            if (cancelled) {
                stopProcess(process);
            } else {
                this.process = process;
            }
        }

        private synchronized void detachProcess(Process process) {
            if (this.process == process) {
                this.process = null;
            }
        }

        private void cancel() {
            HttpURLConnection activeConnection;
            Process activeProcess;
            synchronized (this) {
                cancelled = true;
                activeConnection = connection;
                activeProcess = process;
            }
            if (activeConnection != null) {
                activeConnection.disconnect();
            }
            if (activeProcess != null) {
                stopProcess(activeProcess);
            }
            thread.interrupt();
        }

        private static void stopProcess(Process process) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private static ReentrantLock[] createPlayLocks() {
        ReentrantLock[] locks = new ReentrantLock[PLAY_LOCK_COUNT];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    private void debugLog(String message) {
        if (config.advanced.debug) {
            AllMusic.log.data("<light_purple>[BiliAPI][DEBUG]<gray>" + message);
        }
    }

    // ========== IMusicApi 接口方法 ==========

    @Override
    public synchronized void reload(File path) {
        synchronized (lifecycleLock) {
            reloading = true;
            configValid = false;
        }
        try {
            reloadConfiguration(path);
        } finally {
            synchronized (lifecycleLock) {
                reloading = false;
            }
        }
    }

    private void reloadConfiguration(File path) {
        List<ActivePlayTask> tasksToCancel;
        synchronized (lifecycleLock) {
            tasksToCancel = new ArrayList<>(activePlayTasks);
        }
        tasksToCancel.forEach(ActivePlayTask::cancel);

        boolean cleanupStopped = stopCacheCleanup();
        boolean tasksStopped = awaitPlayTasksStopped();
        boolean serverStopped = true;
        if (fileServer != null) {
            serverStopped = fileServer.stop();
            if (serverStopped) fileServer = null;
        }
        if (!cleanupStopped || !tasksStopped || !serverStopped) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>旧任务或服务未能完全停止，已取消本次重载；API 保持禁用");
            return;
        }
        File configFile = new File(path, "bili.json");
        config = BiliConfig.load(configFile, AllMusic.gson, AllMusic.log::data);
        streamProvider = config.advanced.streamMode.equals("dash")
                ? new BiliDashStreamProvider(this::httpGet, this::debugLog)
                : new BiliMp4StreamProvider(this::httpGet);

        // ---- 检查依赖 ----
        boolean ffmpegOk = checkFfmpeg();
        boolean dirOk = ensureCacheDirectory();
        boolean fileServerOk = !config.advanced.http.enabled;
        if (dirOk) {
            cleanupCache(effectiveCachePath);
        }
        if (config.advanced.http.enabled && config.serveUrl != null && ffmpegOk && dirOk) {
            fileServer = new BiliHttpServer(effectiveCachePath, config.advanced.http.port,
                    List.of(config.advanced.http.listenAddresses), this::debugLog);
            fileServerOk = fileServer.start();
            if (!fileServerOk) {
                fileServer = null;
            }
        }

        if (config.serveUrl != null && ffmpegOk && dirOk && fileServerOk) {
            startCacheCleanup();
            configValid = true;
            AllMusic.log.data("<light_purple>[BiliAPI]<yellow>B站API已加载");
            debugLog("配置加载完成：缓存目录=" + effectiveCachePath.toAbsolutePath()
                    + "，serveUrl=" + config.serveUrl + "，ffmpeg=" + config.ffmpegPath
                    + "，port=" + config.advanced.http.port + "，quality=" + config.quality
                    + "，streamMode=" + config.advanced.streamMode + "，httpServerEnabled="
                    + config.advanced.http.enabled + "，debug=" + config.advanced.debug);
            if (config.advanced.http.enabled) {
                debugLog("HTTP 监听地址：" + String.join(", ", config.advanced.http.listenAddresses));
            } else {
                debugLog("内置 HTTP 服务已禁用，使用外部 HTTP 服务提供缓存文件");
            }
            if (config.maxAudioLength > 0) {
                debugLog("最大音频时长限制：" + config.maxAudioLength + " 分钟");
            } else {
                debugLog("最大音频时长限制：无限制");
            }
            if (config.maxCacheSize > 0) {
                debugLog("最大缓存大小：" + config.maxCacheSize + " MB");
            } else {
                debugLog("最大缓存大小：无限制");
            }
        } else {
            configValid = false;
            if (config.serveUrl == null) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>serveUrl 未配置，API 禁用");
            }
            if (!ffmpegOk) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>ffmpeg 不可用，API 禁用");
            }
            if (!dirOk) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>缓存目录不可用，API 禁用");
            }
            if (config.advanced.http.enabled && !fileServerOk) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>HTTP 文件服务启动失败，API 禁用");
            }
        }

        if (!configValid) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>B站API加载失败：请检查日志中的错误原因");
        }
    }

    // ========== 配置和依赖辅助方法 ==========

    private boolean ensureCacheDirectory() {
        String configuredDir = config.cacheDir;
        final Path target;
        try {
            target = resolveCachePath(configuredDir);
        } catch (RuntimeException e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>缓存目录路径无效：" + configuredDir + " - " + e);
            return false;
        }
        if (Files.exists(target)) {
            if (!Files.isDirectory(target)) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>缓存路径不是目录：" + target.toAbsolutePath());
                return false;
            }
            if (!Files.isWritable(target)) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>缓存目录没有写入权限：" + target.toAbsolutePath());
                return false;
            }
            debugLog("缓存目录已存在且可写：" + target.toAbsolutePath());
            effectiveCachePath = target;
            return true;
        }
        return createCacheDirectory(target, configuredDir);
    }

    private boolean createCacheDirectory(Path target, String configuredDir) {
        try {
            Files.createDirectories(target);
            debugLog("创建缓存目录：" + target.toAbsolutePath());
            if (!Files.isWritable(target)) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>创建后目录仍不可写（权限问题），请检查：" + target.toAbsolutePath());
                return false;
            }
            effectiveCachePath = target;
            return true;
        } catch (IOException e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>创建缓存目录失败：" + target.toAbsolutePath() + " - " + e);
            return tryDefaultCacheDirectory(configuredDir);
        }
    }

    private boolean tryDefaultCacheDirectory(String configuredDir) {
        if (configuredDir.equals(BiliConfig.DEFAULT_CACHE_DIR)) {
            return false;
        }

        AllMusic.log.data("<light_purple>[BiliAPI]<yellow>尝试回退到默认目录："
                + BiliConfig.DEFAULT_CACHE_DIR);
        Path fallback = resolveCachePath(BiliConfig.DEFAULT_CACHE_DIR);
        try {
            if (!Files.exists(fallback)) {
                Files.createDirectories(fallback);
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>创建默认缓存目录：" + fallback.toAbsolutePath());
            }
            if (Files.isDirectory(fallback) && Files.isWritable(fallback)) {
                effectiveCachePath = fallback;
                config.cacheDir = BiliConfig.DEFAULT_CACHE_DIR;
                debugLog("成功回退到默认缓存目录：" + fallback.toAbsolutePath());
                return true;
            }
            AllMusic.log.data("<light_purple>[BiliAPI]<red>默认缓存目录也不可用：" + fallback.toAbsolutePath()
                    + "（可能不可写或不是目录）");
            return false;
        } catch (IOException e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>回退创建默认缓存目录失败：" + e);
            return false;
        }
    }

    private Path resolveCachePath(String dir) {
        Path path = Paths.get(dir);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir")).resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private Path getCachePath() {
        if (effectiveCachePath == null) {
            if (!ensureCacheDirectory()) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>缓存目录未初始化，将使用临时根目录，可能导致错误");
                effectiveCachePath = Paths.get(System.getProperty("user.dir"));
            }
        }
        return effectiveCachePath;
    }

    private boolean checkFfmpeg() {
        Path ffmpegFile = Paths.get(config.ffmpegPath);
        if (ffmpegFile.isAbsolute() && !Files.exists(ffmpegFile)) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>ffmpeg 文件不存在：" + config.ffmpegPath);
            return trySystemFfmpeg();
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(config.ffmpegPath, "-version");
            pb.redirectErrorStream(true);
            debugLog("执行 ffmpeg 检查命令：" + String.join(" ", pb.command()));

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

            if (config.advanced.debug) {
                String firstLine = output.toString().lines().findFirst().orElse("(无输出)");
                debugLog("ffmpeg 检查结果：退出码=" + exitCode + "，输出=" + firstLine);
            }

            if (exitCode == 0) {
                debugLog("ffmpeg 检查通过：" + config.ffmpegPath);
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
        debugLog("尝试使用系统 PATH 中的 ffmpeg");
        try {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                debugLog("系统 ffmpeg 可用，将使用 PATH 中的 ffmpeg");
                config.ffmpegPath = "ffmpeg";
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

    private void startCacheCleanup() {
        if (!stopCacheCleanup()) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>旧缓存清理任务尚未退出，无法启动新的清理任务");
            return;
        }
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "bili-cache-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        long interval = Math.max(1L, config.cleanupInterval);
        cleanupExecutor.scheduleWithFixedDelay(() -> cleanupCache(getCachePath()),
                interval, interval, TimeUnit.MINUTES);
        debugLog("已启动缓存定时清理，间隔 " + interval + " 分钟，保留最近 "
                + config.advanced.preserveMinutes + " 分钟文件");
    }

    private boolean stopCacheCleanup() {
        ScheduledExecutorService executor = cleanupExecutor;
        if (executor == null) {
            return true;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>缓存清理线程未能在强制关闭后退出");
                return false;
            }
            cleanupExecutor = null;
            return true;
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            AllMusic.log.data("<light_purple>[BiliAPI]<red>等待缓存清理线程停止时被中断：" + e);
            return false;
        }
    }

    private boolean awaitPlayTasksStopped() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_TIMEOUT_SECONDS);
        synchronized (lifecycleLock) {
            while (!activePlayTasks.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    AllMusic.log.data("<light_purple>[BiliAPI]<red>播放任务未能在 "
                            + SHUTDOWN_TIMEOUT_SECONDS + " 秒内退出，取消本次重载");
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lifecycleLock, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    AllMusic.log.data("<light_purple>[BiliAPI]<red>等待播放任务停止时被中断：" + e);
                    return false;
                }
            }
        }
        return true;
    }

    private void cleanupCache(Path cachePath) {
        if (config.maxCacheSize <= 0) {
            debugLog("maxCacheSize 为 0，跳过缓存清理");
            return;
        }
        try {
            List<Path> filesToDelete = new ArrayList<>();
            long totalSize = 0;
            long preserveAfter = System.currentTimeMillis()
                    - TimeUnit.MINUTES.toMillis(config.advanced.preserveMinutes);
            try (Stream<Path> walk = Files.walk(cachePath)) {
                Iterator<Path> it = walk.filter(Files::isRegularFile).iterator();
                while (it.hasNext()) {
                    Path p = it.next();
                    if (!p.getFileName().toString().toLowerCase().endsWith(".mp3")) {
                        continue;
                    }
                    long size = Files.size(p);
                    totalSize += size;
                    if (Files.getLastModifiedTime(p).toMillis() < preserveAfter) {
                        filesToDelete.add(p);
                    }
                }
            }
            long maxSizeBytes = config.maxCacheSize * 1024L * 1024L;
            if (totalSize > maxSizeBytes) {
                debugLog("缓存大小 " + (totalSize / 1024 / 1024)
                        + " MB 超过限制 " + config.maxCacheSize + " MB，开始清理");
                filesToDelete.sort((a, b) -> {
                    try {
                        return Long.compare(Files.getLastModifiedTime(a).toMillis(),
                                Files.getLastModifiedTime(b).toMillis());
                    } catch (IOException e) {
                        return 0;
                    }
                });
                int deleted = 0;
                for (Path p : filesToDelete) {
                    if (totalSize <= maxSizeBytes) {
                        break;
                    }
                    try {
                        long size = Files.size(p);
                        Files.delete(p);
                        totalSize -= size;
                        deleted++;
                    } catch (IOException e) {
                        AllMusic.log.data("<light_purple>[BiliAPI]<red>删除文件失败：" + p.getFileName() + " - " + e.toString());
                    }
                }
                debugLog("缓存清理完成，共删除 " + deleted + " 个文件");
                if (totalSize > maxSizeBytes) {
                    debugLog("缓存仍超过限制，最近 " + config.advanced.preserveMinutes
                            + " 分钟内的文件将保留");
                }
            } else {
                debugLog("缓存大小 " + (totalSize / 1024 / 1024)
                        + " MB，未超过限制 " + config.maxCacheSize + " MB，无需清理");
            }
        } catch (IOException e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>缓存清理失败：" + e.toString());
        }
    }

    // ========== IMusicApi 核心方法 ==========

    @Override
    public String getId() {
        return "bili";
    }

    @Override
    public boolean isBusy() {
        synchronized (lifecycleLock) {
            return !activePlayTasks.isEmpty();
        }
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
        return id != null && id.matches("BV[A-Za-z0-9]{10}");
    }

    private void sendPlayerMessage(String player, String message) {
        if (player == null || player.isEmpty()) {
            return;
        }
        Object sender = AllMusic.side.getPlayer(player);
        if (sender != null) {
            AllMusic.side.sendMessage(sender, message);
        }
    }

    @Override
    public SongInfoObj getMusic(String id, String player, boolean isList) {
        if (!configValid) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>API 未正确配置，拒绝处理请求");
            sendPlayerMessage(player, "<light_purple>[BiliAPI]<red>B站API未正确配置，请检查 bili.json");
            return null;
        }
        if (!checkId(id)) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>无效BV号：" + id);
            sendPlayerMessage(player, "<light_purple>[BiliAPI]<red>无效的BV号：" + id);
            return null;
        }

        String url = BILI_API_BASE + "/x/web-interface/view?bvid=" + id;
        HttpResObj res = httpGet(url);
        if (res == null || !res.ok) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>获取视频信息失败：" + id);
            sendPlayerMessage(player, "<light_purple>[BiliAPI]<red>获取视频信息失败：" + id);
            return null;
        }

        try {
            JsonObject root = AllMusic.gson.fromJson(res.data, JsonObject.class);
            if (root.get("code").getAsInt() != 0) {
                String error = "<light_purple>[BiliAPI]<red>B站API返回错误：" + root.get("code").getAsInt();
                AllMusic.log.data(error);
                sendPlayerMessage(player, error);
                return null;
            }
            JsonObject data = root.getAsJsonObject("data");
            String name = data.get("title").getAsString();
            long duration = data.get("duration").getAsLong();

            if (config.maxAudioLength > 0 && duration > config.maxAudioLength * 60L) {
                AllMusic.log.data("<light_purple>[BiliAPI]<yellow>视频时长超过限制：" + duration
                        + " 秒（限制 " + config.maxAudioLength + " 分钟）");
                sendPlayerMessage(player, "<light_purple>[BiliAPI]<red>时长超过 "
                        + config.maxAudioLength + " 分钟");
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
            sendPlayerMessage(player, "<light_purple>[BiliAPI]<red>解析视频信息失败：" + e);
            return null;
        }
    }

    @Override
    public String getPlayUrl(String id) {
        if (!checkId(id)) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>无效BV号：" + id);
            return null;
        }

        ActivePlayTask task = new ActivePlayTask();
        synchronized (lifecycleLock) {
            if (!configValid || reloading) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>API 未正确配置或正在重载，拒绝生成播放链接");
                return null;
            }
            activePlayTasks.add(task);
        }

        currentPlayTask.set(task);
        ReentrantLock playLock = playLocks[Math.floorMod(id.hashCode(), playLocks.length)];
        boolean lockAcquired = false;
        try {
            playLock.lockInterruptibly();
            lockAcquired = true;
            if (task.cancelled) {
                return null;
            }
            return generatePlayUrl(id, task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!task.cancelled) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>等待播放任务锁时被中断：" + e);
            }
            return null;
        } finally {
            if (lockAcquired) {
                playLock.unlock();
            }
            currentPlayTask.remove();
            synchronized (lifecycleLock) {
                activePlayTasks.remove(task);
                lifecycleLock.notifyAll();
            }
        }
    }

    private String generatePlayUrl(String id, ActivePlayTask task) {
        Path cachePath = getCachePath();
        String mp3Name = id + ".mp3";
        Path mp3File = cachePath.resolve(mp3Name);
        if (!mp3File.normalize().startsWith(cachePath)) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>缓存文件路径超出缓存目录：" + mp3File);
            return null;
        }
        Path inputFile = cachePath.resolve(config.advanced.streamMode.equals("dash")
                ? id + ".dash.m4a.tmp" : id + ".mp4.tmp");
        Path outMp3 = cachePath.resolve(id + ".out.mp3");

        try {
            if (Files.exists(mp3File) && Files.size(mp3File) > 1000) {
                debugLog("命中缓存：" + mp3Name);
                return config.serveUrl + mp3Name;
            }

            if (task.cancelled) return null;
            debugLog("开始生成音频：" + id);
            VideoMetadata metadata = fetchVideoMetadata(id);
            if (metadata == null) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>无法获取视频CID和时长：" + id);
                return null;
            }
            if (task.cancelled) return null;

            List<String> streamUrls = streamProvider.fetchUrls(id, metadata.cid());
            if (task.cancelled || streamUrls == null || streamUrls.isEmpty()) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>无法获取"
                        + (config.advanced.streamMode.equals("dash") ? "DASH音频" : "视频")
                        + "播放地址：" + id);
                return null;
            }
            if (!downloadFirstAvailable(streamUrls, inputFile, task)) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>下载"
                        + (config.advanced.streamMode.equals("dash") ? "DASH音频" : "MP4")
                        + "失败：" + id);
                return null;
            }

            long timeoutSeconds = calculateTranscodeTimeout(metadata.durationSeconds());
            debugLog("ffmpeg转码超时上限：" + timeoutSeconds + " 秒（视频时长="
                    + metadata.durationSeconds() + " 秒）");
            if (task.cancelled || !convertToMp3(inputFile, outMp3, timeoutSeconds, task)) {
                return null;
            }

            if (task.cancelled) return null;
            Files.move(outMp3, mp3File, StandardCopyOption.REPLACE_EXISTING);
            debugLog("音频生成完成：" + mp3File.toAbsolutePath());
            return config.serveUrl + mp3Name;

        } catch (IOException e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>处理音频文件时发生IO错误：" + e.toString());
            return null;
        } finally {
            deleteTemporaryFile(inputFile);
            deleteTemporaryFile(cachePath.resolve(id + ".out.mp3"));
        }
    }

    private void deleteTemporaryFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>删除转码临时文件失败："
                    + file.getFileName() + " - " + e);
        }
    }

    @Override
    public LyricSave getLyric(String id) {
        return new LyricSave();
    }

    // ========== 搜索方法（修改：失败返回 null，与 netapi 一致） ==========
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
            return new SearchPageObj(resultList, 1, getId());
        }

        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        String url = BILI_API_BASE + "/x/web-interface/search/type?search_type=video&keyword=" + encoded;

        for (int attempt = 0; attempt < config.advanced.maxRetry; attempt++) {
            try {
                HttpResObj res = httpGet(url);
                if (res == null || !res.ok) {
                    debugLog("搜索请求失败（尝试 " + (attempt + 1) + "/"
                            + config.advanced.maxRetry + "）");
                    continue;
                }

                JsonObject root = AllMusic.gson.fromJson(res.data, JsonObject.class);
                int code = root.get("code").getAsInt();
                if (code != 0) {
                    debugLog("B站搜索 API 返回错误码 " + code + "（尝试 " + (attempt + 1)
                            + "/" + config.advanced.maxRetry + "）");
                    continue;
                }

                JsonObject data = root.getAsJsonObject("data");
                if (!data.has("result") || data.get("result").isJsonNull()) {
                    // 搜索成功但无结果
                    return new SearchPageObj(resultList, 1, getId());
                }

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
                debugLog("搜索完成：关键词=" + keyword + "，结果数=" + resultList.size());
                return new SearchPageObj(resultList, totalPages, getId());

            } catch (Exception e) {
                debugLog("搜索请求异常（尝试 " + (attempt + 1) + "/"
                        + config.advanced.maxRetry + "）：" + e);
            }

            if (attempt < config.advanced.maxRetry - 1) {
                try {
                    TimeUnit.MILLISECONDS.sleep(config.advanced.retryDelay * (attempt + 1));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 所有重试均失败 → 返回 null，触发框架错误消息
        AllMusic.log.data("<light_purple>[BiliAPI]<red>搜索失败，关键词：" + keyword);
        return null;
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

    private VideoMetadata fetchVideoMetadata(String bvid) {
        String url = BILI_API_BASE + "/x/web-interface/view?bvid=" + bvid;
        HttpResObj res = httpGet(url);
        if (res == null || !res.ok) return null;
        try {
            JsonObject root = AllMusic.gson.fromJson(res.data, JsonObject.class);
            JsonObject data = root.getAsJsonObject("data");
            long cid = data.get("cid").getAsLong();
            long durationSeconds = data.get("duration").getAsLong();
            if (cid <= 0 || durationSeconds <= 0) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>视频CID或时长无效：" + bvid);
                return null;
            }
            return new VideoMetadata(cid, durationSeconds);
        } catch (Exception e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>获取视频CID和时长失败：" + e);
            return null;
        }
    }

    private boolean downloadFile(String url, Path target) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            ActivePlayTask task = currentPlayTask.get();
            if (task != null) {
                task.attachConnection(conn);
                if (task.cancelled) {
                    return false;
                }
            }
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", config.advanced.userAgent);
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);

            int code = conn.getResponseCode();
            if (task != null && task.cancelled) {
                return false;
            }
            if (code != 200 && code != 206) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>下载失败，HTTP " + code);
                return false;
            }

            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            boolean success = Files.exists(target) && Files.size(target) > 0;
            debugLog("下载完成：目标=" + target.getFileName() + "，大小="
                    + (success ? Files.size(target) : 0) + " 字节");
            return success;
        } catch (Exception e) {
            ActivePlayTask task = currentPlayTask.get();
            if (task == null || !task.cancelled) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>下载文件失败：" + e.toString());
            }
            return false;
        } finally {
            ActivePlayTask task = currentPlayTask.get();
            if (conn != null) {
                if (task != null) {
                    task.detachConnection(conn);
                }
                conn.disconnect();
            }
        }
    }

    private boolean downloadFirstAvailable(List<String> urls, Path target, ActivePlayTask task) {
        for (int i = 0; i < urls.size(); i++) {
            if (task.cancelled) {
                return false;
            }
            if (downloadFile(urls.get(i), target)) {
                if (i > 0) {
                    debugLog("DASH主地址下载失败，已使用第 " + (i + 1) + " 个备用地址");
                }
                return true;
            }
        }
        return false;
    }

    private long calculateTranscodeTimeout(long durationSeconds) {
        double durationTimeout = durationSeconds * config.advanced.transcodeDurationMultiplier;
        if (durationTimeout >= Long.MAX_VALUE - config.advanced.transcodeMinTimeoutSeconds) {
            return Long.MAX_VALUE;
        }
        long scaledTimeout = (long) Math.ceil(durationTimeout);
        return config.advanced.transcodeMinTimeoutSeconds + scaledTimeout;
    }

    private boolean convertToMp3(Path input, Path output, long timeoutSeconds, ActivePlayTask task) {
        String inputPath = input.toAbsolutePath().toString();
        String outputPath = output.toAbsolutePath().toString();

        ProcessBuilder pb = new ProcessBuilder(
                config.ffmpegPath,
                "-y", "-i", inputPath,
                "-vn", "-acodec", "libmp3lame", "-q:a", String.valueOf(config.quality),
                outputPath
        );
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        Process process = null;
        try {
            process = pb.start();
            task.attachProcess(process);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor();
                AllMusic.log.data("<light_purple>[BiliAPI]<red>ffmpeg转码超时，已停止进程："
                        + timeoutSeconds + " 秒");
                return false;
            }
            int exit = process.exitValue();
            if (exit == 0 && Files.exists(output) && Files.size(output) > 1000) {
                return true;
            } else {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>ffmpeg转码失败，退出码：" + exit);
                return false;
            }
        } catch (InterruptedException e) {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try {
                    process.waitFor();
                } catch (InterruptedException interrupted) {
                    e.addSuppressed(interrupted);
                }
            }
            Thread.currentThread().interrupt();
            if (!task.cancelled) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>ffmpeg转码被中断：" + e);
            }
            return false;
        } catch (Exception e) {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            AllMusic.log.data("<light_purple>[BiliAPI]<red>ffmpeg执行异常：" + e.toString());
            return false;
        } finally {
            if (process != null) {
                task.detachProcess(process);
            }
        }
    }

    // ========== 修改后的 httpGet（使用 MusicHttpClient.client） ==========
    private HttpResObj httpGet(String url) {
        try {
            HttpGet request = new HttpGet(url);
            request.setHeader("User-Agent", config.advanced.userAgent);
            request.setHeader("Referer", "https://www.bilibili.com/");
            request.setHeader("Accept", "application/json");

            return MusicHttpClient.client.execute(request, response -> {
                int code = response.getCode();
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    return new HttpResObj("", false);
                }
                String data = EntityUtils.toString(entity, StandardCharsets.UTF_8);
                EntityUtils.consume(entity);
                if (code == 200) {
                    debugLog("HTTP GET 成功：" + request.getRequestUri() + "，状态码=" + code);
                    return new HttpResObj(data, true);
                } else {
                    AllMusic.log.data("<light_purple>[BiliAPI]<red>HTTP GET 返回非200：" + code);
                    return new HttpResObj(data, false);
                }
            });
        } catch (Exception e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>HTTP GET 失败：" + e.toString());
            return new HttpResObj("", false);
        }
    }

    private String stripHtml(String input) {
        return input.replaceAll("<[^>]+>", "");
    }
}
