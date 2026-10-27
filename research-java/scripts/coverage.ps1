# Phase 1 inventory: runs the commands that produce the thesis figures (and the other probes) under the JaCoCo
# agent, one .exec per command, then reports what no command reached. Needs data/tools/jacocoagent.jar and
# data/tools/jacococli.jar (org.jacoco 0.8.13, from Maven Central) and ../data/coverage-subset (13 Munich tiles,
# 128 buffers, all 16 layers: classes are reached by any buffer of the right shape, so the subset reaches the
# same code as the corpus does).
#
#   cd research-java; ./gradlew compileJava; ./scripts/coverage.ps1
#
# Output: ../data/coverage/exec/*.exec, ../data/coverage/figures.{xml,csv} (the figure commands only) and
# ../data/coverage/all.{xml,csv} (figure commands + the probes no current results file cites).
param([string]$Subset = '../data/coverage-subset', [string]$Munich = '../data/MVT/munich-plantiler.mbtiles')
$ErrorActionPreference = 'Stop'
$tools = Resolve-Path '../data/tools'
$out = New-Item -ItemType Directory -Force '../data/coverage' | Select-Object -ExpandProperty FullName
$execDir = New-Item -ItemType Directory -Force "$out/exec" | Select-Object -ExpandProperty FullName
Remove-Item "$execDir/*.exec" -ErrorAction SilentlyContinue

if (-not (Test-Path build/runtime-classpath.txt)) {
    ./gradlew -q -I scripts/printcp.init.gradle printRuntimeClasspath 2>$null | Select-Object -Last 1 | Set-Content build/runtime-classpath.txt
}
$cp = (Get-Content build/runtime-classpath.txt -Raw).Trim()
$cli = 'org.maplibre.mlt.research.cli.'

