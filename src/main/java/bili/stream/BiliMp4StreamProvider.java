package bili.stream;

import com.coloryr.allmusic.server.core.AllMusic;
import com.coloryr.allmusic.server.core.objs.HttpResObj;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.function.Function;

public final class BiliMp4StreamProvider implements BiliStreamProvider {
    private final Function<String, HttpResObj> httpGet;

    public BiliMp4StreamProvider(Function<String, HttpResObj> httpGet) {
        this.httpGet = httpGet;
    }

    @Override
    public List<String> fetchUrls(String bvid, long cid) {
        String url = API_BASE + "/x/player/playurl?bvid=" + bvid
                + "&cid=" + cid + "&qn=16&type=mp4&platform=html5";
        HttpResObj response = httpGet.apply(url);
        if (response == null || !response.ok) return null;
        try {
            JsonObject root = AllMusic.gson.fromJson(response.data, JsonObject.class);
            if (root.get("code").getAsInt() != 0) return null;
            JsonArray durl = root.getAsJsonObject("data").getAsJsonArray("durl");
            if (durl == null || durl.isEmpty()) return null;
            String mediaUrl = durl.get(0).getAsJsonObject().get("url").getAsString();
            return mediaUrl.isBlank() ? null : List.of(mediaUrl);
        } catch (Exception e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>解析视频播放地址失败：" + e);
            return null;
        }
    }
}
