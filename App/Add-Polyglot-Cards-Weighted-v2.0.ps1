$ErrorActionPreference = "Stop"

$folder        = $PSScriptRoot
$languagesFile = Join-Path $folder "Languages.csv"
$stateFile     = Join-Path $folder "Polyglot_State.csv"

function Stop-WithMessage([string]$Message) {
    Write-Host ""
    Write-Host "ERROR: $Message" -ForegroundColor Red
    Write-Host ""
    Read-Host "Press Enter to close"
    exit 1
}

function Get-ColumnValue($Row, [int]$Index) {
    $props = @($Row.PSObject.Properties)
    if ($Index -ge $props.Count) { return $null }
    return $props[$Index].Value
}

if (-not (Test-Path $languagesFile)) {
    Stop-WithMessage "Languages.csv was not found in $folder"
}
if (-not (Test-Path $stateFile)) {
    Stop-WithMessage "Polyglot_State.csv was not found in $folder"
}

# Languages.csv: first column = language name, second column = positive numeric weight.
$languagesRaw = @(Import-Csv $languagesFile)
if ($languagesRaw.Count -eq 0) {
    Stop-WithMessage "Languages.csv contains no data rows."
}

$languages = @()
foreach ($row in $languagesRaw) {
    $language = ([string](Get-ColumnValue $row 0)).Trim()
    $weightText = ([string](Get-ColumnValue $row 1)).Trim()

    if ([string]::IsNullOrWhiteSpace($language)) {
        Stop-WithMessage "Languages.csv contains a blank language name."
    }

    $weight = 0.0
    if (-not [double]::TryParse($weightText, [ref]$weight) -or $weight -le 0) {
        Stop-WithMessage "Language '$language' has invalid weight '$weightText'. Weights must be numbers greater than zero."
    }

    $curriculumFile = Join-Path $folder ($language + ".csv")
    if (-not (Test-Path $curriculumFile)) {
        Stop-WithMessage "The curriculum file '$language.csv' was not found."
    }

    $languages += [pscustomobject]@{
        Language = $language
        Weight   = $weight
        File     = $curriculumFile
    }
}

# Load and validate current learner state.
$state = @(Import-Csv $stateFile)
$requiredStateColumns = @("ID", "Language", "EnglishPrompt", "Target", "InputHint", "PartOfSpeech", "Weight", "Active", "LastShown")
$stateHeader = if ($state.Count -gt 0) { @($state[0].PSObject.Properties.Name) } else { @() }

# Import-Csv cannot expose the header when there are zero data rows, so read it directly too.
$headerLine = Get-Content $stateFile -TotalCount 1
if ([string]::IsNullOrWhiteSpace($headerLine)) {
    Stop-WithMessage "Polyglot_State.csv is empty."
}
$headerColumns = @($headerLine -split ',' | ForEach-Object { $_.Trim().Trim('"') })
foreach ($col in $requiredStateColumns) {
    if ($headerColumns -notcontains $col) {
        Stop-WithMessage "Polyglot_State.csv is missing required column '$col'."
    }
}

# Cache each curriculum by language and by exact ID.
$curricula = @{}
$highestStateID = @{}

foreach ($lang in $languages) {
    $rows = @(Import-Csv $lang.File)
    if ($rows.Count -eq 0) {
        Stop-WithMessage "$($lang.Language).csv contains no cards."
    }

    $needed = @("ID", "Language", "EnglishPrompt", "Target")
    $cols = @($rows[0].PSObject.Properties.Name)
    foreach ($col in $needed) {
        if ($cols -notcontains $col) {
            Stop-WithMessage "$($lang.Language).csv is missing required column '$col'."
        }
    }

    $byID = @{}
    $minID = [long]::MaxValue
    foreach ($card in $rows) {
        $id = 0L
        if (-not [long]::TryParse(([string]$card.ID).Trim(), [ref]$id)) {
            Stop-WithMessage "$($lang.Language).csv contains invalid ID '$($card.ID)'."
        }
        if ($byID.ContainsKey($id)) {
            Stop-WithMessage "$($lang.Language).csv contains duplicate ID $id."
        }
        $byID[$id] = $card
        if ($id -lt $minID) { $minID = $id }
    }

    $curricula[$lang.Language] = [pscustomobject]@{
        ByID  = $byID
        MinID = $minID
    }

    $idsInState = @(
        $state |
        Where-Object { $_.Language -eq $lang.Language } |
        ForEach-Object {
            $parsed = 0L
            if ([long]::TryParse(([string]$_.ID).Trim(), [ref]$parsed)) { $parsed }
        }
    )

    if ($idsInState.Count -gt 0) {
        $highestStateID[$lang.Language] = ($idsInState | Measure-Object -Maximum).Maximum
    }
}

