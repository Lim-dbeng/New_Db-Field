package com.newdbfield.util;

import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 다중 선택 시설물보내기용 엑셀 생성 (ZIP 내 시설목록.xlsx).
 */
public final class FacExportSelectedUtil {

	private FacExportSelectedUtil() {}

	public static class FacilityExportRow {
		public String code;
		public String projectCode;
		public String projectName;
		public Double lon;
		public Double lat;
		/** 그룹별 코멘트를 " | " 로 합친 문자열 */
		public String comments;
		public List<PhotoExportEntry> photos = new ArrayList<>();
	}

	public static class PhotoExportEntry {
		public String fileName;
		/** ZIP 내부 상대 경로 (예: photos/CODE/file.jpg) */
		public String zipRelativePath;
	}

	/**
	 * @param baseUrl 앱 루트 URL (끝 슬래시 없음), 예: https://host/New_Db-Field
	 */
	public static byte[] buildWorkbookBytes(List<FacilityExportRow> rows, String baseUrl) throws IOException {
		try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
			CreationHelper helper = wb.getCreationHelper();
			CellStyle linkStyle = wb.createCellStyle();
			Font linkFont = wb.createFont();
			linkFont.setUnderline(Font.U_SINGLE);
			linkFont.setColor((short) 12);
			linkStyle.setFont(linkFont);

			Sheet main = wb.createSheet("시설목록");
			String[] headers = { "관리번호", "좌표", "경도", "위도", "사업번호", "사업명", "코멘트", "사진(파일명)", "상세링크" };
			Row headerRow = main.createRow(0);
			for (int i = 0; i < headers.length; i++) {
				headerRow.createCell(i).setCellValue(headers[i]);
			}

			int rowIdx = 1;
			for (FacilityExportRow r : rows) {
				Row row = main.createRow(rowIdx++);
				int c = 0;
				row.createCell(c++).setCellValue(safe(r.code));
				String coord = formatCoord(r.lon, r.lat);
				row.createCell(c++).setCellValue(coord);
				if (r.lon != null) {
					row.createCell(c++).setCellValue(r.lon);
				} else {
					row.createCell(c++).setCellValue("");
				}
				if (r.lat != null) {
					row.createCell(c++).setCellValue(r.lat);
				} else {
					row.createCell(c++).setCellValue("");
				}
				row.createCell(c++).setCellValue(safe(r.projectCode));
				row.createCell(c++).setCellValue(safe(r.projectName));
				row.createCell(c++).setCellValue(safe(r.comments));
				Set<String> names = new LinkedHashSet<>();
				PhotoExportEntry firstPhoto = null;
				for (PhotoExportEntry pe : r.photos) {
					if (pe.fileName != null && !pe.fileName.isEmpty()) {
						names.add(pe.fileName);
						if (firstPhoto == null && pe.zipRelativePath != null && !pe.zipRelativePath.isEmpty()) {
							firstPhoto = pe;
						}
					}
				}
				Cell photoCell = row.createCell(c++);
				String photoNames = String.join("; ", names);
				if (firstPhoto != null) {
					Hyperlink photoHl = helper.createHyperlink(HyperlinkType.FILE);
					photoHl.setAddress(firstPhoto.zipRelativePath.replace("\\", "/"));
					photoCell.setHyperlink(photoHl);
					photoCell.setCellValue(photoNames.isEmpty() ? firstPhoto.fileName : photoNames);
					photoCell.setCellStyle(linkStyle);
				} else {
					photoCell.setCellValue(photoNames);
				}

				Cell linkCell = row.createCell(c++);
				// 엑셀 http 링크는 localhost:8080 만 열리는 경우가 많음 → ZIP 내 links/*.url (FILE, 사진과 동일)
				String shortcutPath = zipDetailLinkPath(r.code);
				if (shortcutPath != null && buildExcelDetailUrl(baseUrl, r.code, null, null, null) != null) {
					Hyperlink hl = helper.createHyperlink(HyperlinkType.FILE);
					hl.setAddress(shortcutPath);
					linkCell.setHyperlink(hl);
					linkCell.setCellValue("상세보기");
					linkCell.setCellStyle(linkStyle);
				} else {
					linkCell.setCellValue("");
				}
			}
			for (int i = 0; i < headers.length; i++) {
				main.autoSizeColumn(i);
			}

			Sheet photoSheet = wb.createSheet("사진");
			Row phHeader = photoSheet.createRow(0);
			phHeader.createCell(0).setCellValue("관리번호");
			phHeader.createCell(1).setCellValue("파일명");
			phHeader.createCell(2).setCellValue("ZIP내경로");
			phHeader.createCell(3).setCellValue("사진열기");

			int phRow = 1;
			for (FacilityExportRow r : rows) {
				for (PhotoExportEntry pe : r.photos) {
					if (pe.zipRelativePath == null || pe.zipRelativePath.isEmpty()) {
						continue;
					}
					Row row = photoSheet.createRow(phRow++);
					row.createCell(0).setCellValue(safe(r.code));
					String relPath = pe.zipRelativePath.replace("\\", "/");
					Cell nameCell = row.createCell(1);
					Hyperlink fileHl = helper.createHyperlink(HyperlinkType.FILE);
					fileHl.setAddress(relPath);
					nameCell.setHyperlink(fileHl);
					nameCell.setCellValue(safe(pe.fileName));
					nameCell.setCellStyle(linkStyle);
					row.createCell(2).setCellValue(relPath);
					Cell openCell = row.createCell(3);
					Hyperlink openHl = helper.createHyperlink(HyperlinkType.FILE);
					openHl.setAddress(relPath);
					openCell.setHyperlink(openHl);
					openCell.setCellValue("열기");
					openCell.setCellStyle(linkStyle);
				}
			}
			for (int i = 0; i < 4; i++) {
				photoSheet.autoSizeColumn(i);
			}

			wb.write(bos);
			return bos.toByteArray();
		}
	}

	/**
	 * 엑셀 상세보기 링크. 쿼리(?code=) 없이 경로만 사용 — 엑셀이 localhost:8080 만 여는 문제 회피.
	 * 예: http://host/go/210546_20260521100930
	 */
	public static String buildExcelDetailUrl(String baseUrl, String code, String projectCode, Double lon, Double lat) {
		if (baseUrl == null || code == null || code.trim().isEmpty()) {
			return null;
		}
		StringBuilder sb = new StringBuilder(baseUrl);
		if (!baseUrl.endsWith("/")) {
			sb.append("/");
		}
		sb.append("go/");
		sb.append(FacDeepLinkCookieUtil.encodePathSegment(code.trim()));
		return sb.toString();
	}

	/** 브라우저 주소창·붙여넣기용: API → index.jsp#fac?… 리다이렉트 */
	public static String buildOpenLinkUrl(String baseUrl, String code, String projectCode, Double lon, Double lat) {
		if (baseUrl == null || code == null || code.trim().isEmpty()) {
			return null;
		}
		StringBuilder sb = new StringBuilder(baseUrl);
		if (!baseUrl.endsWith("/")) {
			sb.append("/");
		}
		sb.append("api/fac/open-link?code=");
		appendUrlParam(sb, code.trim());
		if (projectCode != null && !projectCode.trim().isEmpty()) {
			sb.append("&project=");
			appendUrlParam(sb, projectCode.trim());
		}
		if (lon != null && lat != null && Double.isFinite(lon) && Double.isFinite(lat)) {
			sb.append("&lng=").append(String.format("%.6f", lon));
			sb.append("&lat=").append(String.format("%.6f", lat));
		}
		return sb.toString();
	}

	/** 브라우저 주소창·ui.js 딥링크용 해시 (예: fac?code=…&project=…) */
	public static String buildFacHashQuery(String code, String projectCode, Double lon, Double lat) {
		if (code == null || code.trim().isEmpty()) {
			return null;
		}
		StringBuilder sb = new StringBuilder("fac?code=");
		appendUrlParam(sb, code.trim());
		if (projectCode != null && !projectCode.trim().isEmpty()) {
			sb.append("&project=");
			appendUrlParam(sb, projectCode.trim());
		}
		if (lon != null && lat != null && Double.isFinite(lon) && Double.isFinite(lat)) {
			sb.append("&lng=").append(String.format("%.6f", lon));
			sb.append("&lat=").append(String.format("%.6f", lat));
		}
		return sb.toString();
	}

	/** 엑셀에는 {@link #buildOpenLinkUrl} 사용 */
	@Deprecated
	public static String buildDetailUrl(String baseUrl, String code, String projectCode, Double lon, Double lat) {
		if (baseUrl == null || code == null || code.trim().isEmpty()) {
			return null;
		}
		String hash = buildFacHashQuery(code, projectCode, lon, lat);
		if (hash == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder(baseUrl);
		if (!baseUrl.endsWith("/")) {
			sb.append("/");
		}
		sb.append("index.jsp#").append(hash);
		return sb.toString();
	}

	private static void appendUrlParam(StringBuilder sb, String value) {
		try {
			sb.append(java.net.URLEncoder.encode(value, "UTF-8"));
		} catch (Exception e) {
			sb.append(value);
		}
	}

	static String excelHyperlinkFormula(String url, String label) {
		if (url == null || url.isEmpty()) {
			return null;
		}
		String u = url.replace("\"", "\"\"");
		String l = (label != null ? label : "링크").replace("\"", "\"\"");
		return "HYPERLINK(\"" + u + "\",\"" + l + "\")";
	}

	/** ZIP 내 Windows 인터넷 바로가기 (엑셀 FILE 하이퍼링크용) */
	public static String zipDetailLinkPath(String code) {
		if (code == null || code.trim().isEmpty()) {
			return null;
		}
		return "links/" + sanitizeZipSegment(code) + ".url";
	}

	public static byte[] buildInternetShortcutBytes(String baseUrl, String code) {
		String target = buildExcelDetailUrl(baseUrl, code, null, null, null);
		if (target == null || target.isEmpty()) {
			return new byte[0];
		}
		String content = "[InternetShortcut]\r\nURL=" + target + "\r\n";
		return content.getBytes(StandardCharsets.UTF_8);
	}

	public static String zipPhotoPath(String code, String fileName) {
		String safeCode = sanitizeZipSegment(code);
		String safeFile = sanitizeZipSegment(fileName);
		return "photos/" + safeCode + "/" + safeFile;
	}

	public static String sanitizeZipSegment(String s) {
		if (s == null) return "unknown";
		return s.replace("\\", "_").replace("/", "_").replace("..", "_").trim();
	}

	private static String formatCoord(Double lon, Double lat) {
		if (lon == null || lat == null || !Double.isFinite(lon) || !Double.isFinite(lat)) {
			return "";
		}
		return String.format("%.6f, %.6f", lon, lat);
	}

	private static String safe(String s) {
		return s == null ? "" : s;
	}
}
