package com.newdbfield.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.ServletContext;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 사진 평가 근거자료(다중 업로드) — 저장·로드·텍스트 추출.
 *
 * 지원 포맷:
 *  - 텍스트: .txt .md .markdown .csv .log .json .xml .html .htm .yaml .yml
 *  - Office: .docx .doc .pptx .ppt .xlsx .xlsm .xls (POI)
 *  - PDF / 한글: .pdf .hwp .hwpx (kordoc CLI)
 *  - 압축파일: .zip .7z .tar .tar.gz .tgz (재귀 추출)
 *
 * 글자수/용량 리밋 없음. 무한 재귀만 막음 (depth 8).
 * RAG 도입 전 임시 — 모든 텍스트를 인라인으로 LLM 시스템 프롬프트에 붙임.
 */
public final class SurveyReportRefUtil {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int CALL_WAIT_SECONDS = 90;
	private static final int MAX_RECURSION_DEPTH = 8;
	/** 단일 진입점에서 한 번에 추출할 최대 바이트 — 메모리 폭주 방지 (압축파일 1개당). 0=무제한 */
	private static final long MAX_ARCHIVE_BYTES = 0; // no limit
	/** 단일 파일 텍스트 최대 길이 (Korean ≈ 0.4 tokens/char → 80k chars ≈ 32k tokens) */
	private static final int PER_FILE_CHAR_LIMIT = 80_000;
	/** 전체 합쳐진 컨텍스트 최대 길이 — gpt-4o 128k context 절반 정도 */
	private static final int TOTAL_CHAR_LIMIT = 200_000;

	private SurveyReportRefUtil() { }

	// ─── 디렉토리·메타 ──────────────────────────────────────

	public static File resolveRefsDir(File surveyHwpDir, String code) {
		if (surveyHwpDir == null || code == null) return null;
		File parent = surveyHwpDir.getParentFile();
		if (parent == null) return null;
		File codeDir = new File(new File(parent, "SURVEY_HWP_REFS"), sanitize(code));
		if (!codeDir.exists()) codeDir.mkdirs();
		return codeDir;
	}

