package bili;

import com.coloryr.allmusic.server.core.AllMusic;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class BiliHttpServer {
    private final Path root;
    private final int port;
    private final List<String> listenAddresses;
    private final Consumer<String> debugLogger;
    private final List<HttpServer> servers = new ArrayList<>();
    private ExecutorService executor;

    BiliHttpServer(Path root, int port, List<String> listenAddresses, Consumer<String> debugLogger) {
        this.root = root.toAbsolutePath().normalize();
        this.port = port;
        this.listenAddresses = List.copyOf(listenAddresses);
        this.debugLogger = debugLogger;
    }

    boolean start() {
        List<HttpServer> startedServers = new ArrayList<>();
        try {
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "bili-file-server");
                thread.setDaemon(true);
                return thread;
            });
            for (String address : listenAddresses) {
                HttpServer server = HttpServer.create(new InetSocketAddress(address, port), 0);
                server.createContext("/", exchange -> serveCachedFile(exchange));
                server.setExecutor(executor);
                server.start();
                startedServers.add(server);
            }
            servers.addAll(startedServers);
            debugLogger.accept("HTTP 文件服务已启动：地址=" + String.join(", ", listenAddresses)
                    + "，端口=" + port + "，目录=" + root);
            return true;
        } catch (IOException e) {
            for (HttpServer server : startedServers) {
                server.stop(0);
            }
            stop();
            AllMusic.log.data("<light_purple>[BiliAPI]<red>HTTP 文件服务启动失败（地址="
                    + String.join(", ", listenAddresses) + "，端口=" + port + "）：" + e);
            return false;
        }
    }

    void stop() {
        for (HttpServer server : servers) {
            server.stop(0);
        }
        servers.clear();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void serveCachedFile(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            sendEmptyResponse(exchange, 405);
            return;
        }

        try {
            String requestPath = exchange.getRequestURI().getPath();
            if (requestPath == null || !requestPath.startsWith("/")
                    || requestPath.length() <= 1
                    || requestPath.substring(1).contains("/")) {
                debugLogger.accept("HTTP 文件请求 404：只允许根路径 MP3 文件：" + requestPath);
                sendEmptyResponse(exchange, 404);
                return;
            }
            String fileName = requestPath.substring(1);
            if (fileName.contains("..") || !fileName.endsWith(".mp3")) {
                debugLogger.accept("HTTP 文件请求 404：路径不是 MP3 文件：" + requestPath);
                sendEmptyResponse(exchange, 404);
                return;
            }
            Path file = root.resolve(fileName).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                debugLogger.accept("HTTP 文件请求 404：文件不存在：" + file);
                sendEmptyResponse(exchange, 404);
                return;
            }

            long size = Files.size(file);
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            exchange.sendResponseHeaders(200, size);
            if ("GET".equalsIgnoreCase(method)) {
                try (OutputStream output = exchange.getResponseBody();
                     InputStream input = Files.newInputStream(file)) {
                    input.transferTo(output);
                }
            } else {
                exchange.close();
            }
            debugLogger.accept("HTTP 文件请求：" + file.getFileName() + "，状态码=200");
        } catch (IOException e) {
            debugLogger.accept("HTTP 文件请求失败：" + e);
            exchange.close();
        }
    }

    private void sendEmptyResponse(HttpExchange exchange, int status) {
        try {
            exchange.sendResponseHeaders(status, -1);
        } catch (IOException e) {
            debugLogger.accept("HTTP 错误响应发送失败：" + e);
        } finally {
            exchange.close();
        }
    }
}
