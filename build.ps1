# Script PowerShell pour builder Quest-Mirror
# Configure l'environnement et lance Gradle

$env:JAVA_HOME = "C:\Tools\jdk-17.0.11+9"
$env:ANDROID_HOME = "C:\Users\rouba\AppData\Local\Android\Sdk"
$env:PATH = "C:\Tools\jdk-17.0.11+9\bin;C:\Tools\gradle-8.4\bin;$env:PATH"

# Lancer gradle avec tous les arguments
& "$PSScriptRoot\gradlew" $args
