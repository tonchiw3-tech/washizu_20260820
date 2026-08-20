import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

public class App {
    // Todoを保存するファイル。java Appを実行した場所に作られます。
    static final String SAVE_FILE = "todos.txt";

    // ★変更 List と、次に振る番号を main の外に置く
    static List<Todo> todos = new ArrayList<>();
    // ★変更 次に振る番号は 1 から始める
    static int nextId = 1;

    public static void main(String[] args) throws Exception {
        // サーバー起動時に、前回保存したTodoを読み込む
        boolean saveFileExists = Files.exists(Path.of(SAVE_FILE));
        load();
        if (!saveFileExists) {
            // 保存ファイルがまだない初回だけ、これまでのサンプルTodoを表示する
            todos.add(new Todo(nextId++, "牛乳を買う"));
            Todo egg = new Todo(nextId++, "卵を買う");
            egg.setDone(true);
            todos.add(egg);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String message;
            String method = exchange.getRequestMethod();
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");

            if (path.equals("/add") && method.equals("POST")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String title = formValue(body, "todo");
                String deadline = formValue(body, "deadline");
                if (!deadline.isEmpty() && !deadline.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    deadline = "";
                }
                if (!title.isEmpty()) {
                    // ★変更 フォームの内容から Todo を1件作って List に追加する
                    todos.add(new Todo(nextId, title, deadline));
                    // ★変更 次の Todo に使う番号を進める
                    nextId++;
                    save();
                }
                exchange.getResponseHeaders().set("Location", "/");
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
                // ★追加 /done?id=数字を受け取り、該当するTodoを完了にする
            } else if (path.equals("/edit") && method.equals("POST")) {
                String query = exchange.getRequestURI().getQuery();
                int id;
                try {
                    if (query == null || !query.startsWith("id=")) {
                        throw new NumberFormatException();
                    }
                    id = Integer.parseInt(query.substring(3));
                } catch (NumberFormatException e) {
                    exchange.getResponseHeaders().set("Location", "/");
                    exchange.sendResponseHeaders(303, -1);
                    exchange.close();
                    return;
                }

                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String newTitle = formValue(body, "title");
                String newDeadline = formValue(body, "deadline");
                if (!newDeadline.isEmpty() && !newDeadline.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    newDeadline = "";
                }

                // idが一致するTodoを1件特定し、title・deadlineを置き換える
                for (Todo todo : todos) {
                    if (todo.getId() == id) {
                        if (!newTitle.isEmpty()) {
                            todo.setTitle(newTitle);
                        }
                        todo.setDeadline(newDeadline);
                        break;
                    }
                }
                save();
                exchange.getResponseHeaders().set("Location", "/");
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
            } else if (path.equals("/edit")) {
                String query = exchange.getRequestURI().getQuery();
                int id;
                try {
                    if (query == null || !query.startsWith("id=")) {
                        throw new NumberFormatException();
                    }
                    id = Integer.parseInt(query.substring(3));
                } catch (NumberFormatException e) {
                    exchange.getResponseHeaders().set("Location", "/");
                    exchange.sendResponseHeaders(303, -1);
                    exchange.close();
                    return;
                }

                Todo editingTodo = null;
                for (Todo todo : todos) {
                    if (todo.getId() == id) {
                        editingTodo = todo;
                        break;
                    }
                }
                if (editingTodo == null) {
                    exchange.getResponseHeaders().set("Location", "/");
                    exchange.sendResponseHeaders(303, -1);
                    exchange.close();
                    return;
                }

                message = "<!DOCTYPE html><html lang='ja'><head>"
                        + "<meta charset='UTF-8'>"
                        + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                        + "<title>Todoを編集</title>"
                        + "<style>"
                        + "*{box-sizing:border-box;}"
                        + "body{max-width:680px;margin:0 auto;padding:32px 20px;font-family:sans-serif;font-size:16px;line-height:1.6;color:#333;}"
                        + ".hero{margin-bottom:24px;}"
                        + ".hero h1{margin:0 0 8px;font-size:24px;}"
                        + ".add-form{display:flex;gap:8px;margin-bottom:20px;}"
                        + ".add-form input{flex:1;min-width:0;padding:10px;font-size:16px;border:1px solid #aaa;border-radius:4px;}"
                        + ".add-form button{padding:10px 14px;font-size:16px;border:1px solid #888;border-radius:4px;background:#f5f5f5;}"
                        + "</style></head><body><main class='container'>"
                        + "<header class='hero'><h1>Todoを編集</h1></header>"
                        + "<form class='add-form' method='post' action='/edit?id=" + id + "'>"
                        + "<input name='title' value='" + htmlEscape(editingTodo.getTitle()) + "' autocomplete='off'>"
                        + "<input type='date' name='deadline' value='" + htmlEscape(editingTodo.getDeadline())
                        + "' aria-label='締め切り日'>"
                        + "<button type='submit'>保存</button></form>"
                        + "<p><a href='/'>一覧に戻る</a></p>"
                        + "</main></body></html>";
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                byte[] responseBody = message.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, responseBody.length);
                exchange.getResponseBody().write(responseBody);
                exchange.getResponseBody().close();
                return;
            } else if (path.equals("/done")) {
                String query = exchange.getRequestURI().getQuery();
                int id;
                try {
                    // ★追加 id= の後ろを数字に変換する
                    if (query == null || !query.startsWith("id=")) {
                        throw new NumberFormatException();
                    }
                    id = Integer.parseInt(query.substring(3));
                } catch (NumberFormatException e) {
                    // ★追加 idがない、または数字でないときは何も変えず一覧へ戻す
                    exchange.getResponseHeaders().set("Location", "/");
                    exchange.sendResponseHeaders(303, -1);
                    exchange.close();
                    return;
                }

                // ★追加 idが一致するTodoを1件だけ完了にする
                for (Todo todo : todos) {
                    if (todo.getId() == id) {
                        todo.setDone(true);
                        break;
                    }
                }
                save();
                exchange.getResponseHeaders().set("Location", "/");
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
                // ★追加 /delete?id=数字を受け取り、該当するTodoを削除する
            } else if (path.equals("/delete")) {
                String query = exchange.getRequestURI().getQuery();
                int id;
                try {
                    // ★追加 id= の後ろを数字に変換する
                    if (query == null || !query.startsWith("id=")) {
                        throw new NumberFormatException();
                    }
                    id = Integer.parseInt(query.substring(3));
                } catch (NumberFormatException e) {
                    // ★追加 idがない、または数字でないときは何も変えず一覧へ戻す
                    exchange.getResponseHeaders().set("Location", "/");
                    exchange.sendResponseHeaders(303, -1);
                    exchange.close();
                    return;
                }

                // ★追加 idが一致するTodoを1件だけListから削除する
                todos.removeIf(todo -> todo.getId() == id); // ★修正 URLのidと一致したTodoだけを削除
                save();
                exchange.getResponseHeaders().set("Location", "/");
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
            } else if (path.equals("/delete-completed") && method.equals("POST")) {
                // done が true（完了済み）の Todo だけを一括削除する
                todos.removeIf(todo -> todo.isDone());
                save();
                exchange.getResponseHeaders().set("Location", "/");
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
            } else if (path.equals("/") || path.equals("/search")) {
                // ★追加 画面全体の見た目を整えるHTMLとCSS
                int completedCount = 0;
                String query = exchange.getRequestURI().getQuery();
                String q = "";
                String filter = "";
                String sort = "";
                if (query != null) {
                    for (String parameter : query.split("&")) {
                        String[] keyValue = parameter.split("=", 2);
                        if (keyValue.length == 2 && keyValue[0].equals("q")) {
                            q = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                        }
                        if (keyValue.length == 2 && keyValue[0].equals("filter")) {
                            filter = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                        }
                        if (keyValue.length == 2 && keyValue[0].equals("sort")) {
                            sort = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                        }
                    }
                }

                List<Todo> visibleTodos = new ArrayList<>();
                for (Todo todo : todos) {
                    if (!q.isEmpty() && !todo.getTitle().contains(q)) {
                        continue;
                    }
                    if (filter.equals("todo") && todo.isDone()) {
                        continue;
                    }
                    if (filter.equals("done") && !todo.isDone()) {
                        continue;
                    }
                    visibleTodos.add(todo);
                }

                if (sort.equals("new")) {
                    visibleTodos.sort(Comparator.comparingInt(Todo::getId).reversed());
                } else if (sort.equals("name")) {
                    visibleTodos.sort(Comparator.comparing(Todo::getTitle));
                }

                String html = "<!DOCTYPE html><html lang='ja'><head>"
                        + "<meta charset='UTF-8'>"
                        + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                        + "<title>わたしのTodo</title>"
                        + "<style>"
                        + "*{box-sizing:border-box;}"
                        + "body{max-width:680px;margin:0 auto;padding:32px 20px;font-family:sans-serif;font-size:16px;line-height:1.6;color:#333;}"
                        + ".hero{margin-bottom:24px;}"
                        + ".hero h1{margin:0 0 8px;font-size:24px;}"
                        + ".hero p{margin:0;}"
                        + ".add-form{display:flex;gap:8px;margin-bottom:20px;}"
                        + ".add-form input{flex:1;min-width:0;padding:10px;font-size:16px;border:1px solid #aaa;border-radius:4px;}"
                        + ".add-form button{padding:10px 14px;font-size:16px;border:1px solid #888;border-radius:4px;background:#f5f5f5;}"
                        + ".search-form{display:flex;gap:8px;margin-bottom:20px;}"
                        + ".search-form input{flex:1;min-width:0;padding:10px;font-size:16px;border:1px solid #aaa;border-radius:4px;}"
                        + ".search-form button{padding:10px 14px;font-size:16px;border:1px solid #888;border-radius:4px;background:#f5f5f5;}"
                        + ".todo-list{margin:0;padding-left:0;list-style:none;}"
                        + ".todo-item{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 0;border-bottom:1px solid #ddd;}"
                        + ".todo-title{font-size:1.05rem;overflow-wrap:anywhere;}"
                        + ".todo-item.done .todo-title{color:#94a3b8;text-decoration:line-through;}"
                        + ".actions{display:flex;gap:10px;white-space:nowrap;}"
                        + ".actions a{margin-left:0;}"
                        + "</style></head><body><main class='container'>"
                        + "<header class='hero'><h1>つながるメッセージ</h1><p>今日やることを、すっきり管理しましょう。</p></header>"
                        + "<form class='add-form' method='post' action='/add'>"
                        + "<input name='todo' placeholder='新しいTodoを入力' autocomplete='off'>"
                        + "<input type='date' name='deadline' aria-label='締め切り日'>"
                        + "<button type='submit'>追加する</button></form>"
                        + "<form class='search-form' method='get' action='/search'>"
                        + "<input name='q' value='" + htmlEscape(q) + "' placeholder='Todoを検索' autocomplete='off'>"
                        + "<button type='submit'>検索</button></form>"
                        + "<p><a href='/'>全部</a> | <a href='/?filter=todo'>未完了</a> | <a href='/?filter=done'>完了</a></p>"
                        + "<form method='post' action='/delete-completed' onsubmit='return confirm(\"完了済みのTodoをすべて削除しますか？\");'>"
                        + "<button type='submit'>完了済みを一括削除</button></form>"
                        + "<p>@@TODO_COUNT@@</p>"
                        + "<ul class='todo-list'>";
                if (visibleTodos.isEmpty()) {
                    if (!q.isEmpty()) {
                        html += "<li>該当するTodoはありません</li>";
                    } else {
                        html += "<li>やることは、いまゼロです</li>";
                    }
                } else {
                    // ★変更 Todo の title を表示し、done のときだけ印を付ける
                    for (Todo todo : visibleTodos) {
                        if (todo.isDone()) {
                            completedCount++;
                        }
                        String mark = "";
                        if (todo.isDone()) {
                            mark = " 〔済〕";
                        }
                        // ★追加 Todoの状態に応じたクラスと、見た目を整えたリンクを付ける
                        String itemClass = todo.isDone() ? " done" : "";
                        String deadline = todo.getDeadline().isEmpty() ? "締め切りなし" : todo.getDeadline();
                        html += "<li class='todo-item" + itemClass + "'>"
                                + "<span class='todo-title'>" + todo.getTitle()
                                + " <span class='deadline'>締め切り: " + deadline + "</span>"
                                + mark
                                + "</span>"
                                + "<span class='actions'>"
                                + "<a class='edit-link' href='/edit?id=" + todo.getId() + "'>編集</a>"
                                + "<a class='done-link' href='/done?id=" + todo.getId() + "'>完了</a>"
                                + "<a class='delete-link' href='/delete?id=" + todo.getId() + "'>削除</a>"
                                + "</span></li>";
                    }
                }
                // ★追加 一覧画面のHTMLを閉じる
                html = html.replace("@@TODO_COUNT@@", visibleTodos.size() + "件中" + completedCount + "件 完了");
                html += "</ul></main></body></html>";
                message = html;
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            } else {
                // ★変更 未使用の /hello・/bye ルーティングを削除
                message = "ページが見つかりません";
            }
            byte[] responseBody = message.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.getResponseBody().close();
        });

        // GET /api/todos 専用の入口を追加する
        server.createContext("/api/todos", exchange -> {
            // GET 以外のメソッドは受け付けない
            if (!"GET".equals(exchange.getRequestMethod())) {
                // 許可されていないメソッドであることを返す
                exchange.sendResponseHeaders(405, -1);
                // リクエストを閉じる
                exchange.close();
                // 以降の処理を終了する
                return;
            }
            // Todo 一覧を JSON 文字列に変換する
            byte[] responseBody = todosToJson().getBytes(StandardCharsets.UTF_8);
            // Content-Type に charset を付けずに設定する
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            // JSON のバイト数を指定して成功レスポンスを返す
            exchange.sendResponseHeaders(200, responseBody.length);
            // JSON 本文を書き込む
            exchange.getResponseBody().write(responseBody);
            // レスポンスを閉じる
            exchange.getResponseBody().close();
        });

        server.start();
        System.out.println("サーバー起動: http://localhost:8080 (止めるときは Ctrl+C)");
    }

    // 全 Todo を JSON 配列に変換する
    // Todo一覧をファイルに保存する
    static void save() {
        List<String> lines = new ArrayList<>();
        for (Todo todo : todos) {
            // 文字列はBase64（安全に文字列化する方法）にして、タブ区切りで保存する
            lines.add(todo.getId()
                    + "\t" + todo.isDone()
                    + "\t" + encode(todo.getTitle())
                    + "\t" + encode(todo.getDeadline()));
        }

        try {
            Files.write(Path.of(SAVE_FILE), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Todoの保存に失敗しました: " + e.getMessage());
        }
    }

    // サーバー起動時に、保存ファイルからTodo一覧を読み込む
    static void load() {
        Path savePath = Path.of(SAVE_FILE);
        if (!Files.exists(savePath)) {
            // 初回起動など、保存ファイルがない場合は空の一覧のまま始める
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
                    boolean done = Boolean.parseBoolean(fields[1]);
                    // 旧形式の5項目目（カテゴリ）は読み飛ばし、締め切り日は fields[3] から読み込む
                    Todo todo = new Todo(id, decode(fields[2]), decode(fields[3]));
                    todo.setDone(done);
                    todos.add(todo);
                    maxId = Math.max(maxId, id);
                } catch (IllegalArgumentException e) {
                    // 壊れた行があっても、他のTodoの読み込みは続ける
                }
            }
            nextId = maxId + 1;
        } catch (IOException e) {
            System.err.println("Todoの読み込みに失敗しました: " + e.getMessage());
        }
    }

    static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    static String todosToJson() {
        // JSON 配列の組み立てを開始する
        StringBuilder json = new StringBuilder("[");
        // Todo を順番に変換する
        for (int i = 0; i < todos.size(); i++) {
            // 2件目以降の前にカンマを追加する
            if (i > 0) {
                json.append(',');
            }
            // 現在の Todo を取得する
            Todo todo = todos.get(i);
            // title と done を持つ JSON オブジェクトを追加する
            json.append("{\"title\":\"")
                    .append(esc(todo.getTitle()))
                    .append("\",\"done\":")
                    .append(todo.isDone())
                    .append('}');
        }
        // JSON 配列を閉じて返す
        return json.append(']').toString();
    }

    // JSON 文字列内の特殊文字をエスケープする
    static String esc(String value) {
        // エスケープ済み文字列を作成する
        StringBuilder escaped = new StringBuilder();
        // 文字列を1文字ずつ処理する
        for (int i = 0; i < value.length(); i++) {
            // 現在の文字を取得する
            char c = value.charAt(i);
            // 特殊文字をJSON形式へ変換する
            switch (c) {
                // 引用符をエスケープする
                case '"':
                    escaped.append("\\\"");
                    break;
                // バックスラッシュをエスケープする
                case '\\':
                    escaped.append("\\\\");
                    break;
                // バックスペースをエスケープする
                case '\b':
                    escaped.append("\\b");
                    break;
                // 改ページをエスケープする
                case '\f':
                    escaped.append("\\f");
                    break;
                // 改行をエスケープする
                case '\n':
                    escaped.append("\\n");
                    break;
                // 復帰をエスケープする
                case '\r':
                    escaped.append("\\r");
                    break;
                // タブをエスケープする
                case '\t':
                    escaped.append("\\t");
                    break;
                // その他の制御文字を処理する
                default:
                    // JSON でそのまま書けない制御文字を処理する
                    if (c < 0x20) {
                        // Unicode エスケープの接頭辞を追加する
                        escaped.append("\\u");
                        // 16進数表現を取得する
                        String hex = Integer.toHexString(c);
                        // 4桁になるまで0を補う
                        for (int j = hex.length(); j < 4; j++) {
                            escaped.append('0');
                        }
                        // 16進数表現を追加する
                        escaped.append(hex);
                    } else {
                        // 特別な処理が不要な文字をそのまま追加する
                        escaped.append(c);
                    }
                    break;
            }
        }
        // エスケープ済み文字列を返す
        return escaped.toString();
    }

    // フォームの項目を名前で取り出す
    static String formValue(String body, String name) {
        for (String parameter : body.split("&", -1)) {
            String[] keyValue = parameter.split("=", 2);
            if (keyValue.length == 2 && name.equals(URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8))) {
                return URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    // 編集フォームのvalue属性に安全に表示するためのHTMLエスケープ
    static String htmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&#39;");
    }

}

// ★変更 Todo を表すクラスを追加
class Todo {
    private final int id;
    private String title;
    private String deadline;
    private boolean done;

    // ★変更 Todo は done=false で初期化する
    Todo(int id, String title) {
        this(id, title, "");
    }

    Todo(int id, String title, String deadline) {
        this.id = id;
        this.title = title;
        this.deadline = deadline;
        this.done = false;
    }

    // ★変更 id を読み出すメソッド
    int getId() {
        return id;
    }

    // ★変更 title を読み出すメソッド
    String getTitle() {
        return title;
    }

    // titleを新しい文字列に置き換えるメソッド
    void setTitle(String title) {
        this.title = title;
    }

    // 締め切り日を読み出すメソッド
    String getDeadline() {
        return deadline;
    }

    // 締め切り日を書き換えるメソッド
    void setDeadline(String deadline) {
        this.deadline = deadline;
    }

    // ★変更 done を読み出すメソッド
    boolean isDone() {
        return done;
    }

    // ★変更 done を書き換えるメソッド
    void setDone(boolean done) {
        this.done = done;
    }
}
