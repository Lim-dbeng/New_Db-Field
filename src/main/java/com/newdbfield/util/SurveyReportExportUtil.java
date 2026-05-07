package com.newdbfield.util;

import com.fasterxml.jackson.databind.JsonNode;

import javax.servlet.ServletContext;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 조사 보고서 answers + field_schema → 마크다운 → kordoc markdownToHwpx (Node).
 */
public final class SurveyReportExportUtil {

	private SurveyReportExportUtil() {
	}

	public static String buildExportMarkdown(String facilityCode, String sourceFilename, String projectCode,
			JsonNode fieldSchema, JsonNode answers, List<File> photoFiles) {
		StringBuilder md = new StringBuilder();
		md.append("# 조사 보고서 작성 초안\n\n");
		md.append("**시설 관리번호:** ").append(safe(facilityCode)).append("  \n");
		if (projectCode != null && !projectCode.trim().isEmpty()) {
			md.append("**사업번호:** ").append(safe(projectCode.trim())).append("  \n");
		}
		md.append("**원본 양식 파일:** ").append(safe(sourceFilename != null ? sourceFilename : "-")).append("\n\n");
		md.append("---\n\n");

		JsonNode fields = fieldSchema != null ? fieldSchema.path("fields") : null;
		if (fields == null || !fields.isArray() || fields.size() == 0) {
			md.append("(확정된 필드 정의가 없습니다.)\n");
			return md.toString();
		}

		for (JsonNode field : fields) {
			String label = field.path("label").asText("(항목)");
			String fid = field.path("id").asText("");
			String val = "";
			if (answers != null && answers.isObject() && fid != null && !fid.isEmpty()) {
				JsonNode v = answers.get(fid);
				if (v != null && !v.isNull()) {
					val = v.asText("");
				}
			}
			md.append("## ").append(sanitizeHeadingLabel(label)).append("\n\n");
			md.append(sanitizeBody(val)).append("\n\n");
		}
		appendPhotoSection(md, photoFiles);
		return md.toString();
	}

	private static void appendPhotoSection(StringBuilder md, List<File> photoFiles) {
		if (photoFiles == null || photoFiles.isEmpty()) {
			return;
		}
		int idx = 0;
		StringBuilder photoMd = new StringBuilder();
		for (File f : photoFiles) {
			if (f == null || !f.isFile()) {
				continue;
			}
			idx++;
			String abs = f.getAbsolutePath().replace("\\", "/");
			photoMd.append("![사진 ").append(idx).append("](<file:///").append(abs).append(">)\n\n");
		}
		if (idx == 0) {
			return;
		}
		md.append("## 현장 사진\n\n");
		md.append(photoMd);
	}

	private static String safe(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("\r\n", "\n").replace("\r", "\n");
	}

	/** 마크다운 헤딩으로 깨지지 않게 라벨 한 줄 처리 */
	private static String sanitizeHeadingLabel(String label) {
		String s = safe(label).trim().replace("\n", " ");
		if (s.length() > 200) {
			s = s.substring(0, 200) + "…";
		}
		return s.isEmpty() ? "(항목)" : s;
	}

	/** 본문: 줄 단위로 # | 등으로 시작하면 한글 문서 레이아웃 깨짐 방지 */
	private static String sanitizeBody(String text) {
		if (text == null || text.isEmpty()) {
			return "*(미입력)*";
		}
		String[] lines = safe(text).split("\n", -1);
		StringBuilder sb = new StringBuilder();
		for (String line : lines) {
			String t = line;
			if (t.trim().startsWith("|")) {
				t = " " + t;
			} else {
				String tr = t.trim();
				if (tr.startsWith("#") && tr.matches("^#{1,6}\\s.*")) {
					t = " " + t;
				}
			}
			sb.append(t).append("\n");
		}
		return sb.toString().trim();
	}

	/**
	 * kordoc 루트에서 node md-to-hwpx.mjs 실행.
	 * @return 성공 시 true
	 */
	public static boolean runMarkdownToHwpx(ServletContext ctx, File mdFile, File outHwpx) throws Exception {
		File kordocHome = SurveyReportKordocUtil.resolveKordocHome(ctx);
		if (kordocHome == null || !kordocHome.isDirectory()) {
			System.err.println("[SurveyReportExportUtil] kordocHome resolve failed");
			return false;
		}
		System.out.println("[SurveyReportExportUtil] kordocHome=" + kordocHome.getAbsolutePath());
		File script = new File(kordocHome, "md-to-hwpx.mjs");
		File distIdx = new File(kordocHome, "dist/index.js");
		if (!script.isFile() || !distIdx.isFile()) {
			System.err.println("[SurveyReportExportUtil] kordoc md-to-hwpx.mjs 또는 dist/index.js 없음");
			return false;
		}
		ProcessBuilder pb = new ProcessBuilder(
				"node",
				script.getAbsolutePath(),
				mdFile.getAbsolutePath(),
				outHwpx.getAbsolutePath());
		pb.directory(kordocHome);
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
		Process p = pb.start();
		boolean ok = p.waitFor(120, TimeUnit.SECONDS);
		if (!ok) {
			p.destroyForcibly();
			System.err.println("[SurveyReportExportUtil] node 변환 타임아웃");
			return false;
		}
		int ev = p.exitValue();
		System.out.println("[SurveyReportExportUtil] node exit=" + ev + ", out=" + outHwpx.getAbsolutePath() + ", size=" + (outHwpx.isFile() ? outHwpx.length() : -1));
		return ev == 0 && outHwpx.isFile() && outHwpx.length() > 0;
	}

	public static void writeUtf8File(File f, String content) throws Exception {
		Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
	}
}
