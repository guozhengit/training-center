[CmdletBinding()]
param(
    [string]$WorkspaceRoot,
    [Parameter(DontShow = $true)][switch]$TestFailBeforePublish,
    [Parameter(DontShow = $true)][switch]$TestTamperPublishedAfterReplace,
    [Parameter(DontShow = $true)][switch]$TestReplaceBackupAfterReplace
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$isWindowsRuntime =
    [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
if ($isWindowsRuntime -and -not ('OwnedFileDeletion' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using Microsoft.Win32.SafeHandles;

public static class OwnedFileDeletion
{
    private const uint GenericRead = 0x80000000;
    private const uint DeleteAccess = 0x00010000;
    private const uint FileReadAttributes = 0x00000080;
    private const uint FileShareRead = 0x00000001;
    private const uint OpenExisting = 3;
    private const uint FileFlagOpenReparsePoint = 0x00200000;
    private const uint FileFlagSequentialScan = 0x08000000;
    private const uint FileAttributeReparsePoint = 0x00000400;

    private enum FileInfoByHandleClass
    {
        FileAttributeTagInfo = 9,
        FileDispositionInfo = 4
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct FileAttributeTagInformation
    {
        public uint FileAttributes;
        public uint ReparseTag;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct FileDispositionInformation
    {
        [MarshalAs(UnmanagedType.Bool)]
        public bool DeleteFile;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafeFileHandle CreateFile(
        string fileName,
        uint desiredAccess,
        uint shareMode,
        IntPtr securityAttributes,
        uint creationDisposition,
        uint flagsAndAttributes,
        IntPtr templateFile);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetFileInformationByHandleEx(
        SafeFileHandle file,
        FileInfoByHandleClass informationClass,
        out FileAttributeTagInformation information,
        uint bufferSize);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetFileInformationByHandle(
        SafeFileHandle file,
        FileInfoByHandleClass informationClass,
        ref FileDispositionInformation information,
        uint bufferSize);

    public static bool DeleteIfContentMatches(
        string path,
        long expectedLength,
        string expectedSha256)
    {
        SafeFileHandle handle = CreateFile(
            path,
            GenericRead | DeleteAccess | FileReadAttributes,
            FileShareRead,
            IntPtr.Zero,
            OpenExisting,
            FileFlagOpenReparsePoint | FileFlagSequentialScan,
            IntPtr.Zero);
        if (handle.IsInvalid)
        {
            int error = Marshal.GetLastWin32Error();
            handle.Dispose();
            throw new Win32Exception(error, "Cannot lock the owned backup");
        }

        using (FileStream stream = new FileStream(handle, FileAccess.Read, 4096, false))
        {
            FileAttributeTagInformation tag;
            if (!GetFileInformationByHandleEx(
                    stream.SafeFileHandle,
                    FileInfoByHandleClass.FileAttributeTagInfo,
                    out tag,
                    (uint)Marshal.SizeOf(typeof(FileAttributeTagInformation))))
            {
                throw new Win32Exception(
                    Marshal.GetLastWin32Error(),
                    "Cannot inspect the locked backup");
            }
            if ((tag.FileAttributes & FileAttributeReparsePoint) != 0)
            {
                throw new InvalidOperationException(
                    "Backup is a reparse point and will not be deleted");
            }

            string actualSha256;
            using (SHA256 sha256 = SHA256.Create())
            {
                actualSha256 = BitConverter.ToString(
                    sha256.ComputeHash(stream)).Replace("-", String.Empty);
            }
            if (stream.Length != expectedLength ||
                !String.Equals(
                    actualSha256,
                    expectedSha256,
                    StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidOperationException(
                    "Backup does not match the prior destination");
            }

            FileDispositionInformation disposition =
                new FileDispositionInformation { DeleteFile = true };
            return SetFileInformationByHandle(
                stream.SafeFileHandle,
                FileInfoByHandleClass.FileDispositionInfo,
                ref disposition,
                (uint)Marshal.SizeOf(typeof(FileDispositionInformation)));
        }
    }
}
'@
}

function Assert-StrictJsonNoDuplicateMembers {
    param([Parameter(Mandatory = $true)][string]$Text)

    $script:StrictJsonText = $Text
    $script:StrictJsonIndex = 0
    Read-StrictJsonValue -Depth 0
    Skip-StrictJsonWhitespace
    if ($script:StrictJsonIndex -ne $script:StrictJsonText.Length) {
        throw "Malformed JSON at offset $script:StrictJsonIndex"
    }
}

function Skip-StrictJsonWhitespace {
    while ($script:StrictJsonIndex -lt $script:StrictJsonText.Length -and
        " `t`r`n".Contains(
            [string]$script:StrictJsonText[$script:StrictJsonIndex])) {
        $script:StrictJsonIndex++
    }
}

function Read-StrictJsonValue {
    param([Parameter(Mandatory = $true)][int]$Depth)

    if ($Depth -gt 128) {
        throw 'Malformed JSON: nesting exceeds 128 levels'
    }
    Skip-StrictJsonWhitespace
    if ($script:StrictJsonIndex -ge $script:StrictJsonText.Length) {
        throw 'Malformed JSON: unexpected end of input'
    }
    $current = $script:StrictJsonText[$script:StrictJsonIndex]
    switch ($current) {
        '{' { Read-StrictJsonObject -Depth ($Depth + 1); return }
        '[' { Read-StrictJsonArray -Depth ($Depth + 1); return }
        '"' { $null = Read-StrictJsonString; return }
        't' { Read-StrictJsonLiteral -Literal 'true'; return }
        'f' { Read-StrictJsonLiteral -Literal 'false'; return }
        'n' { Read-StrictJsonLiteral -Literal 'null'; return }
        default {
            Read-StrictJsonNumber
            return
        }
    }
}

function Read-StrictJsonObject {
    param([Parameter(Mandatory = $true)][int]$Depth)

    $script:StrictJsonIndex++
    $members = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal)
    Skip-StrictJsonWhitespace
    if ($script:StrictJsonIndex -lt $script:StrictJsonText.Length -and
        $script:StrictJsonText[$script:StrictJsonIndex] -eq '}') {
        $script:StrictJsonIndex++
        return
    }
    while ($true) {
        Skip-StrictJsonWhitespace
        if ($script:StrictJsonIndex -ge $script:StrictJsonText.Length -or
            $script:StrictJsonText[$script:StrictJsonIndex] -ne '"') {
            throw "Malformed JSON object key at offset $script:StrictJsonIndex"
        }
        $member = Read-StrictJsonString
        if (-not $members.Add($member)) {
            throw "Duplicate JSON member '$member'"
        }
        Skip-StrictJsonWhitespace
        if ($script:StrictJsonIndex -ge $script:StrictJsonText.Length -or
            $script:StrictJsonText[$script:StrictJsonIndex] -ne ':') {
            throw "Malformed JSON object colon at offset $script:StrictJsonIndex"
        }
        $script:StrictJsonIndex++
        Read-StrictJsonValue -Depth $Depth
        Skip-StrictJsonWhitespace
        if ($script:StrictJsonIndex -ge $script:StrictJsonText.Length) {
            throw 'Malformed JSON object: unexpected end of input'
        }
        $separator = $script:StrictJsonText[$script:StrictJsonIndex++]
        if ($separator -eq '}') {
            return
        }
        if ($separator -ne ',') {
            throw "Malformed JSON object separator at offset $($script:StrictJsonIndex - 1)"
        }
    }
}

function Read-StrictJsonArray {
    param([Parameter(Mandatory = $true)][int]$Depth)

    $script:StrictJsonIndex++
    Skip-StrictJsonWhitespace
    if ($script:StrictJsonIndex -lt $script:StrictJsonText.Length -and
        $script:StrictJsonText[$script:StrictJsonIndex] -eq ']') {
        $script:StrictJsonIndex++
        return
    }
    while ($true) {
        Read-StrictJsonValue -Depth $Depth
        Skip-StrictJsonWhitespace
        if ($script:StrictJsonIndex -ge $script:StrictJsonText.Length) {
            throw 'Malformed JSON array: unexpected end of input'
        }
        $separator = $script:StrictJsonText[$script:StrictJsonIndex++]
        if ($separator -eq ']') {
            return
        }
        if ($separator -ne ',') {
            throw "Malformed JSON array separator at offset $($script:StrictJsonIndex - 1)"
        }
    }
}

function Read-StrictJsonString {
    $script:StrictJsonIndex++
    $builder = [System.Text.StringBuilder]::new()
    while ($script:StrictJsonIndex -lt $script:StrictJsonText.Length) {
        $current = $script:StrictJsonText[$script:StrictJsonIndex++]
        if ($current -eq '"') {
            return $builder.ToString()
        }
        if ([int][char]$current -lt 32) {
            throw "Malformed JSON string control character"
        }
        if ($current -ne '\') {
            [void]$builder.Append($current)
            continue
        }
        if ($script:StrictJsonIndex -ge $script:StrictJsonText.Length) {
            throw 'Malformed JSON string escape'
        }
        $escape = $script:StrictJsonText[$script:StrictJsonIndex++]
        switch ($escape) {
            '"' { [void]$builder.Append('"') }
            '\' { [void]$builder.Append('\') }
            '/' { [void]$builder.Append('/') }
            'b' { [void]$builder.Append([char]8) }
            'f' { [void]$builder.Append([char]12) }
            'n' { [void]$builder.Append([char]10) }
            'r' { [void]$builder.Append([char]13) }
            't' { [void]$builder.Append([char]9) }
            'u' {
                if ($script:StrictJsonIndex + 4 -gt $script:StrictJsonText.Length) {
                    throw 'Malformed JSON Unicode escape'
                }
                $hex = $script:StrictJsonText.Substring($script:StrictJsonIndex, 4)
                $code = 0
                if (-not [int]::TryParse(
                        $hex,
                        [System.Globalization.NumberStyles]::HexNumber,
                        [System.Globalization.CultureInfo]::InvariantCulture,
                        [ref]$code)) {
                    throw "Malformed JSON Unicode escape: $hex"
                }
                [void]$builder.Append([char]$code)
                $script:StrictJsonIndex += 4
            }
            default { throw "Malformed JSON string escape: \$escape" }
        }
    }
    throw 'Malformed JSON: unterminated string'
}

function Read-StrictJsonLiteral {
    param([Parameter(Mandatory = $true)][string]$Literal)

    if ($script:StrictJsonIndex + $Literal.Length -gt $script:StrictJsonText.Length -or
        $script:StrictJsonText.Substring(
            $script:StrictJsonIndex, $Literal.Length) -cne $Literal) {
        throw "Malformed JSON literal at offset $script:StrictJsonIndex"
    }
    $script:StrictJsonIndex += $Literal.Length
}

function Read-StrictJsonNumber {
    $start = $script:StrictJsonIndex
    while ($script:StrictJsonIndex -lt $script:StrictJsonText.Length -and
        '-+0123456789.eE'.Contains(
            [string]$script:StrictJsonText[$script:StrictJsonIndex])) {
        $script:StrictJsonIndex++
    }
    $token = $script:StrictJsonText.Substring(
        $start, $script:StrictJsonIndex - $start)
    if ($token -notmatch '^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$') {
        throw "Malformed JSON number at offset $start"
    }
}

function Assert-NoReparseComponents {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $full = [System.IO.Path]::GetFullPath($Path)
    $root = [System.IO.Path]::GetPathRoot($full)
    $rootItem = Get-Item -LiteralPath $root -Force
    if (($rootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Description crosses a reparse root: $root"
    }
    $current = $root
    $relative = $full.Substring($root.Length)
    foreach ($segment in $relative.Split(
            [System.IO.Path]::DirectorySeparatorChar,
            [System.StringSplitOptions]::RemoveEmptyEntries)) {
        $current = [System.IO.Path]::Combine($current, $segment)
        if (-not (Test-Path -LiteralPath $current)) {
            break
        }
        $item = Get-Item -LiteralPath $current -Force
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Description crosses a reparse point: $current"
        }
    }
}

function Assert-ContainedPath {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Candidate,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $normalizedRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $normalizedCandidate = [System.IO.Path]::GetFullPath($Candidate)
    $prefix = $normalizedRoot + [System.IO.Path]::DirectorySeparatorChar
    if (-not [string]::Equals(
            $normalizedCandidate,
            $normalizedRoot,
            [System.StringComparison]::OrdinalIgnoreCase) -and
        -not $normalizedCandidate.StartsWith(
            $prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description escapes workspace: $normalizedCandidate"
    }
}

function Ensure-SafeDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$Workspace,
        [Parameter(Mandatory = $true)][string]$Relative
    )

    $current = $Workspace
    foreach ($segment in $Relative.Split('/')) {
        $current = [System.IO.Path]::Combine($current, $segment)
        if (-not (Test-Path -LiteralPath $current)) {
            [System.IO.Directory]::CreateDirectory($current) | Out-Null
        }
        $item = Get-Item -LiteralPath $current -Force
        if (-not $item.PSIsContainer -or
            ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Output directory is a reparse point or not a safe plain directory: $current"
        }
        Assert-ContainedPath -Root $Workspace -Candidate $item.FullName `
            -Description 'Output directory'
    }
    return $current
}

function Get-SafeRegularFileIdentity {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Description
    )

    Assert-ContainedPath -Root $Root -Candidate $Path -Description $Description
    Assert-NoReparseComponents -Path $Path -Description $Description
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Description is not a regular file: $Path"
    }
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer -or
        ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Description is not a safe plain regular file: $Path"
    }
    return [pscustomobject]@{
        FullName = $item.FullName
        Length = [long]$item.Length
        CreationTicks = [long]$item.CreationTimeUtc.Ticks
        LastWriteTicks = [long]$item.LastWriteTimeUtc.Ticks
        Sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    }
}

function Assert-SameFileIdentity {
    param(
        [Parameter(Mandatory = $true)]$Expected,
        [Parameter(Mandatory = $true)]$Actual,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if (-not [string]::Equals(
            [string]$Expected.FullName,
            [string]$Actual.FullName,
            [System.StringComparison]::OrdinalIgnoreCase) -or
        $Expected.Length -ne $Actual.Length -or
        $Expected.CreationTicks -ne $Actual.CreationTicks -or
        $Expected.LastWriteTicks -ne $Actual.LastWriteTicks -or
        $Expected.Sha256 -cne $Actual.Sha256) {
        throw "$Description identity changed before publication"
    }
}

function Assert-SameFileContent {
    param(
        [Parameter(Mandatory = $true)]$Expected,
        [Parameter(Mandatory = $true)]$Actual,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    # File.Replace can apply filesystem-specific timestamp semantics to the
    # destination and backup. Ownership is therefore based on exact length and
    # SHA-256 bytes; timestamps are deliberately observed but not compared.
    if ($Expected.Length -ne $Actual.Length -or
        $Expected.Sha256 -cne $Actual.Sha256) {
        throw $FailureMessage
    }
}

function Require-PortableRelativePath {
    param(
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][string]$Field
    )

    if ([string]::IsNullOrWhiteSpace($Value) -or
        $Value -ne $Value.Trim() -or
        $Value.StartsWith('/') -or
        $Value.StartsWith('\') -or
        $Value -match '^[A-Za-z]:' -or
        $Value.Contains('\') -or
        $Value -match '[\x00-\x20\x7f]' -or
        @($Value.ToCharArray() | Where-Object {
            [char]::IsWhiteSpace($_) -or [char]::IsControl($_)
        }).Count -gt 0) {
        throw "$Field is not a normalized portable relative path: $Value"
    }
    $segments = $Value.Split([char]'/', [System.StringSplitOptions]::None)
    if ($segments.Count -eq 0) {
        throw "$Field has no path segments"
    }
    foreach ($segment in $segments) {
        if ([string]::IsNullOrEmpty($segment) -or
            $segment -eq '.' -or
            $segment -eq '..' -or
            $segment.Contains(':') -or
            $segment.EndsWith('.') -or
            $segment -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]|CONIN\$|CONOUT\$)(?:\..*)?$') {
            throw "$Field contains an unsafe path segment: $Value"
        }
    }
    return [string]::Join('/', $segments)
}

function Require-TextProperty {
    param(
        [Parameter(Mandatory = $true)]$Record,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][int]$Index
    )

    $propertyNames = @($Record.PSObject.Properties | ForEach-Object { $_.Name })
    $property = $Record.PSObject.Properties[$Name]
    if (-not ($propertyNames -ccontains $Name) -or $null -eq $property -or
        $property.Value -isnot [string] -or
        [string]::IsNullOrWhiteSpace([string]$property.Value)) {
        throw "catalog[$Index].$Name must be nonblank text"
    }
    return [string]$property.Value
}

function Assert-SafeExistingOfficialFile {
    param(
        [Parameter(Mandatory = $true)][string]$OfficialRoot,
        [Parameter(Mandatory = $true)][string]$PortablePath,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $relative = Require-PortableRelativePath -Value $PortablePath -Field $Description
    $candidate = $OfficialRoot
    foreach ($segment in $relative.Split('/')) {
        $candidate = [System.IO.Path]::Combine($candidate, $segment)
        if (-not (Test-Path -LiteralPath $candidate)) {
            throw "$Description does not exist: $PortablePath"
        }
        $item = Get-Item -LiteralPath $candidate -Force
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Description crosses a reparse point: $PortablePath"
        }
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "$Description is not a regular file: $PortablePath"
    }
    $resolved = (Resolve-Path -LiteralPath $candidate).Path
    $rootPrefix = $OfficialRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith(
            $rootPrefix,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description escapes the official root: $PortablePath"
    }
}

function ConvertTo-DeterministicJsonString {
    param([Parameter(Mandatory = $true)][string]$Value)

    $builder = [System.Text.StringBuilder]::new($Value.Length + 2)
    [void]$builder.Append('"')
    foreach ($character in $Value.ToCharArray()) {
        switch ([int][char]$character) {
            8 { [void]$builder.Append('\b'); continue }
            9 { [void]$builder.Append('\t'); continue }
            10 { [void]$builder.Append('\n'); continue }
            12 { [void]$builder.Append('\f'); continue }
            13 { [void]$builder.Append('\r'); continue }
            34 { [void]$builder.Append('\"'); continue }
            92 { [void]$builder.Append('\\'); continue }
        }
        if ([int][char]$character -lt 32) {
            [void]$builder.Append(('\u{0:x4}' -f [int][char]$character))
        } else {
            [void]$builder.Append($character)
        }
    }
    [void]$builder.Append('"')
    return $builder.ToString()
}

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = [System.IO.Path]::GetFullPath(
        [System.IO.Path]::Combine($PSScriptRoot, '..', '..'))
} else {
    $WorkspaceRoot = [System.IO.Path]::GetFullPath($WorkspaceRoot)
}
if (-not (Test-Path -LiteralPath $WorkspaceRoot -PathType Container)) {
    throw "Workspace root must be an existing directory: $WorkspaceRoot"
}
Assert-NoReparseComponents -Path $WorkspaceRoot -Description 'Workspace root'
$WorkspaceRoot = (Resolve-Path -LiteralPath $WorkspaceRoot).Path

$officialRoot = [System.IO.Path]::Combine($WorkspaceRoot, 'output', 'coding-ai-exam')
$catalogPath = [System.IO.Path]::Combine($officialRoot, 'catalog', 'questions.json')
$mappingPath = [System.IO.Path]::Combine(
    $WorkspaceRoot, 'training-center', 'config', 'starter-mapping.json')

if (-not (Test-Path -LiteralPath $catalogPath -PathType Leaf)) {
    throw "Official catalog not found: $catalogPath"
}
Assert-ContainedPath -Root $WorkspaceRoot -Candidate $officialRoot `
    -Description 'Official root'
Assert-NoReparseComponents -Path $officialRoot -Description 'Official root'
Assert-NoReparseComponents -Path $catalogPath -Description 'Official catalog'
$officialRoot = (Resolve-Path -LiteralPath $officialRoot).Path

$strictUtf8 = [System.Text.UTF8Encoding]::new($false, $true)
try {
    $catalogText = [System.IO.File]::ReadAllText($catalogPath, $strictUtf8)
} catch {
    throw "Official catalog is not valid UTF-8: $($_.Exception.Message)"
}
Assert-StrictJsonNoDuplicateMembers -Text $catalogText
try {
    $catalog = $catalogText | ConvertFrom-Json
} catch {
    throw "Official catalog is malformed JSON: $($_.Exception.Message)"
}
if ($catalog -isnot [System.Array]) {
    throw 'Official catalog root must be a JSON array'
}
$catalogCount = [System.Linq.Enumerable]::Count([object[]]$catalog)
if ($catalogCount -ne 120) {
    throw "Official catalog must contain exactly 120 records; found $catalogCount"
}

$ids = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal)
$sources = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal)
$tests = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal)
$mappings = [System.Collections.Generic.List[object]]::new()

for ($index = 0; $index -lt $catalogCount; $index++) {
    $record = $catalog[$index]
    if ($null -eq $record -or $record -is [string] -or
        @($record.PSObject.Properties).Count -eq 0) {
        throw "catalog[$index] must be a JSON object"
    }

    $id = Require-TextProperty -Record $record -Name 'id' -Index $index
    $language = (Require-TextProperty -Record $record -Name 'language' -Index $index).
        ToLowerInvariant()
    $sourcePath = Require-PortableRelativePath `
        -Value (Require-TextProperty -Record $record -Name 'source_path' -Index $index) `
        -Field "catalog[$index].source_path"
    $testPath = Require-PortableRelativePath `
        -Value (Require-TextProperty -Record $record -Name 'test_path' -Index $index) `
        -Field "catalog[$index].test_path"

    if ($id -notmatch '^(B|A|I)\d{3}$') {
        throw "catalog[$index].id is malformed: $id"
    }
    if (-not $ids.Add($id)) {
        throw "Duplicate catalog id: $id"
    }
    if (-not $sources.Add($sourcePath)) {
        throw "Duplicate catalog source_path: $sourcePath"
    }
    if (-not $tests.Add($testPath)) {
        throw "Duplicate catalog test_path: $testPath"
    }

    if ($language -eq 'java') {
        if (-not $sourcePath.StartsWith('java/src/main/java/') -or
            -not $sourcePath.EndsWith('.java') -or
            -not $testPath.StartsWith('java/src/test/java/') -or
            -not $testPath.EndsWith('Test.java')) {
            throw "$id has incompatible Java source/test paths"
        }
        $runnerKind = 'MAVEN'
        $filename = $testPath.Substring($testPath.LastIndexOf('/') + 1)
        $testSelector = $filename.Substring(0, $filename.Length - '.java'.Length)
        if ($testSelector -notmatch '^[A-Za-z_$][A-Za-z0-9_$]*$') {
            throw "$id has an unsafe Maven test selector: $testSelector"
        }
    } elseif ($language -eq 'python') {
        if (-not $sourcePath.StartsWith('python/src/') -or
            -not $sourcePath.EndsWith('.py') -or
            -not $testPath.StartsWith('python/tests/') -or
            -not $testPath.EndsWith('.py')) {
            throw "$id has incompatible Python source/test paths"
        }
        $runnerKind = 'PYTEST'
        $testSelector = $testPath
    } else {
        throw "$id has unsupported language: $language"
    }

    Assert-SafeExistingOfficialFile `
        -OfficialRoot $officialRoot `
        -PortablePath $sourcePath `
        -Description "$id formal source"
    Assert-SafeExistingOfficialFile `
        -OfficialRoot $officialRoot `
        -PortablePath $testPath `
        -Description "$id formal selected test"

    $mappings.Add([ordered]@{
        question_id = $id
        starter_path = 'training-center/starters/' + $sourcePath
        sandbox_source_path = $sourcePath
        runner_kind = $runnerKind
        test_selector = $testSelector
    })
}

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('[')
for ($index = 0; $index -lt $mappings.Count; $index++) {
    $mapping = $mappings[$index]
    $lines.Add('  {')
    $lines.Add('    "question_id": ' +
        (ConvertTo-DeterministicJsonString $mapping.question_id) + ',')
    $lines.Add('    "starter_path": ' +
        (ConvertTo-DeterministicJsonString $mapping.starter_path) + ',')
    $lines.Add('    "sandbox_source_path": ' +
        (ConvertTo-DeterministicJsonString $mapping.sandbox_source_path) + ',')
    $lines.Add('    "runner_kind": ' +
        (ConvertTo-DeterministicJsonString $mapping.runner_kind) + ',')
    $lines.Add('    "test_selector": ' +
        (ConvertTo-DeterministicJsonString $mapping.test_selector))
    $suffix = if ($index -eq $mappings.Count - 1) { '  }' } else { '  },' }
    $lines.Add($suffix)
}
$lines.Add(']')
$json = [string]::Join("`n", $lines) + "`n"

$mappingDirectory = Ensure-SafeDirectory `
    -Workspace $WorkspaceRoot -Relative 'training-center/config'
Assert-ContainedPath -Root $WorkspaceRoot -Candidate $mappingPath `
    -Description 'Starter mapping output'
if ($mappingPath.StartsWith(
        $officialRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Starter mapping output must not alias the official tree: $mappingPath"
}
$destinationExisted = Test-Path -LiteralPath $mappingPath
$existingOutputIdentity = if ($destinationExisted) {
    Get-SafeRegularFileIdentity `
        -Root $mappingDirectory `
        -Path $mappingPath `
        -Description 'Existing Starter mapping output'
} else {
    $null
}
$temporaryPath = $mappingPath + '.' + [System.Guid]::NewGuid().ToString('N') + '.tmp'
$backupPath = $mappingPath + '.' + [System.Guid]::NewGuid().ToString('N') + '.bak'
$operationFailure = $null
try {
    [System.IO.File]::WriteAllText(
        $temporaryPath,
        $json,
        [System.Text.UTF8Encoding]::new($false))

    $temporaryIdentity = Get-SafeRegularFileIdentity `
        -Root $mappingDirectory `
        -Path $temporaryPath `
        -Description 'Starter mapping temporary file'

    # Publication boundary: all bytes are closed/flushed by WriteAllText.
    # Revalidate the directory, temp node, and prior destination immediately
    # before the single atomic filesystem operation.
    Assert-NoReparseComponents -Path $mappingDirectory `
        -Description 'Output directory'
    $currentTemporaryIdentity = Get-SafeRegularFileIdentity `
        -Root $mappingDirectory `
        -Path $temporaryPath `
        -Description 'Starter mapping temporary file'
    Assert-SameFileIdentity `
        -Expected $temporaryIdentity `
        -Actual $currentTemporaryIdentity `
        -Description 'Starter mapping temporary file'

    if ($destinationExisted) {
        $currentOutputIdentity = Get-SafeRegularFileIdentity `
            -Root $mappingDirectory `
            -Path $mappingPath `
            -Description 'Existing Starter mapping output'
        Assert-SameFileIdentity `
            -Expected $existingOutputIdentity `
            -Actual $currentOutputIdentity `
            -Description 'Existing Starter mapping output'
    } elseif (Test-Path -LiteralPath $mappingPath) {
        throw 'Starter mapping destination appeared before first publication'
    }

    if ($TestFailBeforePublish) {
        throw 'Injected publication failure after flush and revalidation'
    }

    if ($destinationExisted -and $isWindowsRuntime) {
        Assert-ContainedPath -Root $mappingDirectory -Candidate $backupPath `
            -Description 'Starter mapping backup file'
        if (Test-Path -LiteralPath $backupPath) {
            throw 'Starter mapping backup destination appeared before publication'
        }
        [System.IO.File]::Replace($temporaryPath, $mappingPath, $backupPath)

        if ($TestTamperPublishedAfterReplace) {
            [System.IO.File]::WriteAllText(
                $mappingPath,
                "TAMPERED TEST OUTPUT`n",
                [System.Text.UTF8Encoding]::new($false))
        }
        if ($TestReplaceBackupAfterReplace) {
            $ownedBackupBeforeInjection = Get-SafeRegularFileIdentity `
                -Root $mappingDirectory `
                -Path $backupPath `
                -Description 'Starter mapping backup before test injection'
            Assert-SameFileContent `
                -Expected $existingOutputIdentity `
                -Actual $ownedBackupBeforeInjection `
                -FailureMessage 'Backup does not match the prior destination'
            [System.IO.File]::Delete($ownedBackupBeforeInjection.FullName)
            [System.IO.File]::WriteAllText(
                $backupPath,
                "UNRELATED TEST BACKUP`n",
                [System.Text.UTF8Encoding]::new($false))
        }
    } elseif ($destinationExisted) {
        if ($TestTamperPublishedAfterReplace -or
            $TestReplaceBackupAfterReplace) {
            throw 'Post-replace backup test injection requires Windows'
        }
        [System.IO.File]::Replace($temporaryPath, $mappingPath, $null)
    } else {
        [System.IO.File]::Move($temporaryPath, $mappingPath)
    }

    $publishedIdentity = Get-SafeRegularFileIdentity `
        -Root $mappingDirectory `
        -Path $mappingPath `
        -Description 'Published Starter mapping output'
    Assert-SameFileContent `
        -Expected $temporaryIdentity `
        -Actual $publishedIdentity `
        -FailureMessage 'Published output does not match the flushed temporary bytes'

    if ($destinationExisted -and $isWindowsRuntime) {
        $backupIdentity = Get-SafeRegularFileIdentity `
            -Root $mappingDirectory `
            -Path $backupPath `
            -Description 'Starter mapping backup file'
        Assert-SameFileContent `
            -Expected $existingOutputIdentity `
            -Actual $backupIdentity `
            -FailureMessage 'Backup does not match the prior destination'

        # The helper opens the backup without following reparse points, locks
        # that exact object against writes/renames, hashes that same handle, and
        # marks that handle (not a later path lookup) for deletion.
        $backupDeleted = [OwnedFileDeletion]::DeleteIfContentMatches(
            $backupIdentity.FullName,
            [long]$existingOutputIdentity.Length,
            [string]$existingOutputIdentity.Sha256)
        if (-not $backupDeleted) {
            Write-Warning "Owned backup was preserved after deletion failed: $backupPath"
        }
    }
} catch {
    $operationFailure = $_
} finally {
    if (Test-Path -LiteralPath $temporaryPath) {
        try {
            Assert-NoReparseComponents -Path $mappingDirectory `
                -Description 'Temporary cleanup directory'
            Assert-ContainedPath -Root $mappingDirectory -Candidate $temporaryPath `
                -Description 'Temporary cleanup file'
            $temporaryItem = Get-Item -LiteralPath $temporaryPath -Force
            if (-not $temporaryItem.PSIsContainer -and
                ($temporaryItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0) {
                Remove-Item -LiteralPath $temporaryPath -Force
            }
        } catch {
            Write-Warning "Temporary file was preserved for safe manual cleanup: $temporaryPath"
        }
    }
}

if ($null -ne $operationFailure) {
    throw $operationFailure
}

$hash = (Get-FileHash -LiteralPath $mappingPath -Algorithm SHA256).Hash
Write-Output "Wrote $($mappings.Count) deterministic Starter mappings"
Write-Output "Path: $mappingPath"
Write-Output "SHA256: $hash"
