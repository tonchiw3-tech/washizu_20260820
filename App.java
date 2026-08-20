import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class App {
    // java App を実行した場所に保存するファイル。従来の保存先をそのまま使う。
    static final String SAVE_FILE = "todos.txt";
    static final String PATIENT_NAME = "父";
    static final String FAMILY_NAME = "友香";

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
                String sender = formValue(body, "sender").trim();
                String receiver = formValue(body, "receiver").trim();
                String text = formValue(body, "text").trim();
                // 旧画面で使われていたフォーム名も引き続き受け付ける。
                if (sender.isEmpty()) {
                    sender = formValue(body, "familyName").trim();
                }
                if (text.isEmpty()) {
                    text = formValue(body, "message").trim();
                }
                if (!sender.isEmpty() && !receiver.isEmpty() && !text.isEmpty()) {
                    messages.add(new Message(nextId++, sender, receiver, text));
                    save();
                }
                String view = queryValue(exchange, "view");
                redirect(exchange, view.equals("family") ? "/family" :
                        view.equals("patient") ? "/patient" : "/");
                return;
            }

            if (path.equals("/mood")) {
                if (!method.equals("POST")) {
                    sendText(exchange, 405, "Method Not Allowed", "text/plain; charset=UTF-8");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String mood = formValue(body, "mood").trim();
                if (mood.equals("😊 元気") || mood.equals("😐 まあまあ") || mood.equals("😢 寂しい")) {
                    String originalIdValue = formValue(body, "originalId").trim();
                    int originalId = parseId(originalIdValue);
                    Message original = findMessage(originalId);
                    if (original != null && isMessageForPatient(original)) {
                        messages.add(new Message(nextId++, PATIENT_NAME, original.getSender(), mood));
                        // 返信した場合も、元のメッセージは既読にする。
                        original.setRead(true);
                        save();
                        redirect(exchange, "/patient?sentTo="
                                + URLEncoder.encode(original.getSender(), StandardCharsets.UTF_8));
                        return;
                    }
                }
                // 返信元が特定できない気分送信は登録しない。
                redirect(exchange, "/patient");
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
                redirect(exchange, queryValue(exchange, "view").equals("patient") ? "/patient" :
                        queryValue(exchange, "view").equals("family") ? "/family" : "/");
                return;
            }

            if (path.equals("/delete")) {
                int id = queryId(exchange);
                messages.removeIf(message -> message.getId() == id);
                save();
                redirect(exchange, queryValue(exchange, "view").equals("family") ? "/family" : "/");
                return;
            }

            if (path.equals("/patient")) {
                int replyId = parseId(queryValue(exchange, "reply"));
                sendHtml(exchange, patientPageHtml(queryValue(exchange, "sentTo"), replyId));
                return;
            }

            if (path.equals("/family") || path.equals("/") || path.equals("/api/todos")) {
                if (path.equals("/api/todos")) {
                    if (!method.equals("GET")) {
                        sendText(exchange, 405, "Method Not Allowed", "text/plain; charset=UTF-8");
                        return;
                    }
                    sendText(exchange, 200, messagesToJson(), "application/json; charset=UTF-8");
                    return;
                }
                sendHtml(exchange, familyPageHtml());
                return;
            }

            sendText(exchange, 404, "ページが見つかりません", "text/plain; charset=UTF-8");
        });

        server.start();
        System.out.println("サーバーを起動しました: http://localhost:8080 (終了するときは Ctrl+C)");
    }

    static void sendHtml(HttpExchange exchange, String html) throws IOException {
        sendText(exchange, 200, html, "text/html; charset=UTF-8");
    }

    static void sendText(HttpExchange exchange, int status, String text, String contentType) throws IOException {
        byte[] responseBody = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, responseBody.length);
        exchange.getResponseBody().write(responseBody);
        exchange.getResponseBody().close();
    }

    static void redirect(HttpExchange exchange) throws IOException {
        redirect(exchange, "/");
    }

    static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    static int queryId(HttpExchange exchange) {
        try {
            return Integer.parseInt(queryValue(exchange, "id"));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static String queryValue(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return "";
        }
        for (String parameter : query.split("&", -1)) {
            String[] keyValue = parameter.split("=", 2);
            if (keyValue.length == 2 && name.equals(URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8))) {
                return URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    static String familyPageHtml() {
        int unreadCount = 0;
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='ja'><head>")
                .append("<meta charset='UTF-8'>")
                .append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
                .append("<title>つながるメッセージ - 家族側</title>")
                .append("<style>")
                .append("*{box-sizing:border-box;}body{max-width:760px;margin:0 auto;padding:32px 20px;font-family:sans-serif;font-size:16px;line-height:1.6;color:#333;background:#fafafa;}")
                .append(".hero{margin-bottom:24px}.hero h1{margin:0 0 8px;font-size:28px}.hero p{margin:0;color:#555}.nav{display:flex;gap:10px;flex-wrap:wrap;margin:0 0 22px}.nav a{padding:10px 14px;border-radius:6px;background:#e5e7eb;color:#222;text-decoration:none}.nav .current{background:#2563eb;color:white}")
                .append(".add-form{display:grid;gap:10px;margin-bottom:28px;padding:18px;background:white;border:1px solid #ddd;border-radius:8px}.add-form label{display:grid;gap:4px;font-weight:bold}.add-form input,.add-form textarea{width:100%;padding:10px;font:inherit;border:1px solid #aaa;border-radius:4px}.add-form button,.actions a{display:inline-block;padding:8px 12px;font:inherit;border:1px solid #888;border-radius:4px;background:#f5f5f5;color:#333;text-decoration:none;cursor:pointer}")
                .append(".message-list{margin:0;padding:0;list-style:none}.message-item{margin-bottom:12px;padding:16px;background:white;border:1px solid #ddd;border-radius:8px}.message-item.unread{border-left:5px solid #2563eb}.message-head{display:flex;justify-content:space-between;gap:12px;align-items:center}.family-name{font-weight:bold;font-size:1.1rem}.status{font-size:.9rem;color:#2563eb}.read .status{color:#666}.message-text{margin:8px 0 12px;white-space:pre-wrap;overflow-wrap:anywhere}.actions{display:flex;gap:8px;justify-content:flex-end}.mood-reply{border-left-color:#c45a00!important}.mood-label{color:#8a3b00;font-weight:bold}")
                .append("</style></head><body><main>")
                .append("<header class='hero'><h1>つながるメッセージ</h1><p>家族からの送信、既読確認、削除を行います。</p></header>")
                .append("<nav class='nav'><a class='current' href='/family'>家族側</a><a href='/patient'>父側の画面</a></nav>")
                .append("<form class='add-form' method='post' action='/add?view=family'>")
                .append("<label>送る人<input name='sender' value='友香' placeholder='送る人' autocomplete='name' required></label>")
                .append("<label>受け取る人<input name='receiver' value='父' placeholder='受け取る人' autocomplete='name' required></label>")
                .append("<label>メッセージ<textarea name='text' placeholder='メッセージを入力してください' rows='4' required></textarea></label>")
                .append("<button type='submit'>送信する</button></form>")
                .append("<h2>送信したメッセージ一覧</h2>")
                .append("<p>届いたメッセージ: ").append(messages.size()).append("件</p><ul class='message-list'>");

        if (messages.isEmpty()) {
            html.append("<li>まだメッセージはありません。</li>");
        } else {
            for (Message message : messages) {
                if (!message.isRead()) {
                    unreadCount++;
                }
                String itemClass = message.isRead() ? "read" : "unread";
                if (isMoodReply(message)) {
                    itemClass += " mood-reply";
                }
                String status = message.isRead() ? "既読" : "未読";
                html.append("<li class='message-item ").append(itemClass).append("'>")
                        .append("<div class='message-head'><span class='family-name'>")
                        .append(htmlEscape(message.getSender())).append(" → ")
                        .append(htmlEscape(message.getReceiver())).append("</span><span class='status'>")
                        .append(status).append("</span></div>");
                if (isMoodReply(message)) {
                    html.append("<div class='mood-label'>今日の気分</div>");
                }
                html.append("<div class='message-text'>").append(htmlEscape(message.getText()))
                        .append("</div><div class='actions'>");
                if (!message.isRead()) {
                    html.append("<a href='/read?id=").append(message.getId()).append("&view=family'>読みました</a>");
                }
                html.append("<a href='/delete?id=").append(message.getId()).append("&view=family'>削除</a>")
                        .append("</div></li>");
            }
        }
        html.append("</ul><p>未読: ").append(unreadCount).append("件</p></main></body></html>");
        return html.toString();
    }

    // 患者画面の表示内容は、管理用の送信者名・削除操作などを含めず必要最小限にする。
    static String patientPageHtml(String sentTo, int replyId) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='ja'><head>")
                .append("<meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>")
                .append("<title>お父さんの画面</title><style>")
                .append("*{box-sizing:border-box}body{max-width:720px;margin:0 auto;padding:24px 18px;font-family:sans-serif;font-size:20px;line-height:1.6;color:#222;background:#fff}.hero{margin-bottom:24px}.hero h1{font-size:32px;margin:0 0 8px}.hero p{margin:0;color:#555}.section{margin:28px 0}.section h2{font-size:26px;margin:0 0 14px}.incoming{padding:20px;margin:0 0 16px;background:#eef6ff;border:2px solid #2563eb;border-radius:12px}.incoming.unread{background:#fff}.incoming p{margin:8px 0 16px;white-space:pre-wrap;overflow-wrap:anywhere}.read-button,.reply-button{display:block;width:100%;min-height:64px;padding:12px;font-size:22px;font-weight:bold;color:#fff;border:0;border-radius:10px;text-decoration:none;text-align:center}.read-button{background:#2563eb}.reply-button{margin-top:12px;background:#7c3aed}.read-mark{color:#555;font-size:18px}.reply-panel{margin-top:18px;padding:18px;border-top:2px solid #7c3aed;background:#f5f3ff;border-radius:10px}.reply-panel h3{margin:0 0 8px;font-size:25px}.reply-panel p{margin:8px 0 14px}.mood-list{display:grid;gap:16px}.mood-button{width:100%;min-height:72px;padding:12px 18px;font-size:22px;font-weight:bold;border:2px solid #333;border-radius:12px;cursor:pointer}.mood-button.good{background:#c45a00;color:#fff}.mood-button.ok{background:#1f5aa6;color:#fff}.mood-button.lonely{background:#5b6573;color:#fff}.sent{padding:16px;margin-bottom:20px;font-size:23px;font-weight:bold;color:#123b5d;background:#e0f2fe;border:2px solid #2563eb;border-radius:10px}")
                .append("</style></head><body><main><header class='hero'><h1>お父さんの画面</h1><p>届いたメッセージを読んだり、今日の気分を選べます。</p></header>");
        if (!sentTo.isEmpty()) {
            html.append("<div class='sent' role='status'>")
                    .append(htmlEscape(sentTo)).append("へ返信しました</div>");
        }
        html.append("<section class='section'><h2>家族から届いたメッセージ</h2>");
        boolean found = false;
        for (Message message : messages) {
            if (!isMessageForPatient(message)) {
                continue;
            }
            found = true;
            html.append("<article class='incoming ").append(message.isRead() ? "read" : "unread").append("'>")
                    .append("<p>").append(htmlEscape(message.getSender())).append("からメッセージが届いています</p>")
                    .append("<p>「").append(htmlEscape(message.getText())).append("」</p>");
            if (!message.isRead()) {
                html.append("<a class='read-button' href='/read?id=").append(message.getId()).append("&view=patient'>読みました</a>");
            } else {
                html.append("<div class='read-mark'>読みました</div>");
            }
            html.append("<a class='reply-button' href='/patient?reply=").append(message.getId())
                    .append("'>返事をする</a>");
            if (message.getId() == replyId) {
                html.append("<div class='reply-panel'><h3>")
                        .append(htmlEscape(message.getSender())).append("へ返事をします</h3>")
                        .append("<p>今日の気分をひとつ押してください。</p>")
                        .append("<div class='mood-list'>")
                        .append(moodForm(message.getId(), "😊 元気", "good"))
                        .append(moodForm(message.getId(), "😐 まあまあ", "ok"))
                        .append(moodForm(message.getId(), "😢 寂しい", "lonely"))
                        .append("</div></div>");
            }
            html.append("</article>");
        }
        if (!found) {
            html.append("<p>今はメッセージはありません。</p>");
        }
        html.append("</section></main></body></html>");
        return html.toString();
    }

    static String moodForm(int originalId, String mood, String cssClass) {
        return "<form method='post' action='/mood'><input type='hidden' name='originalId' value='"
                + originalId + "'><button class='mood-button " + cssClass + "' name='mood' value='"
                + htmlEscape(mood) + "' type='submit'>" + htmlEscape(mood) + "</button></form>";
    }

    static boolean isMessageForPatient(Message message) {
        return PATIENT_NAME.equals(message.getReceiver()) && !PATIENT_NAME.equals(message.getSender());
    }

    static boolean isMoodReply(Message message) {
        return PATIENT_NAME.equals(message.getSender()) && !message.getReceiver().isEmpty()
                && (message.getText().equals("😊 元気") || message.getText().equals("😐 まあまあ")
                || message.getText().equals("😢 寂しい"));
    }

    static int parseId(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static Message findMessage(int id) {
        for (Message message : messages) {
            if (message.getId() == id) {
                return message;
            }
        }
        return null;
    }

    // 既存の save() / load() の形式を維持し、再起動後も新しい気分メッセージを読み込む。
    static void save() {
        List<String> lines = new ArrayList<>();
        for (Message message : messages) {
            lines.add(message.getId() + "\t" + message.isRead() + "\t"
                    + encode(message.getSender()) + "\t" + encode(message.getReceiver()) + "\t"
                    + encode(message.getText()));
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
                    String sender = decode(fields[2]);
                    String receiver;
                    String text;
                    if (fields.length >= 5) {
                        receiver = decode(fields[3]);
                        text = decode(fields[4]);
                    } else {
                        // 旧形式（id/read/familyName/text）も読み込めるようにする。
                        receiver = "";
                        text = decode(fields[3]);
                    }
                    Message message = new Message(id, sender, receiver, text);
                    message.setRead(read);
                    messages.add(message);
                    maxId = Math.max(maxId, id);
                } catch (IllegalArgumentException ignored) {
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
            json.append("{\"id\":").append(message.getId())
                    .append(",\"sender\":\"").append(esc(message.getSender()))
                    .append("\",\"receiver\":\"").append(esc(message.getReceiver()))
                    .append("\",\"text\":\"").append(esc(message.getText()))
                    .append("\",\"read\":").append(message.isRead())
                    .append(",\"familyName\":\"").append(esc(message.getSender()))
                    .append("\",\"message\":\"").append(esc(message.getText())).append("\"}");
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
    private final String sender;
    private final String receiver;
    private final String text;
    private boolean read;

    Message(int id, String sender, String receiver, String text) {
        this.id = id;
        this.sender = sender;
        this.receiver = receiver;
        this.text = text;
        this.read = false;
    }

    int getId() { return id; }
    String getSender() { return sender; }
    String getReceiver() { return receiver; }
    String getText() { return text; }
    boolean isRead() { return read; }
    void setRead(boolean read) { this.read = read; }
}
