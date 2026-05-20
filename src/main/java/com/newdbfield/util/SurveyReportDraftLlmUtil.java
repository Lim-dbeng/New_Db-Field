package com.newdbfield.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.newdbfield.fac.FacFieldVO;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

/**
 * 조사 보고서 필드별 초안을 LLM(OpenAI 호환 Chat Completions)으로 생성한다.
 * web.xml: SURVEY_LLM_PROVIDER(기본 openai), OpenAI용 SURVEY_LLM_API_*,
 * Ollama용 SURVEY_LLM_OLLAMA_API_URL / SURVEY_LLM_OLLAMA_MODEL
 */
public final class SurveyReportDraftLlmUtil {

	/** OpenAI / Ollama 연결 정보 (Chat Completions 호환). */
	public static final class LlmConnection {
		public final String provider;
		public final String apiUrl;
		public final String apiKey;
		public final String model;

		LlmConnection(String provider, String apiUrl, String apiKey, String model) {
			this.provider = provider;
			this.apiUrl = apiUrl;
			this.apiKey = apiKey;
			this.model = model;
		}
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private SurveyReportDraftLlmUtil() {
	}

	public static String generateAndMergeAnswers(
			ServletContext servletContext,
			HttpServletRequest req,
			ArrayNode fields,
			List<FacFieldVO> fieldRows,
			String facilityCode,
			JsonNode existingAnswers,
			Connection conn) throws Exception {
		return generateAndMergeAnswers(servletContext, req, fields, fieldRows, facilityCode,
				existingAnswers, conn, Collections.<File>emptyList());
	}

	/** photos를 LLM(vision)에 함께 전달하는 멀티모달 버전. */
	public static String generateAndMergeAnswers(
			ServletContext servletContext,
			HttpServletRequest req,
			ArrayNode fields,
			List<FacFieldVO> fieldRows,
			String facilityCode,
			JsonNode existingAnswers,
			Connection conn,
			List<File> photos) throws Exception {
		return generateAndMergeAnswers(servletContext, req, fields, fieldRows, facilityCode,
				existingAnswers, conn, photos, null);
	}

	public static String generateAndMergeAnswers(
			ServletContext servletContext,
			HttpServletRequest req,
			ArrayNode fields,
			List<FacFieldVO> fieldRows,
			String facilityCode,
			JsonNode existingAnswers,
			Connection conn,
			List<File> photos,
			String referenceContext) throws Exception {
		return generateAndMergeAnswers(servletContext, req, fields, fieldRows, facilityCode,
				existingAnswers, conn, photos, referenceContext, null, null);
	}

	/** photos + referenceContext + userPrompt(사용자 정의 추가 지시)까지 함께 전달하는 풀버전. */
	public static String generateAndMergeAnswers(
			ServletContext servletContext,
			HttpServletRequest req,
			ArrayNode fields,
			List<FacFieldVO> fieldRows,
			String facilityCode,
			JsonNode existingAnswers,
			Connection conn,
			List<File> photos,
			String referenceContext,
			String userPrompt) throws Exception {
		return generateAndMergeAnswers(servletContext, req, fields, fieldRows, facilityCode,
				existingAnswers, conn, photos, referenceContext, userPrompt, null);
	}

