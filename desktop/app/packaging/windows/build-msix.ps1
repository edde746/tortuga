#!/usr/bin/env pwsh
# Microsoft Store package: packs the Windows app
# image (`:app-desktop:createDistributable`, desktop/app/build/compose/binaries/main/app/Tortuga: Tortuga.exe, app/
# with the jars, libmpv, the licence texts About shows and the launcher config, runtime/) into
# tortuga-windows.msixbundle for Partner Center. It refuses an app image that lacks one of those licence texts (the
# windows groups of the repository's licenses/ in licenses\apps.json, in app\resources\licenses; the licenses\ of the
# libmpv artifact the image was built with, desktop\mpv\build\mpv\<variant>\licenses, in app\resources\mpv\licenses).
#
# x64 only. Windows arm64 is an open decision (Skiko's windows-arm64 support has not been checked); the bundle takes one
# package per architecture, so arm64 becomes one more entry in $AppDirs.
#
# Nothing here signs the bundle. The Store re-signs it after certification, and a package signed with any other
# certificate fails publisher-identity validation; it cannot be installed outside the Store either, so the release
# workflow uploads it as an Actions artifact and does not attach it to the GitHub release.
#
#   desktop/app/packaging/windows/build-msix.ps1 -X64AppDir <app image> [-OutputDir <dir>]
#   desktop/app/packaging/windows/build-msix.ps1 -EmitManifestOnly [-OutputDir <dir>]
# -EmitManifestOnly writes AppxManifest.x64.xml and stops, so the manifest can be read without the Windows SDK.

param(
    [string]$X64AppDir,
    [string]$OutputDir = ".",
    [switch]$EmitManifestOnly
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $ScriptDir)))

function Get-MsixVersion {
    # The Store reserves the fourth (revision) field: MARKETING_VERSION major.minor.patch becomes major.minor.patch.0,
    # so every Store submission needs a new MARKETING_VERSION. Partner Center rejects a package version whose first
    # field is 0 (Microsoft's "App package requirements for MSIX app"), so a Store build needs 1.0.0 or later;
    # refusing here fails the build before anything is uploaded.
    $Match = Select-String -Path (Join-Path $ProjectRoot "version.xcconfig") -Pattern '^MARKETING_VERSION\s*=\s*(\S+)\s*$'
    if (-not $Match) {
        throw "version.xcconfig: no MARKETING_VERSION"
    }
    $Semver = $Match.Matches[0].Groups[1].Value
    if ($Semver -notmatch '^\d+\.\d+\.\d+$') {
        throw "MARKETING_VERSION must be major.minor.patch; got '$Semver'"
    }
    if ($Semver -match '^0+\.') {
        throw "MARKETING_VERSION $Semver starts with 0, which Partner Center rejects as a package version: the Store build needs 1.0.0 or later"
    }
    return "$Semver.0"
}

function Get-Languages {
    # The app's interface languages, one per shared/i18n/<tag>.i18n.json, English first:
    # the first is the package's default. The manifest's Resources declare them (the Store and Windows read the
    # package's languages there) and makepri's default qualifiers name the same list.
    $Tags = Get-ChildItem -Path (Join-Path $ProjectRoot "shared\i18n") -Filter "*.i18n.json" |
        ForEach-Object { $_.Name -replace '\.i18n\.json$', '' }
    if ($Tags -notcontains "en") {
        throw "shared/i18n has no en.i18n.json"
    }
    return @("en-US") + ($Tags | Where-Object { $_ -ne "en" } | Sort-Object)
}

