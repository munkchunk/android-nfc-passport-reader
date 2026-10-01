package io.github.munkchunk.passportreader.sample.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.munkchunk.passportreader.data.AdditionalDocumentDetails
import io.github.munkchunk.passportreader.data.AdditionalPersonDetails
import io.github.munkchunk.passportreader.data.MrzData
import io.github.munkchunk.passportreader.data.Passport
import io.github.munkchunk.passportreader.data.PersonDetails
import io.github.munkchunk.passportreader.data.PersonToNotify
import io.github.munkchunk.passportreader.model.BiometricEncoding
import io.github.munkchunk.passportreader.model.CertificateDetails
import io.github.munkchunk.passportreader.model.CertificateRole
import io.github.munkchunk.passportreader.model.CheckResult
import io.github.munkchunk.passportreader.model.CheckVerdict
import io.github.munkchunk.passportreader.model.DataGroupHash
import io.github.munkchunk.passportreader.model.EyeColour
import io.github.munkchunk.passportreader.model.FaceDetails
import io.github.munkchunk.passportreader.model.FacePose
import io.github.munkchunk.passportreader.model.HairColour
import io.github.munkchunk.passportreader.model.QualityScore
import io.github.munkchunk.passportreader.model.Sex
import io.github.munkchunk.passportreader.model.VerificationReport
import io.github.munkchunk.passportreader.sample.ui.ResultScreen
import io.github.munkchunk.passportreader.sample.ui.theme.PassportReaderTheme
import java.io.ByteArrayOutputStream

/**
 * The result screen showing an invented document that carries every optional
 * group, since no passport to hand has DG5, DG7, DG11 or DG12. Debug builds
 * only, and reached only through adb:
 *
 * ```
 * adb shell am start -n io.github.munkchunk.passportreader.sample/.demo.DemoResultActivity
 * adb shell am start -n io.github.munkchunk.passportreader.sample/.demo.DemoResultActivity --ez noFace true
 * ```
 *
 * `noFace` drops the DG2 face and its record, so the holder panel falls back to the DG5
 * portrait, which also keeps its own panel. The holder is the ICAO 9303 specimen, whose MRZ check digits
 * all verify; the rest is made up in the same spirit, and the images are
 * drawn here rather than taken from anywhere.
 */
class DemoResultActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val passport = demoPassport(withFace = !intent.getBooleanExtra("noFace", false))
        setContent {
            PassportReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ResultScreen(passport = passport, onDone = ::finish)
                }
            }
        }
    }
}

