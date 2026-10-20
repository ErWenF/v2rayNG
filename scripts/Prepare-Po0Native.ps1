#Requires -Version 5.1
<#
.SYNOPSIS
Downloads and verifies the pinned Po0 native build inputs without replacing local dependencies.
.EXAMPLE
.\scripts\Prepare-Po0Native.ps1
.EXAMPLE
.\scripts\Prepare-Po0Native.ps1 -ArchivePath C:\Downloads\v2rayNG-Po0-native-inputs-2.3.11.1.zip
#>
[CmdletBinding()]
param(
    [string] $ProjectRoot,
    [string] $ArchivePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}

function Get-StreamSha256 {
    param([System.IO.Stream] $Stream)
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [System.BitConverter]::ToString($hasher.ComputeHash($Stream)).Replace('-', '').ToLowerInvariant()
    } finally {
        $hasher.Dispose()
    }
}

function Get-NativeDestination {
    param([string] $Root, [string] $RelativePath)
    $parts = $RelativePath.Split('/')
    $destination = $Root
    for ($i = 0; $i -lt $parts.Length; $i++) {
        $destination = Join-Path $destination $parts[$i]
        if (Test-Path -LiteralPath $destination) {
            $item = Get-Item -LiteralPath $destination -Force
            if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Refusing a linked destination: $destination"
            }
            if (($i -lt $parts.Length - 1) -ne $item.PSIsContainer) {
                throw "Unexpected file or directory at: $destination"
            }
        }
    }
    return $destination
}

$root = [System.IO.Path]::GetFullPath($ProjectRoot)
if (-not (Test-Path -LiteralPath (Join-Path $root 'V2rayNG/app/build.gradle.kts') -PathType Leaf)) {
    throw 'ProjectRoot must contain V2rayNG/app/build.gradle.kts.'
}

$manifestPath = Join-Path $PSScriptRoot 'po0-native-inputs.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$fixedArchiveUrl = 'https://github.com/ErWenF/v2rayNG/releases/download/2.3.11.1/v2rayNG-Po0-native-inputs-2.3.11.1.zip'
if ($manifest.archiveUrl -cne $fixedArchiveUrl -or $manifest.archiveSha256 -notmatch '^[a-fA-F0-9]{64}$') {
    throw 'The adjacent manifest must specify the pinned release URL and a SHA-256 digest.'
}

$knownPaths = @('V2rayNG/app/libs/libv2ray.aar')
foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')) {
    $knownPaths += "V2rayNG/app/libs/$abi/libhev-socks5-tunnel.so"
    $knownPaths += "V2rayNG/app/libs/$abi/libhevsockstun.so"
}
$expected = @{}
foreach ($file in @($manifest.files)) {
    if ($knownPaths -cnotcontains $file.path -or $expected.ContainsKey($file.path) -or
        $file.sha256 -notmatch '^[a-fA-F0-9]{64}$' -or [long]$file.bytes -le 0) {
        throw 'Invalid or duplicate native input in the manifest.'
    }
    $expected[$file.path] = $file
}
if ($expected.Count -ne $knownPaths.Count) {
    throw 'The manifest must list exactly the nine supported native inputs.'
}

$sourcePaths = @('AndroidLibXrayLite', 'hev-socks5-tunnel')
$sourceGitlinks = @{}
foreach ($source in @($manifest.sourceGitlinks)) {
    if ($sourcePaths -cnotcontains $source.path -or $sourceGitlinks.ContainsKey($source.path) -or
        $source.commit -notmatch '^[a-fA-F0-9]{40}$') {
        throw 'Invalid or duplicate source gitlink in the manifest.'
    }
    $sourceGitlinks[$source.path] = $source.commit
}
if ($sourceGitlinks.Count -ne $sourcePaths.Count) {
    throw 'The manifest must pin both native-source gitlinks.'
}
if (Test-Path -LiteralPath (Join-Path $root '.git')) {
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        throw 'Git is required to check the native-source revisions in this checkout.'
    }
    $gitlinks = @(& git -C $root ls-tree HEAD -- AndroidLibXrayLite hev-socks5-tunnel)
    if ($LASTEXITCODE -ne 0 -or $gitlinks.Count -ne $sourcePaths.Count) {
        throw 'Unable to read both native-source gitlinks from HEAD.'
    }
    foreach ($gitlink in $gitlinks) {
        if ($gitlink -notmatch '^160000 commit ([a-fA-F0-9]{40})\t(.+)$' -or
            -not $sourceGitlinks.ContainsKey($Matches[2]) -or
            $Matches[1] -ine $sourceGitlinks[$Matches[2]]) {
            throw 'HEAD native-source revisions differ from the pinned archive; build matching inputs instead.'
        }
    }
}

