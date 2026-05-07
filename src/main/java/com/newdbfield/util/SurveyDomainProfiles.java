package com.newdbfield.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 양식별 도메인 프로파일 — fields[]의 라벨/preview에서 키워드를 감지해
 * 해당 도메인의 LLM 시스템 프롬프트 보강 텍스트를 제공한다.
 *
 * 검출 규칙:
 *  1) domainSignals 중 적어도 하나가 form fields에 매치되어야 함 (도메인 고유 키워드)
 *  2) supporting 매치 수 + domainSignal 매치 수로 점수
 *  3) 가장 높은 점수의 프로파일 채택
 *
 * 신규 도메인 추가는 ALL 리스트에 새 Profile 추가만 하면 됨.
 */
public final class SurveyDomainProfiles {

	public static final class Profile {
		public final String name;
		/** 이 도메인에만 등장하는 고유 키워드 — 적어도 하나는 매치되어야 함 */
		public final Set<String> domainSignals;
		/** 보조 키워드 (다른 도메인과 공유 가능) — 매치 점수 가산 */
		public final Set<String> supporting;
		public final String promptAddition;
		public final String instructionStep1;

		public Profile(String name, Set<String> domainSignals, Set<String> supporting,
				String promptAddition, String instructionStep1) {
			this.name = name;
			this.domainSignals = domainSignals;
			this.supporting = supporting;
			this.promptAddition = promptAddition;
			this.instructionStep1 = instructionStep1;
		}
	}

	// ─── 소교량/암거/흄관 ──────────────────────────────────────

	private static final Profile BRIDGE_CULVERT_PIPE = new Profile(
			"소규모 공공시설 (소교량/암거/흄관)",
			new HashSet<>(Arrays.asList(
					// 이 도메인에만 있는 고유 키워드 (교량/암거/흄관 형식 표가 있는 경우)
					"교량명", "교량형식", "암거", "흄관"
			)),
			new HashSet<>(Arrays.asList(
					"단면", "평면", "유형", "형식",
					"위험시설", "북위", "동경", "수혜지역",
					"부속물", "시설부속물", "관할기관", "대표필지"
			)),
			"[★★★ 시설 유형 판별 — 가장 중요한 규칙. 어기면 작업 실패] ★★★\n\n"
					+ "이 양식은 '소교량·암거·흄관' 세 가지를 모두 다루는 통합 양식입니다. 양식 라벨에 '교량명·교량형식'이 있다고 해서 무조건 다리(소교량)로 답하지 마세요. "
					+ "그건 단지 양식의 통합 라벨일 뿐, 실제 시설은 사진을 보고 결정해야 합니다.\n\n"
					+ "[판별 절차 — 반드시 이 순서로]\n"
					+ "STEP 1. 양식 라벨('교량명', '교량형식' 등)을 머릿속에서 잠시 무시하세요.\n"
					+ "STEP 2. 첨부된 모든 사진을 한 장씩 본다. 시설 본체 사진(도로면 말고 구조물 자체)을 찾는다.\n"
					+ "STEP 3. 다음 우선순위로 판별한다:\n"
					+ "  (a) 박스형(사각형) 콘크리트 입출구가 보이는가? → **암거(box culvert)**. 형식은 '철근콘크리트 박스형 암거' 또는 '석축형 암거'.\n"
					+ "  (b) 원형 콘크리트 관 입출구가 보이는가? → **흄관(hume pipe)**. 형식은 '원형 흄관'.\n"
					+ "  (c) 양쪽에 분리된 교대 + 그 사이를 잇는 슬래브/거더 구조이고 다리 아래로 빈 공간이 보이는가? → **소교량(slab bridge)**. 형식은 'RC 슬래브교' 또는 'PSC 빔교'.\n"
					+ "  (d) 위 어느 것에도 해당 안 하면 '확인불가'.\n\n"
					+ "[자주 하는 오답 — 이렇게 하지 마세요]\n"
					+ "❌ '양식에 \"교량형식\"이라고 적혀있으니 슬래브교' — 틀림. 양식 라벨은 통합 양식의 헤더일 뿐.\n"
					+ "❌ '도로 표면 사진을 보니 그냥 도로 같음 → 소교량' — 틀림. 도로 아래에 박스가 있으면 암거.\n"
					+ "❌ '잘 모르겠으니 일단 소교량으로' — 틀림. 모를 땐 '확인불가'로 답하라.\n\n"
					+ "[유형-형식 일관성]\n"
					+ "유형이 '암거'이면 형식은 반드시 '...암거'로 끝나야 함. 절대 '슬래브교'·'빔교' 같은 다리 형식과 섞지 말 것.\n"
					+ "유형이 '소교량'이면 형식은 'RC 슬래브교', 'PSC 빔교' 등 다리 형식. 절대 '암거'·'흄관' 섞지 말 것.",
			"STEP 1: 양식 라벨('교량형식' 등)에 '교량'이 들어있어도 **잠시 무시**하세요.\n"
					+ "STEP 2: 첨부 사진을 한 장씩 보면서 시설 본체(구조물)를 찾으세요.\n"
					+ "STEP 3: 박스형 입출구가 보이면 무조건 암거. 원형 관이면 흄관. 분리된 교대+슬래브+밑이 빈 다리면 소교량. "
					+ "둘 이상이 헷갈리면 '확인불가'로 답하세요.\n"
					+ "STEP 4: 결정한 유형에 일관되게 모든 필드를 작성. 사진에서 확인되지 않는 수치는 빈 문자열로."
	);

