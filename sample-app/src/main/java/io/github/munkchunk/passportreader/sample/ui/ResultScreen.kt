package io.github.munkchunk.passportreader.sample.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.munkchunk.passportreader.data.AdditionalDocumentDetails
import io.github.munkchunk.passportreader.data.AdditionalPersonDetails
import io.github.munkchunk.passportreader.data.Passport
import io.github.munkchunk.passportreader.data.PersonToNotify
import io.github.munkchunk.passportreader.model.BiometricEncoding
import io.github.munkchunk.passportreader.model.EyeColour
import io.github.munkchunk.passportreader.model.FaceDetails
import io.github.munkchunk.passportreader.model.HairColour
import io.github.munkchunk.passportreader.model.CertificateDetails
import io.github.munkchunk.passportreader.model.CertificateRole
import io.github.munkchunk.passportreader.model.CheckVerdict
import io.github.munkchunk.passportreader.sample.R
import io.github.munkchunk.passportreader.sample.mrz.MrzDates
import io.github.munkchunk.passportreader.sample.ui.theme.ExpiredPill
import io.github.munkchunk.passportreader.sample.ui.theme.FieldLabel
import io.github.munkchunk.passportreader.sample.ui.theme.FieldValue
import io.github.munkchunk.passportreader.sample.ui.theme.MonoSmall
import io.github.munkchunk.passportreader.sample.ui.theme.SectionLabel
import io.github.munkchunk.passportreader.sample.ui.theme.verdicts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CERT_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withZone(ZoneId.systemDefault())

/**
 * Everything the chip gave up, ordered rather than hidden: the DG2 portrait
 * beside the identity fields, both MRZ lines, the verification outcomes, and
 * the certificates that back them. See docs/sample-app.md.
 */
