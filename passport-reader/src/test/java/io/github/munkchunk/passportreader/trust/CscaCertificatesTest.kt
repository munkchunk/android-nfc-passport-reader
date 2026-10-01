package io.github.munkchunk.passportreader.trust

import io.github.munkchunk.passportreader.verification.TestDocuments
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Loading CSCA certificates into a [CscaTrustStore]: which become anchors,
 * which are only searched while building a path, and which are left out.
 */
class CscaCertificatesTest {

    private val one = TestDocuments.pki(country = "UT")
    private val two = TestDocuments.pki(country = "UU")

    private fun pem(vararg certificates: X509Certificate, between: String = "\n"): String =
        certificates.joinToString(between) { certificate ->
            "-----BEGIN CERTIFICATE-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded) +
                "\n-----END CERTIFICATE-----"
        }

    private fun domesticUseOnlyRoot(): X509Certificate {
        val keys = TestDocuments.ecKeys()
        val name = X500Name("C=UT,O=Domestic Use Only,CN=Test CSCA UT")
        val holder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(DOMESTIC_SERIAL), TestDocuments.date(2020, 1, 1), TestDocuments.date(2040, 1, 1),
            name, keys.public,
        ).build(JcaContentSignerBuilder("SHA256withECDSA").build(keys.private))
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun CscaTrustStore.pathCertificates() =
        certStores.flatMap { it.getCertificates(null) }.toSet()

    @Test
    fun `every PEM block is read, whatever lies between them`() {
        val text = "Master list export\n" + pem(one.csca, two.csca, between = "\nsubject: something\n\n") + "\ntrailer"
        assertEquals(listOf(one.csca, two.csca), CscaCertificates.fromPem(text))
    }

    @Test
    fun `self-signed roots are anchors, and roots and links are searched`() {
        val store = CscaTrustStore()
        val (anchored, linked) = CscaCertificates.addBundles(store, listOf(one.csca), listOf(two.signer))
        assertEquals(1 to 1, anchored to linked)
        assertEquals(setOf(one.csca), store.anchors.map { it.trustedCert }.toSet())
        assertEquals(setOf(one.csca, two.signer), store.pathCertificates())
    }

    @Test
    fun `a root that is not self-signed is not anchored or searched`() {
        // Anchoring it would let a path end early at a certificate that should
        // itself have been validated.
        val store = CscaTrustStore()
        CscaCertificates.addBundles(store, listOf(one.csca, one.signer), emptyList())
        assertEquals(setOf(one.csca), store.anchors.map { it.trustedCert }.toSet())
        assertEquals(setOf(one.csca), store.pathCertificates())
    }

    @Test
    fun `domestic-use-only certificates and duplicates are left out`() {
        val store = CscaTrustStore()
        val roots = listOf(one.csca, domesticUseOnlyRoot(), one.csca)
        val (anchored, _) = CscaCertificates.addBundles(store, roots, emptyList())
        assertEquals(1, anchored)
        assertEquals(1, store.anchors.size)
    }

    @Test
    fun `a damaged block fails the bundle rather than vanishing`() {
        // Skipped quietly, that country's documents would fail passive
        // authentication with nothing to say why.
        val damaged = pem(one.csca, two.csca).replaceFirst("\n", "\n:stray header\n")
        assertThrows(IllegalArgumentException::class.java) { CscaCertificates.fromPem(damaged) }
    }

    @Test
    fun `no key store file is not an error`() {
        assertNull(CscaCertificates.keyStore(File("does-not-exist.ks"), CharArray(0)))
    }

    @Test
    fun `a key store's certificates are anchored and searched`() {
        val file = File.createTempFile("csca", ".ks").apply { deleteOnExit() }
        KeyStore.getInstance("PKCS12").apply {
            load(null)
            setCertificateEntry("one", one.csca)
            setCertificateEntry("two", two.csca)
            file.outputStream().use { store(it, PASSWORD) }
        }
        val store = CscaTrustStore()
        val keyStore = checkNotNull(CscaCertificates.keyStore(file, PASSWORD))
        assertEquals(2, CscaCertificates.addKeyStore(store, keyStore))
        assertEquals(setOf(one.csca, two.csca), store.anchors.map { it.trustedCert }.toSet())
        assertEquals(setOf(one.csca, two.csca), store.pathCertificates())
    }

    @Test
    fun `a key store file that cannot be opened throws`() {
        // The caller logs it and carries on with the bundled certificates.
        val file = File.createTempFile("csca", ".ks").apply { deleteOnExit(); writeText("not a key store") }
        assertThrows(Exception::class.java) { CscaCertificates.keyStore(file, PASSWORD) }
    }

    @Test
    fun `the bundled master list loads as the device reports it`() {
        // The figures the device logs (508 anchors, 79 links); they must not
        // move unless
        // the bundles are refreshed. The one root of 509 left out is the UK's
        // "Domestic Use Only Country Signing Authority"; the bundle has no
        // duplicates.
        val roots = CscaCertificates.fromPem(File(ASSETS, "trust-anchors.pem").readText())
        val links = CscaCertificates.fromPem(File(ASSETS, "link-certificates.pem").readText())
        assertEquals(509, roots.size)
        assertEquals(79, links.size)
        val store = CscaTrustStore()
        assertEquals(508 to 79, CscaCertificates.addBundles(store, roots, links))
        assertTrue(store.anchors.all { it.trustedCert.subjectX500Principal == it.trustedCert.issuerX500Principal })
    }

    private companion object {
        const val DOMESTIC_SERIAL = 999L
        const val ASSETS = "src/main/assets/csca"
        val PASSWORD = "test".toCharArray()
    }
}
