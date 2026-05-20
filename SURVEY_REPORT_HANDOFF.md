# 조사 보고서 시스템 — 세션 인계 문서

> 다음 Claude 세션에서 **이 파일 먼저 읽고 시작**하세요. 그동안 누적된 결정/한계/사용자 선호가 모두 여기에.

---

## 1. 시스템 흐름 (현재 상태)

```
[양식 .hwp 업로드]
       ↓ HwpToHwpxConverter (한컴 COM, raonkhwp.dll)
[양식 .hwpx 저장 + template 분석]
       ↓ kordoc CLI: node dist/cli.js template <hwpx>
[draft_field_schema (slots: F0~FN 텍스트, IMG0~IMGN 사진)]
       ↓ 사용자가 modal에서 "AI 초안 생성" 클릭 (또는 export 시점에 자동)
       ↓ SurveyReportDraftLlmUtil.generateAndMergeAnswers
       ├─ Stage 1: 사진 + 도메인 정의만 → 시각 분류 (유형/confidence/reasoning)
       ├─ Stage 2: 사진(low) + 양식 + 근거자료 + Stage 1 결과 → 슬롯별 답변 JSON
       └─ enforceTypeConsistency: Stage 1 type을 유형/형식 슬롯에 강제
[answers DB 저장]
       ↓ "보고서 출력" 클릭
[fillFromTemplate (kordoc CLI fill)]
[작성초안 .hwpx 다운로드 — 원본 양식 그대로 + 셀만 채움]
```

---

## 2. 핵심 파일 — 무엇을 한다

### Backend (Java)

| 파일 | 역할 |
|---|---|
| `src/main/java/com/newdbfield/util/HwpToHwpxConverter.java` | .hwp → .hwpx 변환 (한컴 COM via PowerShell). `raonkhwp.dll` 보안모듈 등록 필수 |
| `src/main/java/com/newdbfield/util/SurveyReportKordocUtil.java` | kordoc CLI `parse`/`template` 호출 (legacy + 신규) |
| `src/main/java/com/newdbfield/util/SurveyReportTemplateUtil.java` | kordoc CLI `template`/`fill` 호출. slot 기반 schema 생성 + fillTemplate 호출 |
| `src/main/java/com/newdbfield/util/SurveyReportRefUtil.java` | 근거자료 파싱 (.pdf/.hwp/.docx/.xlsx/.zip/.7z 등). PER_FILE 80k자, TOTAL 200k자 cap |
| `src/main/java/com/newdbfield/util/SurveyReportDraftLlmUtil.java` | **핵심**. LLM 호출. Two-stage (vision 분류 + 양식 채움). preFilledFacts. enforceTypeConsistency. .env loader |
| `src/main/java/com/newdbfield/util/SurveyDomainProfiles.java` | 도메인 프로파일 레지스트리. domainSignals (고유 키워드) + supporting. 현재 BRIDGE_CULVERT_PIPE, STREAM 두 개 |
| `src/main/java/com/newdbfield/util/SurveyReportExportUtil.java` | (legacy) markdown → hwpx 폴백 경로 |
| `src/main/java/com/newdbfield/web/FacCommController.java` | 업로드/generate-draft/export 엔드포인트. Tomcat에서 도는 메인 |
| `src/main/java/com/newdbfield/web/SurveyReportSchemaListener.java` | DB 스키마 마이그레이션 (`facility_survey_report` 테이블 컬럼 추가) |

### Frontend

| 파일 | 역할 |
|---|---|
| `src/main/webapp/index.jsp` | 패널 + 모달(`modalSurveyReport`) HTML |
| `src/main/webapp/assets/js/facility.js` | 모달 컨트롤, schema fields 렌더, 자동 확장 textarea, 액션 dispatch |
| `src/main/webapp/META-INF/context.xml` | `reloadable="true"` — Tomcat 자동 리로드 (한 번 재시작 후 활성화) |

### kordoc (Node — TypeScript)

| 파일 | 역할 |
|---|---|
| `kordoc/src/hwpx/template.ts` | **핵심**. `extractTemplate` (양식 분석) + `fillTemplate` (셀 채우기). 음수 spacing 안전 처리, multi-paragraph 분할, 표 보호 해제 |
| `kordoc/src/hwpx/generator.ts` | (사용 빈도 낮음) markdown → hwpx 생성기 |
| `kordoc/src/cli.ts` | `template`, `fill` 서브커맨드 |
| `kordoc/dist/` | npm run build 산출물 (Java가 호출) |