private fun demoPassport(withFace: Boolean) = Passport(
    face = if (withFace) silhouette(Color.rgb(0x9A, 0xB0, 0xA2)) else null,
    // Only with a face: without one, the demo stands for a document with no DG2 face record.
    faceEncoding = if (withFace) BiometricEncoding.ISO_39794 else null,
    // Every detail a 39794 record can carry, so the Biometric data panel shows them all.
    faceDetails = if (!withFace) emptyList() else listOf(
        FaceDetails(
            encoding = BiometricEncoding.ISO_39794,
            eyeColour = EyeColour.BLUE,
            hairColour = HairColour.BLONDE,
            landmarkCount = 4,
            pose = FacePose(yaw = 2, pitch = -3, roll = 0),
            qualityScores = listOf(QualityScore(score = 87, algorithmOrganisation = 257, algorithmId = 2)),
            captureDate = "2002-04-10",
        ),
    ),
    portrait = silhouette(Color.rgb(0xB8, 0xB8, 0xB8)),
    signature = signature(),
    personDetails = PersonDetails(
        documentCode = "P",
        issuingState = "UTO",
        primaryIdentifier = "ERIKSSON",
        secondaryIdentifier = "ANNA MARIA",
        nationality = "UTO",
        documentNumber = "L898902C3",
        dateOfBirth = "740812",
        dateOfExpiry = "120415",
        optionalData1 = "ZE184226B",
        sex = Sex.FEMALE,
    ),
    mrz = MrzData(
        full = LINE_1 + LINE_2,
        line1 = LINE_1,
        line2 = LINE_2,
    ),
    additionalPersonDetails = AdditionalPersonDetails(
        nameOfHolder = "ERIKSSON<<ANNA<MARIA",
        otherNames = listOf("SVENSSON<<ANNA<MARIA"),
        fullDateOfBirth = "19740812",
        placeOfBirth = listOf("ZENITH", "UTOPIA"),
        permanentAddress = listOf("123 MAPLE ROAD", "ANYTOWN", "UTOPIA"),
        telephone = "+000 555 0100",
        profession = "CARTOGRAPHER",
        title = "DR",
        personalNumber = "ZE184226B",
        personalSummary = "SPECIMEN",
        otherValidTDNumbers = listOf("D23145890"),
        custodyInformation = "NONE",
        proofOfCitizenship = card("CITIZENSHIP", Color.rgb(0xE8, 0xDC, 0xC4)).toPng(),
    ),
    additionalDocumentDetails = AdditionalDocumentDetails(
        issuingAuthority = "UTOPIA PASSPORT OFFICE",
        dateOfIssue = "20020415",
        namesOfOtherPersons = listOf("ERIKSSON<<LARS", "ERIKSSON<<SOFIA"),
        endorsementsAndObservations = "SPECIMEN - NOT A TRAVEL DOCUMENT",
        taxOrExitRequirements = "NONE",
        dateAndTimeOfPersonalization = "20020412140509",
        personalizationSystemSerialNumber = "PS-000142",
        imageOfFront = card("FRONT", Color.rgb(0x2D, 0x6A, 0x4F)),
        imageOfRear = card("REAR", Color.rgb(0x1B, 0x43, 0x32)),
    ),
    // An invented signer, long enough in every field to show how the certificate rows wrap.
    documentSigner = DEMO_DSC,
    chainCertificates = listOf(DEMO_DSC, DEMO_CSCA),
    verificationReport = demoReport(),
    // Issuer-defined, so any bytes will do: a small TLV with text in it.
    optionalDetails = byteArrayOf(0x6D, 0x0B, 0x5F, 0x0F, 0x08) + "SPECIMEN".toByteArray(),
    // ICAO 9303-10 Appendix A.6's two entries.
    personsToNotify = listOf(
        PersonToNotify("20020101", "SMITH<<CHARLES<R", "19525551212", "123 MAPLE RD<ANYTOWN<MN<55100"),
        PersonToNotify("20020315", "BROWN<<MARY<J", "14155551212", "49 REDWOOD LN<OCEAN BREEZE<CA<94000"),
    ),
)

/**
 * What the library reports for a PACE-CAM chip whose DG14 holds a chip
 * authentication key and its PACEInfos, with no DG15, using the library's own
 * reason strings. The chip protects DG3 with terminal authentication, which
 * has no credentials, so DG3 is listed but never compared, as on any real read. The hashes are placeholders:
 * nothing here was computed.
 */
private fun demoReport() = VerificationReport(
    basicAccessControl = CheckResult(CheckVerdict.NOT_CHECKED, "Not needed: PACE was used"),
    pace = CheckResult(CheckVerdict.SUCCEEDED, "PACE completed; EF.CardAccess matches DG14"),
    chipAuthentication = CheckResult(CheckVerdict.SUCCEEDED, "Chip authenticated by PACE-CAM and DG14"),
    activeAuthentication = CheckResult(CheckVerdict.NOT_PRESENT, "Not offered by this chip"),
    documentSignature = CheckResult(CheckVerdict.SUCCEEDED, "EF.SOd signature valid"),
    certificateChain = CheckResult(CheckVerdict.SUCCEEDED, "Chains to a trusted CSCA"),
    dataGroupHashes = CheckResult(CheckVerdict.SUCCEEDED, "Data Group hashes match"),
    terminalAuthentication = CheckResult(CheckVerdict.NOT_CHECKED, "No terminal certificate available"),
    hashes = listOf(1, 2, 3, 5, 7, 11, 12, 13, 14, 16).map { group ->
        val stored = "%02X".format(group).repeat(32)
        if (group == 3) {
            DataGroupHash(dataGroup = group, expectedHex = stored, actualHex = null, matches = false)
        } else {
            DataGroupHash(dataGroup = group, expectedHex = stored, actualHex = stored, matches = true)
        }
    },
    chainCertificates = listOf(DEMO_DSC, DEMO_CSCA),
)