@Composable
fun ResultScreen(
    passport: Passport,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IdentityPanel(passport)
        VerificationPanel(passport)
        BiometricPanel(passport)
        passport.documentSigner?.let { SignerPanel(it) }
        passport.chainCertificates?.takeIf { it.isNotEmpty() }?.let { ChainPanel(it) }
        // Last, so the panels every read produces keep the same place on every
        // document, and verification is not pushed down by groups few carry.
        OptionalGroupPanels(passport)

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            Text(stringResource(R.string.result_read_another), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Panel(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title.uppercase(),
                style = SectionLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
        content()
    }
}

@Composable
private fun IdentityPanel(passport: Passport) {
    val person = passport.personDetails
    val face = passport.face ?: passport.portrait

    Panel(title = stringResource(R.string.panel_holder)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (face != null) {
                    Image(
                        bitmap = face.asImageBitmap(),
                        contentDescription = stringResource(R.string.photo_content_description),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        stringResource(R.string.photo_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Field(stringResource(R.string.field_surname), person?.primaryIdentifier)
                Field(stringResource(R.string.field_given_names), person?.secondaryIdentifier)
                Field(stringResource(R.string.field_document_number), person?.documentNumber)
                Field(stringResource(R.string.field_date_of_birth), MrzDates.formatDateOfBirth(person?.dateOfBirth))
                Field(
                    stringResource(R.string.field_sex_nationality),
                    listOfNotNull(
                        person?.sex?.name?.takeIf { it != "UNKNOWN" },
                        person?.nationality
                    ).joinToString(" · ").takeIf { it.isNotBlank() }
                )
                ExpiryField(person?.dateOfExpiry)
            }
        }

        passport.mrz?.let { mrz ->
            val lines = listOfNotNull(mrz.getLine1(), mrz.getLine2()).filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    // Never wrapped: the character positions are what an MRZ means.
                    lines.forEach { line ->
                        Text(
                            text = line,
                            style = MonoSmall,
                            maxLines = 1,
                            softWrap = false,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(
            label.uppercase(),
            style = FieldLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = FieldValue,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Expiry is marked amber when past, never as a failed check. An expired passport
 * still authenticates - expiry describes the document's validity for travel, not
 * the chip's integrity. See docs/sample-app.md.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpiryField(raw: String?) {
    if (raw.isNullOrBlank()) return
    val expired = MrzDates.isExpired(raw)
    val shown = MrzDates.formatDateOfExpiry(raw) ?: raw

    Column {
        Text(
            stringResource(R.string.field_date_of_expiry),
            style = FieldLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Flows, so the badge drops under the date when the column is too narrow
        // for both. As a Row it was clipped to "EXPIR" at font scale 1.3, and
        // gone altogether at 2.0.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                shown,
                style = FieldValue,
                maxLines = 1,
                color = if (expired) {
                    MaterialTheme.verdicts.unknown
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (expired) {
                // Compact on purpose: the date and the pill share a narrow
                // column beside a 140dp portrait, and at the default text size
                // the pill should fit on the date's line.
                Text(
                    stringResource(R.string.badge_expired),
                    style = ExpiredPill,
                    maxLines = 1,
                    color = MaterialTheme.verdicts.unknown,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.verdicts.unknownContainer)
                        .border(
                            1.dp,
                            MaterialTheme.verdicts.unknownEdge,
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
    }
}

/**
 * The optional data groups, one panel each in data-group order. A group the
 * chip does not carry gets no panel, and nor does one that carried nothing
 * this screen can show.
 */
@Composable
private fun OptionalGroupPanels(passport: Passport) {
    passport.portrait?.let { ImagePanel(R.string.panel_portrait, 5, it) }
    passport.signature?.let { ImagePanel(R.string.panel_signature, 7, it) }
    passport.additionalPersonDetails?.let { PersonDetailsPanel(it) }
    passport.additionalDocumentDetails?.let { DocumentDetailsPanel(it) }
    passport.optionalDetails?.let { OptionalDetailsPanel(it) }
    // An entry with none of the four fields has nothing to show, and nor has a group of them.
    passport.personsToNotify?.filter { it.hasAnyField() }?.takeIf { it.isNotEmpty() }?.let { PersonsToNotifyPanel(it) }
}

/** The group number beside a panel title, as the verification rows name their protocols. */
@Composable
private fun DataGroupTag(number: Int) {
    Text(
        "DG$number",
        style = MonoSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ImagePanel(@StringRes title: Int, dataGroup: Int, image: Bitmap) {
    val label = stringResource(title)
    Panel(title = label, trailing = { DataGroupTag(dataGroup) }) {
        ChipImage(image, contentDescription = label)
    }
}

@Composable
private fun PersonDetailsPanel(details: AdditionalPersonDetails) {
    val proofBytes = details.proofOfCitizenship?.takeIf { it.isNotEmpty() }
    val proof = remember(proofBytes) {
        proofBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    val fields = details.displayRows().toMutableList()
    if (proofBytes != null && proof == null) {
        // Say it is there even though it cannot be shown; silence would read as absent.
        fields += R.string.field_proof_of_citizenship to
            stringResource(R.string.proof_of_citizenship_undecoded, proofBytes.size)
    }
    val images = listOfNotNull(proof?.let { R.string.field_proof_of_citizenship to it })
    if (fields.isEmpty() && images.isEmpty()) return

    Panel(title = stringResource(R.string.panel_personal_details), trailing = { DataGroupTag(11) }) {
        FieldsAndImages(fields, images)
    }
}

@Composable
private fun DocumentDetailsPanel(details: AdditionalDocumentDetails) {
    val fields = details.displayRows()
    val images = listOf(
        R.string.image_front to details.imageOfFront,
        R.string.image_rear to details.imageOfRear,
    ).mapNotNull { (label, image) -> image?.let { label to it } }
    if (fields.isEmpty() && images.isEmpty()) return

    Panel(title = stringResource(R.string.panel_document_details), trailing = { DataGroupTag(12) }) {
        FieldsAndImages(fields, images)
    }
}

/**
 * DG13 as its size and bytes, nothing more: each issuer defines its own
 * contents, so any reading of them would be a guess.
 */
@Composable
private fun OptionalDetailsPanel(bytes: ByteArray) {
    Panel(title = stringResource(R.string.panel_optional_details), trailing = { DataGroupTag(13) }) {
        Field(stringResource(R.string.field_contents), stringResource(R.string.optional_details_summary, bytes.size))
        Text(
            remember(bytes) { ChipFields.hex(bytes, OPTIONAL_DETAILS_SHOWN) },
            style = MonoSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** Enough of DG13 to recognise its structure, without a screen of hex. */
private const val OPTIONAL_DETAILS_SHOWN = 256

/** DG16, one block per person, divided as the verification mismatches are. */
@Composable
private fun PersonsToNotifyPanel(persons: List<PersonToNotify>) {
    Panel(title = stringResource(R.string.panel_persons_to_notify), trailing = { DataGroupTag(16) }) {
        persons.forEachIndexed { index, person ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(stringResource(R.string.field_name), ChipFields.name(person.name))
                Field(stringResource(R.string.field_telephone), person.telephone)
                Field(stringResource(R.string.field_address), ChipFields.address(person.address))
                Field(stringResource(R.string.field_recorded), ChipFields.date(person.dateRecorded))
            }
        }
    }
}

private fun PersonToNotify.hasAnyField() =
    listOf(name, telephone, address, dateRecorded).any { !it.isNullOrBlank() }

@Composable
private fun FieldsAndImages(fields: List<Pair<Int, String>>, images: List<Pair<Int, Bitmap>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.forEach { (label, value) -> Field(stringResource(label), value) }
        images.forEach { (label, image) ->
            val caption = stringResource(label)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    caption.uppercase(),
                    style = FieldLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChipImage(image, contentDescription = caption)
            }
        }
    }
}

/** DG11's text fields that are present, as (label, display value). */
private fun AdditionalPersonDetails.displayRows(): List<Pair<Int, String>> = listOf(
    R.string.field_full_name to ChipFields.name(nameOfHolder),
    R.string.field_other_names to ChipFields.names(otherNames),
    R.string.field_title to title,
    R.string.field_full_date_of_birth to ChipFields.date(fullDateOfBirth),
    R.string.field_place_of_birth to ChipFields.list(placeOfBirth),
    R.string.field_permanent_address to ChipFields.list(permanentAddress),
    R.string.field_telephone to telephone,
    R.string.field_profession to profession,
    R.string.field_personal_number to personalNumber,
    R.string.field_personal_summary to personalSummary,
    R.string.field_other_documents to ChipFields.list(otherValidTDNumbers),
    R.string.field_custody to custodyInformation,
).presentOnly()

/** DG12's text fields that are present, as (label, display value). */
private fun AdditionalDocumentDetails.displayRows(): List<Pair<Int, String>> = listOf(
    R.string.field_issuing_authority to issuingAuthority,
    R.string.field_date_of_issue to ChipFields.date(dateOfIssue),
    R.string.field_other_persons to ChipFields.names(namesOfOtherPersons),
    R.string.field_endorsements to endorsementsAndObservations,
    R.string.field_tax_exit to taxOrExitRequirements,
    R.string.field_personalised to ChipFields.dateTime(dateAndTimeOfPersonalization),
    R.string.field_personalisation_serial to personalizationSystemSerialNumber,
).presentOnly()

private fun List<Pair<Int, String?>>.presentOnly(): List<Pair<Int, String>> =
    mapNotNull { (label, value) -> value?.takeIf { it.isNotBlank() }?.let { label to it } }

/**
 * One image at its own proportions, no taller than a portrait tile, so a wide
 * signature or document scan keeps its shape instead of being cropped.
 */
@Composable
private fun ChipImage(bitmap: Bitmap, contentDescription: String) {
    val ratio = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height else 1f
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .heightIn(max = 160.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
    )
}

@Composable
private fun VerificationPanel(passport: Passport) {
    val report = passport.verificationReport ?: return
    // A group with no computed hash was never compared - DG3 without terminal
    // authentication, or an optional group the chip would not give up. It is
    // unchecked, not a mismatch, and must not be shown as tampered with.
    val (checked, unchecked) = report.hashes.orEmpty()
        .sortedBy { it.dataGroup }
        .partition { it.compared }
    val hashDetail = checked
        .joinToString(" ") { "DG${it.dataGroup}" }
        .takeIf { it.isNotBlank() }

    Panel(
        title = stringResource(R.string.panel_verification),
        trailing = { VerificationTally(report) }
    ) {
        // The protocol acronyms are what the specs and the logs use, so name
        // them even when the check passed - the plain-English label alone
        // leaves the reader guessing which protocol actually ran.
        CheckRow(stringResource(R.string.check_bac), report.basicAccessControl, detail = "BAC")
        CheckRow(stringResource(R.string.check_pace), report.pace, detail = "PACE")
        CheckRow(stringResource(R.string.check_ca), report.chipAuthentication, detail = "CA")
        CheckRow(stringResource(R.string.check_aa), report.activeAuthentication, detail = "AA")
        CheckRow(stringResource(R.string.check_ds), report.documentSignature)
        CheckRow(
            stringResource(R.string.check_chain),
            report.certificateChain,
            // Only a chain that reached an anchor has a length worth giving.
            detail = report.chainCertificates?.size
                ?.takeIf { report.certificateChain?.verdict == CheckVerdict.SUCCEEDED }
                ?.let { stringResource(R.string.check_chain_detail, it) }
        )
        CheckRow(stringResource(R.string.check_hashes), report.dataGroupHashes, detail = hashDetail)
        CheckRow(stringResource(R.string.check_ta), report.terminalAuthentication, detail = "TA")

        val mismatches = checked.filterNot { it.matches }
        if (unchecked.isNotEmpty() || mismatches.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
        if (mismatches.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.check_hash_mismatch,
                    mismatches.joinToString(", ") { "DG${it.dataGroup}" }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.verdicts.fail
            )
        }
        if (unchecked.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.check_hash_unchecked,
                    unchecked.joinToString(", ") { "DG${it.dataGroup}" }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * What DG2 holds about the face, kept apart from the holder panel because it
 * comes from the biometric record rather than the data page - though eye and
 * hair colour do describe the holder.
 *
 * The encoding matters because a face that fails to decode leaves no photo to
 * look at, and "ISO/IEC 39794, could not be decoded" is then the most useful
 * thing a tester can report. Below it, what the first face record says about
 * its image; a field the record leaves out gets no row. Pose and quality are
 * shown only from an ISO/IEC 39794 record, whose published schema defines
 * them. An ISO/IEC 19794 record's are left off: how the 2005 edition scales
 * them is not freely published, and raw numbers there only confused. The
 * library still returns them as FaceDetails.rawPose and rawQuality. Hidden
 * when DG2 held no face record at all.
 */
@Composable
private fun BiometricPanel(passport: Passport) {
    val encoding = passport.faceEncoding ?: return
    val name = when (encoding) {
        BiometricEncoding.ISO_19794 -> stringResource(R.string.encoding_iso19794)
        BiometricEncoding.ISO_39794 -> stringResource(R.string.encoding_iso39794)
    }
    val faces = passport.faceDetails
    val face = faces.firstOrNull()
    Panel(title = stringResource(R.string.panel_biometrics)) {
        CertRow(
            stringResource(R.string.field_face_encoding),
            if (passport.face != null) name else stringResource(R.string.face_encoding_undecoded, name)
        )
        if (faces.size > 1) CertRow(stringResource(R.string.field_faces), faces.size.toString())
        if (face != null) FaceDetailRows(face)
    }
}

@Composable
private fun FaceDetailRows(face: FaceDetails) {
    CertRow(stringResource(R.string.field_eye_colour), colourName(face.eyeColour))
    CertRow(stringResource(R.string.field_hair_colour), colourName(face.hairColour))
    if (face.landmarkCount > 0) CertRow(stringResource(R.string.field_landmarks), face.landmarkCount.toString())
    face.pose?.let { pose ->
        val angles = stringResource(R.string.face_pose, angle(pose.yaw), angle(pose.pitch), angle(pose.roll))
        CertRow(stringResource(R.string.field_pose), angles)
    }
    if (face.qualityScores.isNotEmpty()) {
        val scores = face.qualityScores.map { quality ->
            quality.score?.let { stringResource(R.string.face_quality_score, it) }
                ?: stringResource(R.string.face_quality_failed)
        }
        CertRow(stringResource(R.string.field_quality), scores.joinToString(", "))
    }
    CertRow(stringResource(R.string.field_captured), face.captureDate)
}

@Composable
private fun angle(degrees: Int?): String =
    degrees?.let { stringResource(R.string.face_angle, it) } ?: stringResource(R.string.face_angle_absent)

/** Null - no row - for a colour the record leaves unspecified or unknown. */
@Composable
private fun colourName(colour: EyeColour): String? = when (colour) {
    EyeColour.UNSPECIFIED, EyeColour.UNKNOWN -> null
    EyeColour.OTHER -> stringResource(R.string.colour_other)
    EyeColour.BLACK -> stringResource(R.string.colour_black)
    EyeColour.BLUE -> stringResource(R.string.colour_blue)
    EyeColour.BROWN -> stringResource(R.string.colour_brown)
    EyeColour.GREY -> stringResource(R.string.colour_grey)
    EyeColour.GREEN -> stringResource(R.string.colour_green)
    EyeColour.HAZEL -> stringResource(R.string.colour_hazel)
    EyeColour.MULTI_COLOURED -> stringResource(R.string.colour_multi)
    EyeColour.PINK -> stringResource(R.string.colour_pink)
}

@Composable
private fun colourName(colour: HairColour): String? = when (colour) {
    HairColour.UNSPECIFIED, HairColour.UNKNOWN -> null
    HairColour.OTHER -> stringResource(R.string.colour_other)
    HairColour.BALD -> stringResource(R.string.colour_bald)
    HairColour.BLACK -> stringResource(R.string.colour_black)
    HairColour.BLONDE -> stringResource(R.string.colour_blonde)
    HairColour.BROWN -> stringResource(R.string.colour_brown)
    HairColour.GREY -> stringResource(R.string.colour_grey)
    HairColour.WHITE -> stringResource(R.string.colour_white)
    HairColour.RED -> stringResource(R.string.colour_red)
    HairColour.GREEN -> stringResource(R.string.colour_green)
    HairColour.BLUE -> stringResource(R.string.colour_blue)
    HairColour.KNOWN_COLOURED -> stringResource(R.string.colour_known_coloured)
}

@Composable
private fun SignerPanel(dsc: CertificateDetails) {
    Panel(title = stringResource(R.string.panel_signer)) {
        CertRow(stringResource(R.string.cert_subject), dsc.subjectDn)
        CertRow(stringResource(R.string.cert_issuer), dsc.issuerDn)
        CertRow(stringResource(R.string.cert_serial), dsc.serialNumberHex)
        CertRow(
            stringResource(R.string.cert_valid),
            "${CERT_DATE.format(Instant.ofEpochMilli(dsc.validFromEpochMs))} → " +
                CERT_DATE.format(Instant.ofEpochMilli(dsc.validUntilEpochMs))
        )
        CertRow(
            stringResource(R.string.cert_key),
            listOfNotNull(
                dsc.publicKeyAlgorithm,
                dsc.ecCurve,
                dsc.keySizeBits?.let { stringResource(R.string.cert_key_bits, it) }
            ).joinToString(" · ")
        )
        CertRow(stringResource(R.string.cert_signature), dsc.signatureAlgorithm)
        CertRow(stringResource(R.string.cert_fingerprint), dsc.sha256Fingerprint)
    }
}

@Composable
private fun ChainPanel(chain: List<CertificateDetails>) {
    Panel(title = stringResource(R.string.panel_chain)) {
        chain.forEachIndexed { index, cert ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(
                    cert.role.badge(),
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Text(
                    cert.subjectDn.commonName(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // Weighted so a long common name gives way rather than
                    // pushing the country off the end: the common name is
                    // often generic, so the country is the part worth keeping.
                    modifier = Modifier.weight(1f, fill = false)
                )
                cert.subjectCountry?.let { country ->
                    Text(
                        "· $country",
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (index < chain.lastIndex) {
                Box(
                    modifier = Modifier
                        .padding(start = 22.dp)
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .padding(vertical = 5.dp)
                )
            }
        }
    }
}

/** The short form a chain badge shows: the abbreviations ICAO 9303 uses. */
private fun CertificateRole.badge(): String = when (this) {
    CertificateRole.DOCUMENT_SIGNER -> "DSC"
    CertificateRole.LINK -> "LINK"
    CertificateRole.CSCA -> "CSCA"
}

/**
 * A label beside its value. The label column grows with the font scale, up to
 * [CERT_LABEL_MAX], so a label that fits on one line at the default size still
 * fits at a larger one: a fixed 72dp broke "ENCODING" and "LANDMARKS" mid-word
 * from 1.3x. From [CERT_STACK_SCALE] the label goes above the value instead,
 * because the value column left beside it is too narrow for a certificate
 * subject or fingerprint (stack rather than clip; see docs/sample-app.md).
 */
@Composable
private fun CertRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    val fontScale = LocalDensity.current.fontScale
    val labelText = @Composable { modifier: Modifier ->
        Text(
            label.uppercase(),
            style = FieldLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
    }
    val valueText = @Composable { modifier: Modifier ->
        Text(value, style = MonoSmall, color = MaterialTheme.colorScheme.onSurface, modifier = modifier)
    }
    if (fontScale >= CERT_STACK_SCALE) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            labelText(Modifier)
            valueText(Modifier)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            labelText(Modifier.width((CERT_LABEL_WIDTH * fontScale).coerceAtMost(CERT_LABEL_MAX)))
            valueText(Modifier.weight(1f))
        }
    }
}

/** Pulls CN= out of an RFC 2253 DN, falling back to the whole string. */
private fun String.commonName(): String =
    split(",")
        .map { it.trim() }
        .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
        ?.substringAfter("=")
        ?: this

private val CERT_LABEL_WIDTH = 72.dp

/** Wide enough for the longest label below [CERT_STACK_SCALE], narrow enough to leave the value most of the row. */
private val CERT_LABEL_MAX = 120.dp

/** The font scale from which a row's label stacks above its value. */
private const val CERT_STACK_SCALE = 1.5f
