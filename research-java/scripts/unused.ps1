# Phase 1 inventory, static half: what no entry point can reach, by ProGuard -printusage (class and member level,
# rooted at the main() of every figure command) and PMD (unused private members, parameters, locals).
# Needs data/tools/proguard/*.jar (proguard-base 7.7.0 + proguard-core 9.1.10 + kotlin-stdlib, gson, log4j from Maven
# Central) and data/tools/pmd (pmd-dist 7.17.0). Run scripts/coverage.ps1 first (it writes build/runtime-classpath.txt).
#
#   cd research-java; ./gradlew compileJava compileTestJava; ./scripts/unused.ps1
#
# Output: ../data/coverage/unused-figures.txt (rooted at the figure mains only), unused-with-tests.txt (also rooted
# at every test class: what only ./gradlew test reaches is not listed there) and pmd-unused.txt.
$ErrorActionPreference = 'Stop'
$tools = Resolve-Path '../data/tools'
$out = New-Item -ItemType Directory -Force '../data/coverage' | Select-Object -ExpandProperty FullName
$javaHome = & java -XshowSettings:properties -version 2>&1 | Select-String 'java.home = (.*)' | ForEach-Object { $_.Matches[0].Groups[1].Value.Trim() }
$cp = (Get-Content build/runtime-classpath.txt -Raw).Trim().Split(';') | Where-Object { $_ -notmatch 'build[\\/]classes' -and $_ -notmatch 'build[\\/]resources' }
$pg = (Get-ChildItem "$tools/proguard/*.jar").FullName -join ';'
$cli = 'org.maplibre.mlt.research.cli.'
$mains = @('BenchmarkCli', 'PipelineCli', 'PipelineTileSizeCli', 'DecodeTimeCli', 'WorkshopCli', 'ResidualBitsCli', 'SharedVertexCli',
    'RoadChainCli', 'OrientationPriorCli', 'LineFramePriorCli', 'TurnRadiusCli', 'TurnScalarCli', 'Turn90BitmapCli',
    'ReflexSignalCli', 'ShareTableCli', 'BufferPointCli', 'ExtractCli', 'GoldenDumpCli')

function Write-Config($path, $withTests, $usage) {
    $lines = @('-dontoptimize', '-dontobfuscate', '-dontwarn **', '-ignorewarnings', '-verbose')
    $lines += "-injars $((Resolve-Path build/classes/java/main).Path)"
    if ($withTests) { $lines += "-injars $((Resolve-Path build/classes/java/test).Path)" }
    $lines += "-libraryjars `"$javaHome/jmods/java.base.jmod`"(!**.jar;!module-info.class)"
    foreach ($j in $cp) { $lines += "-libraryjars `"$j`"" }
    $lines += "-printusage `"$usage`""
    foreach ($m in $mains) { $lines += "-keep class $cli$m { public static void main(java.lang.String[]); }" }
    if ($withTests) { $lines += '-keep class **Test { *; }'; $lines += '-keep class **MechanismTest { *; }' }
    $lines | Set-Content $path
}

Write-Config "$out/proguard-figures.pro" $false "$out/unused-figures.txt"
java -cp $pg proguard.ProGuard "@$out/proguard-figures.pro" *> "$out/proguard-figures.log"
Write-Config "$out/proguard-tests.pro" $true "$out/unused-with-tests.txt"
java -cp $pg proguard.ProGuard "@$out/proguard-tests.pro" *> "$out/proguard-tests.log"

$cpFile = "$out/pmd-classpath.txt"
$cp -join ';' | Set-Content $cpFile
& "$tools/pmd/pmd-bin-7.17.0/bin/pmd.bat" check -d src/main/java -R scripts/pmd-unused.xml -f text --no-cache --no-progress --aux-classpath ((Get-Content $cpFile -Raw).Trim()) *> "$out/pmd-unused.txt"
Write-Host "unused reports in $out"
