package bili.config;

public class BiliConfig {
    public String cacheDir = "music_cache";
    public String serveUrl;
    public long cleanupInterval = 60L;
    public String ffmpegPath = "ffmpeg";
    public int maxAudioLength = 45;
    public int maxCacheSize = 512;
    public int quality = 4;
    public Advanced advanced = new Advanced();

    public static class Advanced {
        public int configVersion = 4;
        public boolean debug = false;
        public Http http = new Http();
        public long preserveMinutes = 5L;
        public String streamMode = "dash";
        public long transcodeMinTimeoutSeconds = 60L;
        public double transcodeDurationMultiplier = 0.75;
        public int maxRetry = 3;
        public long retryDelay = 1500L;
        public String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    }

    public static class Http {
        public boolean enabled = true;
        public Integer port = 8090;
        public String[] listenAddresses = {"0.0.0.0"};
    }
}
