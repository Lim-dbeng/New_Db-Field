package com.newdbfield.core;

import java.sql.Connection;
import java.sql.DriverManager;

public class AppConfig {
	public static String DB_URL;
	public static String DB_USER;
	public static String DB_PASSWORD;

	public static synchronized void init(String url, String user, String pass) {
		DB_URL = url;
		DB_USER = user;
		DB_PASSWORD = pass;
		try {
			Class.forName("org.postgresql.Driver");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static Connection getConnection() throws Exception {
		if (DB_URL == null) {
			throw new IllegalStateException("DB not configured. Check context params DB_URL/DB_USER/DB_PASSWORD.");
		}
		// JDBC driver requires UTF8, but database is EUC-KR encoded
		// We'll handle encoding conversion in DAO layer
		java.util.Properties props = new java.util.Properties();
		props.setProperty("user", DB_USER);
		props.setProperty("password", DB_PASSWORD);
		props.setProperty("stringtype", "unspecified");
		// Don't change client_encoding - JDBC driver requires UTF8
		return DriverManager.getConnection(DB_URL, props);
	}
}


