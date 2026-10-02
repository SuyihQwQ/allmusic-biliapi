package bili.http;

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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class BiliHttpServer {
    private static final int SERVER_STOP_DELAY_SECONDS = 5;
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final Path root;
    private final int port;
    private final List<String> listenAddresses;
    private final Consumer<String> debugLogger;
    private final List<HttpServer> servers = new ArrayList<>();
    private ExecutorService executor;

    public BiliHttpServer(Path root, int port, List<String> listenAddresses, Consumer<String> debugLogger) {
        this.root = root.toAbsolutePath().normalize();
        this.port = port;
        this.listenAddresses = List.copyOf(listenAddresses);
        this.debugLogger = debugLogger;
    }

    public synchronized boolean start() {
        if (executor != null || !servers.isEmpty()) {
            AllMusic.log.data("<light_purple>[BiliAPI]<red>HTTP 文件服务仍在运行或停止中，不能重复启动");
            return false;
        }
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

    public synchronized boolean stop() {
        for (HttpServer server : servers) {
            server.stop(SERVER_STOP_DELAY_SECONDS);
        }
        servers.clear();
        if (executor == null) {
            return true;
        }

        ExecutorService stoppedExecutor = executor;
        stoppedExecutor.shutdown();
        try {
            if (!stoppedExecutor.awaitTermination(
                    EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                stoppedExecutor.shutdownNow();
                if (!stoppedExecutor.awaitTermination(
                        EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    AllMusic.log.data("<light_purple>[BiliAPI]<red>HTTP 文件服务请求线程未能在强制关闭后退出");
                    return false;
                }
            }
            executor = null;
        } catch (InterruptedException e) {
            stoppedExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            AllMusic.log.data("<light_purple>[BiliAPI]<red>等待 HTTP 文件服务停止时被中断：" + e);
            return false;
        }

        debugLogger.accept("HTTP 文件服务已停止，所有请求线程均已退出");
        return true;
    }

    private void serveCachedFile(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            sendEmptyResponse(exchange, 405);
            return;
        }

        boolean responseStarted = false;
        try {
            Path file = resolveRequestedFile(exchange);
            if (file == null) {
                return;
            }

            long size = Files.size(file);
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            if ("GET".equalsIgnoreCase(method)) {
                try (InputStream input = Files.newInputStream(file)) {
                    exchange.sendResponseHeaders(200, size);
                    responseStarted = true;
                    try (OutputStream output = exchange.getResponseBody()) {
                        input.transferTo(output);
                    }
                }
            } else {
                exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
                exchange.sendResponseHeaders(200, -1);
                responseStarted = true;
                exchange.close();
            }
            debugLogger.accept("HTTP 文件请求：" + file.getFileName() + "，状态码=200");
        } catch (IOException e) {
            debugLogger.accept("HTTP 文件请求失败：" + e);
            if (responseStarted) {
                exchange.close();
            } else {
                sendEmptyResponse(exchange, 500);
            }
        }
    }

    private Path resolveRequestedFile(HttpExchange exchange) throws IOException {
        String requestPath = exchange.getRequestURI().getPath();
        if (requestPath == null || !requestPath.startsWith("/")) {
            debugLogger.accept("HTTP 文件请求 400：请求路径无效：" + requestPath);
            sendEmptyResponse(exchange, 400);
            return null;
        }

        if (containsTraversalSegment(requestPath)) {
            debugLogger.accept("HTTP 文件请求 403：拒绝路径穿越：" + requestPath);
            sendEmptyResponse(exchange, 403);
            return null;
        }

        if (requestPath.length() <= 1 || requestPath.substring(1).contains("/")) {
            debugLogger.accept("HTTP 文件请求 403：只允许根路径 MP3 文件：" + requestPath);
            sendEmptyResponse(exchange, 403);
            return null;
        }

        String fileName = requestPath.substring(1);
        if (!fileName.endsWith(".mp3")) {
            debugLogger.accept("HTTP 文件请求 403：路径不是 MP3 文件：" + requestPath);
            sendEmptyResponse(exchange, 403);
            return null;
        }

        Path file = root.resolve(fileName).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            debugLogger.accept("HTTP 文件请求 404：文件不存在：" + file);
            sendEmptyResponse(exchange, 404);
            return null;
        }
        return file;
    }

    private boolean containsTraversalSegment(String requestPath) {
        String normalized = requestPath.replace('\\', '/');
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
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
