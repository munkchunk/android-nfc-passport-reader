package io.github.munkchunk.passportreader.mapping

import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.jmrtd.lds.LDSFile
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DG11 and DG12 mapping, without a passport that carries them.
 *
 * Neither test document has either group, so no device read has ever
 * exercised this code. Each file is built with JMRTD, encoded, and parsed back
 * from the bytes - the same parse a chip read goes through - before mapping.
 */
@RunWith(RobolectricTestRunner::class)
class DataGroupMappersTest {

    private fun reparse(file: DG11File) = DG11File(ByteArrayInputStream(file.encoded))
    private fun reparse(file: DG12File) = DG12File(ByteArrayInputStream(file.encoded))

    private fun png(): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    @Test
    fun dg11_everyFieldIsMapped() {
        val dg11 = reparse(
            DG11File(
                "ERIKSSON<<ANNA<MARIA",      // nameOfHolder
                listOf("ANNA ERIKSSON"),     // otherNames
                "ZE184226B",                 // personalNumber
                "19740812",                  // fullDateOfBirth
                listOf("ANYTOWN", "UTOPIA"), // placeOfBirth
                listOf("1 HIGH STREET"),     // permanentAddress
                "+44 1234 567890",           // telephone
                "ENGINEER",                  // profession
                "DR",                        // title
                "summary",                   // personalSummary
                byteArrayOf(1, 2, 3),        // proofOfCitizenship
                listOf("X1234567"),          // otherValidTDNumbers
                "custody",                   // custodyInformation
            )
        )

        val mapped = dg11.toAdditionalPersonDetails()

        assertEquals("ERIKSSON<<ANNA<MARIA", mapped.nameOfHolder)
        assertEquals(listOf("ANNA ERIKSSON"), mapped.otherNames)
        assertEquals("ZE184226B", mapped.personalNumber)
        assertEquals("19740812", mapped.fullDateOfBirth)
        assertEquals(listOf("ANYTOWN", "UTOPIA"), mapped.placeOfBirth)
        assertEquals(listOf("1 HIGH STREET"), mapped.permanentAddress)
        assertEquals("+44 1234 567890", mapped.telephone)
        assertEquals("ENGINEER", mapped.profession)
        assertEquals("DR", mapped.title)
        assertEquals("summary", mapped.personalSummary)
        assertArrayEquals(byteArrayOf(1, 2, 3), mapped.proofOfCitizenship)
        assertEquals(listOf("X1234567"), mapped.otherValidTDNumbers)
        assertEquals("custody", mapped.custodyInformation)
        assertEquals(LDSFile.EF_DG11_TAG, mapped.tag)
        assertEquals(dg11.tagPresenceList, mapped.tagPresenceList)
        assertTrue(mapped.tagPresenceList!!.isNotEmpty())
    }

    @Test
    fun dg12_everyFieldIsMapped() {
        val dg12 = reparse(
            DG12File(
                "UTOPIA PASSPORT OFFICE",    // issuingAuthority
                "20120415",                  // dateOfIssue
                listOf("CHILD ONE"),         // namesOfOtherPersons
                "none",                      // endorsementsAndObservations
                "exempt",                    // taxOrExitRequirements
                png(),                       // imageOfFront
                png(),                       // imageOfRear
                "20120415103000",            // dateAndTimeOfPersonalization
                "SN-0001",                   // personalizationSystemSerialNumber
            )
        )

        val mapped = dg12.toAdditionalDocumentDetails()

        assertEquals("UTOPIA PASSPORT OFFICE", mapped.issuingAuthority)
        assertEquals("20120415", mapped.dateOfIssue)
        assertEquals(listOf("CHILD ONE"), mapped.namesOfOtherPersons)
        assertEquals("none", mapped.endorsementsAndObservations)
        assertEquals("exempt", mapped.taxOrExitRequirements)
        assertNotNull(mapped.imageOfFront)
        assertNotNull(mapped.imageOfRear)
        assertEquals("20120415103000", mapped.dateAndTimeOfPersonalization)
        assertEquals("SN-0001", mapped.personalizationSystemSerialNumber)
        // Easy to miss: left uncopied, every DG12 read reports tag 0 and an
        // empty presence list.
        assertEquals(LDSFile.EF_DG12_TAG, mapped.tag)
        assertEquals(dg12.tagPresenceList, mapped.tagPresenceList)
        assertTrue(mapped.tagPresenceList!!.isNotEmpty())
    }

    /** The usual shape: an issuer that fills in one or two fields. */
    @Test
    fun dg12_sparseFileMapsToNulls() {
        val dg12 = reparse(
            DG12File("UTOPIA PASSPORT OFFICE", null as String?, null, null, null, null, null, null as String?, null)
        )

        val mapped = dg12.toAdditionalDocumentDetails()

        assertEquals("UTOPIA PASSPORT OFFICE", mapped.issuingAuthority)
        assertNull(mapped.dateOfIssue)
        assertNull(mapped.imageOfFront)
        assertNull(mapped.imageOfRear)
        assertEquals(dg12.tagPresenceList, mapped.tagPresenceList)
    }
}
