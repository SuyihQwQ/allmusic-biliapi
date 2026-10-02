package bili.stream;

import com.coloryr.allmusic.server.core.AllMusic;
import com.coloryr.allmusic.server.core.objs.HttpResObj;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public final class BiliDashStreamProvider implements BiliStreamProvider {
    private final Function<String, HttpResObj> httpGet;
    private final Consumer<String> debugLog;

    public BiliDashStreamProvider(Function<String, HttpResObj> httpGet, Consumer<String> debugLog) {
        this.httpGet = httpGet;
        this.debugLog = debugLog;
    }

    @Override
    public List<String> fetchUrls(String bvid, long cid) {
        String url = API_BASE + "/x/player/playurl?bvid=" + bvid
                + "&cid=" + cid + "&qn=16&type=mp4&platform=html5&fnver=0&fnval=4048";
        HttpResObj response = httpGet.apply(url);
        if (response == null || !response.ok) return null;
        try {
            JsonObject root = AllMusic.gson.fromJson(response.data, JsonObject.class);
            if (root.get("code").getAsInt() != 0) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>DASH播放地址请求失败，B站错误码："
                        + root.get("code").getAsInt());
                return null;
            }
            JsonObject data = root.getAsJsonObject("data");
            JsonObject dash = data == null ? null : data.getAsJsonObject("dash");
            JsonArray audioTracks = dash == null ? null : dash.getAsJsonArray("audio");
            if (audioTracks == null || audioTracks.isEmpty()) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>播放响应中没有可用的DASH音频轨道");
                return null;
            }

            JsonObject selectedTrack = null;
            long selectedBandwidth = Long.MIN_VALUE;
            for (JsonElement element : audioTracks) {
                if (!element.isJsonObject()) continue;
                JsonObject track = element.getAsJsonObject();
                if (!hasUrl(track)) continue;
                long bandwidth = track.has("bandwidth") ? track.get("bandwidth").getAsLong() : 0L;
                if (selectedTrack == null || bandwidth > selectedBandwidth) {
                    selectedTrack = track;
                    selectedBandwidth = bandwidth;
                }
            }

            if (selectedTrack == null) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>DASH音频轨道缺少有效播放地址");
                return null;
            }

            LinkedHashSet<String> urls = new LinkedHashSet<>();
            addUrl(urls, selectedTrack.get("baseUrl"));
            addUrl(urls, selectedTrack.get("base_url"));
            addUrls(urls, selectedTrack.get("backupUrl"));
            addUrls(urls, selectedTrack.get("backup_url"));
            if (urls.isEmpty()) {
                AllMusic.log.data("<light_purple>[BiliAPI]<red>选中的DASH音频轨道没有可用播放地址");
                return null;
            }
            debugLog.accept("选择DASH音频轨道：bandwidth=" + selectedBandwidth
                    + "，codec=" + (selectedTrack.has("codecs")
                    ? selectedTrack.get("codecs").getAsString() : "unknown")
                    + "，备用地址数=" + (urls.size() - 1));
            return new ArrayList<>(urls);
        } catch (Exception e) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>解析DASH音频播放地址失败：" + e);
            return null;
        }
    }

    private boolean hasUrl(JsonObject track) {
        return track.has("baseUrl") || track.has("base_url")
                || track.has("backupUrl") || track.has("backup_url");
    }

    private void addUrl(Set<String> urls, JsonElement element) {
        if (element != null && element.isJsonPrimitive() && !element.getAsString().isBlank()) {
            urls.add(element.getAsString());
        }
    }

    private void addUrls(Set<String> urls, JsonElement element) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                addUrl(urls, item);
            }
        } else {
            addUrl(urls, element);
        }
    }
}