function New-AppxManifest {
    param(
        [Parameter(Mandatory)][string]$MsixVersion,
        [Parameter(Mandatory)][string]$Architecture,
        [Parameter(Mandatory)][string[]]$Languages
    )

    # Partner Center's product identity page is the source of these three: Store validation rejects the upload if any
    # differs by a single character. The publisher is the account's; the name is the one Partner Center assigned when
    # the product name Tortuga Player was reserved there (Tortuga was taken; Store ID 9NWC65CPBCFM, package family
    # edde746.TortugaPlayer_13q3sv6jzathm). Identity/@Name is limited to '[-.A-Za-z0-9]+'.
    $IdentityName = "edde746.TortugaPlayer"
    $Publisher = "CN=AA9C53CB-AD3C-48DA-B3E3-D1E8986D4E25"
    $PublisherDisplayName = "edde746"
    # Properties/DisplayName must be one of the product's reserved names, or Partner Center refuses the package ("The
    # name found in the package is not one of your reserved app names"). VisualElements' DisplayName, the name Start
    # and the taskbar show, is the same, so the installed app is called what its Store listing is.
    $DisplayName = "Tortuga Player"

    # The foundation schema fixes the child order and makeappx rejects any other: Identity, Properties, Resources,
    # Dependencies, Capabilities, Applications.
    #
    # runFullTrust (restricted, hence rescap) is what a packaged Win32 app runs as; the JVM, libmpv and Skiko's
    # DirectComposition need nothing from AppContainer. privateNetworkClientServer declares the local network: a
    # self-hosted sync server or addon at home, and the loopback redirect of the browser sign-in.
    #
    # The stremio protocol opens Stremio links (an addon's install link, stremio:///detail/...) in Tortuga: Windows
    # starts Tortuga.exe with the link as its one argument (uap3's Parameters, "%1"; Cli.kt), and a start while Tortuga
    # runs hands it over and exits (SingleInstance.kt). Cli.kt takes nothing after a link, so a link holding a quote,
    # which splits there into more arguments, can't add options. With the official Stremio app installed too, the
    # user's choice decides: Windows asks which app opens them, then Settings > Apps > Default apps keeps it. There is no
    # tortuga protocol: a watch-together invite link is pasted into the app on Windows.
    $ResourceLines = ($Languages | ForEach-Object { "    <Resource Language=""$_"" />" }) -join "`n"
    return @"
<?xml version="1.0" encoding="utf-8"?>
<Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
         xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
         xmlns:uap3="http://schemas.microsoft.com/appx/manifest/uap/windows10/3"
         xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
         IgnorableNamespaces="uap uap3 rescap">

  <Identity Name="$IdentityName"
            Publisher="$Publisher"
            Version="$MsixVersion"
            ProcessorArchitecture="$Architecture" />

  <Properties>
    <DisplayName>$DisplayName</DisplayName>
    <PublisherDisplayName>$PublisherDisplayName</PublisherDisplayName>
    <Logo>assets\StoreLogo.png</Logo>
  </Properties>

  <Resources>
$ResourceLines
  </Resources>

  <Dependencies>
    <TargetDeviceFamily Name="Windows.Desktop"
                        MinVersion="10.0.17763.0"
                        MaxVersionTested="10.0.26100.0" />
  </Dependencies>

  <Capabilities>
    <rescap:Capability Name="runFullTrust" />
    <Capability Name="internetClient" />
    <Capability Name="privateNetworkClientServer" />
  </Capabilities>

  <Applications>
    <Application Id="Tortuga" Executable="Tortuga.exe" EntryPoint="Windows.FullTrustApplication">
      <uap:VisualElements DisplayName="$DisplayName"
                          Description="A video player that works with Stremio addons"
                          BackgroundColor="transparent"
                          Square150x150Logo="assets\Square150x150Logo.png"
                          Square44x44Logo="assets\Square44x44Logo.png">
        <uap:DefaultTile Wide310x150Logo="assets\Wide310x150Logo.png"
                         Square310x310Logo="assets\Square310x310Logo.png" />
        <uap:SplashScreen Image="assets\SplashScreen.png" />
      </uap:VisualElements>
      <Extensions>
        <uap3:Extension Category="windows.protocol">
          <uap3:Protocol Name="stremio" Parameters="&quot;%1&quot;" />
        </uap3:Extension>
      </Extensions>
    </Application>
  </Applications>
</Package>
"@
}

function Write-Utf8File {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Content
    )

    # The manifest declares utf-8. Out-File -Encoding utf8 writes a BOM on Windows PowerShell and none on pwsh 7, so
    # write the bytes directly: the same manifest from either host.
    [System.IO.File]::WriteAllText($Path, $Content, (New-Object System.Text.UTF8Encoding($false)))
}

