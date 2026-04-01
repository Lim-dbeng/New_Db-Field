# 다중선택 일괄 사업번호 변경 기능 롤백 가이드

이 기능을 제거하고 이전 상태로 되돌리려면 아래 파일들을 `.rollback_before_bulk_select/` 백업본으로 복원하면 됩니다.

## 복원할 파일 목록

| 원본 경로 | 백업 경로 |
|-----------|-----------|
| `src/main/webapp/assets/js/facility.js` | `.rollback_before_bulk_select/facility.js` |
| `src/main/webapp/assets/css/custom.css` | `.rollback_before_bulk_select/custom.css` |
| `src/main/webapp/index.jsp` | `.rollback_before_bulk_select/index.jsp` |
| `src/main/java/com/newdbfield/web/FacCommController.java` | `.rollback_before_bulk_select/FacCommController.java` |
| `src/main/webapp/assets/js/project-filter.js` | `.rollback_before_bulk_select/project-filter.js` |

## Windows PowerShell에서 롤백 실행

```powershell
cd D:\PROJECT\Db-Field\New_Db-Field

Copy-Item ".rollback_before_bulk_select\facility.js" "src\main\webapp\assets\js\facility.js" -Force
Copy-Item ".rollback_before_bulk_select\custom.css" "src\main\webapp\assets\css\custom.css" -Force
Copy-Item ".rollback_before_bulk_select\index.jsp" "src\main\webapp\index.jsp" -Force
Copy-Item ".rollback_before_bulk_select\FacCommController.java" "src\main\java\com\newdbfield\web\FacCommController.java" -Force
Copy-Item ".rollback_before_bulk_select\project-filter.js" "src\main\webapp\assets\js\project-filter.js" -Force
```

## 수동 롤백

각 파일을 백업 폴더에서 원본 위치로 복사하여 덮어쓰기하면 됩니다.

## 롤백 후

- Tomcat 재시작 또는 WAR 재배포가 필요할 수 있습니다.
- 브라우저 캐시를 비우거나 강력 새로고침(Ctrl+F5)을 권장합니다.
