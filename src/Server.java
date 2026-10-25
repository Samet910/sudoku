import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class Server {
    static final Map<String, Room> rooms = new ConcurrentHashMap<>();
    static final Random rnd = new Random();
    static final Map<String, Integer> CLUES = Map.of("easy", 40, "medium", 34, "advanced", 30, "hard", 27, "expert", 24);

    static class Player { String name; int slot, sel = -1, count; long last; }

    static class Room {
        String id, diff, puzzle, solution; boolean shared;
        char[] grid; int[] owner = new int[81]; int[] sharedNotes = new int[81];
        Map<String, int[]> privNotes = new HashMap<>();
        Map<String, Player> players = new LinkedHashMap<>();
        int mistakes, total; boolean won; long start, end, last = System.currentTimeMillis();

        void reset() {
            String[] g = take(diff);
            puzzle = g[0]; solution = g[1]; grid = puzzle.toCharArray();
            Arrays.fill(owner, -1); sharedNotes = new int[81]; privNotes.clear();
            mistakes = 0; won = false; start = System.currentTimeMillis();
            total = (int) puzzle.chars().filter(c -> c == '0').count();
            for (Player p : players.values()) p.count = 0;
        }
        int[] notesFor(String pid) { return shared ? sharedNotes : privNotes.computeIfAbsent(pid, k -> new int[81]); }
        void place(int cell, int v, Player p) {
            grid[cell] = (char) ('0' + v); owner[cell] = p.slot; p.count++;
            clear(sharedNotes, cell, v);
            for (int[] n : privNotes.values()) clear(n, cell, v);
            if (new String(grid).equals(solution)) { won = true; end = System.currentTimeMillis(); }
        }
        static void clear(int[] n, int cell, int v) {
            n[cell] = 0; int r = cell / 9, c = cell % 9;
            for (int i = 0; i < 81; i++)
                if (i / 9 == r || i % 9 == c || (i / 9 / 3 == r / 3 && i % 9 / 3 == c / 3)) n[i] &= ~(1 << v);
        }
        String json(String pid, boolean wrong) {
            long now = System.currentTimeMillis();
            StringBuilder s = new StringBuilder("{");
            s.append("\"id\":\"").append(id).append("\",\"diff\":\"").append(diff).append("\",\"shared\":").append(shared)
             .append(",\"puzzle\":\"").append(puzzle).append("\",\"grid\":\"").append(new String(grid))
             .append("\",\"mistakes\":").append(mistakes).append(",\"won\":").append(won).append(",\"wrong\":").append(wrong)
             .append(",\"total\":").append(total).append(",\"elapsed\":").append((won ? end : now) - start)
             .append(",\"owner\":").append(Arrays.toString(owner))
             .append(",\"notes\":").append(Arrays.toString(notesFor(pid)));
            Player me = players.get(pid);
            s.append(",\"me\":").append(me == null ? -1 : me.slot).append(",\"players\":[");
            boolean first = true;
            for (Player p : players.values()) if (now - p.last < 10000) {
                if (!first) s.append(','); first = false;
                s.append("{\"n\":\"").append(p.name).append("\",\"s\":").append(p.slot).append(",\"sel\":").append(p.sel)
                 .append(",\"c\":").append(p.count).append("}");
            }
            return s.append("]}").toString();
        }
    }

    // ---------- Sudoku-Generator ----------
    static int mask(int[] b, int i) {
        int used = 0, r = i / 9, c = i % 9;
        for (int k = 0; k < 9; k++) { used |= 1 << b[r * 9 + k]; used |= 1 << b[k * 9 + c]; }
        int br = r / 3 * 3, bc = c / 3 * 3;
        for (int a = 0; a < 3; a++) for (int d = 0; d < 3; d++) used |= 1 << b[(br + a) * 9 + bc + d];
        return ~used & 0x3FE;
    }
    static boolean fill(int[] b) {
        int best = -1, bm = 0, bc = 10;
        for (int i = 0; i < 81; i++) if (b[i] == 0) {
            int m = mask(b, i), c = Integer.bitCount(m);
            if (c == 0) return false;
            if (c < bc) { bc = c; best = i; bm = m; }
        }
        if (best < 0) return true;
        List<Integer> vs = new ArrayList<>();
        for (int v = 1; v <= 9; v++) if ((bm >> v & 1) != 0) vs.add(v);
        Collections.shuffle(vs, rnd);
        for (int v : vs) { b[best] = v; if (fill(b)) return true; }
        b[best] = 0;
        return false;
    }
    static int count(int[] b, int limit, int[] budget) {
        if (--budget[0] < 0) return limit; // zu aufwendig: als "nicht eindeutig" werten
        int best = -1, bm = 0, bc = 10;
        for (int i = 0; i < 81; i++) if (b[i] == 0) {
            int m = mask(b, i), c = Integer.bitCount(m);
            if (c == 0) return 0;
            if (c < bc) { bc = c; best = i; bm = m; }
        }
        if (best < 0) return 1;
        int n = 0;
        for (int v = 1; v <= 9; v++) if ((bm >> v & 1) != 0) {
            b[best] = v; n += count(b, limit - n, budget);
            if (n >= limit) break;
        }
        b[best] = 0;
        return n;
    }
    static String[] generate(String diff, long maxMs) {
        int target = CLUES.get(diff);
        int[] sol = new int[81]; fill(sol);
        int[] p = sol.clone();
        List<Integer> pos = new ArrayList<>();
        for (int i = 0; i < 81; i++) pos.add(i);
        Collections.shuffle(pos, rnd);
        int clues = 81; long end = System.currentTimeMillis() + maxMs;
        for (int i : pos) {
            if (clues <= target || System.currentTimeMillis() > end) break;
            int old = p[i]; p[i] = 0;
            if (count(p, 2, new int[]{15000}) != 1) p[i] = old; else clues--;
        }
        return new String[]{str(p), str(sol)};
    }
    // ---------- Vorrat: Rätsel werden im Hintergrund vorbereitet, neue Spiele starten sofort ----------
    static final Map<String, BlockingQueue<String[]>> pool = new ConcurrentHashMap<>();
    static String[] take(String d) { String[] g = pool.get(d).poll(); return g != null ? g : generate(d, 1500); }
    static void startPool() {
        for (String d : CLUES.keySet()) pool.put(d, new ArrayBlockingQueue<>(3));
        Thread t = new Thread(() -> {   // nur EIN Hintergrund-Thread, schont kleine Server
            while (true) {
                try {
                    for (String d : List.of("easy", "medium", "advanced", "hard", "expert")) {
                        BlockingQueue<String[]> q = pool.get(d);
                        if (q.remainingCapacity() > 0) q.offer(generate(d, 3000));
                    }
                    Thread.sleep(200);
                } catch (InterruptedException e) { return; }
            }
        });
        t.setDaemon(true); t.start();
    }
    static String str(int[] b) { StringBuilder s = new StringBuilder(); for (int v : b) s.append(v); return s.toString(); }

    // ---------- HTTP ----------
    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(body); }
    }
    static void json(HttpExchange ex, int code, String s) throws IOException { send(ex, code, "application/json", s.getBytes(StandardCharsets.UTF_8)); }
    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) for (String kv : q.split("&")) {
            String[] a = kv.split("=", 2);
            if (a.length == 2) m.put(a[0], URLDecoder.decode(a[1], StandardCharsets.UTF_8));
        }
        return m;
    }

    static List<Integer> cells(Map<String, String> q, int single) {
        List<Integer> l = new ArrayList<>();
        String cs = q.get("cells");
        if (cs != null) {
            for (String x : cs.split(",")) {
                try { int c = Integer.parseInt(x.trim()); if (c >= 0 && c < 81 && !l.contains(c)) l.add(c); } catch (Exception e) { }
            }
        } else if (single >= 0 && single < 81) l.add(single);
        return l;
    }

    static void api(HttpExchange ex) throws IOException {
        String[] parts = ex.getRequestURI().getPath().split("/"); // "", "api", ...
        Map<String, String> q = query(ex);
        try {
            if (parts.length == 3 && parts[2].equals("new")) {
                String d = q.getOrDefault("diff", "easy");
                if (!CLUES.containsKey(d)) { json(ex, 400, "{\"error\":\"diff\"}"); return; }
                Room r = new Room();
                r.id = Long.toString(rnd.nextLong() & 0xFFFFFFFFL, 36).toUpperCase();
                r.diff = d; r.shared = !"private".equals(q.get("notes"));
                long t0 = System.currentTimeMillis();
                r.reset();
                System.out.println("Neues Spiel " + d + " in " + (System.currentTimeMillis() - t0) + " ms");
                rooms.put(r.id, r);
                json(ex, 200, "{\"id\":\"" + r.id + "\"}");
                return;
            }
            if (parts.length >= 4 && parts[2].equals("room")) {
                Room r = rooms.get(parts[3].toUpperCase());
                if (r == null) { json(ex, 404, "{\"error\":\"Raum nicht gefunden\"}"); return; }
                synchronized (r) {
                    long now = System.currentTimeMillis();
                    r.last = now;
                    String pid = q.getOrDefault("p", "");
                    Player me = null;
                    if (!pid.isEmpty()) {
                        me = r.players.get(pid);
                        if (me == null) {
                            me = new Player(); me.slot = r.players.size();
                            String nm = q.getOrDefault("name", "").replaceAll("[^\\p{L}\\p{N} _-]", "");
                            if (nm.length() > 16) nm = nm.substring(0, 16);
                            me.name = nm.isEmpty() ? "Spieler" + (me.slot + 1) : nm;
                            r.players.put(pid, me);
                        }
                        me.last = now;
                        me.sel = Integer.parseInt(q.getOrDefault("sel", "-1"));
                    }
                    String action = parts.length == 5 ? parts[4] : "";
                    boolean wrong = false;
                    if (me != null && ex.getRequestMethod().equals("POST") && !action.isEmpty()) {
                        int cell = Integer.parseInt(q.getOrDefault("cell", "-1")), val = Integer.parseInt(q.getOrDefault("val", "0"));
                        boolean cellOk = cell >= 0 && cell < 81;
                        switch (action) {
                            case "move" -> {
                                if (cellOk && val >= 1 && val <= 9 && !r.won && r.grid[cell] == '0' && r.puzzle.charAt(cell) == '0') {
                                    if (r.solution.charAt(cell) - '0' == val) r.place(cell, val, me);
                                    else { r.mistakes++; wrong = true; }
                                }
                            }
                            case "note" -> {
                                if (val >= 1 && val <= 9 && !r.won) for (int c : cells(q, cell)) if (r.grid[c] == '0') {
                                    int[] n = r.notesFor(pid);
                                    String on = q.get("on");
                                    if (on == null) n[c] ^= 1 << val;
                                    else if (on.equals("1")) n[c] |= 1 << val;
                                    else n[c] &= ~(1 << val);
                                }
                            }
                            case "erase" -> {
                                for (int c : cells(q, cell)) if (r.grid[c] == '0') r.notesFor(pid)[c] = 0;
                            }
                            case "restart" -> {
                                String nd = q.get("diff");
                                if (nd != null && CLUES.containsKey(nd)) r.diff = nd;
                                r.reset();
                            }
                            default -> { }
                        }
                    }
                    json(ex, 200, r.json(pid, wrong));
                }
                return;
            }
            json(ex, 404, "{\"error\":\"unknown\"}");
        } catch (Exception e) { json(ex, 400, "{\"error\":\"bad request\"}"); }
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        startPool();
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.setExecutor(Executors.newFixedThreadPool(32));
        s.createContext("/api/", Server::api);
        s.createContext("/", ex -> send(ex, 200, "text/html", Files.readAllBytes(Path.of("public/index.html"))));
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() ->
            rooms.values().removeIf(r -> System.currentTimeMillis() - r.last > 24 * 3600_000L), 1, 1, TimeUnit.HOURS);
        s.start();
        System.out.println("Sudoku läuft auf Port " + port);
    }
}
