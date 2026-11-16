$env:JAVA_HOME="C:\Tools\jdk-17.0.11+9"
Set-Location C:\dev\quest-mirror
.\gradlew :phone:compileDebugKotlin --no-daemon 2>&1 | Tee-Object -Variable output
$output