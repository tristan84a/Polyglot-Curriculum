package com.tristan.polyglot

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
    // Pick the first card by weighted random selection.
    var currentCard by remember {
        mutableStateOf(weightedRandomCard(cards, null))
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

    var newCardCounter by remember {
        mutableStateOf(
            context.getSharedPreferences(
                "polyglot_state",
                Context.MODE_PRIVATE
            ).getInt("new_card_counter", 3)
        )
    }

    var currentCardIsNew by remember {
        mutableStateOf(false)
    }

    var localeEntry by rememberSaveable {
        mutableStateOf("")
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

                    if (
                        temporaryFile.exists() &&
                        validateVocabularyCsv(
                            temporaryFile
                        )
                    ) {

                        temporaryFile.copyTo(
                            importedFile,
                            overwrite = true
                        )

// Remove state belonging to the previously loaded CSV.
                        clearCardState(context)

// Adopt Weight and LastShown from the newly imported CSV.
                        adoptImportedState(
                            context,
                            importedFile
                        )

// Reload cards from the newly imported CSV.
                        cards =
                            loadCardsFromCsv(context)

// Start with the first unseen active card.
// If there are no unseen cards, use the normal weighted selection.
                        val firstUnseen =
                            cards.firstOrNull {
                                it.active && it.lastShown == null
                            }

                        if (firstUnseen != null) {
                            currentCard = firstUnseen
                            currentCardIsNew = true
                        } else {
                            currentCard =
                                weightedRandomCard(
                                    cards,
                                    null
                                )
                            currentCardIsNew = false
                        }

                        newCardCounter = 3

                        preferences
                            .edit()
                            .putInt("new_card_counter", 3)
                            .apply()

                        answer = ""
                        submitted = false
                        wasCorrect = null
                        oldWeight = null
                        newWeight = null
                    }

                } finally {

                    temporaryFile.delete()
                }
            }
        }

    // Whenever a new card appears, record when it was shown.
    LaunchedEffect(currentCard?.id) {
        currentCard?.let { card ->
            val now = System.currentTimeMillis()
            card.lastShown = now
            saveLastShown(context, card.id, now)
        }
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
                Text("No active vocabulary cards were found.")
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
                    if (currentCardIsNew) {
                        "New card #${card.id}"
                    } else {
                        "New card in: $newCardCounter · ${
                            cards.count {
                                it.active && it.lastShown == null
                            }
                        } waiting"
                    },
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

                            newCardCounter =
                                if (correct) {
                                    newCardCounter - 1
                                } else {
                                    newCardCounter + 1
                                }

                            context.getSharedPreferences(
                                "polyglot_state",
                                Context.MODE_PRIVATE
                            )
                                .edit()
                                .putInt(
                                    "new_card_counter",
                                    newCardCounter
                                )
                                .apply()
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

                    Text(
                        text = if (card.inputHint.isNotBlank()) {
                            "${card.target} [${card.inputHint}]"
                        } else {
                            card.target
                        },
                        fontSize = 24.sp
                    )

                } else {

                    Text(
                        text = "✗ Incorrect",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )

                    Text(
                        text = if (card.inputHint.isNotBlank()) {
                            "Correct answer: ${card.target} [${card.inputHint}]"
                        } else {
                            "Correct answer: ${card.target}"
                        },
                        fontSize = 20.sp
                    )
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

                        if (newCardCounter <= 0) {

                            val unseenCards =
                                cards.filter {
                                    it.active &&
                                            it.lastShown == null
                                }

                            if (unseenCards.isNotEmpty()) {

                                currentCardIsNew = true
                                currentCard =
                                    unseenCards.first()

                                newCardCounter = 3

                                context.getSharedPreferences(
                                    "polyglot_state",
                                    Context.MODE_PRIVATE
                                )
                                    .edit()
                                    .putInt(
                                        "new_card_counter",
                                        3
                                    )
                                    .apply()

                            } else {

                                currentCardIsNew = false
                                currentCard =
                                    weightedRandomCard(
                                        cards = cards,
                                        excludedId = previousId
                                    )
                            }

                        } else {

                            currentCardIsNew = false

                            val nextReviewCard =
                                weightedRandomCard(
                                    cards = cards,
                                    excludedId = previousId
                                )

                            if (nextReviewCard != null) {

                                currentCard = nextReviewCard

                            } else {

                                val nextUnseen =
                                    cards.firstOrNull {
                                        it.active &&
                                                it.lastShown == null
                                    }

                                if (nextUnseen != null) {
                                    currentCard = nextUnseen
                                    currentCardIsNew = true
                                } else {
                                    currentCard =
                                        weightedRandomCard(
                                            cards = cards,
                                            excludedId = null
                                        )
                                }
                            }
                        }
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
                    importLauncher.launch(
                        arrayOf("*/*")
                    )
                }
            ) {
                Text("IMPORT CSV")
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

fun validateVocabularyCsv(
    file: File
): Boolean {

    val lines =
        file.bufferedReader(Charsets.UTF_8)
            .readLines()

    if (lines.size < 2) {
        return false
    }

    val header =
        parseCsvLine(lines.first())
            .map {
                it.removePrefix("\uFEFF")
            }

    val oldHeader =
        listOf(
            "ID",
            "Language",
            "EnglishPrompt",
            "Target",
            "PartOfSpeech",
            "Weight",
            "Active",
            "LastShown"
        )

    val newHeader =
        listOf(
            "ID",
            "Language",
            "EnglishPrompt",
            "Target",
            "InputHint",
            "PartOfSpeech",
            "Weight",
            "Active",
            "LastShown"
        )

    if (header != oldHeader && header != newHeader) {
        return false
    }

    val hasInputHint = header == newHeader

    val ids =
        mutableSetOf<Int>()

    val prompts =
        mutableSetOf<Pair<String, String>>()

    for (line in lines.drop(1)) {

        if (line.isBlank()) {
            continue
        }

        val fields =
            parseCsvLine(line)

        val expectedFieldCount =
            if (hasInputHint) 9 else 8

        if (fields.size != expectedFieldCount) {
            return false
        }

        val partOfSpeechField = if (hasInputHint) 5 else 4
        val weightField = if (hasInputHint) 6 else 5
        val activeField = if (hasInputHint) 7 else 6
        val lastShownField = if (hasInputHint) 8 else 7

        val id =
            fields[0].toIntOrNull()
                ?: return false

        if (id <= 0 || !ids.add(id)) {
            return false
        }

        val language = fields[1].trim()
        val englishPrompt = fields[2].trim()
        val target = fields[3].trim()
        val partOfSpeech = fields[partOfSpeechField].trim()

        if (
            language.isEmpty() ||
            englishPrompt.isEmpty() ||
            target.isEmpty()
        ) {
            return false
        }

        val promptKey = Pair(language, englishPrompt)

        if (!prompts.add(promptKey)) {
            return false
        }

        val weight =
            fields[weightField].toIntOrNull()
                ?: return false

        if (weight !in 2..3200) {
            return false
        }

        if (
            !fields[activeField].equals("TRUE", ignoreCase = true) &&
            !fields[activeField].equals("FALSE", ignoreCase = true)
        ) {
            return false
        }

        if (fields[lastShownField].isNotBlank()) {
            try {
                Instant.parse(fields[lastShownField])
            } catch (_: Exception) {
                return false
            }
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