function Invoke-SdkTool {
    param(
        [Parameter(Mandatory)][string]$Tool,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    # $ErrorActionPreference does not apply to native executables: every SDK call goes through this check.
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$(Split-Path -Leaf $Tool) $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$ResolvedOutput = (Resolve-Path $OutputDir).Path
$MsixVersion = Get-MsixVersion
$Languages = Get-Languages
Write-Host "Package version: $MsixVersion; languages: $($Languages -join ', ')" -ForegroundColor Green

if ($EmitManifestOnly) {
    $EmittedManifest = Join-Path $ResolvedOutput "AppxManifest.x64.xml"
    Write-Utf8File -Path $EmittedManifest -Content (New-AppxManifest -MsixVersion $MsixVersion -Architecture "x64" -Languages $Languages)
    Write-Host "Created: $EmittedManifest" -ForegroundColor Green
    exit 0
}

$AppDirs = [ordered]@{}
if ($X64AppDir) { $AppDirs["x64"] = $X64AppDir }
if ($AppDirs.Count -eq 0) {
    throw "Pass -X64AppDir: the app image from ./gradlew :app-desktop:createDistributable"
}
# The licence texts the Windows app ships, relative to app\resources\licenses: its groups of the repository's licenses/.
$LicensesDir = Join-Path $ProjectRoot "licenses"
$LicenseTexts = foreach ($Group in (Get-Content -Raw -LiteralPath (Join-Path $LicensesDir "apps.json") | ConvertFrom-Json).windows) {
    $Root = (Resolve-Path (Join-Path $LicensesDir $Group)).Path
    Get-ChildItem -LiteralPath $Root -Recurse -File | ForEach-Object { $_.FullName.Substring($Root.Length + 1) }
}
if (-not $LicenseTexts) {
    throw "No licence texts in licenses\apps.json's windows groups"
}
# The variant of mpv-jvm's extracted libmpv artifact each architecture's image is built with.
$MpvVariants = @{ "x64" = "x86_64" }
foreach ($Architecture in $AppDirs.Keys) {
    foreach ($Required in @("Tortuga.exe", "app\Tortuga.cfg", "runtime\bin\server\jvm.dll")) {
        if (-not (Test-Path -LiteralPath (Join-Path $AppDirs[$Architecture] $Required) -PathType Leaf)) {
            throw "Incomplete $Architecture app image: $($AppDirs[$Architecture]) has no $Required"
        }
    }
    if (-not (Get-ChildItem -LiteralPath (Join-Path $AppDirs[$Architecture] "app\resources\mpv") -Recurse -Filter "libmpv-2.dll")) {
        throw "Incomplete $Architecture app image: no libmpv-2.dll in app\resources\mpv"
    }
    foreach ($Text in $LicenseTexts) {
        if (-not (Test-Path -LiteralPath (Join-Path $AppDirs[$Architecture] "app\resources\licenses\$Text") -PathType Leaf)) {
            throw "Incomplete $Architecture app image: no licence text app\resources\licenses\$Text"
        }
    }
    # libmpv's licence texts: the licenses\ folder of the image's libmpv artifact (mpv-jvm's, extracted for the
    # architecture), in app\resources\mpv\licenses.
    $MpvLicenses = Join-Path $ProjectRoot "desktop\mpv\build\mpv\$($MpvVariants[$Architecture])\licenses"
    $MpvTexts = if (Test-Path -LiteralPath $MpvLicenses -PathType Container) {
        $MpvLicenses = (Resolve-Path -LiteralPath $MpvLicenses).Path
        @(Get-ChildItem -LiteralPath $MpvLicenses -Recurse -File | ForEach-Object { $_.FullName.Substring($MpvLicenses.Length + 1) })
    }
    if (-not $MpvTexts) {
        throw "No licence texts of libmpv in $MpvLicenses, the $Architecture app image's libmpv artifact"
    }
    foreach ($Text in $MpvTexts) {
        if (-not (Test-Path -LiteralPath (Join-Path $AppDirs[$Architecture] "app\resources\mpv\licenses\$Text") -PathType Leaf)) {
            throw "Incomplete $Architecture app image: no licence text app\resources\mpv\licenses\$Text"
        }
    }
    Write-Host "$Architecture app image: all $($MpvTexts.Count) licence texts of libmpv" -ForegroundColor Green
}
Write-Host "App image complete, with $($LicenseTexts.Count) licence texts" -ForegroundColor Green

# The newest Windows SDK that ships both tools; older installed kits are often partial.
$SdkRoot = "C:\Program Files (x86)\Windows Kits\10\bin"
$SdkBin = $null
if (Test-Path $SdkRoot) {
    $SdkBin = Get-ChildItem $SdkRoot -Directory |
        Where-Object { $_.Name -as [version] } |
        Sort-Object { [version]$_.Name } -Descending |
        ForEach-Object { Join-Path $_.FullName "x64" } |
        Where-Object { (Test-Path (Join-Path $_ "makeappx.exe")) -and (Test-Path (Join-Path $_ "makepri.exe")) } |
        Select-Object -First 1
}
if (-not $SdkBin) {
    throw "makeappx.exe and makepri.exe not found under $SdkRoot. Install the Windows SDK, or run with -EmitManifestOnly."
}
$MakeAppx = Join-Path $SdkBin "makeappx.exe"
$MakePri = Join-Path $SdkBin "makepri.exe"
Write-Host "Using $SdkBin" -ForegroundColor Cyan

# The per-architecture .msix packages go to a sibling directory: makeappx bundle takes every file under /d as a
# package.
$StagingRoot = Join-Path $ResolvedOutput "staging-msix"
$PackageDir = Join-Path $StagingRoot "packages"
if (Test-Path $StagingRoot) { Remove-Item $StagingRoot -Recurse -Force }
New-Item -ItemType Directory -Path $PackageDir -Force | Out-Null

# The resource index makes the qualified assets (targetsize, altform-unplated) resolvable; without it the shell plates
# the taskbar icon with the accent colour. It stays outside the staged payload. autoResourcePackage would move the
# qualified assets into resource packages a per-architecture .msix never carries, so it is removed.
$PriConfig = Join-Path $StagingRoot "priconfig.xml"
Invoke-SdkTool -Tool $MakePri -Arguments @("createconfig", "/cf", $PriConfig, "/dq", ($Languages -join "_"), "/o")
$PriConfigXml = [xml](Get-Content $PriConfig)
$Packaging = $PriConfigXml.resources.packaging
if ($Packaging) {
    $PriConfigXml.resources.RemoveChild($Packaging) | Out-Null
    $PriConfigXml.Save($PriConfig)
}

foreach ($Architecture in $AppDirs.Keys) {
    Write-Host "Staging $Architecture..." -ForegroundColor Cyan
    $Staging = Join-Path $StagingRoot $Architecture
    New-Item -ItemType Directory -Path $Staging -Force | Out-Null
    Copy-Item -Path (Join-Path $AppDirs[$Architecture] "*") -Destination $Staging -Recurse
    Copy-Item -Path (Join-Path $ScriptDir "assets") -Destination $Staging -Recurse
    Write-Utf8File -Path (Join-Path $Staging "AppxManifest.xml") `
        -Content (New-AppxManifest -MsixVersion $MsixVersion -Architecture $Architecture -Languages $Languages)

    # After the manifest: makepri reads the package identity from it.
    Invoke-SdkTool -Tool $MakePri -Arguments @(
        "new", "/pr", $Staging, "/cf", $PriConfig, "/of", (Join-Path $Staging "resources.pri"), "/o"
    )
    Invoke-SdkTool -Tool $MakeAppx -Arguments @(
        "pack", "/d", $Staging, "/p", (Join-Path $PackageDir "tortuga-$Architecture.msix"), "/o"
    )
}

$Bundle = Join-Path $ResolvedOutput "tortuga-windows.msixbundle"
Invoke-SdkTool -Tool $MakeAppx -Arguments @("bundle", "/d", $PackageDir, "/p", $Bundle, "/bv", $MsixVersion, "/o")
Remove-Item $StagingRoot -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "Created: $Bundle ($($AppDirs.Keys -join ', ')); unsigned, the Store signs it." -ForegroundColor Green
