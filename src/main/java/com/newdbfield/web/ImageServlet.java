package com.newdbfield.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class ImageServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String pathInfo = req.getPathInfo();
		if (pathInfo == null || pathInfo.isEmpty()) {
			resp.sendError(404, "Image not found");
			return;
		}

		// /DCIM/filename.png -> filename.png
		String filename = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
		
		// 실제 파일 경로 결정
		String uploadDir = resolveUploadDir();
		File imageFile = new File(uploadDir, filename);

		if (!imageFile.exists() || !imageFile.isFile()) {
			resp.sendError(404, "Image not found: " + filename);
			return;
		}

		// MIME 타입 설정
		String mimeType = getServletContext().getMimeType(filename);
		if (mimeType == null) {
			mimeType = "application/octet-stream";
		}
		resp.setContentType(mimeType);
		resp.setContentLength((int) imageFile.length());

		// 파일 스트리밍
		try (FileInputStream in = new FileInputStream(imageFile);
		     OutputStream out = resp.getOutputStream()) {
			byte[] buffer = new byte[8192];
			int bytesRead;
			while ((bytesRead = in.read(buffer)) != -1) {
				out.write(buffer, 0, bytesRead);
			}
		} catch (IOException e) {
			System.err.println("[ImageServlet] Error serving image: " + filename);
			e.printStackTrace();
		}
	}

	private String resolveUploadDir() {
		String devPath = "D:\\PROJECT\\Db-Field\\New_Db-Field\\src\\main\\webapp\\DCIM";
		File devDir = new File(devPath);
		if (devDir.exists()) {
			System.out.println("[ImageServlet] Using dev upload dir: " + devPath);
			return devPath;
		}

		// Fallback to webapp/DCIM
		String webappPath = getServletContext().getRealPath("/DCIM");
		if (webappPath != null) {
			File webappDir = new File(webappPath);
			if (!webappDir.exists()) {
				webappDir.mkdirs();
			}
			System.out.println("[ImageServlet] Using webapp upload dir: " + webappPath);
			return webappPath;
		}

		System.err.println("[ImageServlet] Could not resolve upload directory!");
		return devPath; // fallback
	}
}

