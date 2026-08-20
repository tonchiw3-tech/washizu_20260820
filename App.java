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
    static final String DEFAULT_RECEIVER_NAME = "受け取る人";

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
                redirect(exchange, view.equals("family") ? "/family"
                        : view.equals("patient") ? "/patient?receiver="
                                + URLEncoder.encode(receiver, StandardCharsets.UTF_8) : "/");
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
                    if (original != null && isMessageForPatient(original, original.getReceiver())) {
                        messages.add(new Message(nextId++, original.getReceiver(), original.getSender(), "", mood));
                        // 返信した場合も、元のメッセージは既読にする。
                        original.setRead(true);
                        save();
                        redirect(exchange, "/patient?receiver="
                                + URLEncoder.encode(original.getReceiver(), StandardCharsets.UTF_8)
                                + "&sentTo=" + URLEncoder.encode(original.getSender(), StandardCharsets.UTF_8));
                        return;
                    }
                }
                // 返信元が特定できない気分送信は登録しない。
                redirect(exchange, "/patient");
                return;
            }

            if (path.equals("/reply")) {
                if (!method.equals("POST")) {
                    sendText(exchange, 405, "Method Not Allowed", "text/plain; charset=UTF-8");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int originalId = parseId(formValue(body, "originalId").trim());
                String text = formValue(body, "text").trim();
                String mood = formValue(body, "mood").trim();
                Message original = findMessage(originalId);
                if (original != null && isMessageForPatient(original, original.getReceiver())
                        && (text.isEmpty() ? isAllowedMood(mood) : mood.isEmpty() || isAllowedMood(mood))) {
                    // 返信先は画面で選ばせず、元のメッセージの sender を使う。
                    messages.add(new Message(nextId++, original.getReceiver(), original.getSender(), text, mood));
                    original.setRead(true);
                    save();
                    redirect(exchange, "/patient?receiver="
                            + URLEncoder.encode(original.getReceiver(), StandardCharsets.UTF_8)
                            + "&sentTo=" + URLEncoder.encode(original.getSender(), StandardCharsets.UTF_8));
                    return;
                }
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
                redirect(exchange, queryValue(exchange, "view").equals("patient") ? "/patient?receiver="
                        + URLEncoder.encode(queryValue(exchange, "receiver"), StandardCharsets.UTF_8)
                        : queryValue(exchange, "view").equals("family") ? "/family" : "/");
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
                String receiverName = resolveReceiverName(queryValue(exchange, "receiver"));
                sendHtml(exchange, patientPageHtml(receiverName, queryValue(exchange, "sentTo"), replyId));
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
        String receiverName = resolveReceiverName("");
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='ja'><head>")
                .append("<meta charset='UTF-8'>")
                .append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
                .append("<title>つながるメッセージ - 家族側</title>")
                .append("<style>")
                .append("*{box-sizing:border-box}body{max-width:760px;margin:0 auto;padding:32px 20px;font-family:sans-serif;font-size:18px;line-height:1.7;color:#333;background:#fff}.hero{margin-bottom:28px}.hero h1{margin:0 0 8px;font-size:30px;line-height:1.35;color:#333}.hero p{margin:0;color:#596675}.nav{display:flex;gap:12px;flex-wrap:wrap;margin:0 0 28px}.nav a{display:inline-flex;align-items:center;min-height:52px;padding:12px 18px;border:2px solid #e3e6ea;border-radius:12px;background:#e3e6ea;color:#333;font-weight:bold;text-decoration:none}.nav .current{border-color:#c9ddf4;background:#dce9f8;color:#294c70}.add-form{display:grid;gap:14px;margin-bottom:32px;padding:24px;background:#eef6fc;border:2px solid #dce9f8;border-radius:16px}.add-form label{display:grid;gap:8px;font-size:18px;font-weight:bold}.add-form input,.add-form textarea{width:100%;padding:13px;font:inherit;color:#333;background:#fff;border:2px solid #c9ddf4;border-radius:10px}.add-form input:focus,.add-form textarea:focus{outline:3px solid #f6d2a2;outline-offset:2px}.add-form button,.actions a{display:inline-flex;align-items:center;justify-content:center;min-height:52px;padding:12px 18px;font:inherit;font-weight:bold;border:2px solid #c9ddf4;border-radius:10px;background:#fff;color:#294c70;text-decoration:none;cursor:pointer}.add-form button{min-height:64px;font-size:20px;background:#c9ddf4;border-color:#9fbedf}.message-list{margin:0;padding:0;list-style:none}.message-item{margin-bottom:16px;padding:20px;background:#fff;border:2px solid #e3e6ea;border-radius:14px}.message-item.unread{border-left:6px solid #c9ddf4}.message-head{display:flex;justify-content:space-between;gap:12px;align-items:center}.family-name{font-weight:bold;font-size:1.1rem}.status{font-size:1rem;color:#596675}.read .status{color:#596675}.message-text{margin:10px 0 14px;font-size:18px;white-space:pre-wrap;overflow-wrap:anywhere}.actions{display:flex;gap:12px;justify-content:flex-end;flex-wrap:wrap}.actions a{min-height:56px;padding:10px 16px;font-size:20px;background:#eef6fc}.mood-reply{border-left-color:#f6d2a2!important}.mood-label{color:#7a5a2e;font-weight:bold}")
                .append("</style></head><body><main>")
                .append("<header class='hero'><h1>つながるメッセージ</h1><p>家族からの送信、既読確認、削除を行います。</p></header>")
                .append("<nav class='nav'><a class='current' href='/family'>家族側</a><a href='/patient?receiver=")
                .append(URLEncoder.encode(receiverName, StandardCharsets.UTF_8))
                .append("'>受け取る人側の画面</a></nav>")
                .append("<form class='add-form' method='post' accept-charset='UTF-8' action='/add?view=family'>")
                .append("<label>送る人<input name='sender' placeholder='送る人' autocomplete='name' required></label>")
                .append("<label>受け取る人<input name='receiver' value='")
                .append(htmlEscape(receiverName.isEmpty() ? "" : receiverName))
                .append("' placeholder='受け取る人' autocomplete='name' required></label>")
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
                String messageText = getReplyMessageText(message);
                String mood = getReplyMood(message);
                if (!messageText.isEmpty()) {
                    html.append("<div class='message-text'>").append(htmlEscape(messageText)).append("</div>");
                }
                if (!mood.isEmpty()) {
                    html.append("<div class='mood-label'>今日の気分：")
                            .append(htmlEscape(mood)).append("</div>");
                }
                html.append("<div class='actions'>");
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
    static String patientPageHtml(String receiverName, String sentTo, int replyId) {
        String receiverLabel = receiverName.isEmpty() ? DEFAULT_RECEIVER_NAME : receiverName;
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='ja'><head>")
                .append("<meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>")
                .append("<title>").append(htmlEscape(receiverLabel)).append("の画面</title><style>")
                .append("*{box-sizing:border-box}body{max-width:720px;margin:0 auto;padding:32px 20px;font-family:sans-serif;font-size:20px;line-height:1.7;color:#333;background:#fff}.hero{margin-bottom:28px}.hero h1{font-size:32px;line-height:1.35;margin:0 0 8px;color:#333}.hero p{margin:0;color:#596675}.section{margin:32px 0}.section h2{font-size:27px;line-height:1.4;margin:0 0 16px;color:#333}.incoming{padding:22px;margin:0 0 20px;background:#eef6fc;border:2px solid #dce9f8;border-radius:16px}.incoming.unread{border-left:6px solid #f6d2a2;background:#fff}.incoming p{margin:8px 0 16px;white-space:pre-wrap;overflow-wrap:anywhere}.incoming p:first-child{font-size:18px;color:#596675}.incoming p:nth-of-type(2){font-size:22px;color:#333}.read-button,.reply-button{display:flex;align-items:center;justify-content:center;width:100%;min-height:60px;padding:12px 18px;font-size:20px;font-weight:bold;color:#294c70;border:2px solid #9fbedf;border-radius:12px;text-decoration:none;text-align:center;cursor:pointer}.read-button{background:#eef6fc;border-color:#c9ddf4}.reply-button{margin-top:14px;min-height:64px;background:#c9ddf4;border-color:#8fb3d9}.read-mark{display:flex;align-items:center;min-height:52px;padding:10px 14px;color:#596675;font-size:18px;background:#f5f7f9;border:2px solid #e3e6ea;border-radius:10px}.reply-panel{margin-top:20px;padding:22px;border:2px solid #f6d2a2;border-radius:14px;background:#fffaf4}.reply-panel h3{margin:0 0 8px;font-size:26px;color:#333}.reply-panel p{margin:8px 0 16px}.mood-list{display:grid;gap:16px}.mood-button{width:100%;min-height:68px;padding:14px 18px;font-size:21px;font-weight:bold;color:#333;border:2px solid #aeb8c2;border-radius:12px;cursor:pointer}.mood-button.good{background:#f6d2a2}.mood-button.ok{background:#cfe3f7}.mood-button.lonely{background:#e3e6ea}.free-reply{margin-top:26px;padding-top:22px;border-top:2px solid #dce9f8}.free-reply label{display:block;margin-bottom:8px;font-size:20px;font-weight:bold}.free-reply textarea{display:block;width:100%;min-height:110px;padding:13px;font:inherit;font-size:20px;color:#333;background:#fff;border:2px solid #c9ddf4;border-radius:10px;resize:vertical}.free-reply textarea:focus{outline:3px solid #f6d2a2;outline-offset:2px}.free-reply button{display:flex;align-items:center;justify-content:center;width:100%;min-height:64px;margin-top:14px;padding:12px;font-size:20px;font-weight:bold;color:#294c70;background:#c9ddf4;border:2px solid #8fb3d9;border-radius:12px;cursor:pointer}.sent{padding:18px;margin-bottom:24px;font-size:20px;font-weight:bold;color:#333;background:#eef6fc;border:2px solid #c9ddf4;border-radius:12px}")
                .append("</style><style>.mood-field{margin:22px 0 0;padding:16px;border:0}.mood-field legend{padding:0;font-weight:bold}.mood-field .mood-button{margin-top:10px}.mood-button.selected{outline:5px solid #7c3aed;outline-offset:2px}.reply-error{min-height:1.7em;margin:8px 0 0!important;color:#b42318;font-size:18px}</style>")
                .append("<script>function selectMood(button){var form=button.closest('form');form.querySelector('input[name=mood]').value=button.getAttribute('data-mood');form.querySelectorAll('.mood-button').forEach(function(item){item.classList.remove('selected');});button.classList.add('selected');}function validateReply(form){var text=form.querySelector('textarea[name=text]').value.trim();var mood=form.querySelector('input[name=mood]').value.trim();var error=form.querySelector('.reply-error');if(!text&&!mood){error.textContent='メッセージか今日の気分を選んでください。';return false;}error.textContent='';return true;}</script></head><body><main><header class='hero'><h1>").append(htmlEscape(receiverLabel))
                .append("の画面</h1><p>届いたメッセージを読んだり、今日の気分を選べます。</p></header>");
        if (!sentTo.isEmpty()) {
            html.append("<div class='sent' role='status'>")
                    .append(htmlEscape(sentTo)).append("へ返信しました</div>");
        }
        html.append("<section class='section'><h2>").append(htmlEscape(receiverLabel)).append("宛てのメッセージ</h2>");
        boolean found = false;
        for (Message message : messages) {
            if (!isMessageForPatient(message, receiverName)) {
                continue;
            }
            found = true;
            html.append("<article class='incoming ").append(message.isRead() ? "read" : "unread").append("'>")
                    .append("<p>").append(htmlEscape(message.getSender())).append("からメッセージが届いています</p>")
                    .append("<p>「").append(htmlEscape(message.getText())).append("」</p>");
            if (!message.isRead()) {
                html.append("<a class='read-button' href='/read?id=").append(message.getId())
                        .append("&view=patient&receiver=")
                        .append(URLEncoder.encode(receiverName, StandardCharsets.UTF_8)).append("'>読みました</a>");
            } else {
                html.append("<div class='read-mark'>読みました</div>");
            }
            html.append("<a class='reply-button' href='/patient?reply=").append(message.getId())
                    .append("&receiver=").append(URLEncoder.encode(receiverName, StandardCharsets.UTF_8))
                    .append("'>返事をする</a>");
            if (message.getId() == replyId) {
                html.append("<div class='reply-panel'><h3>")
                        .append(htmlEscape(message.getSender())).append("へ返事をします</h3>")
                        .append(replyForm(message.getId()))
                        .append("</div>");
            }
            html.append("</article>");
        }
        if (!found) {
            html.append("<p>今はメッセージはありません。</p>");
        }
        html.append("</section></main></body></html>");
        return html.toString();
    }

    static String replyForm(int originalId) {
        return "<form class='free-reply reply-form' method='post' accept-charset='UTF-8' action='/reply'"
                + " onsubmit='return validateReply(this)'>"
                + "<input type='hidden' name='originalId' value='" + originalId + "'>"
                + "<label for='reply-text-" + originalId + "'>返事を書く</label>"
                + "<textarea id='reply-text-" + originalId + "' name='text' rows='3' "
                + "placeholder='メッセージを入力してください'></textarea>"
                + "<fieldset class='mood-field'><legend>今日の気分</legend>"
                + "<input type='hidden' name='mood' value=''>"
                + moodButton("😊 元気", "good")
                + moodButton("😐 まあまあ", "ok")
                + moodButton("😢 寂しい", "lonely")
                + "</fieldset>"
                + "<p class='reply-error' aria-live='polite'></p>"
                + "<button class='reply-submit' type='submit'>返信する</button></form>";
    }

    static String moodButton(String mood, String cssClass) {
        return "<button class='mood-button " + cssClass + "' type='button' data-mood='"
                + htmlEscape(mood) + "' onclick='selectMood(this)'>" + htmlEscape(mood) + "</button>";
    }

    static String resolveReceiverName(String requestedName) {
        if (requestedName != null && !requestedName.trim().isEmpty()) {
            return requestedName.trim();
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (!isMoodReply(message) && !message.getReceiver().trim().isEmpty()
                    && !message.getReceiver().equals(message.getSender())) {
                return message.getReceiver();
            }
        }
        return "";
    }

    static boolean isMessageForPatient(Message message, String receiverName) {
        return receiverName != null && !receiverName.isEmpty()
                && receiverName.equals(message.getReceiver())
                && !receiverName.equals(message.getSender())
                && !isMoodReply(message);
    }

    static boolean isMoodReply(Message message) {
        return !message.getSender().isEmpty() && !message.getReceiver().isEmpty()
                && !getReplyMood(message).isEmpty();
    }

    static boolean isAllowedMood(String mood) {
        return mood.equals("😊 元気") || mood.equals("😐 まあまあ") || mood.equals("😢 寂しい");
    }

    static String getReplyMood(Message message) {
        if (!message.getMood().trim().isEmpty()) {
            return message.getMood();
        }
        // 旧形式で保存された気分だけの返信も表示できるようにする。
        return isAllowedMood(message.getText()) ? message.getText() : "";
    }

    static String getReplyMessageText(Message message) {
        // 旧形式の気分だけ返信は本文として重複表示しない。
        return getReplyMood(message).equals(message.getText()) && message.getMood().isEmpty()
                ? "" : message.getText();
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
                    + encode(message.getText()) + "\t" + encode(message.getMood()));
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
                    String mood = "";
                    if (fields.length >= 5) {
                        receiver = decode(fields[3]);
                        text = decode(fields[4]);
                        if (fields.length >= 6) {
                            mood = decode(fields[5]);
                        }
                    } else {
                        // 旧形式（id/read/familyName/text）も読み込めるようにする。
                        receiver = "";
                        text = decode(fields[3]);
                    }
                    Message message = new Message(id, sender, receiver, text, mood);
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
                    .append("\",\"mood\":\"").append(esc(getReplyMood(message)))
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
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
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
    private final String mood;
    private boolean read;

    Message(int id, String sender, String receiver, String text) {
        this(id, sender, receiver, text, "");
    }

    Message(int id, String sender, String receiver, String text, String mood) {
        this.id = id;
        this.sender = sender;
        this.receiver = receiver;
        this.text = text;
        this.mood = mood;
        this.read = false;
    }

    int getId() {
        return id;
    }

    String getSender() {
        return sender;
    }

    String getReceiver() {
        return receiver;
    }

    String getText() {
        return text;
    }

    String getMood() {
        return mood;
    }

    boolean isRead() {
        return read;
    }

    void setRead(boolean read) {
        this.read = read;
    }
}
