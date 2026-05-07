package com.newdbfield.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.ServletContext;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * kordoc CLI(template/fill 모드)를 호출해 HWPX 양식 분석·채우기.
 *
 *  template 모드: hwpx 양식 → slots[] JSON → field_schema(텍스트/이미지 필드들)로 변환
 *  fill 모드: hwpx 양식 + answers + photos → 채워진 hwpx
 *
 * 양쪽 다 실패하면 호출부에서 기존 markdown 경로로 폴백.
 */
public final class SurveyReportTemplateUtil {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int MAX_STDOUT_BYTES = 25 * 1024 * 1024;
	private static final int CALL_WAIT_SECONDS = 120;

	private SurveyReportTemplateUtil() { }

	/**
	 * 양식 hwpx 분석 → slot 기반 draft_field_schema JSON.
	 * 실패 시 null.
	 */
	public static String parseToTemplateSchema(ServletContext ctx, File hwpxFile) {
		File kordocHome = SurveyReportKordocUtil.resolveKordocHome(ctx);
		if (kordocHome == null || hwpxFile == null || !hwpxFile.isFile()) {
			return null;
		}
		File cliJs = new File(kordocHome, "dist/cli.js");
		if (!cliJs.isFile()) return null;

		ProcessBuilder pb = new ProcessBuilder(
				"node",
				cliJs.getAbsolutePath(),
				"template",
				hwpxFile.getAbsolutePath()
		);
		pb.directory(kordocHome);
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
		try {
			Process p = pb.start();
			byte[] raw = readWithTimeout(p, CALL_WAIT_SECONDS);
			if (raw == null || raw.length == 0) return null;
			String out = new String(raw, StandardCharsets.UTF_8).trim();
			String json = SurveyReportKordocUtil.extractFirstJsonObject(out);
			if (json == null || json.isEmpty()) return null;
			JsonNode root = MAPPER.readTree(json);
			if (!root.path("success").asBoolean(false)) return null;
			return buildSchemaFromSlots(root.path("slots"));
		} catch (Exception e) {
			System.err.println("[SurveyReportTemplateUtil] parseToTemplateSchema: " + e.getMessage());
			return null;
		}
	}

	/**
	 * 양식 hwpx + 응답·사진 → 채워진 hwpx 파일.
	 * @return 성공 여부
	 */
	public static boolean fillFromTemplate(ServletContext ctx, File templateHwpx, JsonNode answers,
			Map<String, File> photoBySlotId, File outputHwpx) {
		File kordocHome = SurveyReportKordocUtil.resolveKordocHome(ctx);
		if (kordocHome == null || templateHwpx == null || !templateHwpx.isFile() || outputHwpx == null) {
			return false;
		}
		File cliJs = new File(kordocHome, "dist/cli.js");
		if (!cliJs.isFile()) return false;

		File answersTmp = null;
		try {
			// CLI 입력 JSON 작성
			ObjectNode payload = MAPPER.createObjectNode();
			ObjectNode ansOut = MAPPER.createObjectNode();
			if (answers != null && answers.isObject()) {
				answers.fieldNames().forEachRemaining(k -> {
					JsonNode v = answers.get(k);
					if (v != null && !v.isNull()) ansOut.put(k, v.asText(""));
				});
			}
			payload.set("answers", ansOut);
			ObjectNode photosOut = MAPPER.createObjectNode();
			if (photoBySlotId != null) {
				for (Map.Entry<String, File> e : photoBySlotId.entrySet()) {
					if (e.getValue() != null && e.getValue().isFile()) {
						photosOut.put(e.getKey(), e.getValue().getAbsolutePath().replace("\\", "/"));
					}
				}
			}
			payload.set("photos", photosOut);

			answersTmp = File.createTempFile("survey_fill_", ".json");
			try (Writer w = new OutputStreamWriter(new FileOutputStream(answersTmp), StandardCharsets.UTF_8)) {
				w.write(MAPPER.writeValueAsString(payload));
			}

			ProcessBuilder pb = new ProcessBuilder(
					"node",
					cliJs.getAbsolutePath(),
					"fill",
					templateHwpx.getAbsolutePath(),
					"--answers", answersTmp.getAbsolutePath(),
					"--out", outputHwpx.getAbsolutePath()
			);
			pb.directory(kordocHome);
			pb.redirectError(ProcessBuilder.Redirect.INHERIT);

			Process p = pb.start();
			byte[] raw = readWithTimeout(p, CALL_WAIT_SECONDS);
			if (raw == null) return false;
			String out = new String(raw, StandardCharsets.UTF_8).trim();
			JsonNode root = MAPPER.readTree(SurveyReportKordocUtil.extractFirstJsonObject(out));
			if (!root.path("success").asBoolean(false)) {
				System.err.println("[SurveyReportTemplateUtil] fill failed: " + out);
				return false;
			}
			return outputHwpx.isFile() && outputHwpx.length() > 0;
		} catch (Exception e) {
			System.err.println("[SurveyReportTemplateUtil] fillFromTemplate: " + e.getMessage());
			return false;
		} finally {
			if (answersTmp != null && answersTmp.exists()) answersTmp.delete();
		}
	}

