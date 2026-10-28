$ErrorActionPreference = "Stop"

$tomcatHome = "C:\tomcat\apache-tomcat-10.1.61"
$backendHome = $PSScriptRoot
$warPath = Join-Path $backendHome "target\placement.war"
$webappsPath = Join-Path $tomcatHome "webapps"
$deployedWarPath = Join-Path $webappsPath "placement.war"
$deployedAppPath = Join-Path $webappsPath "placement"
$catalinaBat = Join-Path $tomcatHome "bin\catalina.bat"

foreach ($path in @($tomcatHome, $webappsPath, $catalinaBat, $warPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required Tomcat or WAR path was not found: $path"
    }
}

$listeners = Get-NetTCPConnection -LocalPort 8081 -State Listen `
    -ErrorAction SilentlyContinue
if ($listeners) {
    $processIds = ($listeners.OwningProcess | Sort-Object -Unique) -join ", "
    throw "Port 8081 is already in use by process ID(s): $processIds. Stop the existing server before deploying."
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$war = [System.IO.Compression.ZipFile]::OpenRead($warPath)
try {
    $oracleDriver = $war.Entries | Where-Object {
        $_.FullName -match "^WEB-INF/lib/ojdbc[^/]*\.jar$"
    } | Select-Object -First 1

    if (-not $oracleDriver) {
        throw "The WAR does not contain the Oracle JDBC driver under WEB-INF/lib."
    }
}
finally {
    $war.Dispose()
}

$javaHomeMatch = & java -XshowSettings:properties -version 2>&1 |
    Select-String "^\s*java\.home\s*=\s*(.+)$" |
    Select-Object -First 1
if (-not $javaHomeMatch) {
    throw "Could not determine JAVA_HOME from the installed Java runtime."
}
$javaHome = $javaHomeMatch.Matches[0].Groups[1].Value.Trim()
if (-not (Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe"))) {
    throw "The detected JAVA_HOME does not contain bin\java.exe."
}

$secureUsername = Read-Host "Oracle DB username" -AsSecureString
$securePassword = Read-Host "Oracle DB password" -AsSecureString
if ($secureUsername.Length -eq 0 -or $securePassword.Length -eq 0) {
    $secureUsername.Dispose()
    $securePassword.Dispose()
    throw "Oracle DB username and password must not be empty."
}

$environmentNames = @(
    "DB_URL",
    "DB_USERNAME",
    "DB_PASSWORD",
    "JAVA_HOME",
    "JRE_HOME",
    "CATALINA_HOME",
    "CATALINA_BASE"
)
$originalEnvironment = @{}
foreach ($name in $environmentNames) {
    $originalEnvironment[$name] =
        [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    $usernameBstr = [IntPtr]::Zero
    $passwordBstr = [IntPtr]::Zero
    try {
        $usernameBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR(
            $secureUsername
        )
        $passwordBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR(
            $securePassword
        )

        $env:DB_URL = "jdbc:oracle:thin:@localhost:1521:XE"
        $env:DB_USERNAME = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(
            $usernameBstr
        )
        $env:DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(
            $passwordBstr
        )
    }
    finally {
        if ($usernameBstr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($usernameBstr)
        }
        if ($passwordBstr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordBstr)
        }
        $secureUsername.Dispose()
        $securePassword.Dispose()
    }

    $env:JAVA_HOME = $javaHome
    $env:JRE_HOME = $javaHome
    $env:CATALINA_HOME = $tomcatHome
    $env:CATALINA_BASE = $tomcatHome

    if (Test-Path -LiteralPath $deployedAppPath) {
        Remove-Item -LiteralPath $deployedAppPath -Recurse -Force
    }

    Copy-Item -LiteralPath $warPath -Destination $deployedWarPath -Force

    Push-Location $tomcatHome
    try {
        & $catalinaBat run
        if ($LASTEXITCODE -ne 0) {
            throw "Tomcat exited with code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable(
            $name,
            $originalEnvironment[$name],
            "Process"
        )
    }
}
