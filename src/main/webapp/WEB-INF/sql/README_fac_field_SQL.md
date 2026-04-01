# fac_field_SQL.xml 차이점 설명

## SPOTSYSTEM vs New_Db-Field

### SPOTSYSTEM
- **프레임워크**: iBATIS (SQL Mapping Framework)
- **형식**: iBATIS DTD 및 문법 사용
- **구조**:
  ```xml
  <sqlMap namespace="FacField">
    <select id="FacDAO.selectFacInfoList" ...>
      SELECT ...
    </select>
    <insert id="FacDAO.insertFacAdd" ...>
      INSERT ...
    </insert>
  </sqlMap>
  ```
- **특징**: 
  - 동적 SQL 지원 (`<isNotEmpty>`, `<isEmpty>` 등)
  - 복잡한 매핑 및 결과 처리
  - `group_index` 컬럼 없음 (사진과 코멘트가 1:1 관계)

### New_Db-Field
- **프레임워크**: 커스텀 SqlRepository (간단한 XML 파서)
- **형식**: 간단한 `<sql>` 태그 사용
- **구조**:
  ```xml
  <sqls>
    <sql id="fac.selectByBbox">
      SELECT ...
    </sql>
    <sql id="fac.insertFacAddItem">
      INSERT ...
    </sql>
  </sqls>
  ```
- **특징**:
  - 간단하고 직관적인 구조
  - 동적 SQL은 Java 코드에서 처리
  - `group_index` 컬럼 지원 (사진 그룹 기능)

## 주요 차이점

### 1. INSERT 쿼리
**SPOTSYSTEM** (`FacDAO.insertFacAdd`):
- 조건부 컬럼 삽입 (`<isNotEmpty>` 사용)
- `group_index` 컬럼 없음

**New_Db-Field** (`fac.insertFacAddItem`):
- 모든 컬럼 명시 (Java 코드에서 NULL 처리)
- `group_index` 컬럼 포함 (사진 그룹 기능)

### 2. SELECT 쿼리
**SPOTSYSTEM**:
- 여러 복잡한 SELECT 쿼리 존재
- JOIN, UNION 등 복잡한 쿼리 지원

**New_Db-Field**:
- 현재는 `fac.selectByBbox`만 존재 (지도 범위 기반 조회)
- 간단한 구조 유지

## 결론
두 시스템은 서로 다른 프레임워크를 사용하므로 XML 구조가 다릅니다. 이것은 **정상적인 차이**이며, 각 시스템의 설계 목적에 맞게 구현되었습니다.

New_Db-Field는 SPOTSYSTEM보다 더 간단하고 현대적인 구조를 목표로 하고 있으며, 특히 사진 그룹 기능을 위해 `group_index`를 추가했습니다.


