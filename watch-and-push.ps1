# PhotoSync — Auto Git Push Watcher
# Watches the project for source file changes and automatically commits + pushes to GitHub.
# Ignores build output, .git internals, and the gradle cache.
#
# Run once from PowerShell:
#   Set-ExecutionPolicy -Scope CurrentUser RemoteSigned   (first time only)
#   cd "C:\Users\mcubi\Desktop\X\Phone Tablet Sync\PhotoSync"
#   .\watch-and-push.ps1
#
# Or double-click watch-and-push.bat to start it minimised.

$projectRoot = $PSScriptRoot
$debounceSeconds = 10   # wait this long after last change before committing

# Directories/extensions to ignore
$ignoredDirs = @('.git', 'build', '.gradle', '.idea', 'captures')

Write-Host "👁  Watching $projectRoot for changes..." -ForegroundColor Cyan
Write-Host "    Will auto-commit + push $debounceSeconds s after last change." -ForegroundColor DarkGray
Write-Host "    Press Ctrl+C to stop.`n" -ForegroundColor DarkGray

$watcher = New-Object System.IO.FileSystemWatcher
$watcher.Path = $projectRoot
$watcher.IncludeSubdirectories = $true
$watcher.EnableRaisingEvents = $true
$watcher.NotifyFilter = [System.IO.NotifyFilters]::FileName -bor
                        [System.IO.NotifyFilters]::LastWrite -bor
                        [System.IO.NotifyFilters]::DirectoryName

$lastChange = [datetime]::MinValue
$pendingPush = $false

$onChange = {
    $path = $Event.SourceEventArgs.FullPath

    # Skip ignored directories and binary build outputs
    foreach ($dir in $ignoredDirs) {
        if ($path -match [regex]::Escape("\$dir\") -or $path -match [regex]::Escape("\$dir`$")) {
            return
        }
    }
    # Skip .apk, .class, .dex — compiled artefacts
    if ($path -match '\.(apk|class|dex|jar|aar|so|zip)$') { return }

    $script:lastChange = [datetime]::UtcNow
    $script:pendingPush = $true
}

Register-ObjectEvent $watcher Changed -Action $onChange | Out-Null
Register-ObjectEvent $watcher Created -Action $onChange | Out-Null
Register-ObjectEvent $watcher Deleted -Action $onChange | Out-Null
Register-ObjectEvent $watcher Renamed -Action $onChange | Out-Null

try {
    while ($true) {
        Start-Sleep -Seconds 2

        if ($pendingPush) {
            $elapsed = ([datetime]::UtcNow - $lastChange).TotalSeconds
            if ($elapsed -ge $debounceSeconds) {
                $pendingPush = $false

                Set-Location $projectRoot

                # Check if there is anything to commit
                $status = git status --porcelain 2>&1
                if ($status) {
                    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm"
                    Write-Host "`n📝  Changes detected — committing..." -ForegroundColor Yellow

                    git add -A 2>&1 | Out-Null
                    git commit -m "Auto-save $timestamp" 2>&1 | Out-Null
                    $pushResult = git push 2>&1

                    if ($LASTEXITCODE -eq 0) {
                        Write-Host "✅  Pushed to GitHub at $timestamp" -ForegroundColor Green

                        # Increment build number and trigger APK build + upload
                        $bnFile = Join-Path $projectRoot "build_number.txt"
                        $bn = [int](Get-Content $bnFile -Raw).Trim() + 1
                        Set-Content $bnFile $bn
                        git add build_number.txt 2>&1 | Out-Null
                        git commit -m "Build $bn" 2>&1 | Out-Null
                        git push 2>&1 | Out-Null

                        Write-Host "[BUILD]  Building APKs (build $bn)..." -ForegroundColor Yellow
                        & .\gradlew.bat assembleDebug 2>&1 | Out-Null
                        if ($LASTEXITCODE -eq 0) {
                            Write-Host "[OK]  Build $bn complete - APKs uploaded to GitHub" -ForegroundColor Green
                        } else {
                            Write-Host "[FAIL]  Build $bn failed" -ForegroundColor Red
                        }
                    } else {
                        Write-Host "❌  Push failed: $pushResult" -ForegroundColor Red
                        # Re-queue so it tries again next cycle
                        $script:pendingPush = $true
                        $script:lastChange = [datetime]::UtcNow
                    }
                } else {
                    Write-Host "   No tracked changes to commit." -ForegroundColor DarkGray
                }
            }
        }
    }
} finally {
    $watcher.EnableRaisingEvents = $false
    $watcher.Dispose()
    Write-Host "`nWatcher stopped." -ForegroundColor DarkGray
}