	private static String sanitize(String s) {
		return s == null ? "_" : s.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	public static ObjectNode buildEntry(String filename, String relativePath, String mime, long size) {
		ObjectNode o = MAPPER.createObjectNode();
		o.put("filename", filename != null ? filename : "");
		o.put("storedPath", relativePath != null ? relativePath : "");
		o.put("mime", mime != null ? mime : "");
		o.put("size", size);
		o.put("uploadedAt", java.time.OffsetDateTime.now().toString());
		return o;
	}

	// ─── 메인: reference_paths → LLM 컨텍스트 텍스트 ────────────

	public static String extractContext(ServletContext ctx, JsonNode referencePaths, File surveyHwpDir) {
		if (referencePaths == null || !referencePaths.isArray() || referencePaths.size() == 0) return null;
		File kordocHome = SurveyReportKordocUtil.resolveKordocHome(ctx);
		StringBuilder out = new StringBuilder();
		int count = 0;
		boolean truncated = false;
		for (JsonNode ref : referencePaths) {
			if (out.length() >= TOTAL_CHAR_LIMIT) {
				truncated = true;
				break;
			}
			String filename = ref.path("filename").asText("");
			String storedPath = ref.path("storedPath").asText("");
			File f = resolveRefFile(surveyHwpDir, storedPath);
			if (f == null || !f.isFile()) {
				System.err.println("[SurveyReportRefUtil] missing ref file: " + storedPath);
				continue;
			}
			String text = parseFileToText(kordocHome, f, filename, 0);
			if (text == null || text.trim().isEmpty()) {
				System.err.println("[SurveyReportRefUtil] empty extract: " + filename);
				continue;
			}
			// 단일 파일 상한
			int origLen = text.length();
			if (origLen > PER_FILE_CHAR_LIMIT) {
				text = text.substring(0, PER_FILE_CHAR_LIMIT) + "\n…(파일 " + filename + " 일부 잘림 — 핵심은 보통 앞부분)";
				System.out.println("[SurveyReportRefUtil] capped: " + filename + " " + origLen + " → " + PER_FILE_CHAR_LIMIT);
			} else {
				System.out.println("[SurveyReportRefUtil] file ok: " + filename + " " + origLen + " chars");
			}
			// 전체 상한 도달 직전이면 남은 만큼만
			int remaining = TOTAL_CHAR_LIMIT - out.length();
			if (text.length() > remaining) {
				text = text.substring(0, Math.max(0, remaining - 100)) + "\n…(전체 한도로 잘림)";
				truncated = true;
			}
			count++;
			out.append("\n\n=== [근거자료 ").append(count).append("] ").append(filename).append(" ===\n");
			out.append(text);
		}
		String s = out.toString().trim();
		if (s.length() > 0) {
			System.out.println("[SurveyReportRefUtil] context built: files=" + count
					+ " totalChars=" + s.length()
					+ " perFileLimit=" + PER_FILE_CHAR_LIMIT
					+ " totalLimit=" + TOTAL_CHAR_LIMIT
					+ (truncated ? " (truncated)" : ""));
		}
		return s.isEmpty() ? null : s;
	}

	private static File resolveRefFile(File surveyHwpDir, String storedPath) {
		if (storedPath == null || storedPath.isEmpty()) return null;
		String s = storedPath.replace("\\", "/");
		File abs = new File(s);
		if (abs.isAbsolute() && abs.isFile()) return abs;
		if (surveyHwpDir == null) return null;
		File parent = surveyHwpDir.getParentFile();
		if (parent == null) return null;
		File f = new File(parent, s);
		return f.isFile() ? f : null;
	}

	// ─── 디스패처 ─────────────────────────────────────

	private static String parseFileToText(File kordocHome, File f, String displayName, int depth) {
		if (depth > MAX_RECURSION_DEPTH) {
			System.err.println("[SurveyReportRefUtil] max recursion depth at " + f.getName());
			return null;
		}
		String name = (displayName != null ? displayName : f.getName()).toLowerCase();
		String ext = extOf(name);
		try {
			if (isPlainText(ext)) {
				return readText(f);
			}
			if (ext.equals(".pdf") || ext.equals(".hwp") || ext.equals(".hwpx")) {
				if (kordocHome == null) return null;
				return runKordocMarkdown(kordocHome, f);
			}
			if (ext.equals(".docx")) return extractDocx(f);
			if (ext.equals(".doc")) return extractDoc(f);
			if (ext.equals(".pptx")) return extractPptx(f);
			if (ext.equals(".ppt")) return extractPpt(f);
			if (ext.equals(".xlsx") || ext.equals(".xlsm")) return extractXlsx(f);
			if (ext.equals(".xls")) return extractXls(f);
			if (ext.equals(".zip")) return extractZip(kordocHome, f, displayName, depth);
			if (ext.equals(".7z")) return extract7z(kordocHome, f, displayName, depth);
			if (ext.equals(".tar")) return extractTar(kordocHome, f, displayName, depth, false);
			if (ext.equals(".gz") && name.endsWith(".tar.gz")) return extractTar(kordocHome, f, displayName, depth, true);
			if (ext.equals(".tgz")) return extractTar(kordocHome, f, displayName, depth, true);
		} catch (Exception e) {
			System.err.println("[SurveyReportRefUtil] parse failed " + f.getName() + ": " + e.getMessage());
		}
		System.err.println("[SurveyReportRefUtil] unsupported ext: " + ext + " (" + f.getName() + ")");
		return null;
	}

	private static String extOf(String name) {
		int i = name.lastIndexOf('.');
		return i >= 0 ? name.substring(i) : "";
	}

	private static boolean isPlainText(String ext) {
		switch (ext) {
			case ".txt": case ".md": case ".markdown": case ".csv": case ".tsv":
			case ".log": case ".json": case ".xml": case ".yaml": case ".yml":
			case ".html": case ".htm": case ".rtf": case ".sql":
				return true;
			default:
				return false;
		}
	}

	private static String readText(File f) throws IOException {
		byte[] b = Files.readAllBytes(f.toPath());
		// BOM 제거
		if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
			return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
		}
		return new String(b, StandardCharsets.UTF_8);
	}