---

## 3. DB 스키마 (`public.facility_survey_report`)

| 컬럼 | 용도 |
|---|---|
| `code` | 시설코드 (PK) |
| `project_code` | 사업번호 |
| `source_filename` | 원본 양식 파일명 |
| `stored_path` | `SURVEY_HWP/<code>_<ts>.hwpx` (변환 후 저장 경로) |
| `review_status` | `pending_review` / `approved` |
| `draft_field_schema jsonb` | template parser 결과 (slot 기반) |
| `field_schema jsonb` | 사용자 검수 후 확정 schema (옛 흐름) |
| `answers jsonb` | LLM/사용자 답변 |
| `reference_paths jsonb` | 근거자료 메타 [{filename, storedPath, mime, size}] |
| `user_prompt text` | 사용자 정의 LLM 지침 |

근거자료 실제 파일은 `SURVEY_HWP_REFS/<code>/` 디렉토리.

---

## 4. 현재 구현된 동작 / 트레이드오프

### 작동 중 ✓
- 한컴 COM으로 .hwp → .hwpx 변환 (raonkhwp.dll 보안모듈 통과)
- 양식 자동 분석 (slot 식별 — 빈셀, append 라벨, template 단위/좌표 셀)
- 도메인 프로파일 자동 매치 (BRIDGE/STREAM)
- Stage 1 vision 분류 → Stage 2 양식 채움 두 단계
- preFilledFacts: 관리번호 ← code, 사업번호 ← project_code, 시점부 좌표 ← gis_a_layer geometry
- 근거자료 다중 포맷 (PDF/HWPX/DOCX/XLSX/ZIP/7Z) + 재귀 추출 (depth 8)
- char limit: PER_FILE 80k, TOTAL 200k (gpt-4o 128k context 절반 정도)
- 좌표는 ST_Centroid + ST_Transform으로 어떤 SRID·geometry든 WGS84로
- multi-paragraph 분할 (4줄 이상은 별도 단락 → HWP 페이지 분할 가능)
- 음수 spacing (`-900`) 셀 안전 처리 (lineHeight ≥ vertsize × 1.4)
- 표 보호 해제 (`noAdjust=0`, `protect=0`)
- modal textarea 자동 세로 확장 (긴 답변 다 보임)
- .env 파일 로더 (OPENAI_API_KEY 자동 인식, 5분 캐시)

### 알려진 한계 / 미구현
- **세천 양식의 시점부/중류부/종점부 좌표**: facility 좌표 1개만 있어 시점부에만 채움. 중류부/종점부는 빈 칸 (조사자가 직접 입력하거나 photo별 EXIF 좌표 추출 — 미구현)
- **부속물 vs 부속물 유형 구분**: prompt로 안내했지만 LLM이 가끔 헷갈림
- **이미지 슬롯 사진 매핑**: 업로드 순서대로 IMG0, IMG1, ...에 자동 배정. 슬롯별 직접 선택 UI 없음
- **RAG 미도입**: 사용자도 "추후 도입 예정" 명시. 지금은 reference 통째 inline
- **F18-F20 동경 라벨**: cellPath만 다르고 라벨이 모두 "동경"으로 잡힘. UI에서 구분 안 됨
- **gpt-5.5 vision 정확도**: 박스 암거 같은 비-슬래브 시설 분류는 Stage 1 + enforce로 보정 중
- **압축 안 푼 docs/ppt 사례**: poi-scratchpad 사용. 흔하지 않음

---

## 5. 사용자 선호 / 강하게 표현한 의견

- **양식별 하드코드 거부**: 도메인 프로파일은 키워드 매치되면 추가 부스트, 아니면 generic. user_prompt textarea로 보완
- **글자수 리밋 거부 (했었음) → 다시 도입**: 토큰 비용 우려로 PER_FILE 80k, TOTAL 200k 재도입
- **사진 순서 의존 금지**: "사진 순서대로 fixate" 같은 우회는 절대 안 됨
- **Tomcat 매번 재시작 강요 금지**: `META-INF/context.xml` `reloadable="true"` + `nf-deploy.cmd`가 web.xml mtime 갱신 → 자동 reload (~10초)
- **AI가 초안 자동 작성**: UI에 사용자 입력 강요 안 함. LLM이 채움 → 검토 가능
- **사진 + 양식 + 근거자료 + 프롬프트 종합 판단**: 단일 입력 의존 금지

