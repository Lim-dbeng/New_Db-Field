# Maven 없이 nf-build javac 클래스패스용 Jackson 최소 세트 (Maven Central)
param(
    [Parameter(Mandatory = $true)]
    [string]$RootDir
)
$ErrorActionPreference = "Stop"
$lib = Join-Path $RootDir "src\main\webapp\WEB-INF\lib"
$v = "2.13.2"
$base = "https://repo1.maven.org/maven2/com/fasterxml/jackson/core"
New-Item -ItemType Directory -Force -Path $lib | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$pairs = @(
    @{ Name = "jackson-core-$v.jar";        Url = "$base/jackson-core/$v/jackson-core-$v.jar" },
    @{ Name = "jackson-annotations-$v.jar"; Url = "$base/jackson-annotations/$v/jackson-annotations-$v.jar" },
    @{ Name = "jackson-databind-$v.jar";    Url = "$base/jackson-databind/$v/jackson-databind-$v.jar" }
)
foreach ($p in $pairs) {
    $out = Join-Path $lib $p.Name
    if (-not (Test-Path $out)) {
        Write-Host "[nf-fetch-jackson] Downloading $($p.Name) ..."
        Invoke-WebRequest -Uri $p.Url -OutFile $out -UseBasicParsing
    }
}
