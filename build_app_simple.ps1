# Build script for StartFRP app

# Set execution policy
Set-ExecutionPolicy Bypass -Scope Process -Force

# Output current directory and time
Write-Host "Current directory: $pwd"
Write-Host "Current time: $(Get-Date)"

# Clean build directory
Write-Host "Cleaning build directory..."
Remove-Item -Path "./app/build" -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "Cleanup completed!"

# Execute build command
Write-Host "Building app..."
Write-Host "Command: ./gradlew build"

# Execute command using & operator
& ./gradlew build

# Check build status
$buildStatus = $LASTEXITCODE
if ($buildStatus -eq 0) {
    Write-Host "Build successful!"
} else {
    Write-Host "Build failed with exit code: $buildStatus"
    exit $buildStatus
}

# Output build result
Write-Host "Build completed!"
Write-Host "Current time: $(Get-Date)"

# Check APK files
Write-Host "Checking APK files:"

# Check Debug APK
$debugPath = "./app/build/outputs/apk/debug/"
if (Test-Path $debugPath) {
    Write-Host "Debug APK files:"
    Get-Item "$debugPath/*" | Select-Object Name, LastWriteTime
} else {
    Write-Host "Debug APK directory does not exist, build may have failed"
}

# Check Release APK
$releasePath = "./app/build/outputs/apk/release/"
if (Test-Path $releasePath) {
    Write-Host "Release APK files:"
    Get-Item "$releasePath/*" | Select-Object Name, LastWriteTime
} else {
    Write-Host "Release APK directory does not exist, build may have failed"
}

# Output summary
Write-Host ""
Write-Host "Build process completed!"
Write-Host "Please check the output above to confirm build status."