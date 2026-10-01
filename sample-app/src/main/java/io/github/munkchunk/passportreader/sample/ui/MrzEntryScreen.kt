package io.github.munkchunk.passportreader.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.munkchunk.passportreader.sample.BuildConfig
import io.github.munkchunk.passportreader.sample.ui.theme.brandTextButtonColors
import io.github.munkchunk.passportreader.sample.ui.theme.brandTextFieldColors
import io.github.munkchunk.passportreader.sample.ui.theme.SectionLabel
import io.github.munkchunk.passportreader.sample.mrz.MrzKey
import io.github.munkchunk.passportreader.sample.R
import io.github.munkchunk.passportreader.sample.mrz.MrzFieldError
import io.github.munkchunk.passportreader.sample.mrz.validateDocumentNumber
import io.github.munkchunk.passportreader.sample.mrz.validateMrzDate

@Composable
fun MrzEntryScreen(
    nfcAvailable: Boolean,
    nfcEnabled: Boolean,
    onOpenNfcSettings: () -> Unit,
    previousMrz: MrzKey?,
    draftMrz: MrzKey?,
    onScan: () -> Unit,
    onStartRead: (MrzKey) -> Unit
) {
    // Seeded from the draft so coming back here - after a cancelled read, or a
    // read the screen locking interrupted - does not mean typing it all again.
    var documentNumber by remember { mutableStateOf(draftMrz?.documentNumber ?: "") }
    var dateOfBirth by remember { mutableStateOf(draftMrz?.dateOfBirth ?: "") }
    var dateOfExpiry by remember { mutableStateOf(draftMrz?.dateOfExpiry ?: "") }

    val documentError = if (documentNumber.isEmpty()) null else validateDocumentNumber(documentNumber)
    // No check-digit hint here: it interrupted the flow of typing three fields
    // and invited comparing against the document mid-entry. The scanner already
    // verifies check digits, and a wrong MRZ now reports itself clearly.
    val documentSupport = documentError?.let { stringResource(it.messageRes()) }
    val birthError = if (dateOfBirth.isEmpty()) null else validateMrzDate(dateOfBirth)
    val expiryError = if (dateOfExpiry.isEmpty()) null else validateMrzDate(dateOfExpiry)

    val complete = validateDocumentNumber(documentNumber) == null &&
        validateMrzDate(dateOfBirth) == null &&
        validateMrzDate(dateOfExpiry) == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.entry_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            stringResource(R.string.entry_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Above the buttons, not below them: this is why both are disabled, and
        // under the "OR TYPE THEM" heading it read as a caveat about typing.
        if (!nfcAvailable) {
            WarningCard(stringResource(R.string.entry_warning_no_nfc))
        } else if (!nfcEnabled) {
            // No refresh button: the warning goes by itself once NFC is on.
            WarningCard(stringResource(R.string.entry_warning_nfc_off)) {
                TextButton(colors = brandTextButtonColors(), onClick = onOpenNfcSettings) {
                    Text(stringResource(R.string.entry_open_nfc_settings))
                }
            }
        }

        // Scanning is the intended path, so it is the only filled button on the
        // screen; typing the fields is the fallback and reads as one.
        Button(
            onClick = onScan,
            enabled = nfcAvailable && nfcEnabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.entry_scan), style = MaterialTheme.typography.labelLarge)
        }

        Text(
            stringResource(R.string.entry_or_type),
            style = SectionLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        OutlinedTextField(
            value = documentNumber,
            onValueChange = { documentNumber = it.uppercase().take(9) },
            label = { Text(stringResource(R.string.field_document_number)) },
            supportingText = documentSupport?.let { { Text(it) } },
            isError = documentError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            colors = brandTextFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )

        MrzDateField(
            value = dateOfBirth,
            onValueChange = { dateOfBirth = it },
            label = stringResource(R.string.field_date_of_birth_entry),
            fieldName = stringResource(R.string.field_name_date_of_birth),
            error = birthError
        )

        MrzDateField(
            value = dateOfExpiry,
            onValueChange = { dateOfExpiry = it },
            label = stringResource(R.string.field_expiry_date_entry),
            fieldName = stringResource(R.string.field_name_expiry_date),
            error = expiryError
        )

        // Filled, like the scan button: once all three fields are valid this is
        // the action the user is reaching for, so it should look like it.
        Button(
            onClick = {
                onStartRead(MrzKey(documentNumber, dateOfBirth, dateOfExpiry))
            },
            enabled = complete && nfcAvailable && nfcEnabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.entry_read), style = MaterialTheme.typography.labelLarge)
        }

        // Debug-only shortcuts: previousMrz is always null in release builds.
        // Deliberately quieter than the real actions - these are a bench
        // convenience, not something to reach for by accident.
        if (BuildConfig.DEBUG) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Text(
                stringResource(R.string.debug_shortcuts),
                style = SectionLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // The document number is shown on purpose - with more than one
                // passport to hand it is otherwise far too easy to refill the
                // last one and then present a different document.
                previousMrz?.let { previous ->
                    TextButton(
                        colors = brandTextButtonColors(),
                        onClick = {
                            documentNumber = previous.documentNumber
                            dateOfBirth = previous.dateOfBirth
                            dateOfExpiry = previous.dateOfExpiry
                        }
                    ) {
                        Text(
                            stringResource(R.string.debug_previous, previous.documentNumber),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                TextButton(
                    colors = brandTextButtonColors(),
                    onClick = {
                        documentNumber = "AB2134"
                        dateOfBirth = "900101"
                        dateOfExpiry = "301231"
                    }
                ) {
                    Text(stringResource(R.string.debug_example), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun MrzDateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    fieldName: String,
    error: MrzFieldError?
) {
    val support = error?.let { stringResource(it.messageRes(), fieldName) }
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onValueChange(new.filter { it.isDigit() }.take(6)) },
        label = { Text(label) },
        supportingText = support?.let { { Text(it) } },
        isError = error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = brandTextFieldColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun WarningCard(message: String, action: (@Composable () -> Unit)? = null) {
    // Material's default container is off-palette (lavender), and in light mode
    // it is the same lightness as the page. Use the panel colour instead.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        // A text button carries its own padding, so the card gives up its
        // bottom inset when there is one rather than doubling it.
        Column(
            modifier = Modifier.padding(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = if (action != null) 4.dp else 16.dp
            )
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                Box(modifier = Modifier.align(Alignment.CenterHorizontally)) { action() }
            }
        }
    }
}

/**
 * The words for a rejected field.
 *
 * Lives here rather than on the enum because it is a UI concern: the parser
 * uses the same values as a yes/no and must not need a Context to do it.
 * [R.string.validation_date_length] names the field, the rest do not - passing
 * an unused argument to a format without one is harmless.
 */
@StringRes
private fun MrzFieldError.messageRes(): Int = when (this) {
    MrzFieldError.REQUIRED -> R.string.validation_required
    MrzFieldError.DOCUMENT_TOO_LONG -> R.string.validation_document_too_long
    MrzFieldError.DOCUMENT_ALPHABET -> R.string.validation_document_alphabet
    MrzFieldError.DATE_WRONG_LENGTH -> R.string.validation_date_length
    MrzFieldError.DATE_NOT_DIGITS -> R.string.validation_digits_only
    MrzFieldError.DATE_MONTH_RANGE -> R.string.validation_month_range
    MrzFieldError.DATE_DAY_RANGE -> R.string.validation_day_range
}