# Only languages with a consecutive next card remain eligible for selection.
$remaining = @($languages | Where-Object {
    $curriculum = $curricula[$_.Language]
    $nextID = if ($highestStateID.ContainsKey($_.Language)) {
        [long]$highestStateID[$_.Language] + 1
    } else {
        [long]$curriculum.MinID
    }
    $curriculum.ByID.ContainsKey($nextID)
})
$exhausted = @($languages | Where-Object { $remaining.Language -notcontains $_.Language })
$added = 0

Write-Host ""
Write-Host "Weighted Polyglot card addition started." -ForegroundColor Cyan
Write-Host "State: $stateFile"
Write-Host ""

while ($remaining.Count -gt 0) {
    $totalWeight = ($remaining | Measure-Object -Property Weight -Sum).Sum
    # Weighted random language choice.
    $r = Get-Random -Minimum 0.0 -Maximum $totalWeight
    $running = 0.0
    $chosen = $null

    foreach ($lang in $remaining) {
        $running += $lang.Weight
        if ($r -lt $running) {
            $chosen = $lang
            break
        }
    }
    if ($null -eq $chosen) { $chosen = $remaining[-1] }

    $language = $chosen.Language
    $curriculum = $curricula[$language]

    if ($highestStateID.ContainsKey($language)) {
        $nextID = [long]$highestStateID[$language] + 1
    }
    else {
        $nextID = [long]$curriculum.MinID
    }

    # A depleted language is excluded; other languages continue.
    if (-not $curriculum.ByID.ContainsKey($nextID)) {
        $remaining = @($remaining | Where-Object { $_.Language -ne $language })
        $exhausted += $chosen
        continue
    }

    $source = $curriculum.ByID[$nextID]

    # Curriculum content comes from the language file. Learner-state fields use defaults.
    $newCard = [pscustomobject][ordered]@{
        ID           = [string]$source.ID
        Language     = [string]$source.Language
        EnglishPrompt= [string]$source.EnglishPrompt
        Target       = [string]$source.Target
        InputHint    = if ($source.PSObject.Properties.Name -contains "InputHint") { [string]$source.InputHint } else { "" }
        PartOfSpeech = if ($source.PSObject.Properties.Name -contains "PartOfSpeech") { [string]$source.PartOfSpeech } else { "" }
        Weight       = 100
        Active       = "TRUE"
        LastShown    = ""
    }

    # Append immediately so progress is retained even if the script is interrupted later.
    $newCard | Export-Csv -Path $stateFile -NoTypeInformation -Append -Encoding UTF8

    $highestStateID[$language] = $nextID
    $added++

    # Remove this language as soon as its next consecutive card is unavailable.
    $followingID = [long]$nextID + 1
    if (-not $curriculum.ByID.ContainsKey($followingID)) {
        $remaining = @($remaining | Where-Object { $_.Language -ne $language })
        $exhausted += $chosen
        Write-Host "$language exhausted after card $nextID (next expected: $followingID)."
    }

    if (($added % 100) -eq 0) {
        Write-Host "$added cards added..."
    }
}

Write-Host ""
Write-Host "COMPLETE: No languages have further consecutive cards available." -ForegroundColor Green
Write-Host "$added card(s) added to Polyglot_State.csv during this run."
Write-Host "$($exhausted.Count) language(s) exhausted or blocked at a missing next ID."
Write-Host ""
Read-Host "Press Enter to close"
