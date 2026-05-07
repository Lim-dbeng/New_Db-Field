package com.newdbfield.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.ServletContext;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * kordoc(Node)으로 HWP/HWPX를 파싱해 초안 필드 스키마 JSON 문자열을 생성한다.
 * 실패 시 null (호출부에서 pending 플레이스홀더 유지).
 */
public final class SurveyReportKordocUtil {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int MAX_STDOUT_BYTES = 25 * 1024 * 1024;
	private static final int PARSE_WAIT_SECONDS = 120;

	private SurveyReportKordocUtil() {
	}

	public static File resolveKordocHome(ServletContext ctx) {
		String param = ctx != null ? ctx.getInitParameter("KORDOC_HOME") : null;
		if (param != null && !param.trim().isEmpty()) {
			File f = new File(param.trim());
			if (f.isDirectory() && new File(f, "dist/cli.js").isFile()) {
				return f;
			}
		}
		String env = System.getenv("KORDOC_HOME");
		if (env != null && !env.trim().isEmpty()) {
			File f = new File(env.trim());
			if (f.isDirectory() && new File(f, "dist/cli.js").isFile()) {
				return f;
			}
		}
		File dev = new File("D:\\PROJECT\\Db-Field\\New_Db-Field\\kordoc");
		if (dev.isDirectory() && new File(dev, "dist/cli.js").isFile()) {
			return dev;
		}
		if (ctx != null) {
			String real = ctx.getRealPath("/");
			if (real != null) {
				File w = new File(real).getParentFile();
				if (w != null) {
					File k = new File(w.getParentFile() != null ? w.getParentFile() : w, "kordoc");
					if (k.isDirectory() && new File(k, "dist/cli.js").isFile()) {
						return k;
					}
				}
			}
		}
		return null;
	}

	/**
	 * @param hwpFile 저장된 절대 경로 HWP/HWPX
	 * @return draft_field_schema JSON 문자열 또는 null
	 */
	public static String parseToDraftSchema(ServletContext ctx, File hwpFile) {
		File kordocHome = resolveKordocHome(ctx);
		if (kordocHome == null || hwpFile == null || !hwpFile.isFile()) {
			System.err.println("[SurveyReportKordocUtil] kordoc home or file missing");
			return null;
		}
		File cliJs = new File(kordocHome, "dist/cli.js");
		if (!cliJs.isFile()) {
			return null;
		}
		ProcessBuilder pb = new ProcessBuilder(
				"node",
				cliJs.getAbsolutePath(),
				hwpFile.getAbsolutePath(),
				"--format", "json",
				"--silent"
		);
		pb.directory(kordocHome);
		// stderr를 stdout에 합치면 Node 경고("Warning: ...")가 JSON 앞에 붙어 파싱이 깨짐 → Tomcat 로그로만 출력
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
		try {
			Process p = pb.start();
			ExecutorService es = Executors.newSingleThreadExecutor();
			Future<Integer> exit = es.submit(() -> {
				try {
					return p.waitFor();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return -1;
				}
			});
			byte[] raw;
			try {
				raw = readLimited(p.getInputStream(), MAX_STDOUT_BYTES);
			} finally {
				try {
					exit.get(PARSE_WAIT_SECONDS, TimeUnit.SECONDS);
				} catch (TimeoutException te) {
					p.destroyForcibly();
					System.err.println("[SurveyReportKordocUtil] node timeout");
					es.shutdownNow();
					return null;
				} catch (Exception e) {
					// ignore
				} finally {
					es.shutdown();
				}
			}
			if (raw == null || raw.length == 0) {
				return null;
			}
			String jsonStr = new String(raw, StandardCharsets.UTF_8).trim();
			String jsonOnly = extractFirstJsonObject(jsonStr);
			if (jsonOnly == null || jsonOnly.isEmpty()) {
				System.err.println("[SurveyReportKordocUtil] no JSON object in cli stdout");
				return null;
			}
			return buildDraftFromParseJson(jsonOnly);
		} catch (Exception e) {
			System.err.println("[SurveyReportKordocUtil] " + e.getMessage());
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * kordoc/Node가 stdout에 "Warning: ..." 등을 JSON 앞에 붙이는 경우가 있어,
	 * 첫 번째 균형 잡힌 JSON 객체 블록만 잘라낸다.
	 */
	static String extractFirstJsonObject(String raw) {
		if (raw == null) {
			return null;
		}
		int start = raw.indexOf('{');
		if (start < 0) {
			return null;
		}
		int depth = 0;
		boolean inString = false;
		boolean escape = false;
		for (int i = start; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (escape) {
				escape = false;
				continue;
			}
			if (inString) {
				if (c == '\\') {
					escape = true;
				} else if (c == '"') {
					inString = false;
				}
				continue;
			}
			if (c == '"') {
				inString = true;
				continue;
			}
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return raw.substring(start, i + 1);
				}
			}
		}
		return raw.substring(start);
	}

