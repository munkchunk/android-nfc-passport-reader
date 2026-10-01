package io.github.munkchunk.passportreader.data

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Parcel
import android.os.Parcelable
import io.github.munkchunk.passportreader.model.Sex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trips the data-group detail classes through a real [Parcel].
 *
 * Nothing in the sample app parcels a [Passport], so a read on a device never
 * exercises this path; these tests are the only thing that does. Each one also
 * checks the parcel was consumed exactly, since a reader that drifts out of
 * step with its writer often still returns an object - just the wrong one.
 *
 * Run at minSdk and at 33 because the platform's Parcel implementation
 * differs either side of Tiramisu, and a parcelling mistake fails
 * differently on each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33])
class DetailsParcelTest {

    private inline fun <reified T : Parcelable> roundTrip(value: T): T {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(value, 0)
            val written = parcel.dataPosition()
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            val read = parcel.readParcelable<T>(T::class.java.classLoader)
            assertEquals("parcel not consumed exactly", written, parcel.dataPosition())
            assertNotNull(read)
            return read!!
        } finally {
            parcel.recycle()
        }
    }

    // --- PersonDetails -----------------------------------------------------

    @Test
    fun personDetails_allFieldsSurvive() {
        val original = PersonDetails().apply {
            documentCode = "P"
            issuingState = "UTO"
            primaryIdentifier = "ERIKSSON"
            secondaryIdentifier = "ANNA MARIA"
            nationality = "UTO"
            documentNumber = "L898902C3"
            dateOfBirth = "740812"
            dateOfExpiry = "120415"
            optionalData1 = "ZE184226B"
            optionalData2 = "OPT2"
            sex = Sex.FEMALE
        }

        val copy = roundTrip(original)

        assertEquals("P", copy.documentCode)
        assertEquals("UTO", copy.issuingState)
        assertEquals("ERIKSSON", copy.primaryIdentifier)
        assertEquals("ANNA MARIA", copy.secondaryIdentifier)
        assertEquals("UTO", copy.nationality)
        assertEquals("L898902C3", copy.documentNumber)
        assertEquals("740812", copy.dateOfBirth)
        assertEquals("120415", copy.dateOfExpiry)
        assertEquals("ZE184226B", copy.optionalData1)
        assertEquals("OPT2", copy.optionalData2)
        assertEquals(Sex.FEMALE, copy.sex)
    }

    /** Most passports leave the second optional-data field empty. */
    @Test
    fun personDetails_sexSurvivesWithoutOptionalData2() {
        val original = PersonDetails().apply {
            documentNumber = "L898902C3"
            optionalData2 = null
            sex = Sex.MALE
        }

        val copy = roundTrip(original)

        assertEquals("L898902C3", copy.documentNumber)
        assertNull(copy.optionalData2)
        assertEquals(Sex.MALE, copy.sex)
    }

    @Test
    fun personDetails_defaultsSurvive() {
        val copy = roundTrip(PersonDetails())

        assertNull(copy.documentNumber)
        assertEquals(Sex.UNKNOWN, copy.sex)
    }

    // --- AdditionalPersonDetails (DG11) ------------------------------------

    @Test
    fun additionalPersonDetails_allFieldsSurvive() {
        val original = AdditionalPersonDetails().apply {
            custodyInformation = "custody"
            fullDateOfBirth = "19740812"
            nameOfHolder = "ERIKSSON<<ANNA<MARIA"
            otherNames = listOf("ANNA ERIKSSON", "A M ERIKSSON")
            otherValidTDNumbers = listOf("X1234567")
            permanentAddress = listOf("1 HIGH STREET", "ANYTOWN")
            personalNumber = "ZE184226B"
            personalSummary = "summary"
            placeOfBirth = listOf("ANYTOWN", "UTOPIA")
            profession = "ENGINEER"
            proofOfCitizenship = byteArrayOf(1, 2, 3, -1)
            tag = 0x6B
            tagPresenceList = listOf(0x5F0E, 0x5F10, 0x5F11)
            telephone = "+44 1234 567890"
            title = "DR"
        }

        val copy = roundTrip(original)

        assertEquals("custody", copy.custodyInformation)
        assertEquals("19740812", copy.fullDateOfBirth)
        assertEquals("ERIKSSON<<ANNA<MARIA", copy.nameOfHolder)
        assertEquals(listOf("ANNA ERIKSSON", "A M ERIKSSON"), copy.otherNames)
        assertEquals(listOf("X1234567"), copy.otherValidTDNumbers)
        assertEquals(listOf("1 HIGH STREET", "ANYTOWN"), copy.permanentAddress)
        assertEquals("ZE184226B", copy.personalNumber)
        assertEquals("summary", copy.personalSummary)
        assertEquals(listOf("ANYTOWN", "UTOPIA"), copy.placeOfBirth)
        assertEquals("ENGINEER", copy.profession)
        assertArrayEquals(byteArrayOf(1, 2, 3, -1), copy.proofOfCitizenship)
        assertEquals(0x6B, copy.tag)
        assertEquals(listOf(0x5F0E, 0x5F10, 0x5F11), copy.tagPresenceList)
        assertEquals("+44 1234 567890", copy.telephone)
        assertEquals("DR", copy.title)
    }

    @Test
    fun additionalPersonDetails_nullsSurvive() {
        val original = AdditionalPersonDetails().apply {
            otherNames = null
            otherValidTDNumbers = null
            permanentAddress = null
            placeOfBirth = null
            tagPresenceList = null
            title = "DR"
        }

        val copy = roundTrip(original)

        assertNull(copy.otherNames)
        assertNull(copy.otherValidTDNumbers)
        assertNull(copy.permanentAddress)
        assertNull(copy.placeOfBirth)
        assertNull(copy.tagPresenceList)
        assertNull(copy.proofOfCitizenship)
        assertEquals("DR", copy.title)
    }

    // --- AdditionalDocumentDetails (DG12) ----------------------------------

    @Test
    fun additionalDocumentDetails_allFieldsSurvive() {
        val front = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val original = AdditionalDocumentDetails().apply {
            endorsementsAndObservations = "none"
            dateAndTimeOfPersonalization = "20120415103000"
            dateOfIssue = "20120415"
            imageOfFront = front
            imageOfRear = null
            issuingAuthority = "UTOPIA PASSPORT OFFICE"
            namesOfOtherPersons = listOf("CHILD ONE", "CHILD TWO")
            personalizationSystemSerialNumber = "SN-0001"
            taxOrExitRequirements = "exempt"
            tag = 0x6C
            tagPresenceList = listOf(0x5F19, 0x5F26)
        }

        val copy = roundTrip(original)

        assertEquals("none", copy.endorsementsAndObservations)
        assertEquals("20120415103000", copy.dateAndTimeOfPersonalization)
        assertEquals("20120415", copy.dateOfIssue)
        val image = copy.imageOfFront
        assertNotNull(image)
        assertEquals(4, image!!.width)
        assertEquals(3, image.height)
        assertNull(copy.imageOfRear)
        assertEquals("UTOPIA PASSPORT OFFICE", copy.issuingAuthority)
        assertEquals(listOf("CHILD ONE", "CHILD TWO"), copy.namesOfOtherPersons)
        assertEquals("SN-0001", copy.personalizationSystemSerialNumber)
        assertEquals("exempt", copy.taxOrExitRequirements)
        assertEquals(0x6C, copy.tag)
        assertEquals(listOf(0x5F19, 0x5F26), copy.tagPresenceList)
    }

    @Test
    fun additionalDocumentDetails_nullsSurvive() {
        val original = AdditionalDocumentDetails().apply {
            namesOfOtherPersons = null
            tagPresenceList = null
            issuingAuthority = "UTOPIA PASSPORT OFFICE"
        }

        val copy = roundTrip(original)

        assertNull(copy.namesOfOtherPersons)
        assertNull(copy.tagPresenceList)
        assertNull(copy.imageOfFront)
        assertEquals("UTOPIA PASSPORT OFFICE", copy.issuingAuthority)
    }
}
