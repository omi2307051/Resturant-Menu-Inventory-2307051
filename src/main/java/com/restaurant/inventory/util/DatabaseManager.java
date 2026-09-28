package com.restaurant.inventory.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * DATABASE INTEGRATION.
 *
 * Talks to a small local SQLite database file ("restaurant_inventory.db", created next to
 * where the app is run from). SQLite needs no separate server/install - the JDBC driver
 * (org.xerial:sqlite-jdbc, see pom.xml) opens the .db file directly.
 *
 * Tables:
 *   ingredients(name TEXT PRIMARY KEY, unit TEXT, quantity REAL, min_level REAL)
 *   staff(id INTEGER PRIMARY KEY AUTOINCREMENT, name, gender, skill_level, country,
 *         date_of_birth TEXT, hobbies, photo_file, role)
 *   orders(id INTEGER PRIMARY KEY AUTOINCREMENT, summary TEXT, total REAL, placed_at TEXT,
 *          customer_name TEXT, payment_method TEXT, items TEXT, bill_text TEXT)
 *
 * "staff" and "ingredients" are the two tables with a real relationship in this app:
 * every RecipeLine links a Dish (in memory) to an Ingredient row, and a placed order
 * (see "orders") always corresponds to ingredient rows whose quantity just changed -
 * i.e. orders/ingredients are related through the recipe, and each row is what CRUD
 * (Create/Read/Update/Delete) operates on from the Inventory, Staff and Orders tabs.
 *
 * Every method fails soft: if the database file cannot be created or written to (for
 * example, no write permission), the error is printed to the console and the app keeps
 * working from its in-memory lists only - the database is a persistence *bonus*, never
 * a hard requirement to run the app.
 */
public final class DatabaseManager {

    private static final String DB_URL = "jdbc:sqlite:restaurant_inventory.db";
    private static volatile boolean available = true;

    private DatabaseManager() { }

    /** True once the database has failed once (so callers can stop retrying noisily). */
    public static boolean isAvailable() { return available; }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    /** Creates the tables if they do not already exist. Call once at startup. */
    public static void initSchema() {
        String ingredientsSql = "CREATE TABLE IF NOT EXISTS ingredients (" +
                "name TEXT PRIMARY KEY, unit TEXT NOT NULL, quantity REAL NOT NULL, min_level REAL NOT NULL)";
        String staffSql = "CREATE TABLE IF NOT EXISTS staff (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, gender TEXT, skill_level TEXT, " +
                "country TEXT, date_of_birth TEXT, hobbies TEXT, photo_file TEXT, role TEXT)";
        String ordersSql = "CREATE TABLE IF NOT EXISTS orders (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, summary TEXT NOT NULL, total REAL NOT NULL, placed_at TEXT NOT NULL, " +
                "customer_name TEXT, payment_method TEXT, items TEXT, bill_text TEXT)";

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(ingredientsSql);
            st.execute(staffSql);
            st.execute(ordersSql);
            available = true;
        } catch (SQLException e) {
            available = false;
            System.err.println("[DatabaseManager] Could not initialise database - running without persistence. " + e.getMessage());
            return;
        }