---

## 6. 빌드 / 배포 / 디버그

### 빌드 + 배포 (cmd만)
```bat
cd D:\PROJECT\Db-Field\New_Db-Field

REM kordoc 변경 시
cd kordoc & npm run build & cd ..

REM Java 변경 시
mvnw.cmd compile -o
scripts\nf-build.cmd
scripts\nf-deploy.cmd
```

### Tomcat 자동 reload
`scripts\nf-deploy.cmd` 마지막에 web.xml mtime 갱신 → context reload (JVM 안 죽음).
처음 한 번만 Tomcat 재시작 필요 (`META-INF/context.xml` 활성화 위해).

### Tomcat 콘솔에서 확인할 핵심 로그
```
[FacCommController] upload outFile=... isHwpxNow=true
[FacCommController] parseToTemplateSchema => OK len=N
[SurveyReportRefUtil] context built: files=N totalChars=N
[SurveyReportRefUtil] file ok: <name> N chars  (또는 capped: ...)
[SurveyReportDraftLlmUtil] .env loaded from <path> (N keys)
[SurveyDomainProfiles] selected=<프로파일> score=N
[Stage1] type=<유형> confidence=<high|medium|low>
[loadFacilityMeta] code=... lat=... lng=... srid=4326
[SurveyReportDraftLlmUtil] preFilled facts=N
[Stage2] vision photos attached=N/M (detail=low)
[Stage1-enforce] F<id> 유형 보정: '<old>' → '<new>'
```

### DB 정리 (테스트 시)
```sql
UPDATE public.facility_survey_report
SET answers = '{}', field_schema = '{}', user_prompt = '',
    review_status = 'pending_review'
WHERE code = '시설코드';
```

---

## 7. 구성·환경

- **OS**: Windows 11
- **Java**: 11 (`D:\PROJECT\jdk-11.0.13`)
- **Tomcat**: 9.0.80 (`.run/apache-tomcat-9.0.80/`)
- **Node**: kordoc은 ESM TypeScript, tsup으로 build
- **DB**: PostgreSQL + PostGIS, 세션·인증은 Java 측
- **한컴오피스**: COM 자동화. ProgID `HWPFrame.HwpObject`. 보안모듈 `raonkhwp.dll` HKCU 등록됨
- **LLM**: OpenAI 호환 (`web.xml` 또는 `.env` `OPENAI_API_KEY`/`SURVEY_LLM_API_KEY`). 기본 모델 gpt-4o, 사용자는 gpt-5.5 사용
- **API 키**: `.env` 파일 (project root). `.gitignore` 등록 권장

---

## 8. 마지막 작업 (가장 최근)

1. **modal textarea 자동 확장** — 모든 text 입력을 `<textarea class="fac-survey-autosize">`로 통일. 입력 시·렌더 시 `autosizeTextarea()` 호출
2. **토큰 절감** — PER_FILE_CHAR_LIMIT 80k, TOTAL 200k, max_tokens(Stage1=1500/Stage2=6000)
3. **.env 파일 자동 로드** — `lookupConfig()` 통해 web.xml → env → .env 순서
4. **API 키 fallback** — `OPENAI_API_KEY` 도 인식

## 9. 다음 세션에서 자주 나올 만한 질문/요청

- "여전히 글씨 겹쳐" → cellSz/spacing 음수 + multi-paragraph 분할 다시 점검
- "양식 일부 못 잡음" → CLI로 `template` 직접 돌려 schema 추출 결과 비교
- "엉뚱한 분류" → Stage 1 결과 콘솔 로그 + enforce 로그 확인
- "근거자료 반영 안 됨" → `[SurveyReportRefUtil] context built ... totalChars=N` 봐서 잘렸는지
- "Tomcat 재시작 매번?" → context.xml + nf-deploy의 web.xml touch 동작 설명
- "다른 양식 (예: 옹벽)" → 새 도메인 프로파일을 SurveyDomainProfiles.ALL에 추가
