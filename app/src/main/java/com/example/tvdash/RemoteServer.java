package com.example.tvdash;

import android.content.Context;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server (no libraries) so a phone browser on the same Wi-Fi can control the TV.
 * GET /            -> the remote web page
 * GET /api/...     -> needs ?pin=XXXX (the PIN shown on the TV)
 */
final class RemoteServer {

    interface Host {
        void onKey(String key);
        void onAppsChanged();
    }

    private final Context ctx;
    private final Host host;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile boolean running;

    RemoteServer(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
    }

    void start(int port) throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try {
                        final Socket s = server.accept();
                        pool.execute(new Runnable() {
                            @Override public void run() { handle(s); }
                        });
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            }
        }, "remote-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String request = in.readLine();
            if (request == null) return;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) { /* skip headers */ }

            String[] parts = request.split(" ");
            if (parts.length < 2) return;
            String target = parts[1];
            String path = target;
            Map<String, String> q = new HashMap<>();
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                for (String kv : target.substring(qi + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        q.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                              URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                    }
                }
            }

            OutputStream out = s.getOutputStream();
            if (path.equals("/") || path.equals("/index.html")) {
                send(out, 200, "text/html", readAsset("remote.html"));
            } else if (path.startsWith("/api/")) {
                if (!AppStore.pin(ctx).equals(q.get("pin"))) {
                    send(out, 401, "application/json", "{\"error\":\"bad pin\"}");
                } else {
                    send(out, 200, "application/json", api(path, q));
                }
            } else {
                send(out, 404, "text/plain", "not found");
            }
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private String api(String path, Map<String, String> q) {
        switch (path) {
            case "/api/ping":
                return "{\"ok\":true}";
            case "/api/apps": {
                List<AppStore.Entry> list = AppStore.load(ctx, true);
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < list.size(); i++) {
                    AppStore.Entry e = list.get(i);
                    if (i > 0) sb.append(',');
                    sb.append("{\"label\":").append(json(e.label))
                      .append(",\"pkg\":").append(json(e.pkg))
                      .append(",\"hidden\":").append(e.hidden).append('}');
                }
                return sb.append(']').toString();
            }
            case "/api/launch": {
                boolean ok = AppStore.launch(ctx, String.valueOf(q.get("pkg")));
                return "{\"ok\":" + ok + "}";
            }
            case "/api/key":
                host.onKey(String.valueOf(q.get("k")));
                return "{\"ok\":true}";
            case "/api/hide":
                AppStore.setHidden(ctx, String.valueOf(q.get("pkg")), "1".equals(q.get("hide")));
                host.onAppsChanged();
                return "{\"ok\":true}";
            default:
                return "{\"error\":\"unknown\"}";
        }
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private String readAsset(String name) throws IOException {
        InputStream is = ctx.getAssets().open(name);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return bo.toString("UTF-8");
    }

    private void send(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : "Not Found";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(b);
    }
}
