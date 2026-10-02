package bili.stream;

import java.util.List;

public interface BiliStreamProvider {
    String API_BASE = "https://api.bilibili.com";

    List<String> fetchUrls(String bvid, long cid);
}