	/** schema에 slot-스타일 필드(id가 F\d+ 또는 IMG\d+)가 있으면 true. */
	public static boolean isSlotBasedSchema(JsonNode fieldSchemaOrFields) {
		JsonNode fields = fieldSchemaOrFields == null ? null
				: (fieldSchemaOrFields.isArray() ? fieldSchemaOrFields : fieldSchemaOrFields.path("fields"));
		if (fields == null || !fields.isArray() || fields.size() == 0) return false;
		for (JsonNode f : fields) {
			String id = f.path("id").asText("");
			if (id.matches("^(F|IMG)\\d+$")) return true;
		}
		return false;
	}

	private static String buildSchemaFromSlots(JsonNode slots) {
		try {
			ObjectNode out = MAPPER.createObjectNode();
			out.put("schemaVersion", 2);
			out.put("parseStatus", "ok");
			out.put("templateMode", true);
			ArrayNode fields = MAPPER.createArrayNode();
			if (slots != null && slots.isArray()) {
				for (JsonNode s : slots) {
					ObjectNode f = MAPPER.createObjectNode();
					String id = s.path("id").asText("");
					String label = s.path("label").asText("(항목)");
					String kind = s.path("kind").asText("text");
					f.put("id", id);
					f.put("label", label);
					f.put("type", "image".equals(kind) ? "image" : (label != null && label.length() > 30 ? "textarea" : "text"));
					if (s.has("preview")) f.set("preview", s.get("preview"));
					if (s.has("appendMode")) f.set("appendMode", s.get("appendMode"));
					if (s.has("templateMode")) f.set("templateMode", s.get("templateMode"));
					if (s.has("cellPath")) f.set("cellPath", s.get("cellPath"));
					f.put("kind", kind);
					fields.add(f);
				}
			}
			out.set("fields", fields);
			if (fields.size() == 0) {
				out.put("message", "양식에서 채울 슬롯을 찾지 못했습니다.");
			}
			return MAPPER.writeValueAsString(out);
		} catch (Exception e) {
			return null;
		}
	}

	private static byte[] readWithTimeout(Process p, int seconds) throws Exception {
		ExecutorService es = Executors.newSingleThreadExecutor();
		Future<Integer> exit = es.submit(() -> {
			try { return p.waitFor(); }
			catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
		});
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[65536];
		int total = 0;
		try (java.io.InputStream in = p.getInputStream()) {
			int n;
			while ((n = in.read(buf)) != -1) {
				if (total + n > MAX_STDOUT_BYTES) {
					bos.write(buf, 0, MAX_STDOUT_BYTES - total);
					break;
				}
				bos.write(buf, 0, n);
				total += n;
			}
		}
		try {
			exit.get(seconds, TimeUnit.SECONDS);
		} catch (TimeoutException te) {
			p.destroyForcibly();
			System.err.println("[SurveyReportTemplateUtil] node timeout");
			return null;
		} catch (Exception ignore) {
			// fallthrough
		} finally {
			es.shutdownNow();
		}
		return bos.toByteArray();
	}

	/** photos 매핑 만들기 헬퍼: schema 안의 image slot id 순서 ↔ 사진 파일 리스트. */
	public static java.util.Map<String, File> mapPhotosToImageSlots(JsonNode fieldSchema, List<File> photoFiles) {
		java.util.LinkedHashMap<String, File> out = new java.util.LinkedHashMap<>();
		if (fieldSchema == null || photoFiles == null || photoFiles.isEmpty()) return out;
		JsonNode fields = fieldSchema.isArray() ? fieldSchema : fieldSchema.path("fields");
		if (fields == null || !fields.isArray()) return out;
		int pi = 0;
		for (JsonNode f : fields) {
			if (pi >= photoFiles.size()) break;
			String kind = f.path("kind").asText("text");
			if (!"image".equals(kind)) continue;
			String id = f.path("id").asText("");
			if (id.isEmpty()) continue;
			File ph = photoFiles.get(pi++);
			if (ph != null && ph.isFile()) out.put(id, ph);
		}
		return out;
	}
}
