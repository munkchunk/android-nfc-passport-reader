package io.github.munkchunk.passportreader.mapping

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.munkchunk.passportreader.model.CertificateRole
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Checks the country is read off a real CSCA certificate.
 *
 * The certificate is the GBR root from the bundled BSI master list, whose
 * common name is the generic "Country Signing Authority" that ICAO 9303
 * suggests. That is exactly why the country is worth surfacing: the common
 * name alone identifies nothing.
 */
@RunWith(AndroidJUnit4::class)
class CertificateCountryTest {

    private fun gbCsca(): X509Certificate {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("certs/csca-gb.pem").use { it.readBytes() }
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    @Test
    fun readsCountryFromSubject() {
        val csca = gbCsca().toDetails(CertificateRole.CSCA)
        assertEquals("GB", csca.subjectCountry)
    }

    @Test
    fun stillCarriesTheGenericCommonName() {
        // Guards the premise: if this ever stops being generic the country is
        // still correct, but the reason for showing it would have changed.
        val csca = gbCsca().toDetails(CertificateRole.CSCA)
        assertEquals(
            "CN=Country Signing Authority,O=UKKPA,C=GB",
            csca.subjectDn,
        )
    }
}
