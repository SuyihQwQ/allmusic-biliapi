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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class BiliMusicApi implements IMusicApi {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    // 缓存目录与对外提供地址，可用环境变量覆盖（便于部署到不同服务器）
    private static final String CACHE_DIR = System.getenv().getOrDefault("ALLMUSIC_BILI_CACHE_DIR", "/home/minecraft/music_cache");
    private static final String SERVE_URL = System.getenv().getOrDefault("ALLMUSIC_BILI_SERVE_URL", "https://YOUR-DOMAIN/music/");
    private boolean isUpdate;

    @Override
    public String getId() {
        return "bili";
    }

    @Override
    public boolean isBusy() {
        return isUpdate;
    }

    private HttpResObj get(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code != 200) {
                return new HttpResObj("", false);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            return new HttpResObj(sb.toString(), true);
        } catch (Exception e) {
            return new HttpResObj("", false);
        }
    }

    private String downloadToFile(String url, String path) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) return null;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(path)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            return path;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getMusicId(String arg) {
        if (arg.contains("video/")) {
            int i = arg.indexOf("video/");
            String sub = arg.substring(i + 6);
            int j = sub.indexOf('/');
            if (j > 0) sub = sub.substring(0, j);
            int q = sub.indexOf('?');
            if (q > 0) sub = sub.substring(0, q);
            return sub;
        }
        return arg;
    }

    @Override
    public boolean checkId(String id) {
        return id != null && id.startsWith("BV");
    }

    @Override
    public SongInfoObj getMusic(String id, String player, boolean isList) {
        HttpResObj res = get("https://api.bilibili.com/x/web-interface/view?bvid=" + id);
        if (res != null && res.ok) {
            try {
                JsonObject root = AllMusic.gson.fromJson(res.data, JsonObject.class);
                if (root.get("code").getAsInt() != 0) return null;
                JsonObject data = root.getAsJsonObject("data");
                String name = data.get("title").getAsString();
                long duration = data.get("duration").getAsLong();
                String pic = data.get("pic").getAsString();
                String author = "";
                JsonElement owner = data.get("owner");
                if (owner != null && !owner.isJsonNull()) {
                    author = owner.getAsJsonObject().get("name").getAsString();
                }
                long cid = data.get("cid").getAsLong();
                return new SongInfoObj(author, name, id, "BV视频", player, "B站", isList, duration * 1000L,
                        pic, false, null, getId());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public String getPlayUrl(String id) {
        String mp3Name = id + ".mp3";
        String mp3Path = CACHE_DIR + "/" + mp3Name;
        File mp3File = new File(mp3Path);
        if (mp3File.exists() && mp3File.length() > 1000) {
            return SERVE_URL + mp3Name;
        }
        // 拿 cid
        HttpResObj view = get("https://api.bilibili.com/x/web-interface/view?bvid=" + id);
        if (view == null || !view.ok) return null;
        long cid = 0;
        try {
            JsonObject root = AllMusic.gson.fromJson(view.data, JsonObject.class);
            cid = root.getAsJsonObject("data").get("cid").getAsLong();
        } catch (Exception e) {
            return null;
        }
        // 拿 durl 混合 MP4
        HttpResObj play = get("https://api.bilibili.com/x/player/playurl?bvid=" + id
                + "&cid=" + cid + "&qn=16&type=mp4&platform=html5");
        if (play == null || !play.ok) return null;
        String durlUrl = null;
        try {
            JsonObject root = AllMusic.gson.fromJson(play.data, JsonObject.class);
            if (root.get("code").getAsInt() != 0) return null;
            JsonArray durl = root.getAsJsonObject("data").getAsJsonArray("durl");
            if (durl != null && durl.size() > 0) {
                durlUrl = durl.get(0).getAsJsonObject().get("url").getAsString();
            }
        } catch (Exception e) {
            return null;
        }
        if (durlUrl == null) return null;
        // 下载 MP4 到临时文件
        String tmpMp4 = CACHE_DIR + "/" + id + ".mp4.tmp";
        try {
            File tmp = new File(tmpMp4);
            if (tmp.exists()) tmp.delete();
            if (downloadToFile(durlUrl, tmpMp4) == null) return null;
            // 输出临时文件用 .mp3 扩展名，否则 ffmpeg 无法推断格式
            String outTmp = CACHE_DIR + "/" + id + ".out.mp3";
            File outTmpFile = new File(outTmp);
            if (outTmpFile.exists()) outTmpFile.delete();
            // ffmpeg 转 MP3
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-y", "-i", tmpMp4,
                    "-vn", "-acodec", "libmp3lame", "-q:a", "6", outTmp);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            byte[] buf = new byte[4096];
            try (InputStream in = process.getInputStream()) {
                while (in.read(buf) != -1) {
                }
            }
            int exit = process.waitFor();
            File result = new File(outTmp);
            if (exit == 0 && result.exists() && result.length() > 1000) {
                if (mp3File.exists()) mp3File.delete();
                result.renameTo(mp3File);
                return SERVE_URL + mp3Name;
            }
        } catch (Exception e) {
            return null;
        } finally {
            new File(tmpMp4).delete();
        }
        return null;
    }

    @Override
    public LyricSave getLyric(String id) {
        return new LyricSave();
    }

    @Override
    public SearchPageObj search(String[] name, boolean isDefault) {
        List<SearchMusicObj> resData = new ArrayList<>();
        StringBuilder name1 = new StringBuilder();
        for (int a = isDefault ? 0 : 1; a < name.length; a++) {
            name1.append(name[a]).append(" ");
        }
        String keyword = name1.toString().trim();
        String url = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        // B站搜索接口有频率限制，失败重试几次
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                HttpResObj res = get(url);
                if (res == null || !res.ok) continue;
                JsonObject root = AllMusic.gson.fromJson(res.data, JsonObject.class);
                if (root.get("code").getAsInt() != 0) continue;
                JsonObject data = root.getAsJsonObject("data");
                if (!data.has("result") || data.get("result").isJsonNull()) continue;
                JsonArray result = data.getAsJsonArray("result");
                for (JsonElement el : result) {
                    JsonObject item = el.getAsJsonObject();
                    String bvid = item.get("bvid").getAsString();
                    String title = stripTags(item.get("title").getAsString());
                    String author = item.get("author").getAsString();
                    String duration = item.get("duration").getAsString();
                    resData.add(new SearchMusicObj(bvid, title, author, duration));
                }
                int maxpage = resData.size() / 10;
                return new SearchPageObj(resData, maxpage, getId());
            } catch (Exception e) {
                // 重试
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
                break;
            }
        }
        return null;
    }

    private String stripTags(String s) {
        return s.replaceAll("<[^>]+>", "");
    }

    @Override
    public void setList(String id, Object sender) {
        AllMusic.log.data("B站源暂不支持歌单");
    }
}
