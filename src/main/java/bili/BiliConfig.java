package bili;

public class BiliConfig {
    public String cacheDir = "music_cache";
    public String serveUrl;
    public Integer port = 8090;
    public long cleanupInterval = 60L;
    public String ffmpegPath = "ffmpeg";
    public int maxAudioLength = 45;
    public int maxCacheSize = 512;
    public int quality = 6;
    public Advanced advanced = new Advanced();

    public static class Advanced {
        public int configVersion = 2;
        public boolean debug = false;
        public String[] listenAddresses = {"0.0.0.0"};
        public long preserveMinutes = 5L;
        public int maxRetry = 3;
        public long retryDelay = 1500L;
        public String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    }
}
