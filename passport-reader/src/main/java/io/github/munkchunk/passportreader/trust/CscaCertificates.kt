package io.github.munkchunk.passportreader.trust

import io.github.munkchunk.passportreader.crypto.BouncyCastleSupport
import java.io.File
import java.security.KeyStore
import java.security.cert.CertStore
import java.security.cert.CertificateFactory
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Turns certificate files into [CscaTrustStore] entries: the CSCA bundles
 * shipped in the library's assets, and a PKCS12 key store a consuming app may
 * leave in its files directory.
 *
 * Roots and link certificates are kept apart as CscaTrustStore requires.
 * Only self-signed roots become anchors, and paths are built through those
 * roots and the links; a root that is not self-signed is not used at all.
 */
internal object CscaCertificates {

    /**
     * Every certificate in [pem]: each `-----BEGIN CERTIFICATE-----` block is
     * decoded on its own, so text between blocks does no harm. Parsed with
     * BouncyCastle, as every getInstance here is: many CSCAs use explicit EC
     * curve parameters, which the JDK's own parser refuses.
     *
     * @throws IllegalArgumentException when a block is damaged. Skipping it
     *   would drop that country's CSCA without a word, and its documents would
     *   then fail passive authentication with nothing to say why.
     */
    fun fromPem(pem: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509", BouncyCastleSupport.provider)
        val blocks = PEM_BLOCK.findAll(pem).toList()
        val markers = pem.split(BEGIN_MARKER).size - 1
        require(blocks.size == markers) { "$markers certificate blocks, but only ${blocks.size} readable" }
        return blocks.map { block ->
            val der = Base64.getMimeDecoder().decode(block.groupValues[1])
            factory.generateCertificate(der.inputStream()) as X509Certificate
        }
    }

    /**
     * Adds bundled roots and links to [trustStore]. Certificates marked for
     * domestic use only are left out - they do not sign travel documents - and
     * so is any second copy of a certificate. Roots that are not self-signed
     * are not anchored.
     *
     * @return how many roots were anchored and how many links added.
     */
    fun addBundles(
        trustStore: CscaTrustStore,
        roots: List<X509Certificate>,
        links: List<X509Certificate>,
    ): Pair<Int, Int> {
        val anchoredRoots = usable(roots).filter { it.isSelfSigned() }
        val usableLinks = usable(links)
        trustStore.addAnchors(anchoredRoots.map { TrustAnchor(it, null) })
        // addCertStore rather than addCertStoreWithSelfSignedAnchors: the
        // anchors are in already, and TrustAnchor has no equals, so adding
        // them a second time would genuinely double them.
        trustStore.addCertStore(certStore(anchoredRoots + usableLinks))
        return anchoredRoots.size to usableLinks.size
    }

    /**
     * The PKCS12 key store [file], opened with [password]; null when there is
     * no such file. A file that exists but cannot be opened throws.
     */
    fun keyStore(file: File, password: CharArray): KeyStore? {
        if (!file.isFile) return null
        return KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } }
    }

    /**
     * Adds every X.509 certificate in [keyStore] to [trustStore], both to
     * build paths through and as anchors.
     *
     * @return how many certificates were added.
     */
    fun addKeyStore(trustStore: CscaTrustStore, keyStore: KeyStore): Int {
        val certificates = keyStore.aliases().toList()
            .mapNotNull { keyStore.getCertificate(it) as? X509Certificate }
        trustStore.addCertStore(certStore(certificates))
        trustStore.addAnchors(certificates.map { TrustAnchor(it, null) })
        return certificates.size
    }

    private fun usable(certificates: List<X509Certificate>): List<X509Certificate> =
        certificates
            .filterNot { it.subjectX500Principal.name.contains(DOMESTIC_USE_ONLY, ignoreCase = true) }
            .distinctBy { it.encoded.toList() }

    private fun X509Certificate.isSelfSigned() = subjectX500Principal == issuerX500Principal

    private fun certStore(certificates: List<X509Certificate>): CertStore =
        CertStore.getInstance("Collection", CollectionCertStoreParameters(certificates))

    private const val BEGIN_MARKER = "-----BEGIN CERTIFICATE-----"
    private val PEM_BLOCK = Regex("$BEGIN_MARKER([A-Za-z0-9+/=\\s]+)-----END CERTIFICATE-----")
    private const val DOMESTIC_USE_ONLY = "Domestic Use Only"
}