	/**
	 * @param llmProvider 요청별 선택 {@code openai} | {@code ollama}. null이면 web.xml {@code SURVEY_LLM_PROVIDER}.
	 */
	public static String generateAndMergeAnswers(
			ServletContext servletContext,
			HttpServletRequest req,
			ArrayNode fields,
			List<FacFieldVO> fieldRows,
			String facilityCode,
			JsonNode existingAnswers,
			Connection conn,
			List<File> photos,
			String referenceContext,
			String userPrompt,
			String llmProvider) throws Exception {

		if (servletContext == null) {
			throw new IllegalArgumentException("ServletContext가 없습니다.");
		}
		LlmConnection llm = resolveConnection(servletContext, llmProvider);
		String apiUrl = llm.apiUrl;
		String apiKey = llm.apiKey;
		String model = llm.model;

		// 양식 키워드로 도메인 프로파일 자동 감지 — 매치되면 도메인 hint 추가, 아니면 generic
		SurveyDomainProfiles.Profile profile = SurveyDomainProfiles.detect(fields);
		if (apiUrl == null || apiUrl.isEmpty()) {
			throw new IllegalStateException("LLM URL 미설정입니다. provider=" + llm.provider
					+ " — web.xml에 SURVEY_LLM_API_URL 또는 SURVEY_LLM_OLLAMA_API_URL을 확인하세요.");
		}
		System.out.println("[SurveyReportDraftLlmUtil] provider=" + llm.provider + " model=" + model + " url=" + apiUrl);

		// ★ Stage 1: 시각 분류 — 사진만 보고 유형 판별 (양식·근거자료에 휘둘리지 않음).
		//   profile이 detect되고 photos가 있을 때만 실행. 결과는 Stage 2의 system prompt 최상단에 prepend.
		VisionClassification visionResult = null;
		boolean hasPhotosForCls = photos != null && !photos.isEmpty();
		if (profile != null && hasPhotosForCls) {
			try {
				visionResult = classifyByVision(apiUrl, apiKey, model, llm.provider, profile, photos);
				if (visionResult != null) {
					System.out.println("[Stage1] type=" + visionResult.type
							+ " confidence=" + visionResult.confidence
							+ " reasoning=" + truncate(visionResult.reasoning, 200));
				}
			} catch (Exception e) {
				System.err.println("[Stage1] classification failed (continuing without): " + e.getMessage());
			}
		}

		ObjectNode facilityMeta = loadFacilityMeta(conn, facilityCode, req);
		ObjectNode groupsJson = buildGroupsJson(fieldRows, facilityCode, req);

		// 알려진 사실(시설코드·사업번호 등)을 슬롯 라벨 키워드로 자동 매칭하여 사전 채움 — LLM 추측 방지
		ObjectNode preFilled = autoPreFillFromFacility(fields, facilityMeta, facilityCode);

		ObjectNode userPayload = MAPPER.createObjectNode();
		userPayload.set("facility", facilityMeta);
		userPayload.set("surveyGroups", groupsJson);
		userPayload.set("fields", fields);
		if (preFilled.size() > 0) {
			userPayload.set("preFilledFacts",
					preFilled.deepCopy().put("__note__", "이 값들은 시스템이 이미 알고 있는 사실입니다. "
							+ "해당 키는 답변에 절대 포함하지 말고(또는 동일 값 그대로), 다른 슬롯에만 답변하세요."));
			System.out.println("[SurveyReportDraftLlmUtil] preFilled facts=" + preFilled.size());
		}
		boolean hasRefs = referenceContext != null && !referenceContext.trim().isEmpty();
		if (hasRefs) {
			userPayload.put("referenceContext", referenceContext);
		}
		boolean hasPhotos = photos != null && !photos.isEmpty();
		String instruction;
		if (hasPhotos) {
			if (profile != null && profile.instructionStep1 != null) {
				instruction = profile.instructionStep1 + "\n"
						+ "2단계: 결정한 유형에 일관되게 각 필드의 답변을 작성하세요. "
						+ "손상·균열·노후도·주변 환경을 사진에서 관찰한 사실만 적고, 확인되지 않는 수치·명칭은 빈 문자열로.";
			} else {
				instruction = "첨부된 모든 사진을 살펴 각 필드 id에 대해 현장 조사 보고서용 한국어 초안을 작성하세요. "
						+ "손상·균열·노후도·주변 환경을 사진에서 관찰한 사실만 적고, 확인되지 않는 수치·명칭은 빈 문자열로 두세요.";
			}
		} else {
			instruction = "각 필드 id에 대해 현장 조사 보고서용 한국어 초안을 작성하세요. 컨텍스트에 없는 수치·명칭은 지어내지 마세요.";
		}
		userPayload.put("instruction", instruction);

		boolean hasUserPrompt = userPrompt != null && !userPrompt.trim().isEmpty();
		String roleIntro = "당신은 한국 공공시설 현장조사 보고서 작성 보조입니다.";
		if (profile != null) {
			roleIntro += " 양식 분석 결과 대상 도메인: " + profile.name + ".";
		}
		String visionBlock = "";
		if (visionResult != null && visionResult.type != null && !visionResult.type.isEmpty()) {
			visionBlock = "[★★★ 시각 분류 결과 (Stage 1) — 절대 우선 ★★★]\n"
					+ "별도의 시각 전용 호출(Stage 1)에서 사진만 보고 결정한 시설 유형입니다. "
					+ "Stage 1은 양식 라벨·근거자료에 휘둘리지 않은 순수 시각 판단입니다.\n"
					+ "  유형: " + visionResult.type + "\n"
					+ "  신뢰도: " + visionResult.confidence + "\n"
					+ "  근거: " + (visionResult.reasoning != null ? visionResult.reasoning : "") + "\n\n"
					+ "[Stage 2 작성 지침]\n"
					+ "- 위 '유형'을 무조건 따르세요. 양식 라벨('교량형식' 등)이 다른 단어를 시사해도 위 유형 우선.\n"
					+ "- '유형' 슬롯의 답변 = 위 type. '형식' 슬롯의 답변 = 위 type에 일관된 형식 (예: 암거→'철근콘크리트 박스형 암거', 소교량→'RC 슬래브교').\n"
					+ "- 신뢰도가 'low'이거나 type='확인불가'면 유형/형식 답변에 '확인불가'로 답하세요.\n\n";
		}
		String systemPrompt =
				ollamaJsonOutputPrefix(llm.provider)
						+ roleIntro + "\n\n"
						+ visionBlock
						+ (hasUserPrompt
							? "[사용자 지정 지침 — 최우선 적용]\n"
							+ userPrompt.trim()
							+ "\n\n"
							: "")
						+ (hasRefs
							? "[근거자료 활용 — 절대 우선]\n"
							+ "사용자가 'referenceContext'에 시설 평가기준·점검 매뉴얼·시방서·등급표 등을 첨부했습니다. 다음을 반드시 따르세요:\n"
							+ "1) 등급/점수 체계: 근거자료에 명시된 등급(예: A·B·C, 양호·보통·불량)과 점수 기준을 그대로 사용. 임의 등급 만들지 말 것.\n"
							+ "2) 평가 항목: 근거자료의 점검 항목 표현을 그대로 사용. 예: '제방단면 부족', '하도 침식·퇴적', '유송잡물에 따른 통수능 부족'.\n"
							+ "3) '조사자의견' 같은 narrative 슬롯: 근거자료의 평가 항목을 인용하면서 사진 관찰 결과를 연결하세요. "
							+ "예: '평가항목 \"호안 노후화 및 파손상태\" 검토 결과 — 사진상 우안 호안 콘크리트 일부 박리 → B등급 추정'.\n"
							+ "4) 근거자료를 보지 않은 듯 일반적 답변만 내면 안 됩니다. 적어도 한 두 항목은 명시적으로 인용하세요.\n"
							+ "5) 근거자료에 없는 임의 기준은 사용 금지.\n\n"
							: "")
						+ (profile != null
							? profile.promptAddition + "\n\n"
							: "")
						+ "[출력 형식]\n"
						+ "- 반드시 하나의 JSON 객체만 출력. 마크다운 코드블록 금지.\n"
						+ "- 키는 요청의 fields[].id 값과 정확히 일치.\n"
						+ "- id가 IMG로 시작하는 사진 슬롯은 키 자체를 출력에서 빼세요.\n"
						+ "- 'appendMode=true' 슬롯(preview가 콜론으로 끝남, 예 '・단면 :'): 라벨은 셀에 이미 있으니 답변엔 라벨 빼고 값만. "
						+ "예: preview='・단면 :' → 답변='철근콘크리트 박스형 (폭×높이 1.5×1.2 m)'.\n"
						+ "- 'templateMode=true' 슬롯(preview에 단위/심볼이 미리 박힘, 예 '・농경지 : ha', '북위 ° ' \"'): "
						+ "preview의 구조(라벨·단위·기호)를 그대로 유지하면서 빈 자리에만 값을 채워서 전체 문자열로 답하세요. "
						+ "예: preview='・농경지 : ha' → 답변='・농경지 : 5.2 ha'. "
						+ "예: preview='북위 ° ' \"' → 답변='북위 35° 12' 30\"'. "
						+ "사진에서 수치를 확인할 수 없으면 단위는 두고 수치 자리만 비운 채로 그대로 출력. 예: '・농경지 : ha'.\n"
						+ "- 각 슬롯은 표 셀이라 한두 줄(40자 내외)이 적당.\n"
						+ "- label에 '의견·소견·특기사항·평가' 들어간 슬롯은 다음 절차로 작성하세요:\n"
						+ "  ① 근거자료(referenceContext)에 평가기준이 있으면 모든 점검 항목(보통 8~12개)을 처음부터 끝까지 훑어볼 것.\n"
						+ "  ② 각 항목에 대해 사진에서 관찰 가능한지 확인. 가능한 항목은 '항목명: 관찰 결과(등급)' 형식으로 한 줄씩 작성.\n"
						+ "  ③ 적어도 5개 이상 항목을 명시. 분량 제한 없음.\n"
						+ "  ④ 각 줄은 '\\n'으로 줄바꿈해 표 셀에 보기 좋게.\n"
						+ "  예: '교량 부분파손·콘크리트 균열: 측벽 외관 노후, 미세 균열 일부 관찰 (B등급 추정)\\n"
						+ "교각·교대 세굴피해: 입출구부 일부 토사 퇴적 관찰, 추가 확인 필요\\n"
						+ "철근 노출·부식: 사진상 확인불가\\n...'.\n"
						+ "  ⑤ 마지막 줄에 종합 의견(1~2문장)을 적어 마무리.\n"
						+ "- 근거자료 없으면 사진 관찰 결과만 위와 비슷한 형식으로 5개 이상 항목 나열.\n"
						+ "- 사진에서 확인되지 않는 수치는 지어내지 말고 빈 문자열 또는 '확인불가'로.\n"
						+ "- '부속물' 관련 슬롯 처리 — 라벨로 명확히 구분:\n"
						+ "  · 라벨이 '시설부속물'(또는 '부속물' 단독)이면 **개수**를 적습니다. 예: '3개', '5개소', '없음'.\n"
						+ "  · 라벨이 '부속물 유형' / '부속물 종류'이면 **종류**를 적습니다. 예: '난간, 연석, 옹벽' (개수 아님).\n"
						+ "  · 시설 자체 유형(소교량/암거/흄관/세천 등)을 부속물 슬롯에 적지 마세요. 부속물은 시설에 딸린 보조 구조물(난간·연석·포장·옹벽·날개벽·가드레일·조명·안내판 등).\n"
						+ "  · 두 슬롯이 같이 존재하면 반드시 다르게 답: 한쪽은 개수, 다른쪽은 종류.\n\n"
						+ "[줄바꿈 규칙 — 셀 안에서 깔끔히 보이려면 다음을 지키세요]\n"
						+ "- 항목이 2개 이상 나열되는 슬롯(예: 부속물·유형 등)은 항목마다 '\\n'으로 줄바꿈. "
						+ "예: '날개벽\\n석축 옹벽\\n콘크리트 연석'.\n"
						+ "- '의견·소견·특기사항' 슬롯은 한 문장이 끝날 때마다 '\\n'으로 줄바꿈. "
						+ "예: '박스형 입출구와 석축 옹벽이 확인됨.\\n도로면 균열·단차 및 자갈 유실이 보임.\\n수로 내 토사·잡목 퇴적 확인됨.'.\n"
						+ "- 한 줄짜리 짧은 답변(예: 단면 치수, 날짜, 이름)은 줄바꿈 넣지 말 것.\n"
						+ "- JSON 문자열 안의 줄바꿈은 반드시 '\\n' 이스케이프 시퀀스로 적습니다.";

		ObjectNode rootReq = MAPPER.createObjectNode();
		rootReq.put("model", model);
		applyOllamaRequestOptions(rootReq, llm.provider);
		// gpt-5.x / o1·o3 같은 reasoning 모델은 temperature 커스텀을 거부(default=1만 허용).
		// 그 외 모델만 분류 일관성 위해 0.1로 내림.
		if (!isReasoningModel(model)) {
			rootReq.put("temperature", 0.1);
		}
		// OpenAI 등 클라우드만 출력 토큰 상한. Ollama는 모델/서버 기본값 사용(필드 생략).
		applyOutputTokenLimit(rootReq, llm.provider, model, 6000);
		ArrayNode messages = rootReq.putArray("messages");
		ObjectNode m0 = messages.addObject();
		m0.put("role", "system");
		m0.put("content", systemPrompt);
		ObjectNode m1 = messages.addObject();
		m1.put("role", "user");
		if (hasPhotos) {
			ArrayNode contentArr = m1.putArray("content");
			ObjectNode textPart = contentArr.addObject();
			textPart.put("type", "text");
			textPart.put("text", userPayload.toString());
			int attached = 0;
			for (File ph : photos) {
				if (ph == null || !ph.isFile()) continue;
				if (ph.length() <= 0 || ph.length() > 12L * 1024 * 1024) continue; // 12MB 가드
				String dataUrl = toDataUrl(ph);
				if (dataUrl == null) continue;
				ObjectNode imgPart = contentArr.addObject();
				imgPart.put("type", "image_url");
				ObjectNode imgUrl = imgPart.putObject("image_url");
				imgUrl.put("url", dataUrl);
				// Stage 2: 분류는 Stage 1에서 끝남. 여기는 narrative(조사자의견 등)에 손상 묘사용 → "low"로 페이로드 절감
				imgUrl.put("detail", "low");
				if (++attached >= 8) break; // 페이로드 절감 위해 8장 상한
			}
			System.out.println("[Stage2] vision photos attached=" + attached + "/" + photos.size() + " (detail=low)");
		} else {
			m1.put("content", userPayload.toString());
		}

		byte[] bodyBytes = MAPPER.writeValueAsBytes(rootReq);
		System.out.println("[Stage2] payload bytes=" + bodyBytes.length);

		// 5xx (게이트웨이 connection termination 등) 는 일시 장애일 가능성 → 재시도
		String respStr = postWithRetry(apiUrl, apiKey, bodyBytes, 2 /*max attempts after first*/);

		JsonNode respRoot = MAPPER.readTree(respStr);
		String content = respRoot.path("choices").path(0).path("message").path("content").asText("");
		if (content.isEmpty()) {
			throw new IllegalStateException("LLM 응답에 content가 없습니다.");
		}

		JsonNode generated;
		try {
			generated = MAPPER.readTree(extractJsonObject(content));
		} catch (Exception ex) {
			String hint = "ollama".equals(llm.provider)
					? " Ollama가 마크다운 등 비JSON을 반환한 경우입니다. 모델이 JSON 출력을 지원하는지, ollama serve 버전을 확인하세요."
					: "";
			throw new IllegalStateException(
					"LLM 응답 JSON 파싱 실패: " + ex.getMessage() + hint
							+ " (응답 앞부분: " + truncate(content.replace('\n', ' '), 120) + ")",
					ex);
		}
		if (!generated.isObject()) {
			throw new IllegalStateException("LLM 응답이 JSON 객체가 아닙니다.");
		}

		ObjectNode merged = MAPPER.createObjectNode();
		if (existingAnswers != null && existingAnswers.isObject()) {
			merged.setAll((ObjectNode) existingAnswers);
		}
		for (JsonNode f : fields) {
			String fid = f.path("id").asText("").trim();
			if (fid.isEmpty()) {
				continue;
			}
			// 사전채움 사실은 LLM 출력보다 우선 — 시스템이 알고 있는 행정정보는 LLM이 못 뒤집게
			if (preFilled.has(fid) && !preFilled.get(fid).isNull()) {
				merged.put(fid, preFilled.get(fid).asText(""));
				continue;
			}
			JsonNode g = generated.get(fid);
			if (g != null && !g.isNull()) {
				String textVal;
				if (g.isTextual()) {
					textVal = g.asText();
				} else if (g.isValueNode()) {
					textVal = g.asText();
				} else {
					textVal = g.toString();
				}
				merged.put(fid, textVal);
			} else if (!merged.has(fid)) {
				merged.put(fid, "");
			}
		}

		// Stage 1 결과로 유형/형식 일관성 강제 — Stage 2가 무시한 경우 보정
		if (visionResult != null && visionResult.type != null
				&& !visionResult.type.isEmpty() && !"확인불가".equals(visionResult.type)) {
			enforceTypeConsistency(merged, fields, visionResult);
		}
		return MAPPER.writeValueAsString(merged);
	}

