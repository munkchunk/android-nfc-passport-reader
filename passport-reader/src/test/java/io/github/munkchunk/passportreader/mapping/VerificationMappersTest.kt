package io.github.munkchunk.passportreader.mapping

import io.github.munkchunk.passportreader.model.CertificateRole
import io.github.munkchunk.passportreader.model.CheckResult
import io.github.munkchunk.passportreader.model.CheckVerdict
import io.github.munkchunk.passportreader.verification.HashMatchResult
import io.github.munkchunk.passportreader.verification.TestDocuments
import io.github.munkchunk.passportreader.verification.VerificationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.Certificate
import java.util.TreeMap

/** The internal verification state as the public report presents it. */
class VerificationMappersTest {

    private fun roles(chain: List<Certificate>?): List<CertificateRole>? {
        val state = VerificationState().apply { setCs(CheckVerdict.SUCCEEDED, null, chain) }
        return state.toReport().chainCertificates?.map { it.role }
    }

    @Test
    fun `a chain that reached its anchor runs signer, links, CSCA`() {
        val pki = TestDocuments.pki()
        val other = TestDocuments.pki()
        assertEquals(
            listOf(CertificateRole.DOCUMENT_SIGNER, CertificateRole.CSCA),
            roles(listOf(pki.signer, pki.csca)),
        )
        assertEquals(
            listOf(CertificateRole.DOCUMENT_SIGNER, CertificateRole.LINK, CertificateRole.CSCA),
            roles(listOf(pki.signer, other.csca, pki.csca)),
        )
    }

    @Test
    fun `a failed chain is the document signer alone, never a CSCA`() {
        // What the chain check returns when no path reaches a trust anchor.
        assertEquals(listOf(CertificateRole.DOCUMENT_SIGNER), roles(listOf(TestDocuments.pki().signer)))
    }

    @Test
    fun `no document signer gives no chain`() {
        assertEquals(emptyList<CertificateRole>(), roles(emptyList()))
        assertNull(roles(null))
    }

    @Test
    fun `certificate details are read from the certificate`() {
        val pki = TestDocuments.pki(country = "GB")
        val state = VerificationState().apply { setCs(CheckVerdict.SUCCEEDED, null, listOf(pki.signer, pki.csca)) }

        val signer = state.toReport().chainCertificates!!.first()

        assertEquals("GB", signer.subjectCountry)
        assertEquals(pki.signer.subjectX500Principal.getName("RFC2253"), signer.subjectDn)
        assertEquals(pki.csca.subjectX500Principal.getName("RFC2253"), signer.issuerDn)
        assertEquals(pki.signer.notAfter.time, signer.validUntilEpochMs)
        assertEquals("prime256v1", signer.ecCurve) // secp256r1, under the name its OID is registered as
        assertEquals(64, signer.sha256Fingerprint.length)
        assertEquals(40, signer.sha1Fingerprint.length)
    }

    @Test
    fun `an unread group is not compared, and does not match`() {
        val hashes = TreeMap<Int, HashMatchResult>().apply {
            put(2, HashMatchResult(byteArrayOf(0x0A, 0x0B), byteArrayOf(0x0A, 0x0B)))
            put(1, HashMatchResult(byteArrayOf(0x01), byteArrayOf(0x02)))
            put(3, HashMatchResult(byteArrayOf(0x03), null))
        }
        val state = VerificationState().apply { setHt(CheckVerdict.SUCCEEDED, null, hashes) }

        val report = state.toReport().hashes!!

        assertEquals(listOf(1, 2, 3), report.map { it.dataGroup })
        with(report[0]) { assertTrue(compared); assertFalse(matches) }
        with(report[1]) { assertTrue(compared); assertTrue(matches); assertEquals("0A0B", expectedHex) }
        with(report[2]) { assertFalse(compared); assertFalse(matches); assertNull(actualHex) }
    }

    @Test
    fun `each check lands in its named field`() {
        fun r(reason: String) = CheckResult(CheckVerdict.FAILED, reason)
        val state = VerificationState().apply {
            bac = r("bac"); sac = r("sac"); ds = r("ds"); cs = r("cs")
            ht = r("ht"); ca = r("ca"); aa = r("aa"); eac = r("eac")
        }

        val report = state.toReport()

        assertEquals(
            listOf("bac", "sac", "ds", "cs", "ht", "ca", "aa", "eac"),
            listOf(
                report.basicAccessControl, report.pace, report.documentSignature, report.certificateChain,
                report.dataGroupHashes, report.chipAuthentication, report.activeAuthentication,
                report.terminalAuthentication,
            ).map { it?.reason },
        )
    }
}