$temporaryArchive = $null
$archiveStream = $null
$zip = $null
$originalTls = $null
$createdFiles = New-Object 'System.Collections.Generic.List[string]'
try {
    if ([string]::IsNullOrWhiteSpace($ArchivePath)) {
        $temporaryArchive = [System.IO.Path]::GetTempFileName()
        $ArchivePath = $temporaryArchive
        if ($PSVersionTable.PSEdition -eq 'Desktop') {
            $originalTls = [System.Net.ServicePointManager]::SecurityProtocol
            [System.Net.ServicePointManager]::SecurityProtocol = $originalTls -bor [System.Net.SecurityProtocolType]::Tls12
        }
        Write-Host 'Downloading the pinned native-input archive...'
        Invoke-WebRequest -Uri $fixedArchiveUrl -OutFile $ArchivePath -UseBasicParsing -TimeoutSec 120
    }

    $archiveFullPath = (Resolve-Path -LiteralPath $ArchivePath).ProviderPath
    # Keep the verified archive open for reading, denying concurrent changes on Windows.
    $archiveStream = [System.IO.File]::Open($archiveFullPath, [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    if ((Get-StreamSha256 $archiveStream) -ine $manifest.archiveSha256) {
        throw 'Archive SHA-256 mismatch; no native inputs were written.'
    }
    $archiveStream.Position = 0
    $zip = [System.IO.Compression.ZipArchive]::new($archiveStream, [System.IO.Compression.ZipArchiveMode]::Read, $true)
    if ($zip.Entries.Count -ne $expected.Count) {
        throw 'Archive must contain exactly the nine listed files and no other entries.'
    }

    $entries = @{}
    foreach ($entry in $zip.Entries) {
        # An exact allowlist also excludes ../, rooted paths, backslashes and directory entries.
        if ($knownPaths -cnotcontains $entry.FullName -or $entries.ContainsKey($entry.FullName)) {
            throw "Unexpected or duplicate archive entry: $($entry.FullName)"
        }
        $file = $expected[$entry.FullName]
        if ($entry.Length -ne [long]$file.bytes) {
            throw "Archive entry size mismatch: $($entry.FullName)"
        }
        $inputStream = $entry.Open()
        try {
            if ((Get-StreamSha256 $inputStream) -ine $file.sha256) {
                throw "Archive entry SHA-256 mismatch: $($entry.FullName)"
            }
        } finally {
            $inputStream.Dispose()
        }
        $entries[$entry.FullName] = $entry
    }

    # Preflight every destination before creating directories or writing any dependency.
    $pending = @()
    foreach ($file in $manifest.files) {
        $destination = Get-NativeDestination -Root $root -RelativePath $file.path
        if (Test-Path -LiteralPath $destination -PathType Leaf) {
            $existing = Get-Item -LiteralPath $destination
            if ($existing.Length -ne [long]$file.bytes -or
                (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ine $file.sha256) {
                throw "Existing native input differs; refusing to replace it: $destination"
            }
        } else {
            $pending += [pscustomobject]@{ Entry = $entries[$file.path]; Destination = $destination }
        }
    }

    foreach ($item in $pending) {
        [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($item.Destination)) | Out-Null
        $outputStream = [System.IO.File]::Open($item.Destination, [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        $createdFiles.Add($item.Destination)
        try {
            $inputStream = $item.Entry.Open()
            try {
                $inputStream.CopyTo($outputStream)
            } finally {
                $inputStream.Dispose()
            }
        } finally {
            $outputStream.Dispose()
        }
    }
    Write-Host ("Verified {0} native inputs; installed {1}, already matching {2}." -f
        $expected.Count, $createdFiles.Count, ($expected.Count - $createdFiles.Count))
} catch {
    $failure = $_
    # Roll back only the specific files this invocation created; never remove pre-existing inputs.
    foreach ($createdFile in $createdFiles) {
        try { [System.IO.File]::Delete($createdFile) } catch { Write-Warning "Could not remove newly created file: $createdFile" }
    }
    throw $failure
} finally {
    if ($null -ne $zip) { $zip.Dispose() }
    if ($null -ne $archiveStream) { $archiveStream.Dispose() }
    if ($null -ne $originalTls) { [System.Net.ServicePointManager]::SecurityProtocol = $originalTls }
    if ($null -ne $temporaryArchive) {
        [System.IO.File]::Delete($temporaryArchive)
    }
}
