package io.github.munkchunk.passportreader.data

import android.graphics.Bitmap
import android.os.Parcel
import io.github.munkchunk.passportreader.model.BiometricEncoding
import io.github.munkchunk.passportreader.model.EyeColour
import io.github.munkchunk.passportreader.model.FaceDetails
import io.github.munkchunk.passportreader.model.FacePose
import io.github.munkchunk.passportreader.model.HairColour
import io.github.munkchunk.passportreader.model.QualityScore
import io.github.munkchunk.passportreader.model.CertificateDetails
import io.github.munkchunk.passportreader.model.CertificateRole
import io.github.munkchunk.passportreader.model.CheckResult
import io.github.munkchunk.passportreader.model.CheckVerdict
import io.github.munkchunk.passportreader.model.DocumentFeatures
import io.github.munkchunk.passportreader.model.FeatureSupport
import io.github.munkchunk.passportreader.model.DataGroupHash
import io.github.munkchunk.passportreader.model.Sex
import io.github.munkchunk.passportreader.model.VerificationReport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trips a whole [Passport] through a real [Parcel].
 *
 * [Passport] is what a consumer hands between activities or saves across
 * process death, so this is the parcel that matters. The detail classes it
 * nests are covered field by field in [DetailsParcelTest]; this checks the
 * container, and in particular its two lists: written in one format and read
 * in another, they break on API 33 for every Passport that carries a
 * certificate chain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33])
class PassportParcelTest {

