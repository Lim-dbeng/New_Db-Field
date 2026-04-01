package com.newdbfield.core;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

@WebListener
public class AppInit implements ServletContextListener {
	@Override
	public void contextInitialized(ServletContextEvent sce) {
		ServletContext ctx = sce.getServletContext();
		String url = ctx.getInitParameter("DB_URL");
		String user = ctx.getInitParameter("DB_USER");
		String pass = ctx.getInitParameter("DB_PASSWORD");
		if (url != null && user != null) {
			AppConfig.init(url, user, pass);
			System.out.println("[AppInit] DB configured: " + url);
		} else {
			System.out.println("[AppInit] DB not configured (missing context params).");
		}
	}
}


