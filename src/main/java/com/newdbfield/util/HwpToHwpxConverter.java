package com.newdbfield.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * 한컴오피스 COM(HWPFrame.HwpObject)을 PowerShell로 호출해 .hwp → .hwpx 변환.
 * 한컴 설치 PC(서버)에서만 작동.
 *
 * 보안 모듈(예: raonkhwp.dll)은 HKCU\Software\HNC\HwpAutomation\Modules에 이미 등록돼 있어야 함.
 * 등록 안 되어 있으면 SaveAs 시 보안 다이얼로그가 떠서 무한 대기.
 */
public final class HwpToHwpxConverter {

	/** 변환 1회 타임아웃(초). 큰 양식이라도 60초면 충분. */
	private static final int CONVERT_TIMEOUT_SECONDS = 60;

	private HwpToHwpxConverter() { }

	/**
	 * 입력 hwp/hwpx 파일을 hwpx로 변환.
	 *  - 입력이 이미 .hwpx면 그대로 반환(변환 생략).
	 *  - 변환 성공 시 입력 파일 옆에 같은 이름의 .hwpx를 만들고, 원본 .hwp를 .hwp.bak로 보관.
	 *  - 실패 시 null 반환(호출부에서 원본 .hwp로 폴백 가능).
	 */
	public static File convertToHwpx(File input) {
		if (input == null || !input.isFile()) {
			return null;
		}
		String name = input.getName().toLowerCase();
		if (name.endsWith(".hwpx")) {
			return input;
		}
		if (!name.endsWith(".hwp")) {
			return null;
		}

		File parent = input.getParentFile();
		String base = input.getName();
		int dot = base.lastIndexOf('.');
		String stem = dot > 0 ? base.substring(0, dot) : base;
		File outFile = new File(parent, stem + ".hwpx");
		File tempOut = new File(parent, stem + ".converting.hwpx");
		if (tempOut.exists()) {
			tempOut.delete();
		}

		String script = buildPsScript(input.getAbsolutePath(), tempOut.getAbsolutePath());

		ProcessBuilder pb = new ProcessBuilder(
				"powershell.exe",
				"-NoProfile",
				"-NonInteractive",
				"-ExecutionPolicy", "Bypass",
				"-Command", script
		);
		pb.redirectErrorStream(true);

		Process p = null;
		try {
			p = pb.start();
			StringBuilder sb = new StringBuilder();
			try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.forName("UTF-8")))) {
				String line;
				while ((line = br.readLine()) != null) {
					sb.append(line).append('\n');
					if (sb.length() > 16384) break;
				}
			}
			boolean done = p.waitFor(CONVERT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if (!done) {
				p.destroyForcibly();
				killStrayHwpProcesses();
				System.err.println("[HwpToHwpxConverter] timeout");
				return null;
			}
			if (p.exitValue() != 0) {
				System.err.println("[HwpToHwpxConverter] ps exit=" + p.exitValue() + " out=" + sb);
				return null;
			}
			if (!tempOut.isFile() || tempOut.length() == 0) {
				System.err.println("[HwpToHwpxConverter] no output. ps out=" + sb);
				return null;
			}
			// 원본 .hwp는 .hwp.bak로 백업, .hwpx를 정착시킴
			File backup = new File(parent, base + ".bak");
			if (backup.exists()) {
				backup.delete();
			}
			Files.move(input.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
			Files.move(tempOut.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			return outFile;
		} catch (IOException | InterruptedException e) {
			System.err.println("[HwpToHwpxConverter] " + e.getMessage());
			if (p != null) {
				p.destroyForcibly();
			}
			killStrayHwpProcesses();
			return null;
		}
	}

	private static String buildPsScript(String inAbs, String outAbs) {
		String inEsc = inAbs.replace("'", "''");
		String outEsc = outAbs.replace("'", "''");
		// SaveAs 호출 직후에 Quit해야 Hwp.exe가 메모리에 남지 않음.
		return "$ErrorActionPreference='Stop';"
				+ "try {"
				+ "  $h = New-Object -ComObject HWPFrame.HwpObject;"
				+ "  $ok1 = $h.Open('" + inEsc + "', 'HWP', 'forceopen:true;suspendpassword:FALSE');"
				+ "  if (-not $ok1) { Write-Output 'OPEN_FAIL'; exit 2 };"
				+ "  $ok2 = $h.SaveAs('" + outEsc + "', 'HWPX', '');"
				+ "  $h.Quit() | Out-Null;"
				+ "  [System.Runtime.Interopservices.Marshal]::ReleaseComObject($h) | Out-Null;"
				+ "  if (-not $ok2) { Write-Output 'SAVE_FAIL'; exit 3 };"
				+ "  Write-Output 'OK'"
				+ "} catch {"
				+ "  Write-Output ('ERR ' + $_.Exception.Message);"
				+ "  exit 1"
				+ "}";
	}

	/** 변환 실패·타임아웃 시 메모리에 남은 Hwp.exe / HwpApi.exe 정리. */
	private static void killStrayHwpProcesses() {
		try {
			ProcessBuilder pb = new ProcessBuilder(
					"powershell.exe",
					"-NoProfile",
					"-Command",
					"Get-Process -Name Hwp,HwpApi -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue"
			);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			p.waitFor(5, TimeUnit.SECONDS);
			if (p.isAlive()) {
				p.destroyForcibly();
			}
		} catch (Exception ignore) {
			// best-effort
		}
	}
}
