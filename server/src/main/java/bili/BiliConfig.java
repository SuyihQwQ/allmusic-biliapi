package bili;

public class BiliConfig {
    public String cacheDir = "music_cache";
    public String serveUrl;
    public String ffmpegPath = "ffmpeg";
    public int maxAudioLength = 45;
    public int maxCacheSize = 100;
    public int quality = 6;
    public Advanced advanced = new Advanced();

    public static class Advanced {
        public int maxRetry = 3;
        public long retryDelay = 1500L;
        public String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    }
}
