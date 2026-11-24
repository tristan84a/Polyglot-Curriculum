package com.tristan.polyglot

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tristan.polyglot.ui.theme.PolyglotTheme
import java.text.Normalizer
import kotlin.math.roundToInt
import kotlin.random.Random
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.time.Instant
import java.io.File
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable

data class VocabCard(
    val id: Int,
    val language: String,
    val englishPrompt: String,
    val target: String,
    val inputHint: String,
    val partOfSpeech: String,
    var weight: Int,
    val active: Boolean,
    var lastShown: Long? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PolyglotTheme {
                StudyScreen(applicationContext)
            }
        }
    }
}

@Composable
fun StudyScreen(context: Context) {

    val preferences = remember {
        context.getSharedPreferences(
            "polyglot_state",
            Context.MODE_PRIVATE
        )
    }

    // Read the 200 vocabulary records from the bundled CSV.
    var cards by remember {
        mutableStateOf(
            loadCardsFromCsv(context)
        )
    }
    // V2: exclusions persist; newly imported languages are included by default.
    val availableLanguages = cards.map { it.language }.distinct().sorted()
    var excludedLanguages by remember {
        mutableStateOf(preferences.getStringSet("excluded_study_languages", emptySet())!!.toSet())
    }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var languageSearch by remember { mutableStateOf("") }
    val studyCards = cards.filter { it.language !in excludedLanguages }
    var currentCard by remember {
        mutableStateOf(selectScheduledCard(studyCards, emptyMap(), emptyMap(), null).first)
    }

    var answer by remember {
        mutableStateOf("")
    }

    var submitted by remember {
        mutableStateOf(false)
    }

    var wasCorrect by remember {
        mutableStateOf<Boolean?>(null)
    }

    var oldWeight by remember {
        mutableStateOf<Int?>(null)
    }

    var newWeight by remember {
        mutableStateOf<Int?>(null)
    }

    var languageCounters by remember {
        mutableStateOf(cards.map { it.language }.distinct().associateWith {
            preferences.getInt("counter_$it", 3)
        })
    }
    var languageWeights by remember {
        mutableStateOf(cards.map { it.language }.distinct().associateWith {
            preferences.getInt("language_weight_$it", 100)
        })
    }
    var currentCardIsNew by remember { mutableStateOf(currentCard?.lastShown == null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var replaceConfirmation by remember { mutableStateOf(false) }
    var clearConfirmation by remember { mutableStateOf(false) }

    fun setCounter(language: String, value: Int) {
        languageCounters = languageCounters + (language to value)
        preferences.edit().putInt("counter_$language", value).apply()
    }
    fun resetCounters() {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("counter_") }.forEach { editor.remove(it) }
        languageCounters = cards.map { it.language }.distinct().associateWith { 3 }
        languageCounters.forEach { (language, counter) -> editor.putInt("counter_$language", counter) }
        editor.apply()
    }
    fun chooseCard(excludedId: Int?) {
        val (next, isNew) = selectScheduledCard(
            cards.filter { it.language !in excludedLanguages },
            languageWeights, languageCounters, excludedId
        )
        currentCard = next
        currentCardIsNew = isNew
        if (isNew && next != null) setCounter(next.language, 3)
    }

    var localeEntry by rememberSaveable {
        mutableStateOf("")
    }

    fun applyLanguageExclusions(next: Set<String>) {
        excludedLanguages = next
        preferences.edit().putStringSet("excluded_study_languages", next).apply()
        val (selected, isNew) = selectScheduledCard(
            cards.filter { it.language !in next }, languageWeights, languageCounters, null
        )
        currentCard = selected
        currentCardIsNew = isNew
        if (isNew && selected != null) setCounter(selected.language, 3)
        answer = ""
        submitted = false
        wasCorrect = null
        oldWeight = null
        newWeight = null
    }

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv")
        ) { uri ->

            if (uri != null) {

                val csvText =
                    buildExportCsv(cards)

                context.contentResolver
                    .openOutputStream(uri)
                    ?.bufferedWriter(Charsets.UTF_8)
                    ?.use { writer ->
                        writer.write("\uFEFF")
                        writer.write(csvText)
                    }
            }
        }
    var pendingImportReplace by remember { mutableStateOf(false) }
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {

                val temporaryFile =
                    File(
                        context.cacheDir,
                        "polyglot_import_temp.csv"
                    )

                val importedFile =
                    File(
                        context.filesDir,
                        "polyglot_vocabulary.csv"
                    )

                try {

                    context.contentResolver
                        .openInputStream(uri)
                        ?.use { input ->

                            temporaryFile
                                .outputStream()
                                .use { output ->
                                    input.copyTo(output)
                                }
                        }

                    if (temporaryFile.exists() && validateVocabularyCsv(temporaryFile)) {
                        val incoming = readVocabularyCsv(temporaryFile)
                        val existingIds = cards.map { it.id }.toSet()
                        val additions = incoming.filter { it.id !in existingIds }
                        val replacing = pendingImportReplace
                        val combined = if (replacing) incoming else cards + additions
                        val staged = File(context.filesDir, "polyglot_vocabulary.stage.csv")
                        staged.writeText(buildExportCsv(combined), Charsets.UTF_8)
                        if (replacing) {
                            clearCardState(context)
                            adoptImportedState(context, staged)
                        }
                        staged.copyTo(importedFile, overwrite = true)
                        staged.delete()
                        cards = loadCardsFromCsv(context)
                        if (replacing) resetCounters()
                        languageWeights = cards.map { it.language }.distinct().associateWith {
                            preferences.getInt("language_weight_$it", 100)
                        }
                        languageCounters = cards.map { it.language }.distinct().associateWith {
                            preferences.getInt("counter_$it", 3)
                        }
                        chooseCard(null)
                        importMessage = if (replacing) "Replaced deck with ${cards.size} cards."
                            else "Added ${additions.size} cards; skipped ${incoming.size - additions.size} existing IDs."
                        answer = ""
                        submitted = false
                        wasCorrect = null
                        oldWeight = null
                        newWeight = null
                    } else {
                        importMessage = "Invalid vocabulary CSV. Existing cards were not changed."
                    }

                } finally {

                    temporaryFile.delete()
                }
            }
        }

    val weightImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val csv = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                val updated = csv?.let { parseLanguageWeightsCsv(it, languageWeights) }
                if (updated == null) {
                    importMessage = "Invalid language-weight CSV; nothing changed."
                } else {
                    languageWeights = updated
                    val editor = preferences.edit()
                    updated.forEach { (language, weight) ->
                        editor.putInt("language_weight_$language", weight)
                    }
                    editor.apply()
                    importMessage = "Language weights updated."
                }
            } catch (_: Exception) { importMessage = "Could not import language weights." }
        }
    }

    if (replaceConfirmation) {
        AlertDialog(onDismissRequest = { replaceConfirmation = false },
            title = { Text("Replace entire deck?") },
            text = { Text("All existing cards and card study history will be replaced with the selected CSV. Language preferences will be kept.") },
            confirmButton = { TextButton(onClick = {
                replaceConfirmation = false
                pendingImportReplace = true
                importLauncher.launch(arrayOf("*/*"))
            }) { Text("REPLACE") } },
            dismissButton = { TextButton(onClick = { replaceConfirmation = false }) { Text("CANCEL") } })
    }
    if (clearConfirmation) {
        AlertDialog(onDismissRequest = { clearConfirmation = false },
            title = { Text("Clear entire deck?") },
            text = { Text("All cards and card study history will be deleted. Language preferences will be kept.") },
            confirmButton = { TextButton(onClick = {
                File(context.filesDir, "polyglot_vocabulary.csv").writeText(
                    "ID,Language,EnglishPrompt,Target,InputHint,PartOfSpeech,Weight,Active,LastShown\n"
                )
                clearCardState(context)
                cards = emptyList()
                currentCard = null
                resetCounters()
                clearConfirmation = false
                importMessage = "Deck cleared."
            }) { Text("CLEAR") } },
            dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("CANCEL") } })
    }
    if (importMessage != null) {
        AlertDialog(onDismissRequest = { importMessage = null },
            title = { Text("Data management") }, text = { Text(importMessage ?: "") },
            confirmButton = { TextButton(onClick = { importMessage = null }) { Text("OK") } })
    }

    // Whenever a new card appears, record when it was shown.
    LaunchedEffect(currentCard?.id) {
        currentCard?.let { card ->
            val now = System.currentTimeMillis()
            card.lastShown = now
            saveLastShown(context, card.id, now)
        }
    }

    if (showLanguagePicker) {
        AlertDialog(
            onDismissRequest = { showLanguagePicker = false },
            title = { Text("Study languages") },
            text = {
                Column {
                    Text("${availableLanguages.count { it !in excludedLanguages }} of ${availableLanguages.size} selected")
                    androidx.compose.foundation.layout.Row {
                        TextButton(onClick = { applyLanguageExclusions(emptySet()) }) { Text("ALL") }
                        TextButton(onClick = { applyLanguageExclusions(availableLanguages.toSet()) }) { Text("NONE") }
                    }
                    OutlinedTextField(
                        value = languageSearch,
                        onValueChange = { languageSearch = it },
                        label = { Text("Search languages") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Column(modifier = Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                        availableLanguages.filter { it.contains(languageSearch, ignoreCase = true) }
                            .forEach { language ->
                                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = language !in excludedLanguages,
                                        onCheckedChange = { checked ->
                                            applyLanguageExclusions(
                                                if (checked) excludedLanguages - language
                                                else excludedLanguages + language
                                            )
                                        }
                                    )
                                    Text(language)
                                }
                            }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLanguagePicker = false }) { Text("DONE") } }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->

        if (currentCard == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("No cards available in the selected languages.")
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { showLanguagePicker = true }) { Text("LANGUAGES") }
                Button(onClick = {
                    pendingImportReplace = false
                    importLauncher.launch(arrayOf("*/*"))
                }) { Text("ADD CARDS") }
            }

            return@Scaffold
        }

        val card = currentCard!!

        var localeCode by remember(card.language) {
            mutableStateOf(
                preferences.getString(
                    "keyboard_locale_${card.language}",
                    null
                )
            )
        }

        if (localeCode == null) {

            AlertDialog(
                onDismissRequest = {
                    // Don't dismiss by tapping outside the dialog.
                },
                title = {
                    Text("Keyboard for ${card.language}")
                },
                text = {
                    Column {
                        Text(
                            "Enter the keyboard locale code for ${card.language}."
                        )

                        Spacer(
                            modifier = Modifier.height(16.dp)
                        )

                        OutlinedTextField(
                            value = localeEntry,
                            onValueChange = {
                                localeEntry = it
                            },
                            label = {
                                Text("Locale code")
                            },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {

                            val code = localeEntry.trim()

                            if (code.isNotEmpty()) {

                                preferences
                                    .edit()
                                    .putString(
                                        "keyboard_locale_${card.language}",
                                        code
                                    )
                                    .apply()

                                localeCode = code
                                localeEntry = ""
                            }
                        }
                    ) {
                        Text("SAVE")
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(onClick = { showLanguagePicker = true }) {
                Text("LANGUAGES (${availableLanguages.count { it !in excludedLanguages }}/${availableLanguages.size})")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = card.language.uppercase(),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            Text(
                text =
                    "${card.id} · ${if (currentCardIsNew) "New" else (languageCounters[card.language] ?: 3).toString()} · ${
                        "%,d".format(studyCards.count { it.active && it.language !in excludedLanguages && it.lastShown == null })
                    } · ${
                        "%,d".format(studyCards.count { it.active && it.lastShown != null })
                    }",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(
                modifier = Modifier.height(28.dp)
            )

            Text(
                text = card.englishPrompt,
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(32.dp)
            )

            OutlinedTextField(
                value = answer,
                onValueChange = {
                    answer = it
                },
                label = {
                    Text("Translation")
                },
                singleLine = true,
                enabled = !submitted,
                keyboardOptions = if (localeCode != null) {
                    KeyboardOptions(
                        hintLocales = LocaleList(
                            Locale(localeCode!!)
                        )
                    )
                } else {
                    KeyboardOptions.Default
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(
                modifier = Modifier.height(24.dp)
            )

            if (!submitted) {

                Button(
                    onClick = {

                        val correct =
                            normalizeForComparison(answer) ==
                                    normalizeForComparison(card.target)

                        val before = card.weight

                        val after =
                            if (correct) {
                                (before * 0.75)
                                    .roundToInt()
                                    .coerceAtLeast(2)
                            } else {
                                (before * 2)
                                    .coerceAtMost(3200)
                            }

                        card.weight = after

                        saveWeight(
                            context = context,
                            id = card.id,
                            weight = after
                        )

                        if (!currentCardIsNew) {
                            val old = languageCounters[card.language] ?: 3
                            setCounter(card.language, if (correct) (old - 1).coerceAtLeast(0) else old + 1)
                        }

                        wasCorrect = correct
                        oldWeight = before
                        newWeight = after
                        submitted = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("SUBMIT")
                }

            } else {

                if (wasCorrect == true) {

                    Text(
                        text = "✓ Correct",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )

                    SelectionContainer {
                        Text(text = card.target, fontSize = 24.sp)
                    }
                    if (card.inputHint.isNotBlank()) {
                        Text(text = "[${card.inputHint}]", fontSize = 24.sp)
                    }

                } else {

                    Text(
                        text = "✗ Incorrect",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(text = "Correct answer:", fontSize = 20.sp)
                    SelectionContainer {
                        Text(text = card.target, fontSize = 20.sp)
                    }
                    if (card.inputHint.isNotBlank()) {
                        Text(text = "[${card.inputHint}]", fontSize = 20.sp)
                    }
                }

                Spacer(
                    modifier = Modifier.height(16.dp)
                )

                Text(
                    text = "$oldWeight → $newWeight",
                    fontSize = 20.sp
                )

                Spacer(
                    modifier = Modifier.height(24.dp)
                )

                Button(
                    onClick = {

                        val previousId = card.id

                        answer = ""
                        submitted = false
                        wasCorrect = null
                        oldWeight = null
                        newWeight = null

                        chooseCard(previousId)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("NEXT")
                }
            }

            Spacer(
                modifier = Modifier.height(32.dp)
            )

            Button(
                onClick = {
                    exportLauncher.launch(
                        "polyglot_export.csv"
                    )
                }
            ) {
                Text("EXPORT CSV")
            }
            Spacer(
                modifier = Modifier.height(12.dp)
            )

            Button(
                onClick = {
                    pendingImportReplace = false
                    importLauncher.launch(arrayOf("*/*"))
                }
            ) {
                Text("ADD CARDS")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { replaceConfirmation = true }) { Text("REPLACE DECK") }
            Button(onClick = { clearConfirmation = true }) { Text("CLEAR DECK") }
            Button(onClick = { weightImportLauncher.launch(arrayOf("*/*")) }) {
                Text("IMPORT LANGUAGE WEIGHTS")
            }
        }
    }
}

/*
 * Exact matching, except:
 *
 * 1. Leading/trailing spaces are ignored.
 * 2. Unicode is normalized so visually identical accented
 *    characters aren't rejected because they were encoded differently.
 *
 * Case remains significant.
 * Diacritics remain significant.
 */
fun normalizeForComparison(text: String): String {
    return Normalizer.normalize(
        text.trim(),
        Normalizer.Form.NFC
    )
}


/*
 * Weighted random selection.
 *
 * A card of weight 200 is twice as likely to be chosen
 * as a card of weight 100.
 *
 * excludedId is omitted from ONE draw only.
 */
fun weightedRandomCard(
    cards: List<VocabCard>,
    excludedId: Int?
): VocabCard? {

    val eligibleCards =
        cards.filter {
            it.active &&
                    it.lastShown != null &&
                    it.id != excludedId
        }

    if (eligibleCards.isEmpty()) {
        return null
    }

    val totalWeight =
        eligibleCards.sumOf {
            it.weight.toLong()
        }

    var draw =
        Random.nextLong(totalWeight)

    for (card in eligibleCards) {

        if (draw < card.weight) {
            return card
        }

        draw -= card.weight
    }

    // Should never normally be needed.
    return eligibleCards.last()
}

/*
 * Loads the bundled seed CSV.
 *
 * Existing saved weights on the phone override
 * the original CSV weight of 100.
 */
fun loadCardsFromCsv(
    context: Context
): List<VocabCard> {

    val preferences =
        context.getSharedPreferences(
            "polyglot_state",
            Context.MODE_PRIVATE
        )

    val importedFile =
        File(
            context.filesDir,
            "polyglot_vocabulary.csv"
        )

    val lines =
        if (importedFile.exists()) {

            importedFile
                .bufferedReader(Charsets.UTF_8)
                .readLines()

        } else {

            context.assets
                .open("multilanguage_vocab_v1.csv")
                .bufferedReader(Charsets.UTF_8)
                .readLines()
        }

    if (lines.isEmpty()) {
        return emptyList()
    }

    val header =
        parseCsvLine(lines.first())
            .map {
                it.removePrefix("\uFEFF")
            }

    val idIndex =
        header.indexOf("ID")

    val languageIndex =
        header.indexOf("Language")

    val promptIndex =
        header.indexOf("EnglishPrompt")

    val targetIndex =
        header.indexOf("Target")

    val inputHintIndex =
        header.indexOf("InputHint")

    val partOfSpeechIndex =
        header.indexOf("PartOfSpeech")

    val weightIndex =
        header.indexOf("Weight")

    val activeIndex =
        header.indexOf("Active")

    val lastShownIndex =
        header.indexOf("LastShown")

    val cards =
        mutableListOf<VocabCard>()

    for (line in lines.drop(1)) {

        if (line.isBlank()) {
            continue
        }

        val fields =
            parseCsvLine(line)

        try {

            val id =
                fields[idIndex].toInt()

            val csvWeight =
                fields[weightIndex]
                    .toIntOrNull()
                    ?.coerceIn(2, 3200)
                    ?: 100

            val savedWeight =
                preferences.getInt(
                    "weight_$id",
                    csvWeight
                )
                    .coerceIn(2, 3200)

            val active =
                fields[activeIndex]
                    .equals(
                        "TRUE",
                        ignoreCase = true
                    )

            val csvLastShown =
                if (
                    lastShownIndex >= 0 &&
                    lastShownIndex < fields.size &&
                    fields[lastShownIndex].isNotBlank()
                ) {
                    try {
                        Instant.parse(
                            fields[lastShownIndex]
                        ).toEpochMilli()
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }

            val savedLastShown =
                if (
                    preferences.contains(
                        "lastShown_$id"
                    )
                ) {
                    preferences.getLong(
                        "lastShown_$id",
                        0L
                    )
                } else {
                    csvLastShown
                }

            cards.add(
                VocabCard(
                    id = id,
                    language =
                        fields[languageIndex],
                    englishPrompt =
                        fields[promptIndex],
                    target =
                        fields[targetIndex],
                    inputHint =
                        if (inputHintIndex >= 0 && inputHintIndex < fields.size) {
                            fields[inputHintIndex]
                        } else {
                            ""
                        },
                    partOfSpeech =
                        fields[partOfSpeechIndex],
                    weight =
                        savedWeight,
                    active =
                        active,
                    lastShown =
                        savedLastShown
                )
            )

        } catch (_: Exception) {
            // For now, malformed rows are skipped.
            // Later the importer will report them explicitly.
        }
    }

    return cards
}

fun selectScheduledCard(
    cards: List<VocabCard>,
    languageWeights: Map<String, Int>,
    counters: Map<String, Int>,
    excludedId: Int?
): Pair<VocabCard?, Boolean> {
    val active = cards.filter { it.active }
    val languages = active.map { it.language }.distinct().filter { (languageWeights[it] ?: 100) > 0 }
    if (languages.isEmpty()) return Pair(null, false)
    val total = languages.sumOf { (languageWeights[it] ?: 100).toLong() }
    var draw = Random.nextLong(total)
    var chosen = languages.last()
    for (language in languages) {
        val weight = (languageWeights[language] ?: 100).toLong()
        if (draw < weight) { chosen = language; break }
        draw -= weight
    }
    val pool = active.filter { it.language == chosen }
    val unseen = pool.firstOrNull { it.lastShown == null }
    val reviews = pool.filter { it.lastShown != null }
    if (unseen != null && ((counters[chosen] ?: 3) <= 0 || reviews.isEmpty()))
        return Pair(unseen, true)
    return Pair(weightedRandomCard(reviews, excludedId)
        ?: weightedRandomCard(reviews, null), false)
}

fun parseLanguageWeightsCsv(csv: String, current: Map<String, Int>): Map<String, Int>? {
    val lines = csv.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return null
    if (parseCsvLine(lines.first()).map { it.removePrefix("\uFEFF").trim() } != listOf("Language", "Weight")) return null
    val result = current.toMutableMap()
    for (line in lines.drop(1)) {
        val fields = parseCsvLine(line)
        if (fields.size != 2) return null
        val language = fields[0].trim()
        val weight = fields[1].trim().toIntOrNull() ?: return null
        if (language.isEmpty() || weight !in 0..100000) return null
        result[language] = weight
    }
    return result
}

fun readVocabularyCsv(file: File): List<VocabCard> {
    val lines = file.readLines(Charsets.UTF_8)
    val header = parseCsvLine(lines.first()).map { it.removePrefix("\uFEFF").trim() }
    fun index(name: String) = header.indexOf(name)
    return lines.drop(1).filter { it.isNotBlank() }.map { line ->
        val fields = parseCsvLine(line)
        fun value(name: String): String {
            val i = index(name)
            return if (i >= 0) fields[i] else ""
        }
        VocabCard(
            id = value("ID").toInt(),
            language = value("Language"),
            englishPrompt = value("EnglishPrompt"),
            target = value("Target"),
            inputHint = value("InputHint"),
            partOfSpeech = value("PartOfSpeech"),
            weight = if (index("Weight") >= 0) value("Weight").toInt() else 100,
            active = if (index("Active") >= 0) value("Active").equals("TRUE", ignoreCase = true) else true,
            lastShown = value("LastShown").takeIf { it.isNotBlank() }
                ?.let { Instant.parse(it).toEpochMilli() }
        )
    }
}

fun validateVocabularyCsv(file: File): Boolean {
    val lines = file.readLines(Charsets.UTF_8)
    if (lines.size < 2) return false
    val header = parseCsvLine(lines.first()).map { it.removePrefix("\uFEFF").trim() }
    val mandatory = listOf("ID", "Language", "EnglishPrompt", "Target", "PartOfSpeech")
    val allowed = listOf(
        listOf("ID", "Language", "EnglishPrompt", "Target", "PartOfSpeech"),
        listOf("ID", "Language", "EnglishPrompt", "Target", "InputHint", "PartOfSpeech"),
        listOf("ID", "Language", "EnglishPrompt", "Target", "PartOfSpeech", "Weight", "Active", "LastShown"),
        listOf("ID", "Language", "EnglishPrompt", "Target", "InputHint", "PartOfSpeech", "Weight", "Active", "LastShown")
    )
    if (header !in allowed) return false
    val ids = mutableSetOf<Int>()
    val prompts = mutableSetOf<Pair<String, String>>()
    fun field(fields: List<String>, name: String) = fields[header.indexOf(name)]
    for (line in lines.drop(1)) {
        if (line.isBlank()) continue
        val fields = parseCsvLine(line)
        if (fields.size != header.size) return false
        val id = field(fields, "ID").toIntOrNull() ?: return false
        if (id <= 0 || !ids.add(id)) return false
        val language = field(fields, "Language").trim()
        val prompt = field(fields, "EnglishPrompt").trim()
        val target = field(fields, "Target").trim()
        if (language.isEmpty() || prompt.isEmpty() || target.isEmpty()) return false
        if (!prompts.add(language to prompt)) return false
        if ("Weight" in header) {
            val weight = field(fields, "Weight").toIntOrNull() ?: return false
            if (weight !in 2..3200) return false
        }
        if ("Active" in header) {
            if (field(fields, "Active").uppercase() !in listOf("TRUE", "FALSE")) return false
        }
        if ("LastShown" in header && field(fields, "LastShown").isNotBlank()) {
            try { Instant.parse(field(fields, "LastShown")) }
            catch (_: Exception) { return false }
        }
    }
    return true
}
fun adoptImportedState(
    context: Context,
    file: File
) {
    val lines =
        file.bufferedReader(Charsets.UTF_8)
            .readLines()

    if (lines.isEmpty()) {
        return
    }

    val header =
        parseCsvLine(lines.first())
            .map {
                it.removePrefix("\uFEFF")
            }

    val idIndex =
        header.indexOf("ID")

    val weightIndex =
        header.indexOf("Weight")

    val lastShownIndex =
        header.indexOf("LastShown")

    if (
        idIndex < 0 ||
        weightIndex < 0 ||
        lastShownIndex < 0
    ) {
        return
    }

    val preferences =
        context.getSharedPreferences(
            "polyglot_state",
            Context.MODE_PRIVATE
        )

    val editor =
        preferences.edit()

    for (line in lines.drop(1)) {

        if (line.isBlank()) {
            continue
        }

        val fields =
            parseCsvLine(line)

        try {

            val id =
                fields[idIndex].toInt()

            val weight =
                fields[weightIndex]
                    .toInt()
                    .coerceIn(2, 3200)

            editor.putInt(
                "weight_$id",
                weight
            )

            if (
                lastShownIndex < fields.size &&
                fields[lastShownIndex].isNotBlank()
            ) {

                val time =
                    Instant.parse(
                        fields[lastShownIndex]
                    ).toEpochMilli()

                editor.putLong(
                    "lastShown_$id",
                    time
                )

            } else {

                editor.remove(
                    "lastShown_$id"
                )
            }

        } catch (_: Exception) {
            // Ignore malformed rows for now.
        }
    }

    editor.apply()
}

/*
 * Small CSV parser that supports:
 *
 * commas inside quoted fields
 * escaped quotation marks
 *
 * Example:
 *
 * "to leave, abandon",laisser
 */
fun parseCsvLine(line: String): List<String> {

    val fields =
        mutableListOf<String>()

    val current =
        StringBuilder()

    var insideQuotes = false
    var i = 0

    while (i < line.length) {

        val character =
            line[i]

        when {

            character == '"' -> {

                if (
                    insideQuotes &&
                    i + 1 < line.length &&
                    line[i + 1] == '"'
                ) {
                    current.append('"')
                    i++
                } else {
                    insideQuotes =
                        !insideQuotes
                }
            }

            character == ',' &&
                    !insideQuotes -> {

                fields.add(
                    current.toString()
                )

                current.clear()
            }

            else -> {
                current.append(character)
            }
        }

        i++
    }

    fields.add(
        current.toString()
    )

    return fields
}

fun clearCardState(context: Context) {

    val preferences =
        context.getSharedPreferences(
            "polyglot_state",
            Context.MODE_PRIVATE
        )

    val editor = preferences.edit()

    for (key in preferences.all.keys) {

        if (
            key.startsWith("weight_") ||
            key.startsWith("lastShown_")
        ) {
            editor.remove(key)
        }
    }

    editor.apply()
}

fun saveWeight(
    context: Context,
    id: Int,
    weight: Int
) {

    context
        .getSharedPreferences(
            "polyglot_state",
            Context.MODE_PRIVATE
        )
        .edit()
        .putInt(
            "weight_$id",
            weight
        )
        .apply()
}

fun saveLastShown(
    context: Context,
    id: Int,
    time: Long
) {

    context
        .getSharedPreferences(
            "polyglot_state",
            Context.MODE_PRIVATE
        )
        .edit()
        .putLong(
            "lastShown_$id",
            time
        )
        .apply()
}
fun buildExportCsv(
    cards: List<VocabCard>
): String {

    val builder =
        StringBuilder()

    builder.append(
        "ID,Language,EnglishPrompt,Target,InputHint,PartOfSpeech,Weight,Active,LastShown\n"
    )

    for (card in cards) {

        val lastShownText =
            card.lastShown?.let {
                Instant.ofEpochMilli(it).toString()
            } ?: ""

        val row =
            listOf(
                card.id.toString(),
                card.language,
                card.englishPrompt,
                card.target,
                card.inputHint,
                card.partOfSpeech,
                card.weight.toString(),
                if (card.active) "TRUE" else "FALSE",
                lastShownText
            )

        builder.append(
            row.joinToString(",") {
                escapeCsvField(it)
            }
        )

        builder.append("\n")
    }

    return builder.toString()
}

fun escapeCsvField(
    value: String
): String {

    val needsQuotes =
        value.contains(",") ||
                value.contains("\"") ||
                value.contains("\n") ||
                value.contains("\r")

    val escaped =
        value.replace(
            "\"",
            "\"\""
        )

    return if (needsQuotes) {
        "\"$escaped\""
    } else {
        escaped
    }
}