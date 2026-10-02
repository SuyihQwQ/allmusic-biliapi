package bili.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

public class BiliConfig {
    public static final int CONFIG_VERSION = 4;
    public static final String DEFAULT_CACHE_DIR = "music_cache";
    public static final String DEFAULT_STREAM_MODE = "dash";
    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    public static final long DEFAULT_TRANSCODE_MIN_TIMEOUT_SECONDS = 60L;
    public static final double DEFAULT_TRANSCODE_DURATION_MULTIPLIER = 0.75;

    public String cacheDir = DEFAULT_CACHE_DIR;
    public String serveUrl = "http://localhost:8090/";
    public long cleanupInterval = 60L;
    public String ffmpegPath = "ffmpeg";
    public int maxAudioLength = 45;
    public int maxCacheSize = 512;
    public int quality = 4;
    public Advanced advanced = new Advanced();

    public static class Advanced {
        public int configVersion = CONFIG_VERSION;
        public boolean debug;
        public Http http = new Http();
        public long preserveMinutes = 5L;
        public String streamMode = DEFAULT_STREAM_MODE;
        public long transcodeMinTimeoutSeconds = DEFAULT_TRANSCODE_MIN_TIMEOUT_SECONDS;
        public double transcodeDurationMultiplier = DEFAULT_TRANSCODE_DURATION_MULTIPLIER;
        public int maxRetry = 3;
        public long retryDelay = 1500L;
        public String userAgent = DEFAULT_USER_AGENT;
    }

    public static class Http {
        public boolean enabled = true;
        public Integer port = 8090;
        public String[] listenAddresses = {"0.0.0.0"};
    }

    public static BiliConfig load(File file, Gson gson, Consumer<String> logger) {
        if (!file.exists()) {
            BiliConfig defaults = new BiliConfig();
            save(file, defaults, gson, logger);
            logger.accept("<light_purple>[BiliAPI]<yellow>已生成 bili.json 默认配置；如客户端不在本机，请将 serveUrl 改为客户端可访问的地址后重载");
            return defaults;
        }

        JsonObject json;
        try (InputStreamReader reader = new InputStreamReader(
                Files.newInputStream(file.toPath()), StandardCharsets.UTF_8);
             BufferedReader bufferedReader = new BufferedReader(reader)) {
            JsonElement parsed = JsonParser.parseReader(bufferedReader);
            if (!parsed.isJsonObject()) {
                throw new JsonParseException("配置文件内容必须是 JSON 对象");
            }
            json = parsed.getAsJsonObject();
        } catch (Exception e) {
            logger.accept("<light_purple>[BiliAPI]<red>读取配置文件失败：" + e);
            backupBrokenConfig(file, logger);
            BiliConfig defaults = new BiliConfig();
            save(file, defaults, gson, logger);
            return defaults;
        }

        boolean changed = migrateLegacyHttpSettings(json);
        JsonObject advanced = json.has("advanced") && json.get("advanced").isJsonObject()
                ? json.getAsJsonObject("advanced") : null;
        int version = getConfigVersion(advanced);
        if (version != CONFIG_VERSION) {
            logger.accept("<light_purple>[BiliAPI]<yellow>检测到配置版本 " + version
                    + "，当前版本为 " + CONFIG_VERSION + "，开始检查缺失配置项");
            changed = true;
        }
        changed |= mergeMissing(json, createDefaultConfigJson(gson));
        json.getAsJsonObject("advanced").addProperty("configVersion", CONFIG_VERSION);
        if (json.has("configVersion")) {
            json.remove("configVersion");
            changed = true;
        }

        BiliConfig config;
        try {
            config = gson.fromJson(json, BiliConfig.class);
            if (config == null) {
                throw new JsonParseException("配置文件内容必须是 JSON 对象");
            }
        } catch (RuntimeException e) {
            logger.accept("<light_purple>[BiliAPI]<red>解析配置文件失败：" + e);
            backupBrokenConfig(file, logger);
            config = new BiliConfig();
            save(file, config, gson, logger);
            return config;
        }

        config.normalize(logger);
        if (changed) {
            save(file, config, gson, logger);
            logger.accept("<light_purple>[BiliAPI]<yellow>已迁移并更新 bili.json 配置");
        }
        return config;
    }

    public void normalize(Consumer<String> logger) {
        if (advanced == null) advanced = new Advanced();
        if (advanced.http == null) advanced.http = new Http();

        normalizeHttpSettings(logger);
        normalizeGeneralSettings(logger);
        normalizeAdvancedSettings(logger);
    }