    private fun roundTrip(value: Passport): Passport {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(value, 0)
            val written = parcel.dataPosition()
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            val read = parcel.readParcelable<Passport>(Passport::class.java.classLoader)
            assertEquals("parcel not consumed exactly", written, parcel.dataPosition())
            assertNotNull(read)
            return read!!
        } finally {
            parcel.recycle()
        }
    }

    private fun cert(role: CertificateRole, serial: String) = CertificateDetails(
        role = role,
        subjectDn = "CN=$role,C=GB",
        issuerDn = "CN=CSCA,C=GB",
        subjectCountry = "GB",
        serialNumberHex = serial,
        validFromEpochMs = 1_600_000_000_000,
        validUntilEpochMs = 1_900_000_000_000,
        publicKeyAlgorithm = "EC",
        keySizeBits = 256,
        ecCurve = "brainpoolP256r1",
        signatureAlgorithm = "SHA256withECDSA",
        sha256Fingerprint = "ab".repeat(32),
        sha1Fingerprint = "ab".repeat(20),
    )

    private fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    /** Shaped like a successful read: a chain present, fingerprints absent. */
    @Test
    fun populatedPassportSurvives() {
        val chain = listOf(cert(CertificateRole.DOCUMENT_SIGNER, "01"), cert(CertificateRole.CSCA, "02"))
        val original = Passport().apply {
            sodBytes = byteArrayOf(0x77, 0x01, 0x02, -1)
            face = bitmap(3, 4)
            faceEncoding = BiometricEncoding.ISO_39794
            personDetails = PersonDetails(documentNumber = "L898902C3", sex = Sex.FEMALE)
            additionalPersonDetails = AdditionalPersonDetails(nameOfHolder = "ERIKSSON<<ANNA")
            additionalDocumentDetails = AdditionalDocumentDetails(dateOfIssue = "20120415")
            optionalDetails = byteArrayOf(0x6D, 0x01, -1)
            personsToNotify = listOf(PersonToNotify("20020101", "SMITH<<CHARLES<R", "19525551212", null))
            features = DocumentFeatures(
                pace = FeatureSupport.SUPPORTED,
                basicAccessControl = FeatureSupport.NOT_SUPPORTED,
            )
            mrz = MrzData("LINE1\nLINE2", "LINE1", "LINE2")
            documentSigner = chain.first()
            chainCertificates = chain
            verificationReport = VerificationReport(
                pace = CheckResult(CheckVerdict.SUCCEEDED),
                dataGroupHashes = CheckResult(CheckVerdict.SUCCEEDED),
                hashes = listOf(DataGroupHash(1, "aa", "aa", true), DataGroupHash(3, "bb", null, false)),
                chainCertificates = chain,
            )
        }

        val copy = roundTrip(original)

        assertArrayEquals(byteArrayOf(0x77, 0x01, 0x02, -1), copy.sodBytes)
        assertEquals(3, copy.face!!.width)
        assertEquals(BiometricEncoding.ISO_39794, copy.faceEncoding)
        assertNull(copy.portrait)
        assertNull(copy.signature)
        assertEquals("L898902C3", copy.personDetails!!.documentNumber)
        assertEquals(Sex.FEMALE, copy.personDetails!!.sex)
        assertEquals("ERIKSSON<<ANNA", copy.additionalPersonDetails!!.nameOfHolder)
        assertEquals("20120415", copy.additionalDocumentDetails!!.dateOfIssue)
        assertArrayEquals(byteArrayOf(0x6D, 0x01, -1), copy.optionalDetails)
        with(copy.personsToNotify!!.single()) {
            assertEquals("20020101", dateRecorded)
            assertEquals("SMITH<<CHARLES<R", name)
            assertEquals("19525551212", telephone)
            assertNull(address)
        }
        assertEquals(original.features, copy.features)
        assertEquals("LINE2", copy.mrz!!.getLine2())
        assertEquals(original.documentSigner, copy.documentSigner)
        assertEquals(chain, copy.chainCertificates)
        assertEquals(listOf(true, false), copy.verificationReport!!.hashes!!.map { it.compared })
        assertEquals(original.verificationReport, copy.verificationReport)
        assertEquals(emptyList<Bitmap>(), copy.fingerprints)
    }

    /** Both encodings' shapes: 39794's converted fields and 19794's raw ones. */
    @Test
    fun faceDetailsSurvive() {
        val original = Passport().apply {
                faceDetails = listOf(
                    FaceDetails(
                        BiometricEncoding.ISO_39794, EyeColour.HAZEL, HairColour.GREY, landmarkCount = 3,
                        pose = FacePose(-12, null, 4), qualityScores = listOf(QualityScore(null, 257, 3)),
                        captureDate = "2025-03-14",
                    ),
                    FaceDetails(BiometricEncoding.ISO_19794, rawPose = listOf(91, 0, 1), rawQuality = 0),
                )
        }

        val copy = roundTrip(original)

        with(copy.faceDetails[0]) {
            assertEquals(EyeColour.HAZEL, eyeColour)
            assertEquals(HairColour.GREY, hairColour)
            assertEquals(3, landmarkCount)
            assertEquals(FacePose(-12, null, 4), pose)
            assertEquals(listOf(QualityScore(null, 257, 3)), qualityScores)
            assertEquals("2025-03-14", captureDate)
        }
        assertEquals(listOf(91, 0, 1), copy.faceDetails[1].rawPose)
        assertEquals(0, copy.faceDetails[1].rawQuality)
    }

    /** DG3 is behind terminal authentication, so this is rare - but typed. */
    @Test
    fun fingerprintsSurvive() {
        val original = Passport().apply { fingerprints = listOf(bitmap(2, 2), bitmap(5, 1)) }

        val copy = roundTrip(original)

        assertEquals(listOf(2 to 2, 5 to 1), copy.fingerprints!!.map { it.width to it.height })
    }

    @Test
    fun emptyPassportSurvives() {
        val copy = roundTrip(Passport())

        assertNull(copy.sodBytes)
        assertNull(copy.personDetails)
        assertNull(copy.faceEncoding)
        assertNull(copy.chainCertificates)
        assertNull(copy.optionalDetails)
        assertNull(copy.personsToNotify)
        assertEquals(DocumentFeatures(), copy.features)
        assertEquals(emptyList<Bitmap>(), copy.fingerprints)
    }

    @Test
    fun nullListsSurvive() {
        val original = Passport().apply {
            fingerprints = null
            features = null
        }

        val copy = roundTrip(original)

        assertNull(copy.fingerprints)
        assertNull(copy.features)
    }
}
