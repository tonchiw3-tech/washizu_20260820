import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class App {
    // java Appを実行した場所に保存するファイル。従来の保存先をそのまま使う。
    static final String SAVE_FILE = "todos.txt";

    static List<Message> messages = new ArrayList<>();
    static int nextId = 1;

    public static void main(String[] args) throws Exception {
        load();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (path.equals("/add") && method.equals("POST")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String familyName = formValue(body, "familyName").trim();
                String text = formValue(body, "message").trim();
                if (!familyName.isEmpty() && !text.isEmpty()) {
                    messages.add(new Message(nextId++, familyName, text));
                    save();
                }
                redirect(exchange);
                return;
            }

            if (path.equals("/read")) {
                int id = queryId(exchange);
                for (Message message : messages) {
                    if (message.getId() == id) {
                        message.setRead(true);
                        save();
                        break;
                    }
                }
                redirect(exchange);
                return;
            }

            if (path.equals("/delete")) {
                int id = queryId(exchange);
                messages.removeIf(message -> message.getId() == id);
                save();
                redirect(exchange);
                return;
            }

            if (path.equals("/") || path.equals("/api/todos")) {
                if (path.equals("/api/todos")) {
                    if (!method.equals("GET")) {
                        exchange.sendResponseHeaders(405, -1);
                        exchange.close();
                        return;
                    }
                    byte[] responseBody = messagesToJson().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.sendResponseHeaders(200, responseBody.length);
                    exchange.getResponseBody().write(responseBody);
                    exchange.getResponseBody().close();
                    return;
                }

                String html = pageHtml();
                byte[] responseBody = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, responseBody.length);
                exchange.getResponseBody().write(responseBody);
                exchange.getResponseBody().close();
                return;
            }

            byte[] responseBody = "ページが見つかりません".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(404, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.getResponseBody().close();
        });

        server.start();
        System.out.println("サーバー起動: http://localhost:8080 (止めるときは Ctrl+C)");
    }

    static void redirect(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    static int queryId(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query != null && query.startsWith("id=")) {
            try {
                return Integer.parseInt(query.substring(3));
            } catch (NumberFormatException ignored) {
                // 不正なidは、どのメッセージにも変更を加えない。
            }
        }
        return -1;
    }

    static String pageHtml() {
        int unreadCount = 0;
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='ja'><head>")
                .append("<meta charset='UTF-8'>")
                .append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
                .append("<title>つながるメッセージ</title>")
                .append("<style>")
                .append("*{box-sizing:border-box;}")
                .append("body{max-width:680px;margin:0 auto;padding:32px 20px;font-family:sans-serif;font-size:16px;line-height:1.6;color:#333;background:#fafafa;}")
                .append(".hero{margin-bottom:24px;}.hero h1{margin:0 0 8px;font-size:26px;}.hero p{margin:0;color:#555;}")
                .append(".add-form{display:grid;gap:10px;margin-bottom:28px;padding:18px;background:white;border:1px solid #ddd;border-radius:8px;}")
                .append(".add-form input,.add-form textarea{width:100%;padding:10px;font:inherit;border:1px solid #aaa;border-radius:4px;}")
                .append(".add-form button,.actions a{display:inline-block;padding:8px 12px;font:inherit;border:1px solid #888;border-radius:4px;background:#f5f5f5;color:#333;text-decoration:none;cursor:pointer;}")
                .append(".message-list{margin:0;padding:0;list-style:none;}.message-item{margin-bottom:12px;padding:16px;background:white;border:1px solid #ddd;border-radius:8px;}")
                .append(".message-item.unread{border-left:5px solid #2563eb;}.message-head{display:flex;justify-content:space-between;gap:12px;align-items:center;}")
                .append(".family-name{font-weight:bold;font-size:1.1rem;}.status{font-size:.9rem;color:#2563eb;}.read .status{color:#666;}")
                .append(".message-text{margin:8px 0 12px;white-space:pre-wrap;overflow-wrap:anywhere;}.actions{display:flex;gap:8px;justify-content:flex-end;}")
                .append("</style></head><body><main>")
                .append("<header class='hero'><h1>つながるメッセージ</h1><p>家族からのメッセージを届けます。</p></header>")
                .append("<form class='add-form' method='post' action='/add'>")
                .append("<input name='familyName' placeholder='家族名' autocomplete='name' required>")
                .append("<textarea name='message' placeholder='メッセージを入力してください' rows='4' required></textarea>")
                .append("<button type='submit'>送信する</button></form>")
                .append("<p>届いたメッセージ: ").append(messages.size()).append("件</p>")
                .append("<ul class='message-list'>");

        if (messages.isEmpty()) {
            html.append("<li>まだメッセージはありません。</li>");
        } else {
            for (Message message : messages) {
                if (!message.isRead()) {
                    unreadCount++;
                }
                String itemClass = message.isRead() ? "read" : "unread";
                String status = message.isRead() ? "既読" : "未読";
                html.append("<li class='message-item ").append(itemClass).append("'>")
                        .append("<div class='message-head'><span class='family-name'>")
                        .append(htmlEscape(message.getFamilyName()))
                        .append("</span><span class='status'>").append(status).append("</span></div>")
                        .append("<div class='message-text'>")
                        .append(htmlEscape(message.getText()))
                        .append("</div><div class='actions'>");
                if (!message.isRead()) {
                    html.append("<a href='/read?id=").append(message.getId()).append("'>既読にする</a>");
                }
                html.append("<a href='/delete?id=").append(message.getId()).append("'>削除</a>")
                        .append("</div></li>");
            }
        }
        html.append("</ul><p>未読: ").append(unreadCount).append("件</p></main></body></html>");
        return html.toString();
    }

    // 既存の save() / load() の仕組みを使い、UTF-8の文字列をBase64で安全に保存する。
    static void save() {
        List<String> lines = new ArrayList<>();
        for (Message message : messages) {
            lines.add(message.getId()
                    + "\t" + message.isRead()
                    + "\t" + encode(message.getFamilyName())
                    + "\t" + encode(message.getText()));
        }

        try {
            Files.write(Path.of(SAVE_FILE), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("メッセージの保存に失敗しました: " + e.getMessage());
        }
    }

    static void load() {
        Path savePath = Path.of(SAVE_FILE);
        if (!Files.exists(savePath)) {
            return;
        }

        int maxId = 0;
        try {
            for (String line : Files.readAllLines(savePath, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] fields = line.split("\\t", -1);
                if (fields.length < 4) {
                    continue;
                }
                try {
                    int id = Integer.parseInt(fields[0]);
                    boolean read = Boolean.parseBoolean(fields[1]);
                    Message message = new Message(id, decode(fields[2]), decode(fields[3]));
                    message.setRead(read);
                    messages.add(message);
                    maxId = Math.max(maxId, id);
                } catch (IllegalArgumentException e) {
                    // 壊れた行があっても、他のメッセージの読み込みは続ける。
                }
            }
            nextId = maxId + 1;
        } catch (IOException e) {
            System.err.println("メッセージの読み込みに失敗しました: " + e.getMessage());
        }
    }

    static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    static String messagesToJson() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            Message message = messages.get(i);
            json.append("{\"familyName\":\"")
                    .append(esc(message.getFamilyName()))
                    .append("\",\"message\":\"")
                    .append(esc(message.getText()))
                    .append("\",\"read\":")
                    .append(message.isRead())
                    .append('}');
        }
        return json.append(']').toString();
    }

    static String esc(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
            }
        }
        return escaped.toString();
    }

    static String formValue(String body, String name) {
        for (String parameter : body.split("&", -1)) {
            String[] keyValue = parameter.split("=", 2);
            if (keyValue.length == 2 && name.equals(URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8))) {
                return URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    static String htmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&#39;");
    }
}

class Message {
    private final int id;
    private final String familyName;
    private final String text;
    private boolean read;

    Message(int id, String familyName, String text) {
        this.id = id;
        this.familyName = familyName;
        this.text = text;
        this.read = false;
    }

    int getId() {
        return id;
    }

    String getFamilyName() {
        return familyName;
    }

    String getText() {
        return text;
    }

    boolean isRead() {
        return read;
    }

    void setRead(boolean read) {
        this.read = read;
    }
}