    private void normalizeHttpSettings(Consumer<String> logger) {
        if (advanced.http.port == null || advanced.http.port <= 0 || advanced.http.port > 65535) {
            logger.accept("<light_purple>[BiliAPI]<yellow>advanced.http.port 无效，已回退到 8090");
            advanced.http.port = 8090;
        }
    }

    private void normalizeGeneralSettings(Consumer<String> logger) {
        cacheDir = clean(cacheDir);
        if (cacheDir == null || cacheDir.isEmpty()) cacheDir = DEFAULT_CACHE_DIR;

        if (cleanupInterval <= 0) {
            logger.accept("<light_purple>[BiliAPI]<yellow>cleanupInterval 无效，已回退到 60 分钟");
            cleanupInterval = 60L;
        }
        serveUrl = clean(serveUrl);
        if (serveUrl != null && !serveUrl.isEmpty()) {
            if (isValidServeUrl(serveUrl)) {
                if (!serveUrl.endsWith("/")) serveUrl += "/";
            } else {
                logger.accept("<light_purple>[BiliAPI]<red>serveUrl 格式无效，API 禁用：" + serveUrl);
                serveUrl = null;
            }
        } else {
            serveUrl = null;
        }

        ffmpegPath = clean(ffmpegPath);
        if (ffmpegPath == null || ffmpegPath.isEmpty()) ffmpegPath = "ffmpeg";
        if (maxAudioLength < 0) {
            logger.accept("<light_purple>[BiliAPI]<yellow>maxAudioLength 为负数，已回退到 45 分钟");
            maxAudioLength = 45;
        }
        if (maxCacheSize < 0) {
            logger.accept("<light_purple>[BiliAPI]<yellow>maxCacheSize 为负数，已回退到 512 MB");
            maxCacheSize = 512;
        }
        if (quality < 0 || quality > 9) {
            logger.accept("<light_purple>[BiliAPI]<yellow>quality 超出 0-9 范围，使用默认值 4");
            quality = 4;
        }
    }