        // MIGRATION: older database files created before the "role" column existed won't have
        // it yet ("CREATE TABLE IF NOT EXISTS" only applies to brand-new tables) - add it here,
        // ignoring the error SQLite raises if the column is already present.
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE staff ADD COLUMN role TEXT");
        } catch (SQLException ignored) {
            // column already exists - nothing to do
        }

        // MIGRATION: older "orders" tables have no customer / payment / bill columns - add them.
        for (String column : new String[]{"customer_name TEXT", "payment_method TEXT", "items TEXT", "bill_text TEXT"}) {
            try (Connection conn = connect(); Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE orders ADD COLUMN " + column);
            } catch (SQLException ignored) {
                // column already exists - nothing to do
            }
        }

        // BACKFILL: any row written before the "role" column existed now has role = NULL
        // (ADD COLUMN does not fill in old rows). Give those legacy rows a sane default
        // instead of showing a blank/"null" Role everywhere in the UI.
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("UPDATE staff SET role = 'Server' WHERE role IS NULL OR role = ''");
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Could not backfill legacy 'role' values: " + e.getMessage());
        }
    }

    // ======================================================================= INGREDIENTS (CRUD)

    /** CREATE or UPDATE (SQLite "INSERT OR REPLACE") one ingredient row. */
    public static void upsertIngredient(String name, String unit, double quantity, double minLevel) {
        if (!available) return;
        String sql = "INSERT INTO ingredients(name, unit, quantity, min_level) VALUES (?,?,?,?) " +
                "ON CONFLICT(name) DO UPDATE SET unit = excluded.unit, quantity = excluded.quantity, min_level = excluded.min_level";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, unit);
            ps.setDouble(3, quantity);
            ps.setDouble(4, minLevel);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] upsertIngredient failed: " + e.getMessage());
        }
    }

    /** READ: the persisted quantity for an ingredient, or null if it has never been saved. */
    public static Double readIngredientQuantity(String name) {
        if (!available) return null;
        String sql = "SELECT quantity FROM ingredients WHERE name = ?";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : null;
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] readIngredientQuantity failed: " + e.getMessage());
            return null;
        }
    }

    /** READ: every ingredient row currently in the database, as {name, unit, quantity, minLevel}. */
    public static List<Object[]> readAllIngredients() {
        List<Object[]> rows = new ArrayList<>();
        if (!available) return rows;
        String sql = "SELECT name, unit, quantity, min_level FROM ingredients ORDER BY name";
        try (Connection conn = connect(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new Object[]{ rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4) });
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] readAllIngredients failed: " + e.getMessage());
        }
        return rows;
    }

    /** DELETE one ingredient row. */
    public static void deleteIngredient(String name) {
        if (!available) return;
        String sql = "DELETE FROM ingredients WHERE name = ?";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] deleteIngredient failed: " + e.getMessage());
        }
    }

    // ======================================================================= STAFF (CRUD)

    /** CREATE a staff row, returns the generated id (or -1 if the database is unavailable). */
    public static int insertStaff(String name, String gender, String skillLevel, String country,
                                   String dateOfBirthIso, String hobbies, String photoFile, String role) {
        if (!available) return -1;
        String sql = "INSERT INTO staff(name, gender, skill_level, country, date_of_birth, hobbies, photo_file, role) " +
                "VALUES (?,?,?,?,?,?,?,?)";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, gender);
            ps.setString(3, skillLevel);
            ps.setString(4, country);
            ps.setString(5, dateOfBirthIso);
            ps.setString(6, hobbies);
            ps.setString(7, photoFile);
            ps.setString(8, role);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] insertStaff failed: " + e.getMessage());
            return -1;
        }
    }

    /** READ: every staff row, as {id, name, gender, skillLevel, country, dateOfBirthIso, hobbies, photoFile, role}. */
    public static List<Object[]> readAllStaff() {
        List<Object[]> rows = new ArrayList<>();
        if (!available) return rows;
        String sql = "SELECT id, name, gender, skill_level, country, date_of_birth, hobbies, photo_file, role FROM staff ORDER BY id";
        try (Connection conn = connect(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new Object[]{ rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9) });
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] readAllStaff failed: " + e.getMessage());
        }
        return rows;
    }

    /** UPDATE: change just the skill level of one staff member (demonstrates the "U" in CRUD). */
    public static void updateStaffSkill(int id, String newSkillLevel) {
        if (!available || id < 0) return;
        String sql = "UPDATE staff SET skill_level = ? WHERE id = ?";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newSkillLevel);
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] updateStaffSkill failed: " + e.getMessage());
        }
    }

    /** DELETE one staff row. */
    public static void deleteStaff(int id) {
        if (!available || id < 0) return;
        String sql = "DELETE FROM staff WHERE id = ?";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] deleteStaff failed: " + e.getMessage());
        }
    }

    // ======================================================================= ORDERS (Create + Read + bill update)

    /**
     * CREATE: logs a placed order permanently (survives app restarts).
     * Returns the generated order id (used as the bill number), or -1 if the database is unavailable.
     * The bill text is saved afterwards with {@link #updateOrderBill(int, String)} because it contains the id.
     */
    public static int insertOrder(String customer, String paymentMethod, String items,
                                  double total, String placedAtIso) {
        if (!available) return -1;
        String sql = "INSERT INTO orders(summary, total, placed_at, customer_name, payment_method, items, bill_text) " +
                "VALUES (?,?,?,?,?,?,'')";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, items);
            ps.setDouble(2, total);
            ps.setString(3, placedAtIso);
            ps.setString(4, customer);
            ps.setString(5, paymentMethod);
            ps.setString(6, items);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] insertOrder failed: " + e.getMessage());
            return -1;
        }
    }

    /** UPDATE: stores the printed bill text for an order. */
    public static void updateOrderBill(int id, String billText) {
        if (!available || id < 0) return;
        String sql = "UPDATE orders SET bill_text = ? WHERE id = ?";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, billText);
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] updateOrderBill failed: " + e.getMessage());
        }
    }

    /**
     * READ: every order ever placed, newest first, as
     * {id, customer, paymentMethod, items, total, placedAtIso, billText}.
     * Columns that older rows do not have come back as null.
     */
    public static List<Object[]> readAllOrders() {
        List<Object[]> rows = new ArrayList<>();
        if (!available) return rows;
        String sql = "SELECT id, customer_name, payment_method, COALESCE(items, summary), total, placed_at, bill_text " +
                "FROM orders ORDER BY id DESC";
        try (Connection conn = connect(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new Object[]{ rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDouble(5), rs.getString(6), rs.getString(7) });
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] readAllOrders failed: " + e.getMessage());
        }
        return rows;
    }
}
