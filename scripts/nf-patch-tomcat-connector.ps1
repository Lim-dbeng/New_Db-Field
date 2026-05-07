param(
    [Parameter(Mandatory = $true)]
    [string] $ServerXml
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $ServerXml)) {
    Write-Error "server.xml not found: $ServerXml"
    exit 1
}
$c = [IO.File]::ReadAllText($ServerXml)
if ($c -match "maxPostSize") {
    Write-Host "[nf-patch-tomcat-connector] maxPostSize already set — skip."
    exit 0
}
$pat = '(<Connector\s+port="8080"\s+protocol="HTTP/1\.1")'
$rep = '$1 maxPostSize="134217728" maxSwallowSize="134217728"'
$nc = $c -replace $pat, $rep
if ($nc -eq $c) {
    Write-Host "[nf-patch-tomcat-connector] WARNING: HTTP/8080 Connector pattern not matched — server.xml unchanged."
    exit 0
}
[IO.File]::WriteAllText($ServerXml, $nc, [Text.UTF8Encoding]::new($false))
Write-Host "[nf-patch-tomcat-connector] Patched maxPostSize / maxSwallowSize (128MB)."
exit 0
