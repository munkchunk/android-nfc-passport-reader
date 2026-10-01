package io.github.munkchunk.passportreader.utils

import org.jmrtd.cert.CVCAuthorizationTemplate.Permission
import org.jmrtd.cert.CVCAuthorizationTemplate.Role
import org.jmrtd.cert.CVCPrincipal
import org.jmrtd.cert.CardVerifiableCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Date

/**
 * Choosing Terminal Authentication credentials for a chip's CVCA references,
 * and ordering the chain as the chip verifies it (BSI TR-03110-3). The
 * certificates are built with JMRTD's own constructor and are not signed by
 * anything real: the choice depends only on references and roles.
 */
class TerminalCredentialsTest {

    private val keys: KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private val cvca = CVCPrincipal("DECVCA00001")
    private val newerCvca = CVCPrincipal("DECVCA00002")
    private val dv = CVCPrincipal("DEDVTEST00001")
    private val terminal = CVCPrincipal("DEISTEST00001")

    private fun cert(authority: CVCPrincipal, holder: CVCPrincipal, role: Role) = CardVerifiableCertificate(
        authority, holder, keys.public, "SHA256withECDSA",
        Date(0), Date(Long.MAX_VALUE / 2), role, Permission.READ_ACCESS_DG3_AND_DG4, ByteArray(SIGNATURE_BYTES),
    )

    private val cvcaCert = cert(cvca, cvca, Role.CVCA)
    private val dvCert = cert(cvca, dv, Role.DV_D)
    private val isCert = cert(dv, terminal, Role.IS)

    private fun entry(vararg certificates: CardVerifiableCertificate) =
        TerminalKeyEntry(keys.private, certificates.toList())

    @Test
    fun `a chain stored terminal-first is sent CVCA-first`() {
        // KeyStore.getCertificateChain gives chains leaf-first, and JMRTD
        // rejects that order.
        assertEquals(listOf(dvCert, isCert), terminalChain(cvca, listOf(isCert, dvCert)))
    }

    @Test
    fun `a self-signed CVCA certificate is left out`() {
        assertEquals(listOf(dvCert, isCert), terminalChain(cvca, listOf(isCert, dvCert, cvcaCert)))
    }

    @Test
    fun `a chain that does not end at an inspection system is refused`() {
        assertNull(terminalChain(cvca, listOf(dvCert)))
    }

    @Test
    fun `a chain from another CVCA is refused`() {
        assertNull(terminalChain(newerCvca, listOf(isCert, dvCert)))
    }

    @Test
    fun `a key entry for another CVCA is not chosen`() {
        // The first private key found is not good enough: it must chain to a
        // CVCA the chip trusts.
        assertNull(chooseTerminalCredentials(listOf(newerCvca), listOf(entry(isCert, dvCert))))
    }

    @Test
    fun `the chip's alternative CVCA is used when the first has no credentials`() {
        val chosen = chooseTerminalCredentials(listOf(newerCvca, cvca), listOf(entry(isCert, dvCert)))
        assertEquals(cvca, chosen?.caReference)
        assertEquals(listOf(dvCert, isCert), chosen?.chain)
        assertSame(keys.private, chosen?.privateKey)
    }

    @Test
    fun `the most recent CVCA is preferred when both have credentials`() {
        val newerDv = cert(newerCvca, dv, Role.DV_D)
        val chosen = chooseTerminalCredentials(
            listOf(newerCvca, cvca),
            listOf(entry(isCert, dvCert), entry(isCert, newerDv)),
        )
        assertEquals(newerCvca, chosen?.caReference)
        assertEquals(listOf(newerDv, isCert), chosen?.chain)
    }

    @Test
    fun `nothing to choose from gives no credentials`() {
        assertNull(chooseTerminalCredentials(listOf(cvca), emptyList()))
        assertNull(chooseTerminalCredentials(emptyList(), listOf(entry(isCert, dvCert))))
    }

    private companion object {
        /** Any bytes: nothing here verifies the signature. */
        const val SIGNATURE_BYTES = 64
    }
}