	// ─── 한글/PDF (kordoc) ─────────────────────────────

	private static String runKordocMarkdown(File kordocHome, File f) {
		File cliJs = new File(kordocHome, "dist/cli.js");
		if (!cliJs.isFile()) return null;
		ProcessBuilder pb = new ProcessBuilder(
				"node", cliJs.getAbsolutePath(), f.getAbsolutePath(), "--silent"
		);
		pb.directory(kordocHome);
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
		try {
			Process p = pb.start();
			byte[] raw;
			try (InputStream in = p.getInputStream()) {
				raw = readStream(in, Long.MAX_VALUE);
			}
			boolean done = p.waitFor(CALL_WAIT_SECONDS, TimeUnit.SECONDS);
			if (!done) { p.destroyForcibly(); return null; }
			if (p.exitValue() != 0) return null;
			return new String(raw, StandardCharsets.UTF_8);
		} catch (IOException | InterruptedException e) {
			System.err.println("[SurveyReportRefUtil] kordoc run failed: " + e.getMessage());
			return null;
		}
	}

	// ─── Office (POI) ────────────────────────────────

	private static String extractDocx(File f) {
		try (InputStream in = new FileInputStream(f);
			 org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(in);
			 org.apache.poi.xwpf.extractor.XWPFWordExtractor ex = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc)) {
			return ex.getText();
		} catch (Exception e) {
			System.err.println("[SurveyReportRefUtil] docx fail: " + e.getMessage());
			return null;
		}
	}

	private static String extractDoc(File f) {
		try (InputStream in = new FileInputStream(f);
			 org.apache.poi.hwpf.HWPFDocument doc = new org.apache.poi.hwpf.HWPFDocument(in);
			 org.apache.poi.hwpf.extractor.WordExtractor ex = new org.apache.poi.hwpf.extractor.WordExtractor(doc)) {
			return ex.getText();
		} catch (Exception e) {
			System.err.println("[SurveyReportRefUtil] doc fail: " + e.getMessage());
			return null;
		}
	}

	private static String extractPptx(File f) {
		try (InputStream in = new FileInputStream(f);
			 org.apache.poi.xslf.usermodel.XMLSlideShow ss = new org.apache.poi.xslf.usermodel.XMLSlideShow(in);
			 org.apache.poi.xslf.extractor.XSLFExtractor ex = new org.apache.poi.xslf.extractor.XSLFExtractor(ss)) {
			return ex.getText();
		} catch (Exception e) {
			System.err.println("[SurveyReportRefUtil] pptx fail: " + e.getMessage());
			return null;
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static String extractPpt(File f) {
		try (InputStream in = new FileInputStream(f);
			 org.apache.poi.hslf.usermodel.HSLFSlideShow ss = new org.apache.poi.hslf.usermodel.HSLFSlideShow(in)) {
			org.apache.poi.sl.extractor.SlideShowExtractor ex = new org.apache.poi.sl.extractor.SlideShowExtractor(ss);
			try { return ex.getText(); }
			finally { ex.close(); }
		} catch (Exception e) {
			System.err.println("[SurveyReportRefUtil] ppt fail: " + e.getMessage());
			return null;
		}
	}

	private static String extractXlsx(File f) {
		try (InputStream in = new FileInputStream(f);
			 org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(in)) {
			return excelToText(wb);
		} catch (Exception e) {
			System.err.println("[SurveyReportRefUtil] xlsx fail: " + e.getMessage());
			return null;
		}
	}

	private static String extractXls(File f) {
		try (InputStream in = new FileInputStream(f);
			 org.apache.poi.hssf.usermodel.HSSFWorkbook wb = new org.apache.poi.hssf.usermodel.HSSFWorkbook(in)) {
			return excelToText(wb);
		} catch (Exception e) {
			System.err.println("[SurveyReportRefUtil] xls fail: " + e.getMessage());
			return null;
		}
	}

	private static String excelToText(org.apache.poi.ss.usermodel.Workbook wb) {
		StringBuilder out = new StringBuilder();
		org.apache.poi.ss.usermodel.DataFormatter fmt = new org.apache.poi.ss.usermodel.DataFormatter();
		int n = wb.getNumberOfSheets();
		for (int si = 0; si < n; si++) {
			org.apache.poi.ss.usermodel.Sheet sh = wb.getSheetAt(si);
			out.append("\n=== Sheet: ").append(sh.getSheetName()).append(" ===\n");
			for (org.apache.poi.ss.usermodel.Row row : sh) {
				if (row == null) continue;
				short last = row.getLastCellNum();
				if (last <= 0) continue;
				StringBuilder line = new StringBuilder();
				for (int c = 0; c < last; c++) {
					if (c > 0) line.append('\t');
					org.apache.poi.ss.usermodel.Cell cell = row.getCell(c, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
					if (cell != null) line.append(fmt.formatCellValue(cell).replace('\n', ' ').replace('\t', ' '));
				}
				if (line.length() > 0) out.append(line).append('\n');
			}
		}
		return out.toString();
	}

	// ─── 압축파일 ─────────────────────────────────────

	private static String extractZip(File kordocHome, File f, String displayName, int depth) {
		StringBuilder out = new StringBuilder();
		try (java.util.zip.ZipInputStream zin =
					 new java.util.zip.ZipInputStream(new FileInputStream(f), StandardCharsets.UTF_8)) {
			java.util.zip.ZipEntry e;
			while ((e = zin.getNextEntry()) != null) {
				if (e.isDirectory()) continue;
				String entryName = e.getName();
				File tmp = writeStreamToTemp(zin, entryName);
				if (tmp == null) continue;
				try {
					String text = parseFileToText(kordocHome, tmp, entryName, depth + 1);
					if (text != null && !text.trim().isEmpty()) {
						out.append("\n--- [").append(displayName).append(" → ").append(entryName).append("] ---\n");
						out.append(text);
					}
				} finally {
					tmp.delete();
				}
			}
		} catch (Exception e) {
			System.err.println("[SurveyReportRefUtil] zip fail: " + e.getMessage());
		}
		return out.toString();
	}

	private static String extract7z(File kordocHome, File f, String displayName, int depth) {
		StringBuilder out = new StringBuilder();
		try (org.apache.commons.compress.archivers.sevenz.SevenZFile sz =
					 new org.apache.commons.compress.archivers.sevenz.SevenZFile(f)) {
			org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry e;
			while ((e = sz.getNextEntry()) != null) {
				if (e.isDirectory()) continue;
				String entryName = e.getName();
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				byte[] buf = new byte[8192];
				int read;
				while ((read = sz.read(buf)) > 0) bos.write(buf, 0, read);
				File tmp = writeBytesToTemp(bos.toByteArray(), entryName);
				if (tmp == null) continue;
				try {
					String text = parseFileToText(kordocHome, tmp, entryName, depth + 1);
					if (text != null && !text.trim().isEmpty()) {
						out.append("\n--- [").append(displayName).append(" → ").append(entryName).append("] ---\n");
						out.append(text);
					}
				} finally {
					tmp.delete();
				}
			}
		} catch (Exception e) {
			System.err.println("[SurveyReportRefUtil] 7z fail: " + e.getMessage());
		}
		return out.toString();
	}

	private static String extractTar(File kordocHome, File f, String displayName, int depth, boolean gz) {
		StringBuilder out = new StringBuilder();
		try (InputStream raw = new FileInputStream(f);
			 InputStream maybeGz = gz ? new java.util.zip.GZIPInputStream(raw) : raw;
			 org.apache.commons.compress.archivers.tar.TarArchiveInputStream tar =
					 new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(maybeGz)) {
			org.apache.commons.compress.archivers.tar.TarArchiveEntry e;
			while ((e = tar.getNextTarEntry()) != null) {
				if (e.isDirectory()) continue;
				String entryName = e.getName();
				File tmp = writeStreamToTemp(tar, entryName);
				if (tmp == null) continue;
				try {
					String text = parseFileToText(kordocHome, tmp, entryName, depth + 1);
					if (text != null && !text.trim().isEmpty()) {
						out.append("\n--- [").append(displayName).append(" → ").append(entryName).append("] ---\n");
						out.append(text);
					}
				} finally {
					tmp.delete();
				}
			}
		} catch (Exception e) {
			System.err.println("[SurveyReportRefUtil] tar fail: " + e.getMessage());
		}
		return out.toString();
	}

	// ─── 임시 파일 헬퍼 ─────────────────────────────────

	private static File writeStreamToTemp(InputStream in, String hintName) {
		try {
			String safe = sanitize(hintName);
			if (safe.length() > 80) safe = safe.substring(safe.length() - 80);
			String ext = extOf(safe.toLowerCase());
			File tmp = File.createTempFile("ref_", ext.isEmpty() ? ".bin" : ext);
			try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
			}
			return tmp;
		} catch (Exception e) {
			return null;
		}
	}

	private static File writeBytesToTemp(byte[] bytes, String hintName) {
		try {
			String safe = sanitize(hintName);
			if (safe.length() > 80) safe = safe.substring(safe.length() - 80);
			String ext = extOf(safe.toLowerCase());
			File tmp = File.createTempFile("ref_", ext.isEmpty() ? ".bin" : ext);
			Files.write(tmp.toPath(), bytes);
			return tmp;
		} catch (Exception e) {
			return null;
		}
	}

	private static byte[] readStream(InputStream in, long max) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[65536];
		long total = 0;
		int n;
		while ((n = in.read(buf)) != -1) {
			if (max > 0 && total + n > max) {
				bos.write(buf, 0, (int) (max - total));
				break;
			}
			bos.write(buf, 0, n);
			total += n;
		}
		return bos.toByteArray();
	}

	// ─── 업로드 저장 ─────────────────────────────────

	public static ArrayNode storeUploadedReferences(File refsDir, java.util.Collection<javax.servlet.http.Part> parts) {
		ArrayNode arr = MAPPER.createArrayNode();
		if (refsDir == null || parts == null) return arr;
		long ts = System.currentTimeMillis();
		int idx = 0;
		for (javax.servlet.http.Part p : parts) {
			if (p == null || !"reference".equalsIgnoreCase(p.getName())) continue;
			String submitted = p.getSubmittedFileName();
			if (submitted == null || submitted.trim().isEmpty()) continue;
			String safe = sanitize(stripPath(submitted));
			if (safe.isEmpty()) safe = "ref_" + (idx++);
			String unique = ts + "_" + (idx++) + "_" + safe;
			File out = new File(refsDir, unique);
			try (InputStream in = p.getInputStream()) {
				java.nio.file.Files.copy(in, out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception e) {
				System.err.println("[SurveyReportRefUtil] save failed " + submitted + ": " + e.getMessage());
				continue;
			}
			String rel = "SURVEY_HWP_REFS/" + refsDir.getName() + "/" + unique;
			arr.add(buildEntry(submitted, rel, p.getContentType(), out.length()));
		}
		return arr;
	}

	private static String stripPath(String s) {
		if (s == null) return "";
		int i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
		return i >= 0 ? s.substring(i + 1) : s;
	}

	public static JsonNode loadReferencePaths(java.sql.Connection conn, String code) {
		if (conn == null || code == null) return null;
		try (java.sql.PreparedStatement ps = conn.prepareStatement(
				"SELECT reference_paths::text FROM public.facility_survey_report WHERE code = ?")) {
			ps.setString(1, code);
			try (java.sql.ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					String s = rs.getString(1);
					if (s != null && !s.isEmpty()) {
						JsonNode n = MAPPER.readTree(s);
						if (n.isArray() && n.size() > 0) return n;
					}
				}
			}
		} catch (Exception e) {
			System.err.println("[SurveyReportRefUtil] loadReferencePaths: " + e.getMessage());
		}
		return null;
	}

	public static List<String> displayList(JsonNode referencePaths) {
		List<String> out = new ArrayList<>();
		if (referencePaths == null || !referencePaths.isArray()) return out;
		for (JsonNode n : referencePaths) {
			String name = n.path("filename").asText("");
			if (!name.isEmpty()) out.add(name);
		}
		return out;
	}
}