# name = main class, args. figure = a current-corpus results file cites it.
$commands = [ordered]@{
    'benchmark'            = @{ figure = $true;  main = 'BenchmarkCli'; args = @($Subset, '--by-zoom', '--verbose', '--brotli-oracle', '--csv', 'build/cov-benchmark.csv') }
    'benchmark-building'   = @{ figure = $true;  main = 'BenchmarkCli'; args = @($Subset, '--layer', 'building', '--by-zoom') }
    'pipeline-s0'          = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 's0', '--by-zoom') }
    'pipeline-s1'          = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 's1', '--by-zoom', '--stats') }
    'pipeline-s2'          = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 's2', '--by-zoom') }
    'pipeline-s3'          = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 's3', '--by-zoom', '--stats') }
    'pipeline-s4'          = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 's4') }
    'pipeline-s6'          = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 's6', '--stats') }
    'pipeline-s7'          = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 's7') }
    'pipeline-s8'          = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 's8', '--stats', '--by-zoom', '--with-old', '--isolated', '--csv', 'build/cov-s8.csv') }
    'pipeline-ladder'      = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 'ladder', '--by-zoom') }
    'pipeline-oracle'      = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 'oracle', '--by-zoom') }
    'pipeline-layer'       = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 'layer', '--by-zoom') }
    'pipeline-selector'    = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 'selector') }
    'pipeline-tile'        = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 'tile', '--trials', 'gzip,order0', '--features', 'build/cov-tile-features.csv') }
    'pipeline-turn'        = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 'turn', '--layer', 'building', '--by-zoom', '--with-old') }
    'pipeline-frame'       = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 'frame', '--layer', 'building', '--by-zoom', '--with-old') }
    'pipeline-framelayers' = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 'framelayers') }
    'pipeline-shared'      = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 'shared', '--layer', 'building') }
    'pipeline-framelines'  = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 'framelines', '--layer', 'transportation', '--by-zoom') }
    'pipeline-linesshared' = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 'linesshared', '--layer', 'transportation', '--by-zoom') }
    'pipeline-fit'         = @{ figure = $true;  main = 'PipelineCli'; args = @($Subset, '--step', 'fit') }
    'pipelineTileSize'     = @{ figure = $true;  main = 'PipelineTileSizeCli'; args = @($Munich, '--limit-tiles', '6', '--by-layer', '--runs', '--modes', 'NONE,HILBERT_CENTROID_ROT_MP,CHAIN') }
    'pipelineTileSize-joint' = @{ figure = $true; main = 'PipelineTileSizeCli'; args = @($Munich, '--limit-tiles', '6', '--joint', 'transportation', '--ids', 'sequential') }
    'decodeTime-grid'      = @{ figure = $true;  main = 'DecodeTimeCli'; args = @($Subset, '--reps', '1', '--warmup', '1') }
    'decodeTime-rows'      = @{ figure = $true;  main = 'DecodeTimeCli'; args = @($Subset, '--encoding', 'QUAD_CORNER,QUAD_CORNER_SHARED,QUAD_CORNER_SHARED_FRAME,LINE_CONTINUATION,LINE_CONTINUATION_SHARED_DICT,LINE_CONTINUATION_SHARED_MARKED', '--reps', '1', '--warmup', '1') }
    'workshop'             = @{ figure = $true;  main = 'WorkshopCli'; args = @($Subset, '--layer', 'building', '--by-zoom') }
    'workshop-transportation' = @{ figure = $true; main = 'WorkshopCli'; args = @($Subset, '--layer', 'transportation', '--by-zoom', '--reorder') }
    'residualBits'         = @{ figure = $true;  main = 'ResidualBitsCli'; args = @($Subset, '--layer', 'transportation', '--by-zoom') }
    'sharedVertex'         = @{ figure = $true;  main = 'SharedVertexCli'; args = @($Subset, '--layer', 'transportation') }
    'roadChain'            = @{ figure = $true;  main = 'RoadChainCli'; args = @($Subset, '--layer', 'transportation') }
    'orientationPrior'     = @{ figure = $true;  main = 'OrientationPriorCli'; args = @($Subset, '--layer', 'building') }
    'lineFramePrior'       = @{ figure = $true;  main = 'LineFramePriorCli'; args = @($Subset, '--layer', 'transportation') }
    'turnRadius'           = @{ figure = $true;  main = 'TurnRadiusCli'; args = @($Subset, '--layer', 'transportation') }
    'turnScalar'           = @{ figure = $true;  main = 'TurnScalarCli'; args = @($Subset, '--layer', 'building') }
    'turn90Bitmap'         = @{ figure = $true;  main = 'Turn90BitmapCli'; args = @($Subset, '--layer', 'building') }
    'reflexSignal'         = @{ figure = $true;  main = 'ReflexSignalCli'; args = @($Subset, '--layer', 'building') }
    'shareTable'           = @{ figure = $true;  main = 'ShareTableCli'; args = @($Subset) }
    'bufferPoint'          = @{ figure = $true;  main = 'BufferPointCli'; args = @($Munich) }
    'extract'              = @{ figure = $true;  main = 'ExtractCli'; args = @('--mbtiles', $Munich, '--out', 'build/cov-extract', '--zoom', '10-10', '--limit', '3') }
    # no current-corpus results file cites these
    'entropy'              = @{ figure = $false; main = 'EntropyCli'; args = @($Subset, '--layer', 'building') }
    'condEntropy'          = @{ figure = $false; main = 'CondEntropyCli'; args = @($Subset, '--layer', 'building') }
    'ringPrior'            = @{ figure = $false; main = 'RingPriorCli'; args = @($Subset) }
    'buildingShape'        = @{ figure = $false; main = 'BuildingShapeCli'; args = @($Subset) }
    'featureBoundary'      = @{ figure = $false; main = 'FeatureBoundaryCli'; args = @($Subset) }
    'quadCornerResidual'   = @{ figure = $false; main = 'QuadCornerResidualCli'; args = @($Subset, '--layer', 'building') }
    'quadRotation'         = @{ figure = $false; main = 'QuadRotationCli'; args = @($Subset, '--layer', 'building') }
    'tileSize'             = @{ figure = $false; main = 'TileSizeCli'; args = @($Munich) }
}

$status = @()
foreach ($name in $commands.Keys) {
    $c = $commands[$name]
    $exec = "$execDir/$($c.figure ? 'fig' : 'oth')-$name.exec"
    Write-Host "== $name"
    $started = Get-Date
    & java "-javaagent:$tools/jacocoagent.jar=destfile=$exec,append=false" -Xmx3g -cp $cp "$cli$($c.main)" @($c.args) *> "$out/$name.log"
    $status += [pscustomobject]@{ name = $name; exit = $LASTEXITCODE; seconds = [int]((Get-Date) - $started).TotalSeconds }
}
$status | Format-Table | Out-String | Set-Content "$out/status.txt"
$status | Format-Table

$classes = 'build/classes/java/main'
$sources = 'src/main/java'
java -jar "$tools/jacococli.jar" report (Get-ChildItem "$execDir/fig-*.exec").FullName --classfiles $classes --sourcefiles $sources --xml "$out/figures.xml" --csv "$out/figures.csv"
java -jar "$tools/jacococli.jar" report (Get-ChildItem "$execDir/*.exec").FullName --classfiles $classes --sourcefiles $sources --xml "$out/all.xml" --csv "$out/all.csv"
Write-Host "reports in $out"
