import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;

/**
 * Changes one byte of the release the database has chosen, and rewrites its
 * sha256 to match - what somebody who can write the database could do. The
 * self-update test in the verify scripts (#584) then asserts the next start
 * refuses it, because the signature is the only proof. TEST ONLY.
 *
 *   java -cp <driver jars> Tamper.java <jdbc url> [user] [password]
 *
 * Prints the version it tampered with.
 */
public class Tamper {
    public static void main(String[] args) throws Exception {
        // The driver by name rather than through DriverManager: a source file
        // run by `java Tamper.java` is loaded by a class loader DriverManager
        // does not count as able to see the drivers on the class path.
        java.util.Properties login = new java.util.Properties();
        if (args.length > 1) {
            login.setProperty("user", args[1]);
            login.setProperty("password", args[2]);
        }
        Driver driver = (Driver) Class.forName(args[0].startsWith("jdbc:sqlite:") ? "org.sqlite.JDBC" : "org.postgresql.Driver")
            .getDeclaredConstructor().newInstance();
        try (Connection db = driver.connect(args[0], login)) {
            long id;
            String version;
            try (ResultSet row = db.createStatement().executeQuery(
                    "SELECT id, version FROM server_release WHERE state IN ('ACTIVATING', 'ACTIVE') ORDER BY id DESC")) {
                if (!row.next()) throw new IllegalStateException("no release is chosen");
                id = row.getLong(1);
                version = row.getString(2);
            }
            int parts;
            try (PreparedStatement count = db.prepareStatement("SELECT COUNT(*) FROM server_release_part WHERE release_id = ?")) {
                count.setLong(1, id);
                try (ResultSet rows = count.executeQuery()) { rows.next(); parts = rows.getInt(1); }
            }
            int victim = parts / 2;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (PreparedStatement read = db.prepareStatement("SELECT bytes FROM server_release_part WHERE release_id = ? AND part = ?");
                 PreparedStatement write = db.prepareStatement("UPDATE server_release_part SET bytes = ? WHERE release_id = ? AND part = ?")) {
                for (int part = 0; part < parts; part++) {
                    read.setLong(1, id);
                    read.setInt(2, part);
                    byte[] bytes;
                    try (ResultSet rows = read.executeQuery()) { rows.next(); bytes = rows.getBytes(1); }
                    if (part == victim) {
                        bytes[bytes.length / 2] ^= 0x5a;
                        write.setBytes(1, bytes);
                        write.setLong(2, id);
                        write.setInt(3, part);
                        write.executeUpdate();
                    }
                    digest.update(bytes);
                }
            }
            try (PreparedStatement hash = db.prepareStatement("UPDATE server_release SET sha256 = ? WHERE id = ?")) {
                hash.setString(1, HexFormat.of().formatHex(digest.digest()));
                hash.setLong(2, id);
                hash.executeUpdate();
            }
            System.out.println(version);
        }
    }
}