    private void normalizeAdvancedSettings(Consumer<String> logger) {
        advanced.maxRetry = advanced.maxRetry > 0 ? advanced.maxRetry : 3;
        advanced.retryDelay = advanced.retryDelay > 0 ? advanced.retryDelay : 1500L;
        advanced.userAgent = clean(advanced.userAgent);
        if (advanced.userAgent == null || advanced.userAgent.isEmpty()) {
            advanced.userAgent = DEFAULT_USER_AGENT;
        }
        advanced.http.listenAddresses = normalizeListenAddresses(advanced.http.listenAddresses, logger);
        if (advanced.preserveMinutes < 0) {
            logger.accept("<light_purple>[BiliAPI]<yellow>advanced.preserveMinutes 无效，已回退到 5 分钟");
            advanced.preserveMinutes = 5L;
        }
        advanced.streamMode = advanced.streamMode == null
                ? DEFAULT_STREAM_MODE : advanced.streamMode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!advanced.streamMode.equals("mp4") && !advanced.streamMode.equals("dash")) {
            logger.accept("<light_purple>[BiliAPI]<yellow>advanced.streamMode 无效，已回退到 "
                    + DEFAULT_STREAM_MODE);
            advanced.streamMode = DEFAULT_STREAM_MODE;
        }
        if (advanced.transcodeMinTimeoutSeconds <= 0) {
            logger.accept("<light_purple>[BiliAPI]<yellow>advanced.transcodeMinTimeoutSeconds 无效，已回退到 "
                    + DEFAULT_TRANSCODE_MIN_TIMEOUT_SECONDS + " 秒");
            advanced.transcodeMinTimeoutSeconds = DEFAULT_TRANSCODE_MIN_TIMEOUT_SECONDS;
        }
        if (!Double.isFinite(advanced.transcodeDurationMultiplier)
                || advanced.transcodeDurationMultiplier <= 0) {
            logger.accept("<light_purple>[BiliAPI]<yellow>advanced.transcodeDurationMultiplier 无效，已回退到 "
                    + DEFAULT_TRANSCODE_DURATION_MULTIPLIER);
            advanced.transcodeDurationMultiplier = DEFAULT_TRANSCODE_DURATION_MULTIPLIER;
        }
    }

    private static boolean isValidServeUrl(String url) {
        try {
            URI parsed = URI.create(url);
            return ("http".equalsIgnoreCase(parsed.getScheme())
                    || "https".equalsIgnoreCase(parsed.getScheme()))
                    && parsed.getHost() != null
                    && parsed.getUserInfo() == null
                    && parsed.getFragment() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String clean(String input) {
        return input == null ? null
                : input.replaceAll("[\\p{C}\\u200B\\u200C\\u200D\\u200E\\u200F\\uFEFF]", "").trim();
    }

    private static String[] normalizeListenAddresses(String[] configured, Consumer<String> logger) {
        Set<String> addresses = new LinkedHashSet<>();
        if (configured != null) {
            for (String address : configured) {
                if (address == null || address.trim().isEmpty()) continue;
                String normalized = address.trim();
                if (normalized.contains("/") || normalized.contains("\\")
                        || normalized.chars().anyMatch(Character::isWhitespace)) {
                    logger.accept("<light_purple>[BiliAPI]<yellow>忽略无效监听地址：" + normalized);
                } else {
                    addresses.add(normalized);
                }
            }
        }
        if (addresses.isEmpty()) {
            logger.accept("<light_purple>[BiliAPI]<yellow>advanced.http.listenAddresses 未配置，已回退到 0.0.0.0");
            addresses.add("0.0.0.0");
        }
        return addresses.toArray(String[]::new);
    }

    private static int getConfigVersion(JsonObject advanced) {
        if (advanced == null || !advanced.has("configVersion")
                || !advanced.get("configVersion").isJsonPrimitive()
                || !advanced.getAsJsonPrimitive("configVersion").isNumber()) {
            return -1;
        }
        return advanced.get("configVersion").getAsInt();
    }

    private static boolean migrateLegacyHttpSettings(JsonObject config) {
        JsonObject advanced = config.has("advanced") && config.get("advanced").isJsonObject()
                ? config.getAsJsonObject("advanced") : new JsonObject();
        config.add("advanced", advanced);
        JsonObject http = advanced.has("http") && advanced.get("http").isJsonObject()
                ? advanced.getAsJsonObject("http") : new JsonObject();
        advanced.add("http", http);

        boolean changed = false;
        if (config.has("port")) {
            if (!http.has("port")) http.add("port", config.get("port").deepCopy());
            config.remove("port");
            changed = true;
        }
        if (advanced.has("httpServerEnabled")) {
            if (!http.has("enabled")) http.add("enabled", advanced.get("httpServerEnabled").deepCopy());
            advanced.remove("httpServerEnabled");
            changed = true;
        }
        if (advanced.has("listenAddresses")) {
            if (!http.has("listenAddresses")) {
                http.add("listenAddresses", advanced.get("listenAddresses").deepCopy());
            }
            advanced.remove("listenAddresses");
            changed = true;
        }
        return changed;
    }

    private static JsonObject createDefaultConfigJson(Gson gson) {
        return gson.toJsonTree(new BiliConfig()).getAsJsonObject();
    }

    private static boolean mergeMissing(JsonObject target, JsonObject defaults) {
        boolean changed = false;
        for (String key : defaults.keySet()) {
            JsonElement defaultValue = defaults.get(key);
            if (!target.has(key) || target.get(key).isJsonNull()) {
                target.add(key, defaultValue.deepCopy());
                changed = true;
            } else if (defaultValue.isJsonObject() && target.get(key).isJsonObject()) {
                changed |= mergeMissing(target.getAsJsonObject(key), defaultValue.getAsJsonObject());
            }
        }
        return changed;
    }

    private static void backupBrokenConfig(File file, Consumer<String> logger) {
        if (!file.exists()) return;
        Path backup = file.toPath().resolveSibling(file.getName() + ".bak");
        try {
            Files.move(file.toPath(), backup, StandardCopyOption.REPLACE_EXISTING);
            logger.accept("<light_purple>[BiliAPI]<yellow>已备份损坏的配置文件到：" + backup.getFileName());
        } catch (IOException e) {
            logger.accept("<light_purple>[BiliAPI]<red>备份损坏配置文件失败：" + e);
        }
    }

    private static void save(File file, BiliConfig config, Gson gson, Consumer<String> logger) {
        File parent = file.getParentFile();
        try {
            if (parent != null) Files.createDirectories(parent.toPath());
            try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                gson.toJson(config, writer);
            }
        } catch (IOException e) {
            logger.accept("<light_purple>[BiliAPI]<red>写入 bili.json 失败：" + e);
        }
    }
}
