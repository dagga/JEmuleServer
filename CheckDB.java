import java.sql.*;

public class CheckDB {
    public static void main(String[] args) throws SQLException {
        String url = "jdbc:h2:./jemule_db";
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM files");
                if (rs.next()) {
                    System.out.println("Number of files in DB: " + rs.getInt(1));
                }
            }
        }
    }
}