	/**
	 * Stage 1 분류 결과(visionResult.type)와 Stage 2 출력의 '유형'·'형식' 답변이 어긋나면 type을 강제로 박는다.
	 *  - 라벨에 '유형'이 들어있는 슬롯: type 그대로 채움
	 *  - 라벨에 '형식'이 들어있는 슬롯: type와 안 맞는 답이면 일반화된 형식으로 교체
	 *  - 신뢰도 'low'면 둘 다 '확인불가'
	 */
	private static void enforceTypeConsistency(ObjectNode merged, ArrayNode fields, VisionClassification vc) {
		boolean lowConfidence = "low".equalsIgnoreCase(vc.confidence);
		String type = vc.type.trim();
		for (JsonNode f : fields) {
			String fid = f.path("id").asText("");
			String label = f.path("label").asText("");
			String preview = f.path("preview").asText("");
			if (fid.isEmpty() || label.isEmpty()) continue;
			// 시설 자체 유형/형식 슬롯은 preview가 '・유형 :' / '・형식 :' 으로 시작.
			// '부속물 유형' 같은 다른 슬롯(preview 없음)은 건드리지 않는다.
			String previewKey = preview.replaceAll("\\s", "");
			boolean isTypeSlot = previewKey.startsWith("・유형") || previewKey.startsWith("·유형");
			boolean isFormSlot = previewKey.startsWith("・형식") || previewKey.startsWith("·형식");
			if (!isTypeSlot && !isFormSlot) continue;

			String currentVal = merged.path(fid).asText("");
			if (lowConfidence) {
				merged.put(fid, "확인불가");
				continue;
			}
			if (isTypeSlot) {
				// 라벨이 '유형': Stage 1 type을 그대로
				if (!currentVal.contains(type)) {
					System.out.println("[Stage1-enforce] " + fid + " 유형 보정: '" + currentVal + "' → '" + type + "'");
					merged.put(fid, type);
				}
				continue;
			}
			if (isFormSlot) {
				// 라벨이 '형식': type과 일관되지 않으면 일반 형식으로 대체
				boolean consistent = isFormConsistentWithType(currentVal, type);
				if (!consistent) {
					String fallback = defaultFormByType(type);
					System.out.println("[Stage1-enforce] " + fid + " 형식 보정: '" + currentVal + "' → '" + fallback + "' (type=" + type + ")");
					merged.put(fid, fallback);
				}
			}
		}
	}

