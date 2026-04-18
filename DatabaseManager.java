import java.sql.*;

public class DatabaseManager {

    private static final String DB_URL = "jdbc:sqlite:lexiguess.db";

    private Connection conn;

    public void connect() {
        try {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection(DB_URL);

            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA foreign_keys=ON;");
            }

            createTables();
            System.out.println("[DB] Connected to " + DB_URL);
        } catch (ClassNotFoundException e) {
            System.err.println("[DB] sqlite-jdbc driver not found. "
                + "Add sqlite-jdbc.jar to your classpath.");
        } catch (SQLException e) {
            System.err.println("[DB] Connection error: " + e.getMessage());
        }
        // ← REMOVE the stray stmt.execute(...) line that was here
    }

    /** Cleanly closes the database connection. Call on application exit. */
    public void disconnect() {
        if (conn != null) {
            try {
                conn.close();
                System.out.println("[DB] Connection closed.");
            } catch (SQLException e) {
                System.err.println("[DB] Error closing connection: " + e.getMessage());
            }
        }
    }

    public boolean isConnected() {
        try {
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    private void createTables() throws SQLException {
        try (Statement st = conn.createStatement()) {

            st.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    name       TEXT    NOT NULL UNIQUE COLLATE NOCASE,
                    created_at TEXT    NOT NULL DEFAULT (datetime('now'))
                );
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS leaderboard (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_id   INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
                    score       INTEGER NOT NULL DEFAULT 0,
                    stage       INTEGER NOT NULL DEFAULT 1,
                    recorded_at TEXT    NOT NULL DEFAULT (datetime('now'))
                );
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS level_progress (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_id        INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
                    difficulty       TEXT    NOT NULL CHECK(difficulty IN ('EASY','MEDIUM','HARD')),
                    highest_unlocked INTEGER NOT NULL DEFAULT 1,
                    UNIQUE(player_id, difficulty)
                );
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS session_scores (
                    name  TEXT PRIMARY KEY COLLATE NOCASE,
                    score INTEGER NOT NULL DEFAULT 0,
                    stage INTEGER NOT NULL DEFAULT 1
                );
            """);
        }
        System.out.println("[DB] Tables verified / created.");
    }

    public int getOrCreatePlayer(String name) {
        if (!isConnected()) return -1;
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM players WHERE name = ? COLLATE NOCASE")) {
                ps.setString(1, name);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getInt("id");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players (name) VALUES (?)")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }

            try (Statement st = conn.createStatement()) {
                ResultSet rs = st.executeQuery("SELECT last_insert_rowid()");
                if (rs.next()) {
                    int id = rs.getInt(1);
                    System.out.println("[DB] New player '" + name + "' created with id=" + id);
                    return id;
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] getOrCreatePlayer error: " + e.getMessage());
        }
        return -1;
    }

    public void saveScore(String playerName, int score, int stage) {
        if (!isConnected()) return;
        int playerId = getOrCreatePlayer(playerName);
        if (playerId == -1) return;

        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, score FROM leaderboard WHERE player_id = ?")) {
                ps.setInt(1, playerId);
                ResultSet rs = ps.executeQuery();

                if (rs.next()) {
                    int existingScore = rs.getInt("score");
                    if (score > existingScore) {
                        try (PreparedStatement upd = conn.prepareStatement(
                                "UPDATE leaderboard SET score=?, stage=?, recorded_at=datetime('now') WHERE player_id=?")) {
                            upd.setInt(1, score);
                            upd.setInt(2, stage);
                            upd.setInt(3, playerId);
                            upd.executeUpdate();
                            System.out.println("[DB] Leaderboard updated for '" + playerName
                                + "': " + existingScore + " -> " + score);
                        }
                    }
                } else {
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO leaderboard (player_id, score, stage) VALUES (?,?,?)")) {
                        ins.setInt(1, playerId);
                        ins.setInt(2, score);
                        ins.setInt(3, stage);
                        ins.executeUpdate();
                        System.out.println("[DB] Leaderboard entry created for '"
                            + playerName + "' score=" + score);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] saveScore error: " + e.getMessage());
        }
    }

    /**
     * Returns the top {@code limit} leaderboard entries sorted by score descending.
     *
     * @param limit  Maximum number of rows to return (e.g. 10).
     * @return       Array of {@link LeaderboardRow}; empty array on error.
     */
    public LeaderboardRow[] getTopScores(int limit) {
        if (!isConnected()) return new LeaderboardRow[0];
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT p.name, l.score, l.stage, l.recorded_at
                FROM leaderboard l
                JOIN players p ON p.id = l.player_id
                ORDER BY l.score DESC
                LIMIT ?
            """)) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();

            java.util.List<LeaderboardRow> rows = new java.util.ArrayList<>();
            while (rs.next()) {
                rows.add(new LeaderboardRow(
                    rs.getString("name"),
                    rs.getInt("score"),
                    rs.getInt("stage"),
                    rs.getString("recorded_at")));
            }
            return rows.toArray(new LeaderboardRow[0]);
        } catch (SQLException e) {
            System.err.println("[DB] getTopScores error: " + e.getMessage());
            return new LeaderboardRow[0];
        }
    }

    /** Wipes all leaderboard rows (keeps player records intact). */
    public void clearLeaderboard() {
        if (!isConnected()) return;
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM leaderboard;");
            System.out.println("[DB] Leaderboard cleared.");
        } catch (SQLException e) {
            System.err.println("[DB] clearLeaderboard error: " + e.getMessage());
        }
    }

    public int getProgress(String playerName, String difficulty) {
        if (!isConnected()) return 1;
        int playerId = getOrCreatePlayer(playerName);
        if (playerId == -1) return 1;

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT highest_unlocked FROM level_progress WHERE player_id=? AND difficulty=?")) {
            ps.setInt(1, playerId);
            ps.setString(2, difficulty.toUpperCase());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("highest_unlocked");
        } catch (SQLException e) {
            System.err.println("[DB] getProgress error: " + e.getMessage());
        }
        return 1;
    }

    public void saveProgress(String playerName, String difficulty, int highestUnlocked) {
        if (!isConnected()) return;
        int playerId = getOrCreatePlayer(playerName);
        if (playerId == -1) return;

        try {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO level_progress (player_id, difficulty, highest_unlocked)
                    VALUES (?, ?, ?)
                    ON CONFLICT(player_id, difficulty) DO UPDATE
                        SET highest_unlocked = MAX(excluded.highest_unlocked, highest_unlocked)
                """)) {
                ps.setInt(1, playerId);
                ps.setString(2, difficulty.toUpperCase());
                ps.setInt(3, highestUnlocked);
                ps.executeUpdate();
                System.out.println("[DB] Progress saved: " + playerName
                    + " / " + difficulty + " -> level " + highestUnlocked);
            }
        } catch (SQLException e) {
            System.err.println("[DB] saveProgress error: " + e.getMessage());
        }
    }

    public java.util.Map<String, Integer> loadAllProgress(String playerName) {
        java.util.Map<String, Integer> map = new java.util.HashMap<>();
        map.put("EASY",   getProgress(playerName, "EASY"));
        map.put("MEDIUM", getProgress(playerName, "MEDIUM"));
        map.put("HARD",   getProgress(playerName, "HARD"));
        return map;
    }

    public static class LeaderboardRow {
        public final String name;
        public final int    score;
        public final int    stage;
        public final String recordedAt;

        LeaderboardRow(String name, int score, int stage, String recordedAt) {
            this.name       = name;
            this.score      = score;
            this.stage      = stage;
            this.recordedAt = recordedAt;
        }

        @Override
        public String toString() {
            return name + "  score=" + score + "  stage=" + stage + "  @" + recordedAt;
        }
    }

    public void saveSessionScore(String name, int score, int stage) {
        try {
            String sql = "INSERT INTO session_scores(name, score, stage) VALUES(?,?,?) " +
                        "ON CONFLICT(name) DO UPDATE SET score=excluded.score, stage=excluded.stage";
            java.sql.PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, name); ps.setInt(2, score); ps.setInt(3, stage);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("saveSessionScore error: " + e.getMessage());
        }
    }

    public int[] loadSessionScore(String name) {
        try {
            java.sql.PreparedStatement ps = conn.prepareStatement(
                "SELECT score, stage FROM session_scores WHERE name=?");
            ps.setString(1, name);
            java.sql.ResultSet rs = ps.executeQuery();
            if (rs.next()) return new int[]{rs.getInt("score"), rs.getInt("stage")};
        } catch (Exception e) {
            System.err.println("loadSessionScore error: " + e.getMessage());
        }
        return new int[]{0, 1};
    }
}