	// ─── 세천 ────────────────────────────────────────

	private static final Profile STREAM = new Profile(
			"세천 (소하천)",
			new HashSet<>(Arrays.asList(
					// 이 도메인에만 있는 고유 키워드
					"세천", "세천명", "통수능", "하도", "호안", "유송잡물",
					"중류부", "시점부", "종점부"
			)),
			new HashSet<>(Arrays.asList(
					"시작위치", "종료위치", "연장", "평균",
					"위험시설", "북위", "동경", "수혜지역",
					"부속물", "시설부속물", "관할기관", "대표필지",
					"제방", "하폭", "통관", "수로"
			)),
			"[★ 시설 유형 — 세천(소하천) ★]\n\n"
					+ "이 양식은 세천(소하천) 점검용입니다. 사진은 하도·제방·호안·통수 단면 등을 평가하기 위한 것.\n"
					+ "사진에 다리(소교량)·암거가 같이 보일 수 있지만, 이 양식의 평가 대상은 '세천 본체(하천 단면, 호안, 제방, 통수능)'입니다. "
					+ "다리·암거는 평가 대상이 아니거나 부속 시설로만 언급하세요.\n\n"
					+ "[판별 절차]\n"
					+ "STEP 1. 사진에서 하천(물길)을 찾아 그 하천의 호안·제방·하도 상태를 평가.\n"
					+ "STEP 2. 시점부/중류부/종점부 좌표는 사진별 위치가 다르므로 임의로 채우지 말 것 (모르면 빈 칸).\n"
					+ "STEP 3. 부속물은 세천에 딸린 호안·제방·통관·낙차공·취입보 등.\n\n"
					+ "[흔한 오답 차단]\n"
					+ "❌ 사진에 다리가 한 장 있어서 '소교량 평가' 답변을 하면 안 됨. 이 양식은 세천 평가용.\n"
					+ "❌ '교량형식', '슬래브교', '암거' 같은 다리 용어 사용 금지.\n"
					+ "❌ 시점부/중류부/종점부 3개 좌표 슬롯에 facility 좌표 1개를 똑같이 박지 말 것.\n\n"
					+ "[평가 항목 — 세천 표준]\n"
					+ "근거자료의 세천 평가표가 있으면 그것을 따르세요. 일반적으로:\n"
					+ "- 과거 인명·재산피해 이력\n"
					+ "- 제내지 침수피해 이력\n"
					+ "- 제방단면 부족 및 파손 여부\n"
					+ "- 호안 유무·노후화·파손 상태\n"
					+ "- 기타시설물(통관·문비) 노후화\n"
					+ "- 하폭 부족·급축소\n"
					+ "- 하도 침식·퇴적\n"
					+ "- 유송잡물·식생에 따른 통수능\n"
					+ "- 입지여건(취락지 등)\n"
					+ "- 인위적 훼손·점용",
			"STEP 1: 사진에서 하천(물길)을 찾아 평가 대상으로 삼으세요. 다리·암거가 함께 보여도 그건 평가 대상 아님.\n"
					+ "STEP 2: 호안·제방·하도·유송잡물 등 세천 평가항목을 확인.\n"
					+ "STEP 3: 시점/중류/종점 각각의 좌표는 모르면 빈 칸. facility 좌표 하나로 셋 다 채우지 말 것.\n"
					+ "STEP 4: 결정한 항목별로 근거자료의 등급(A/B/C 등) 적용."
	);

	private static final List<Profile> ALL = Collections.unmodifiableList(Arrays.asList(
			BRIDGE_CULVERT_PIPE,
			STREAM
			// 신규 도메인은 여기에 추가
	));

	/**
	 * fields[] (label/preview)에서 도메인 고유 키워드(domainSignals)와 supporting 키워드를 매치.
	 * domainSignal이 하나도 안 매치된 프로파일은 후보에서 탈락.
	 * 점수 = domainSignal 매치 × 3 + supporting 매치. 최고 점수 프로파일 반환.
	 */
	public static Profile detect(JsonNode fields) {
		if (fields == null || !fields.isArray() || fields.size() == 0) return null;
		StringBuilder corpus = new StringBuilder();
		for (JsonNode f : fields) {
			corpus.append(f.path("label").asText("")).append(' ');
			corpus.append(f.path("preview").asText("")).append(' ');
		}
		String text = corpus.toString();

		Profile best = null;
		int bestScore = 0;
		for (Profile p : ALL) {
			int signalMatches = 0;
			for (String kw : p.domainSignals) {
				if (text.contains(kw)) signalMatches++;
			}
			if (signalMatches == 0) continue; // 고유 키워드 0개면 후보 아님
			int suppMatches = 0;
			for (String kw : p.supporting) {
				if (text.contains(kw)) suppMatches++;
			}
			int score = signalMatches * 3 + suppMatches;
			System.out.println("[SurveyDomainProfiles] candidate=" + p.name + " signal=" + signalMatches + "/" + p.domainSignals.size()
					+ " supporting=" + suppMatches + "/" + p.supporting.size() + " score=" + score);
			if (score > bestScore) {
				best = p;
				bestScore = score;
			}
		}
		if (best != null) {
			System.out.println("[SurveyDomainProfiles] selected=" + best.name + " score=" + bestScore);
		} else {
			System.out.println("[SurveyDomainProfiles] no domain profile matched (generic prompt)");
		}
		return best;
	}

	private SurveyDomainProfiles() { }
}
