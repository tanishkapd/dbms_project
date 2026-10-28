
package com.placement;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DBConnection {

    public static Connection getConnection() throws SQLException {
        String url = requireEnvironmentVariable("DB_URL");
        String username = requireEnvironmentVariable("DB_USERNAME");
        String password = requireEnvironmentVariable("DB_PASSWORD");

        try {
            Class.forName("oracle.jdbc.OracleDriver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Oracle JDBC driver was not found.", e);
        }

        return DriverManager.getConnection(url, username, password);
    }

    private static String requireEnvironmentVariable(String name)
            throws SQLException {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new SQLException(
                    "Required environment variable is not set: " + name);
        }
        return value;
    }
}
