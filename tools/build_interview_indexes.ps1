[CmdletBinding()]
param([string]$WorkspaceRoot = (Join-Path $PSScriptRoot '..\..'))

$ErrorActionPreference = 'Stop'
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$workspace = [System.IO.Path]::GetFullPath($WorkspaceRoot)

function Get-WorkspacePath([string]$RelativePath) {
    $path = [System.IO.Path]::GetFullPath((Join-Path $workspace $RelativePath))
    if (-not $path.StartsWith($workspace, [System.StringComparison]::OrdinalIgnoreCase)) { throw "Path escapes workspace: $RelativePath" }
    return $path
}
function Get-RelativePath([string]$Path) { return $Path.Substring($workspace.Length).TrimStart('\', '/') -replace '\\', '/' }
function Decode-Utf8Base64([string]$Value) { return [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($Value)) }
function Write-Utf8Json([string]$Path, [object]$Value) {
    [System.IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    [System.IO.File]::WriteAllText($Path, (($Value | ConvertTo-Json -Depth 8) + "`n"), $utf8NoBom)
}
function First-BoldLocator([string[]]$Lines, [int]$Start, [int]$Ordinal) {
    $bold = @($Lines[$Start..($Lines.Length - 1)] | Where-Object { $_ -match '^\*\*.*\*\*$' })
    if ($bold.Count -le $Ordinal) { throw 'Expected bold Markdown locator is missing.' }
    return $bold[$Ordinal]
}

$interviewDirectory = Get-WorkspacePath 'output/interview'
$oralFile = @(Get-ChildItem -LiteralPath $interviewDirectory -File -Filter '03-*.md' | Sort-Object Length -Descending | Select-Object -First 1)[0]
$projectFile = @(Get-ChildItem -LiteralPath $interviewDirectory -File -Filter '01-*.md' | Sort-Object Length -Descending | Select-Object -First 1)[0]
if ($null -eq $oralFile -or $null -eq $projectFile) { throw 'Interview Markdown sources are required.' }
$oralPath = $oralFile.FullName; $projectPath = $projectFile.FullName
$oralRelative = Get-RelativePath $oralPath; $projectRelative = Get-RelativePath $projectPath

$oralLines = [System.IO.File]::ReadAllLines($oralPath, [System.Text.Encoding]::UTF8)
$oralQuestions = [System.Collections.Generic.List[object]]::new(); $topic = $null
for ($index = 0; $index -lt $oralLines.Length; $index++) {
    $heading = $oralLines[$index].TrimEnd("`r", "`n")
    if ($heading -match '^## (?<topic>.+)$') { $topic = $Matches.topic; continue }
    if ($heading -match '^### (?<number>\d+)\. (?<title>.+)$') {
        $number = [int]$Matches.number
        $oralQuestions.Add([ordered]@{ id = ('O{0:D3}' -f $number); title = $Matches.title; topic = $topic; source_path = $oralRelative; question_heading = $heading; answer_locator = First-BoldLocator $oralLines ($index + 1) 0; follow_up_locator = First-BoldLocator $oralLines ($index + 1) 3; recommended_seconds = 90 })
    }
}
if ($oralQuestions.Count -ne 80 -or @($oralQuestions | ForEach-Object { $_['id'] } | Select-Object -Unique).Count -ne 80) { throw "Expected 80 unique oral headings but found $($oralQuestions.Count)." }

$projectLines = [System.IO.File]::ReadAllLines($projectPath, [System.Text.Encoding]::UTF8)
$projectHeadings = @(@($projectLines | Where-Object { $_ -match '^## ' }) | Select-Object -Skip 3 -First 8)
if ($projectHeadings.Count -ne 8) { throw 'Expected eight project case H2 headings.' }
$projectNames = @('AnySign SaaS', 'AnySign SaaS', '5G-sui-e-qian', 'hybrid-cloud', 'hybrid-cloud', 'ZKT', 'jxindependent', 'zkt-hotel-cloud')
$projectNatures = @('production', 'production', 'production', 'production', 'production', 'historical-production', 'historical-production', 'personal-practice')
$evidenceHeadingNumbers = @(3, 3, 3, 3, 3, 3, 2, 2)
$projectCases = [System.Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt 8; $index++) {
    $sectionHeading = $projectHeadings[$index]; $start = [Array]::IndexOf($projectLines, $sectionHeading); $end = $projectLines.Length
    for ($candidate = $start + 1; $candidate -lt $projectLines.Length; $candidate++) {
        if ($projectLines[$candidate] -match '^## ') { $end = $candidate; break }
    }
    if ($start -lt 0 -or $end -le $start) { throw "Cannot locate project section $($index + 1)." }
    $headings = @($projectLines[$start..($end - 1)] | Where-Object { $_ -match '^### ' })
    if ($headings.Count -lt 3) { throw "Project section has too few H3 headings: $sectionHeading" }
    $evidenceEntry = @($headings | Where-Object { $_ -match ('^### {0}\.' -f $evidenceHeadingNumbers[$index]) })
    if ($evidenceEntry.Count -ne 1) { throw "Cannot locate explicit evidence entry for P{0:D3}." -f ($index + 1) }
    $projectCases.Add([ordered]@{ id = ('P{0:D3}' -f ($index + 1)); project_name = $projectNames[$index]; project_nature = $projectNatures[$index]; title = $sectionHeading.Substring(3); source_path = $projectRelative; section_heading = $sectionHeading; evidence_entry = $evidenceEntry[0]; fact_boundary = $headings[$headings.Count - 1]; recommended_seconds = 120 })
}
Write-Utf8Json (Get-WorkspacePath 'training-center/config/oral-questions.json') ([ordered]@{ version = 1; source_path = $oralRelative; questions = $oralQuestions })
Write-Utf8Json (Get-WorkspacePath 'training-center/config/project-cases.json') ([ordered]@{ version = 1; source_path = $projectRelative; cases = $projectCases })

$planPath = Get-WorkspacePath 'training-center/config/plan-template.json'
if (-not (Test-Path -LiteralPath $planPath -PathType Leaf)) { throw 'Plan template is required.' }
$plan = Get-Content -Raw -Encoding utf8 $planPath | ConvertFrom-Json
$planLines = [System.Collections.Generic.List[string]]::new()
$planLines.Add((Decode-Utf8Base64 'IyAxNCDlpKnogZTlkIjorq3nu4PorqHliJI=')); $planLines.Add(''); $planLines.Add((Decode-Utf8Base64 '5pys6K6h5YiS55SxIGB0cmFpbmluZy1jZW50ZXIvY29uZmlnL3BsYW4tdGVtcGxhdGUuanNvbmAg5riy5p+T77yb5a6M5oiQ6K6w5b2V55Sx6K6t57uD5Lit5b+D5Y2V54us5L+d5a2Y44CC')); $planLines.Add(''); $planLines.Add((Decode-Utf8Base64 'IyMg5q+P5pel6buY6K6k57uT5p6E')); $planLines.Add('')
foreach ($item in $plan.daily_structure) { $planLines.Add("- $($item.description)") }
$planLines.Add(''); $planLines.Add((Decode-Utf8Base64 'IyMg5q+P5pel5Li76aKY')); $planLines.Add(''); $planLines.Add('| Day | Theme |'); $planLines.Add('|---|---|')
foreach ($day in $plan.days) { $planLines.Add("| $($day.day) | $($day.theme) |") }
$planLines.Add(''); $planLines.Add((Decode-Utf8Base64 '6YeN5paw55Sf5oiQ5LiN5Lya6KaG55uW5bey5a6M5oiQ55qE6K6h5YiS5a6e5L6L44CC'))
$planOutput = Decode-Utf8Base64 'b3V0cHV0L2ludGVydmlldy8wNC0xNOWkqeiBlOWQiOiuree7g+iuoeWIki5tZA=='
[System.IO.File]::WriteAllText((Get-WorkspacePath $planOutput), (($planLines -join "`n") + "`n"), $utf8NoBom)