	private static boolean isFormConsistentWithType(String form, String type) {
		if (form == null || form.isEmpty()) return true; // 빈 값은 LLM이 답 못한 거니 보정 대상 아님(type 슬롯에서 처리)
		String f = form.toLowerCase();
		if (type.contains("암거")) {
			return f.contains("암거") || f.contains("박스") || f.contains("box");
		}
		if (type.contains("소교량")) {
			return f.contains("교") || f.contains("슬래브") || f.contains("빔") || f.contains("rc") || f.contains("psc");
		}
		if (type.contains("흄관")) {
			return f.contains("흄") || f.contains("원형") || f.contains("관");
		}
		return true;
	}

	private static String defaultFormByType(String type) {
		if (type.contains("암거")) return "철근콘크리트 박스형 암거";
		if (type.contains("소교량")) return "RC 슬래브교";
		if (type.contains("흄관")) return "원형 흄관";
		return "확인불가";
	}

	private static ObjectNode loadFacilityMeta(Connection conn, String code, HttpServletRequest req) throws Exception {
		ObjectNode o = MAPPER.createObjectNode();
		o.put("code", code == null ? "" : code);
		if (conn == null || code == null || code.trim().isEmpty()) {
			return o;
		}
		try (PreparedStatement ps = conn.prepareStatement(
				// ST_Centroid로 감싸 어떤 geometry(점/선/면)든 대표 좌표 추출.
				// SRID 4326(WGS84) 가정 — gis_a_layer에 다른 SRID면 ST_Transform 필요.
				"SELECT project_code, "
				+ "  ST_X(ST_Centroid(geometry)) AS lng, "
				+ "  ST_Y(ST_Centroid(geometry)) AS lat, "
				+ "  ST_SRID(geometry) AS srid "
				+ "FROM public.gis_a_layer WHERE code = ?")) {
			ps.setString(1, code.trim());
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					o.put("project_code", rs.getString("project_code"));
					double lng = rs.getDouble("lng");
					boolean lngWasNull = rs.wasNull();
					double lat = rs.getDouble("lat");
					boolean latWasNull = rs.wasNull();
					int srid = rs.getInt("srid");
					System.out.println("[loadFacilityMeta] code=" + code + " lat=" + (latWasNull ? "null" : lat)
							+ " lng=" + (lngWasNull ? "null" : lng) + " srid=" + srid);
					if (!latWasNull && !lngWasNull) {
						// SRID 4326이 아니면 변환된 게 아니라 그냥 raw 좌표 (위경도 아닐 수 있음).
						// 한국 좌표계(EPSG:5179, 5181, 5186 등)일 경우 ST_Transform으로 4326으로 바꿔야 도분초 표기 의미.
						if (srid == 4326 || srid == 0) {
							o.put("lng", lng);
							o.put("lat", lat);
						} else {
							// 다른 SRID — ST_Transform 사용해 다시 조회
							try (PreparedStatement ps2 = conn.prepareStatement(
									"SELECT ST_X(ST_Transform(ST_Centroid(geometry), 4326)) AS lng, "
									+ "       ST_Y(ST_Transform(ST_Centroid(geometry), 4326)) AS lat "
									+ "FROM public.gis_a_layer WHERE code = ?")) {
								ps2.setString(1, code.trim());
								try (ResultSet rs2 = ps2.executeQuery()) {
									if (rs2.next()) {
										double lng2 = rs2.getDouble("lng");
										double lat2 = rs2.getDouble("lat");
										if (!rs2.wasNull()) {
											o.put("lng", lng2);
											o.put("lat", lat2);
											System.out.println("[loadFacilityMeta] transformed to WGS84: lat=" + lat2 + " lng=" + lng2);
										}
									}
								}
							}
						}
					}
				} else {
					System.err.println("[loadFacilityMeta] no row in gis_a_layer for code=" + code);
				}
			}
		}
		return o;
	}

	private static ObjectNode buildGroupsJson(List<FacFieldVO> fieldRows, String facilityCode, HttpServletRequest req) {
		ObjectNode root = MAPPER.createObjectNode();
		TreeMap<Integer, ObjectNode> groups = new TreeMap<>();
		String ctxPath = req != null ? req.getContextPath() : "";
		for (FacFieldVO row : fieldRows) {
			int gi = row.getGroupIndex() != null ? row.getGroupIndex() : 0;
			ObjectNode g = groups.computeIfAbsent(gi, k -> MAPPER.createObjectNode());
			if (row.getSurvey() != null && !row.getSurvey().trim().isEmpty()) {
				g.put("comment", row.getSurvey().trim());
			}
			if (row.getImage() != null && !row.getImage().trim().isEmpty()) {
				ArrayNode photos = g.withArray("photos");
				ObjectNode p = MAPPER.createObjectNode();
				p.put("fileName", row.getImage());
				if (row.getPhotoDirection() != null) {
					p.put("direction", row.getPhotoDirection());
				}
				if (row.getSurveyDate() != null) {
					p.put("surveyDate", row.getSurveyDate().toString());
				}
				if (row.getSurveyUserName() != null) {
					p.put("surveyUserName", row.getSurveyUserName());
				}
				p.put("url", ctxPath + "/DCIM/" + row.getImage());
				photos.add(p);
			}
		}
		for (java.util.Map.Entry<Integer, ObjectNode> en : groups.entrySet()) {
			root.set("group_" + en.getKey(), en.getValue());
		}
		root.put("facilityCode", facilityCode != null ? facilityCode : "");
		return root;
	}

	static String extractJsonObject(String raw) throws Exception {
		if (raw == null) {
			throw new IllegalArgumentException("empty");
		}
		String s = raw.trim();
		if (s.startsWith("```")) {
			int nl = s.indexOf('\n');
			if (nl > 0) {
				s = s.substring(nl + 1).trim();
			}
			int endFence = s.lastIndexOf("```");
			if (endFence > 0) {
				s = s.substring(0, endFence).trim();
			}
		}
		int start = s.indexOf('{');
		int end = s.lastIndexOf('}');
		if (start < 0 || end <= start) {
			throw new IllegalArgumentException(
					"응답에 JSON 객체({...})가 없습니다. 마크다운·설명문 대신 JSON만 출력해야 합니다.");
		}
		return s.substring(start, end + 1);
	}

	private static byte[] readAll(InputStream in) throws Exception {
		if (in == null) {
			return new byte[0];
		}
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) >= 0) {
			bos.write(buf, 0, n);
		}
		return bos.toByteArray();
	}

	private static String truncate(String s, int max) {
		if (s == null) {
			return "";
		}
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}

	private static String trimOrNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private static String firstNonBlank(String a, String b) {
		String ta = trimOrNull(a);
		if (ta != null) {
			return ta;
		}
		return trimOrNull(b);
	}

	private static String firstNonBlank(String a, String b, String c) {
		String ab = firstNonBlank(a, b);
		return ab != null ? ab : trimOrNull(c);
	}

	/** openai | ollama. null/빈 값이면 null. 잘못된 값은 IllegalArgumentException. */
	public static String normalizeProvider(String raw) {
		if (raw == null) {
			return null;
		}
		String p = raw.trim().toLowerCase();
		if (p.isEmpty()) {
			return null;
		}
		if ("openai".equals(p) || "oai".equals(p)) {
			return "openai";
		}
		if ("ollama".equals(p) || "local".equals(p)) {
			return "ollama";
		}
		throw new IllegalArgumentException("llmProvider는 openai 또는 ollama 여야 합니다: " + raw);
	}

	/**
	 * OpenAI / Ollama 설정 해석. providerOverride가 있으면 우선, 없으면 SURVEY_LLM_PROVIDER(기본 openai).
	 */
	public static LlmConnection resolveConnection(ServletContext ctx, String providerOverride) {
		String provider = normalizeProvider(providerOverride);
		if (provider == null) {
			provider = normalizeProvider(lookupConfig(ctx, "SURVEY_LLM_PROVIDER"));
		}
		if (provider == null) {
			provider = "openai";
		}
		if ("ollama".equals(provider)) {
			String apiUrl = firstNonBlank(
					lookupConfig(ctx, "SURVEY_LLM_OLLAMA_API_URL"),
					"http://localhost:11434/v1/chat/completions");
			String model = firstNonBlank(lookupConfig(ctx, "SURVEY_LLM_OLLAMA_MODEL"), "qwen3:14b");
			String apiKey = trimOrNull(lookupConfig(ctx, "SURVEY_LLM_OLLAMA_API_KEY"));
			return new LlmConnection(provider, apiUrl, apiKey != null ? apiKey : "", model);
		}
		String apiUrl = lookupConfig(ctx, "SURVEY_LLM_API_URL");
		String apiKey = firstNonBlank(
				lookupConfig(ctx, "SURVEY_LLM_API_KEY"),
				lookupConfig(ctx, "OPENAI_API_KEY"));
		String model = firstNonBlank(lookupConfig(ctx, "SURVEY_LLM_MODEL"), "gpt-4o");
		return new LlmConnection("openai", apiUrl, apiKey != null ? apiKey : "", model);
	}

	/** 사진 파일 → "data:image/jpeg;base64,..." URL. 너무 크거나 IO 실패 시 null. */
	private static String toDataUrl(File f) {
		try {
			byte[] bytes = Files.readAllBytes(f.toPath());
			String mime = guessImageMime(f.getName());
			return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
		} catch (Exception e) {
			System.err.println("[SurveyReportDraftLlmUtil] image load failed: " + f.getAbsolutePath() + " - " + e.getMessage());
			return null;
		}
	}

	private static String guessImageMime(String name) {
		String n = name == null ? "" : name.toLowerCase();
		if (n.endsWith(".png")) return "image/png";
		if (n.endsWith(".gif")) return "image/gif";
		if (n.endsWith(".webp")) return "image/webp";
		if (n.endsWith(".bmp")) return "image/bmp";
		return "image/jpeg";
	}

	/**
	 * 슬롯 라벨에서 키워드를 보고 시스템이 이미 아는 사실(시설코드, 사업번호 등)을 자동 채움.
	 * LLM이 이런 행정 정보까지 추측하느라 잘못 답하는 것 방지.
	 */
	private static ObjectNode autoPreFillFromFacility(ArrayNode fields, ObjectNode facilityMeta, String facilityCode) {
		ObjectNode out = MAPPER.createObjectNode();
		if (fields == null || facilityMeta == null) return out;
		String code = facilityCode != null ? facilityCode.trim() : "";
		String projectCode = facilityMeta.path("project_code").asText("");
		for (JsonNode f : fields) {
			String fid = f.path("id").asText("");
			String label = f.path("label").asText("");
			if (fid.isEmpty() || label.isEmpty()) continue;
			// 사진 슬롯은 텍스트 사전채움 대상 아님
			if ("image".equals(f.path("kind").asText(""))) continue;
			// 관리번호 슬롯 → 시설코드
			if (!code.isEmpty() && (label.contains("관리번호") && !label.contains("상위"))) {
				out.put(fid, code);
				continue;
			}
			// 사업번호 슬롯 → project_code
			if (!projectCode.isEmpty() && (label.contains("사업번호") || label.contains("프로젝트"))) {
				out.put(fid, projectCode);
				continue;
			}
		}

		// 좌표 — facility에 대표 좌표 1개뿐이므로 한 좌표 그룹만 채움.
		// 그룹 식별: 시점부 라벨 우선 → 같은 컬럼에 있는 동경 슬롯 함께 채움.
		// 그래야 '시점부 북위 + 종점부 동경' 같이 어긋나게 채워지지 않음.
		double lat = facilityMeta.path("lat").asDouble(Double.NaN);
		double lng = facilityMeta.path("lng").asDouble(Double.NaN);
		if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
			String latToFill = null;
			int latCol = -1;
			String lngToFill = null;
			// 1) 시점부 라벨이 있는 북위 슬롯 우선
			for (JsonNode f : fields) {
				String fid = f.path("id").asText("");
				if (fid.isEmpty()) continue;
				if ("image".equals(f.path("kind").asText(""))) continue;
				String label = f.path("label").asText("");
				String preview = f.path("preview").asText("");
				boolean hasLat = preview.contains("북위") || label.contains("북위");
				if (!hasLat) continue;
				boolean isStart = label.contains("시점") || preview.contains("시점");
				if (isStart && latToFill == null) {
					latToFill = fid;
					latCol = f.path("cellPath").path("colAddr").asInt(-1);
				}
			}
			// 2) 시점부 표시가 없으면 첫 북위 슬롯
			if (latToFill == null) {
				for (JsonNode f : fields) {
					String fid = f.path("id").asText("");
					if (fid.isEmpty()) continue;
					if ("image".equals(f.path("kind").asText(""))) continue;
					String label = f.path("label").asText("");
					String preview = f.path("preview").asText("");
					if (preview.contains("북위") || label.contains("북위")) {
						latToFill = fid;
						latCol = f.path("cellPath").path("colAddr").asInt(-1);
						break;
					}
				}
			}
			// 3) 동경 슬롯: 1순위 같은 컬럼(시점부 그룹), 없으면 첫 동경
			if (latCol >= 0) {
				for (JsonNode f : fields) {
					String fid = f.path("id").asText("");
					if (fid.isEmpty()) continue;
					if ("image".equals(f.path("kind").asText(""))) continue;
					String label = f.path("label").asText("");
					String preview = f.path("preview").asText("");
					boolean hasLng = preview.contains("동경") || label.contains("동경");
					if (!hasLng) continue;
					int col = f.path("cellPath").path("colAddr").asInt(-1);
					if (col == latCol) { lngToFill = fid; break; }
				}
			}
			if (lngToFill == null) {
				for (JsonNode f : fields) {
					String fid = f.path("id").asText("");
					if (fid.isEmpty()) continue;
					if ("image".equals(f.path("kind").asText(""))) continue;
					String label = f.path("label").asText("");
					String preview = f.path("preview").asText("");
					if (preview.contains("동경") || label.contains("동경")) {
						lngToFill = fid;
						break;
					}
				}
			}
			if (latToFill != null) out.put(latToFill, formatDms("북위", lat));
			if (lngToFill != null) out.put(lngToFill, formatDms("동경", lng));
		}
		return out;
	}

	/** decimal degree → 도분초 표기. 예: 37.858369 → "북위 37° 51' 30\"" */
	private static String formatDms(String prefix, double deg) {
		double abs = Math.abs(deg);
		int d = (int) abs;
		double minRem = (abs - d) * 60.0;
		int m = (int) minRem;
		double secRem = (minRem - m) * 60.0;
		int s = (int) Math.round(secRem);
		if (s == 60) { s = 0; m += 1; }
		if (m == 60) { m = 0; d += 1; }
		return prefix + " " + d + "° " + m + "' " + s + "\"";
	}

	// ─── Stage 1: 시각 분류 ──────────────────────────────────────

	public static final class VisionClassification {
		public final String type;
		public final String confidence;
		public final String reasoning;
		public VisionClassification(String type, String confidence, String reasoning) {
			this.type = type;
			this.confidence = confidence;
			this.reasoning = reasoning;
		}
	}

	/**
	 * Stage 1: 사진만 보고 시설 유형(소교량/암거/흄관/확인불가)을 결정한다.
	 * 양식·근거자료·이전 답변은 일체 입력에 안 들어가고, 도메인 정의(profile)만 system prompt로 둠.
	 * → 시각 판단이 텍스트 컨텍스트에 휘둘리지 않도록 격리.
	 */
	private static VisionClassification classifyByVision(
			String apiUrl, String apiKey, String model, String provider,
			SurveyDomainProfiles.Profile profile, List<File> photos) throws Exception {
		String stage1System =
				ollamaJsonOutputPrefix(provider)
						+ "당신은 사진만 보고 한국 공공시설 유형을 분류하는 시각 전문가입니다.\n\n"
						+ profile.promptAddition + "\n\n"
						+ "[출력 형식 — 반드시 지킬 것]\n"
						+ "다음 JSON 객체 하나만 출력. 마크다운 금지.\n"
						+ "{\n"
						+ "  \"type\": \"<소교량|암거|흄관|확인불가>\",\n"
						+ "  \"confidence\": \"<high|medium|low>\",\n"
						+ "  \"reasoning\": \"<2~3문장. 어떤 사진의 어떤 시각적 단서로 그렇게 분류했는지>\"\n"
						+ "}";
		String stage1User =
				"첨부된 사진 모두를 살펴보고 시설 유형을 분류하세요. "
						+ "양식 라벨이나 사용자 메모는 입력에 없으니, 오직 사진의 시각 단서로만 판단합니다. "
						+ "한 장이라도 박스형 입출구가 보이면 '암거'. 원형 관 입출구면 '흄관'. "
						+ "분리된 교대+슬래브+다리 밑 빈 공간이 보이면 '소교량'. "
						+ "어디에도 해당 안 하면 '확인불가'.";

		ObjectNode root = MAPPER.createObjectNode();
		root.put("model", model);
		applyOllamaRequestOptions(root, provider);
		if (!isReasoningModel(model)) {
			root.put("temperature", 0.0);
		}
		applyOutputTokenLimit(root, provider, model, 1500);
		ArrayNode messages = root.putArray("messages");
		ObjectNode m0 = messages.addObject();
		m0.put("role", "system");
		m0.put("content", stage1System);
		ObjectNode m1 = messages.addObject();
		m1.put("role", "user");
		ArrayNode contentArr = m1.putArray("content");
		ObjectNode tp = contentArr.addObject();
		tp.put("type", "text");
		tp.put("text", stage1User);
		int attached = 0;
		for (File ph : photos) {
			if (ph == null || !ph.isFile()) continue;
			if (ph.length() <= 0 || ph.length() > 12L * 1024 * 1024) continue;
			String dataUrl = toDataUrl(ph);
			if (dataUrl == null) continue;
			ObjectNode imgPart = contentArr.addObject();
			imgPart.put("type", "image_url");
			ObjectNode imgUrl = imgPart.putObject("image_url");
			imgUrl.put("url", dataUrl);
			imgUrl.put("detail", "high");
			if (++attached >= 12) break;
		}
		System.out.println("[Stage1] vision photos attached=" + attached + "/" + photos.size() + " (detail=high)");
		if (attached == 0) return null;

		byte[] body = MAPPER.writeValueAsBytes(root);
		System.out.println("[Stage1] payload bytes=" + body.length);
		String respStr = postWithRetry(apiUrl, apiKey, body, 2);
		JsonNode resp = MAPPER.readTree(respStr);
		String content = resp.path("choices").path(0).path("message").path("content").asText("");
		if (content.isEmpty()) return null;
		JsonNode parsed;
		try { parsed = MAPPER.readTree(extractJsonObject(content)); }
		catch (Exception ex) { throw new IllegalStateException("Stage1 응답 JSON 파싱 실패: " + ex.getMessage()); }

		String type = parsed.path("type").asText("").trim();
		String confidence = parsed.path("confidence").asText("medium").trim().toLowerCase();
		String reasoning = parsed.path("reasoning").asText("").trim();
		if (type.isEmpty()) return null;
		return new VisionClassification(type, confidence, reasoning);
	}

	/**
	 * LLM HTTP POST + 5xx 재시도 (지수 백오프). 4xx는 즉시 실패.
	 * 게이트웨이 'connection termination' 같은 일시 장애 대응.
	 */
	private static String postWithRetry(String apiUrl, String apiKey, byte[] body, int retries) throws Exception {
		Exception lastError = null;
		int totalAttempts = 1 + Math.max(0, retries);
		long backoffMs = 1500;
		for (int attempt = 1; attempt <= totalAttempts; attempt++) {
			try {
				HttpURLConnection c = (HttpURLConnection) new URL(apiUrl).openConnection();
				c.setRequestMethod("POST");
				c.setConnectTimeout(20000);
				c.setReadTimeout(180000);
				c.setDoOutput(true);
				c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
				if (apiKey != null && !apiKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + apiKey);
				try (java.io.OutputStream os = c.getOutputStream()) { os.write(body); }
				int status = c.getResponseCode();
				InputStream stream = (status >= 200 && status < 300) ? c.getInputStream() : c.getErrorStream();
				byte[] raw = readAll(stream);
				String resp = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
				if (status >= 200 && status < 300) return resp;
				if (status >= 500 && status < 600 && attempt < totalAttempts) {
					System.err.println("[LLM] HTTP " + status + " — retry " + attempt + "/" + (totalAttempts - 1)
							+ " in " + backoffMs + "ms. body=" + truncate(resp, 200));
					try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
					backoffMs *= 2;
					continue;
				}
				throw new IllegalStateException("LLM HTTP " + status + ": " + truncate(resp, 800));
			} catch (java.io.IOException ioEx) {
				lastError = ioEx;
				if (attempt < totalAttempts) {
					System.err.println("[LLM] " + ioEx.getClass().getSimpleName() + " — retry " + attempt + "/" + (totalAttempts - 1) + " in " + backoffMs + "ms");
					try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
					backoffMs *= 2;
				}
			}
		}
		throw new IllegalStateException("LLM 호출 최대 재시도 초과: " + (lastError != null ? lastError.getMessage() : "unknown"));
	}

	/**
	 * 설정값 조회 우선순위: web.xml ServletContext init-param → OS 환경변수 → .env 파일.
	 * .env 파일은 Java 표준이 아니지만 Node 컨벤션을 흉내내 읽어준다.
	 */
	private static String lookupConfig(ServletContext ctx, String key) {
		if (ctx != null) {
			String v = trimOrNull(ctx.getInitParameter(key));
			if (v != null && !v.isEmpty()) return v;
		}
		String env = trimOrNull(System.getenv(key));
		if (env != null && !env.isEmpty()) return env;
		String dot = trimOrNull(readDotEnv().get(key));
		return (dot != null && !dot.isEmpty()) ? dot : null;
	}

	private static volatile java.util.Map<String, String> DOT_ENV_CACHE = null;
	private static volatile long DOT_ENV_LOADED_AT = 0;

	/** .env 파일을 후보 경로들에서 찾아 KEY=VALUE 라인을 파싱. 5분 캐시. */
	private static java.util.Map<String, String> readDotEnv() {
		long now = System.currentTimeMillis();
		if (DOT_ENV_CACHE != null && (now - DOT_ENV_LOADED_AT) < 5 * 60 * 1000) {
			return DOT_ENV_CACHE;
		}
		java.util.Map<String, String> map = new java.util.HashMap<>();
		// 후보 경로들 — 일반적인 위치 순회
		String[] candidates = new String[] {
				System.getProperty("user.dir") + "/.env",
				System.getProperty("catalina.base") != null ? System.getProperty("catalina.base") + "/../.env" : null,
				System.getProperty("catalina.base") != null ? System.getProperty("catalina.base") + "/../../.env" : null,
				"D:/PROJECT/Db-Field/New_Db-Field/.env",
				"./.env"
		};
		for (String path : candidates) {
			if (path == null) continue;
			try {
				File f = new File(path).getCanonicalFile();
				if (!f.isFile()) continue;
				try (java.io.BufferedReader r = new java.io.BufferedReader(
						new java.io.InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
					String line;
					while ((line = r.readLine()) != null) {
						line = line.trim();
						if (line.isEmpty() || line.startsWith("#")) continue;
						int eq = line.indexOf('=');
						if (eq <= 0) continue;
						String k = line.substring(0, eq).trim();
						String v = line.substring(eq + 1).trim();
						// quotes 제거
						if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
							v = v.substring(1, v.length() - 1);
						}
						if (!k.isEmpty()) map.put(k, v);
					}
				}
				System.out.println("[SurveyReportDraftLlmUtil] .env loaded from " + f.getAbsolutePath() + " (" + map.size() + " keys)");
				break;
			} catch (Exception ignore) {
				// 다음 후보
			}
		}
		DOT_ENV_CACHE = map;
		DOT_ENV_LOADED_AT = now;
		return map;
	}

	/** Ollama Chat API: JSON 모드 (마크다운 설명문 방지). */
	private static void applyOllamaRequestOptions(ObjectNode root, String provider) {
		if (root == null || !"ollama".equals(provider)) {
			return;
		}
		root.put("format", "json");
	}

	/** Ollama 등 로컬 모델용 — 출력을 JSON 객체로 강제하는 system 프롬프트 접두어. */
	private static String ollamaJsonOutputPrefix(String provider) {
		if (!"ollama".equals(provider)) {
			return "";
		}
		return "[출력 형식 — 최우선, 위반 시 실패]\n"
				+ "- 응답 전체는 반드시 하나의 JSON 객체만. 첫 글자는 {, 마지막 글자는 }.\n"
				+ "- 마크다운(#, ##, ###, **, ---, 목록)·제목·설명 문단·이미지별 서술 형식 금지.\n"
				+ "- 코드블록(```) 금지. JSON 이외의 텍스트 한 글자도 앞뒤에 붙이지 마세요.\n\n";
	}

	/**
	 * 클라우드(OpenAI)만 max_tokens 상한 적용. Ollama는 요청에 토큰 필드를 넣지 않음.
	 */
	private static void applyOutputTokenLimit(ObjectNode root, String provider, String model, int maxTokens) {
		if (root == null || maxTokens <= 0) {
			return;
		}
		if ("ollama".equals(provider)) {
			return;
		}
		if (isReasoningModel(model)) {
			root.put("max_completion_tokens", maxTokens);
		} else {
			root.put("max_tokens", maxTokens);
		}
	}

	/** gpt-5.x / o1·o3 등 reasoning 모델 — temperature·top_p 커스텀 거부, default=1만 허용. */
	private static boolean isReasoningModel(String model) {
		if (model == null) return false;
		String m = model.toLowerCase().trim();
		return m.startsWith("gpt-5") || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4");
	}
}
