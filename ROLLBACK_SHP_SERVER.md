# SHP 서버 변환 기능

## 빌드 방법
`nf-build`/`nf-start` 사용 시 Maven(mvn)이 PATH에 없으면 GeoTools JAR가 복사되지 않아 컴파일 오류가 발생합니다.
**해결**: `scripts\nf-copy-deps.cmd` 를 먼저 실행한 뒤 `nf-build` / `nf-start` 실행.
(Maven이 PATH에 있는 터미널에서 `nf-copy-deps.cmd` 1회 실행 후, WEB-INF/lib에 JAR가 채워지면 이후 nf-build는 mvn 없이도 동작)

---

## 롤백 가이드

### 변경 사항 요약
서버에서 ZIP 내 SHP(.shp, .shx, .dbf, .prj, .cpg, .qmd 등) → GeoJSON 변환 지원.
모바일 앱 등 클라이언트에서 변환 없이 ZIP만 업로드 가능.

## 롤백 방법

### 1. pom.xml
- `<repositories>` 내 OSGeo 저장소(id="osgeo") 제거
- `<dependencies>` 내 gt-shapefile, gt-geojson 의존성 2개 제거

### 2. ShpUploadController.java
- GeoTools 관련 import 제거
- `convertShpToGeoJson()` 메서드 삭제
- `extractGeometryFromZip()` 내 shpFile 변수/로직 제거, 예외 메시지 원복

### 3. nf-build.cmd
- Maven dependency:copy-dependencies 호출 블록 제거

### 4. scripts/nf-copy-deps.cmd
- 파일 삭제

### 5. Git
```bash
git checkout -- pom.xml src/main/java/com/newdbfield/web/ShpUploadController.java scripts/nf-build.cmd
```

### 6. 이 파일
롤백 후 `ROLLBACK_SHP_SERVER.md` 삭제.