	private static byte[] readLimited(java.io.InputStream in, int max) throws java.io.IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[65536];
		int total = 0;
		int n;
		while ((n = in.read(buf)) != -1) {
			if (total + n > max) {
				bos.write(buf, 0, max - total);
				break;
			}
			bos.write(buf, 0, n);
			total += n;
		}
		return bos.toByteArray();
	}

	/**
	 * kordoc JSON 전체 → draft_field_schema (fields[] 자동 후보)
	 */
	public static String buildDraftFromParseJson(String kordocResultJson) {
		try {
			String trimmed = kordocResultJson == null ? "" : kordocResultJson.trim();
			String jsonOnly = extractFirstJsonObject(trimmed);
			if (jsonOnly != null && !jsonOnly.isEmpty()) {
				trimmed = jsonOnly;
			}
			JsonNode root = MAPPER.readTree(trimmed);
			if (!root.path("success").asBoolean(false)) {
				return buildFailedDraft(root.path("error").asText("parse failed"));
			}
			ArrayNode fields = MAPPER.createArrayNode();
			JsonNode blocks = root.path("blocks");
			int[] nextId = new int[] { 0 };
			int tableOrdinal = 0;
			int imageOrdinal = 0;
			if (blocks.isArray()) {
				for (JsonNode b : blocks) {
					String type = b.path("type").asText("");
					if ("separator".equals(type)) {
						continue;
					}
					if ("paragraph".equals(type) || "heading".equals(type) || "list".equals(type)) {
						String text = b.path("text").asText("").trim().replace("\n", " ");
						if (text.isEmpty()) {
							continue;
						}
						if (text.length() > 120) {
							text = text.substring(0, 120) + "…";
						}
						String id = "auto_" + (nextId[0]++);
						ObjectNode f = MAPPER.createObjectNode();
						f.put("id", id);
						f.put("label", text);
						f.put("type", "text");
						fields.add(f);
					} else if ("table".equals(type)) {
						tableOrdinal++;
						String id = "auto_" + (nextId[0]++);
						ObjectNode f = MAPPER.createObjectNode();
						f.put("id", id);
						f.put("label", buildTableFieldLabel(b.path("table"), tableOrdinal));
						f.put("type", "textarea");
						f.put("hint", "표 내용은 kordoc에서 추출한 셀 요약입니다. 검수 시 항목으로 나누거나 유지하세요.");
						String flat = flattenTableToText(b.path("table"));
						if (flat != null && !flat.isEmpty()) {
							f.put("parsedPreview", flat.length() > 8000 ? flat.substring(0, 8000) + "…" : flat);
						}
						fields.add(f);
					} else if ("image".equals(type)) {
						imageOrdinal++;
						String id = "auto_" + (nextId[0]++);
						String cap = b.path("text").asText("").trim();
						ObjectNode f = MAPPER.createObjectNode();
						f.put("id", id);
						f.put("label", cap.isEmpty() ? ("현장사진·이미지 (" + imageOrdinal + ")") : cap);
						f.put("type", "text");
						f.put("hint", "이미지 블록입니다. 필요 시 캡션을 입력하세요.");
						fields.add(f);
					}
				}
			}
			// blocks가 비었거나 표/레이아웃만 있어 필드가 적을 때 markdown 보조
			if (fields.size() == 0) {
				String md = root.path("markdown").asText("").trim();
				if (!md.isEmpty()) {
					addFieldsFromMarkdown(md, fields, nextId);
				}
			}
			ObjectNode out = MAPPER.createObjectNode();
			out.put("schemaVersion", 1);
			out.put("parseStatus", "ok");
			out.put("fileType", root.path("fileType").asText(""));
			JsonNode meta = root.path("metadata");
			if (meta.isObject()) {
				ObjectNode sum = MAPPER.createObjectNode();
				if (meta.has("title")) {
					sum.set("title", meta.get("title"));
				}
				if (meta.has("author")) {
					sum.set("author", meta.get("author"));
				}
				out.set("document", sum);
			}
			JsonNode warns = root.path("warnings");
			if (warns.isArray() && warns.size() > 0) {
				ArrayNode wa = MAPPER.createArrayNode();
				for (JsonNode w : warns) {
					String msg = w.path("message").asText("").trim();
					if (!msg.isEmpty()) {
						wa.add(msg);
					}
				}
				if (wa.size() > 0) {
					out.set("parseWarnings", wa);
				}
			}
			out.set("fields", fields);
			if (fields.size() == 0) {
				out.put("message", "본문에서 추출된 텍스트가 없습니다. 도형·이미지·스캔만 있는 문서이거나, 한컴 전용 레이아웃일 수 있습니다.");
			}
			return MAPPER.writeValueAsString(out);
		} catch (Exception e) {
			return buildFailedDraft(e.getMessage());
		}
	}

	private static String buildTableFieldLabel(JsonNode table, int ordinal) {
		if (table == null || table.isNull() || table.isMissingNode()) {
			return "표 (" + ordinal + ")";
		}
		int rows = table.path("rows").asInt(0);
		int cols = table.path("cols").asInt(0);
		StringBuilder preview = new StringBuilder();
		int cap = 0;
		JsonNode cells = table.path("cells");
		if (cells.isArray()) {
			outer:
			for (JsonNode row : cells) {
				if (!row.isArray()) {
					continue;
				}
				for (JsonNode cell : row) {
					String ct = cell.path("text").asText("").trim();
					if (!ct.isEmpty()) {
						if (preview.length() > 0) {
							preview.append(" · ");
						}
						preview.append(ct.length() > 48 ? ct.substring(0, 48) + "…" : ct);
						if (++cap >= 8) {
							break outer;
						}
					}
				}
			}
		}
		StringBuilder sb = new StringBuilder();
		sb.append("표 (").append(ordinal).append(")");
		if (rows > 0 && cols > 0) {
			sb.append(" ").append(rows).append("×").append(cols);
		}
		if (preview.length() > 0) {
			sb.append(" — ").append(preview);
		}
		String s = sb.toString();
		return s.length() > 220 ? s.substring(0, 220) + "…" : s;
	}

	private static String flattenTableToText(JsonNode table) {
		if (table == null || table.isNull() || table.isMissingNode()) {
			return "";
		}
		JsonNode cells = table.path("cells");
		if (!cells.isArray()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (JsonNode row : cells) {
			if (!row.isArray()) {
				continue;
			}
			boolean first = true;
			for (JsonNode cell : row) {
				if (!first) {
					sb.append("\t");
				}
				first = false;
				sb.append(cell.path("text").asText("").replace('\n', ' ').trim());
			}
			sb.append("\n");
		}
		return sb.toString().trim();
	}

	/** 마크다운: | 로 시작하는 연속 줄은 하나의 표 필드로 묶음 (줄 단위 쪼개짐 방지) */
	private static void addFieldsFromMarkdown(String md, ArrayNode fields, int[] nextId) {
		String[] lines = md.split("\\r?\\n", -1);
		List<String> tableBuf = new ArrayList<>();
		int mdTableIdx = 0;
		for (String line : lines) {
			String trimmed = line.trim();
			boolean mdRow = trimmed.startsWith("|");
			if (mdRow) {
				String sepProbe = trimmed.replace("-", "").replace("|", "").replace(":", "").trim();
				if (sepProbe.isEmpty()) {
					continue;
				}
				tableBuf.add(line);
			} else {
				if (!tableBuf.isEmpty()) {
					mdTableIdx++;
					flushMarkdownTable(tableBuf, fields, nextId, mdTableIdx);
					tableBuf.clear();
				}
				if (trimmed.isEmpty()) {
					continue;
				}
				String text = trimmed.replaceAll("^#+\\s*", "").trim();
				if (text.isEmpty()) {
					continue;
				}
				if (text.length() > 120) {
					text = text.substring(0, 120) + "…";
				}
				ObjectNode f = MAPPER.createObjectNode();
				f.put("id", "auto_" + (nextId[0]++));
				f.put("label", text);
				f.put("type", "text");
				fields.add(f);
				if (fields.size() > 200) {
					return;
				}
			}
		}
		if (!tableBuf.isEmpty()) {
			mdTableIdx++;
			flushMarkdownTable(tableBuf, fields, nextId, mdTableIdx);
		}
	}

	private static void flushMarkdownTable(List<String> tableBuf, ArrayNode fields, int[] nextId, int mdTableIdx) {
		String joined = String.join("\n", tableBuf).trim();
		if (joined.isEmpty()) {
			return;
		}
		String firstLine = tableBuf.get(0).trim();
		if (firstLine.length() > 80) {
			firstLine = firstLine.substring(0, 80) + "…";
		}
		ObjectNode f = MAPPER.createObjectNode();
		f.put("id", "auto_" + (nextId[0]++));
		f.put("label", "표 (마크다운 " + mdTableIdx + ") — " + firstLine);
		f.put("type", "textarea");
		f.put("parsedPreview", joined.length() > 8000 ? joined.substring(0, 8000) + "…" : joined);
		fields.add(f);
	}

	private static String buildFailedDraft(String msg) {
		try {
			ObjectNode out = MAPPER.createObjectNode();
			out.put("schemaVersion", 1);
			out.put("parseStatus", "failed");
			out.put("message", msg != null ? msg : "unknown");
			out.set("fields", MAPPER.createArrayNode());
			return MAPPER.writeValueAsString(out);
		} catch (Exception e) {
			return "{\"schemaVersion\":1,\"parseStatus\":\"failed\",\"fields\":[],\"message\":\"json\"}";
		}
	}
}