// Certificates are checked at today's date, so a chain that passes needs
// certificates valid today, whatever the specimen's own dates.
private val DEMO_DSC = CertificateDetails(
    role = CertificateRole.DOCUMENT_SIGNER,
    subjectDn = "CN=Document Signer 001,OU=Passport Office,O=Government of Utopia,C=UT",
    issuerDn = "CN=Country Signing CA,OU=Passport Office,O=Government of Utopia,C=UT",
    subjectCountry = "UT",
    serialNumberHex = "01A2B3C4D5E6F708",
    validFromEpochMs = 1_704_067_200_000, // 2024-01-01
    validUntilEpochMs = 2_019_686_400_000, // 2034-01-01
    publicKeyAlgorithm = "EC",
    keySizeBits = 256,
    ecCurve = "secp256r1",
    signatureAlgorithm = "SHA256withECDSA",
    sha256Fingerprint = "3F".repeat(32),
    sha1Fingerprint = "3F".repeat(20),
)

private val DEMO_CSCA = CertificateDetails(
    role = CertificateRole.CSCA,
    subjectDn = "CN=Country Signing CA,OU=Passport Office,O=Government of Utopia,C=UT",
    issuerDn = "CN=Country Signing CA,OU=Passport Office,O=Government of Utopia,C=UT",
    subjectCountry = "UT",
    serialNumberHex = "01",
    validFromEpochMs = 1_577_836_800_000, // 2020-01-01
    validUntilEpochMs = 2_051_222_400_000, // 2035-01-01
    publicKeyAlgorithm = "EC",
    keySizeBits = 384,
    ecCurve = "secp384r1",
    signatureAlgorithm = "SHA384withECDSA",
    sha256Fingerprint = "A5".repeat(32),
    sha1Fingerprint = "A5".repeat(20),
)

private const val LINE_1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
private const val LINE_2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"

/** A head-and-shoulders outline at the 3:4 of a passport photograph. */
private fun silhouette(ground: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(ground)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x4A, 0x55, 0x4E) }
    canvas.drawCircle(150f, 150f, 72f, paint)
    canvas.drawOval(RectF(40f, 250f, 260f, 520f), paint)
    return bitmap
}

/** A wide, short scrawl, the shape DG7 signatures usually are. */
private fun signature(): Bitmap {
    val bitmap = Bitmap.createBitmap(480, 120, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x1A, 0x23, 0x7E)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    val path = Path().apply {
        moveTo(30f, 80f)
        cubicTo(60f, 10f, 90f, 110f, 130f, 60f)
        cubicTo(170f, 10f, 190f, 100f, 230f, 70f)
        cubicTo(270f, 40f, 300f, 90f, 340f, 55f)
        cubicTo(380f, 20f, 420f, 95f, 455f, 45f)
    }
    canvas.drawPath(path, paint)
    return bitmap
}

/** An ID-1 card, 85.6 x 54mm, labelled so it is obviously not a scan. */
private fun card(label: String, ground: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(428, 270, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(ground)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    canvas.drawText(label, 214f, 125f, paint)
    paint.textSize = 24f
    paint.isFakeBoldText = false
    canvas.drawText("SPECIMEN", 214f, 170f, paint)
    return bitmap
}

private fun Bitmap.toPng(): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
