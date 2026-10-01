package io.github.munkchunk.passportreader.trust

import java.security.KeyStore
import java.security.cert.CertStore
import java.security.cert.Certificate
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate

/**
 * The trust material passive authentication is validated against.
 *
 * Two distinct roles, and conflating them is the usual way to get passive
 * authentication subtly wrong:
 *
 *  - [anchors] terminate a certificate path. Only self-signed CSCA roots
 *    belong here; anchoring a link certificate would let a path terminate
 *    early at a certificate that was itself meant to be validated.
 *  - [certStores] are searched while *building* a path. Roots and link
 *    certificates both belong here, because a document signer may chain to a
 *    current root only by way of a link certificate from a superseded one.
 *
 * [cvcaKeyStores] hold inspection-system credentials for Terminal
 * Authentication: a private key and its CV certificate chain from an issuing
 * state's CVCA. Without them Terminal Authentication is reported as not
 * checked, and DG3 and DG4 are never read.
 *
 * Not thread-safe. It is built once on a background thread before any read
 * begins, then only read from.
 */
class CscaTrustStore {

    private val mutableAnchors = LinkedHashSet<TrustAnchor>()
    private val mutableCertStores = ArrayList<CertStore>()
    private val mutableCvcaKeyStores = ArrayList<KeyStore>()

    /** Self-signed CSCA roots, used to terminate certificate paths. */
    val anchors: Set<TrustAnchor> get() = mutableAnchors

    /** Certificates searched while building a path: roots and link certificates. */
    val certStores: List<CertStore> get() = mutableCertStores

    /** Inspection-system credentials for Terminal Authentication; see the class description. */
    val cvcaKeyStores: List<KeyStore> get() = mutableCvcaKeyStores

    fun clear() {
        mutableAnchors.clear()
        mutableCertStores.clear()
        mutableCvcaKeyStores.clear()
    }

    fun addAnchors(trustAnchors: Collection<TrustAnchor>) {
        mutableAnchors.addAll(trustAnchors)
    }

    fun addCertStore(certStore: CertStore) {
        mutableCertStores.add(certStore)
    }

    fun addCvcaKeyStore(keyStore: KeyStore) {
        mutableCvcaKeyStores.add(keyStore)
    }

    /**
     * Adds [certStore] for path building and promotes the self-signed
     * certificates it holds to trust anchors.
     *
     * Self-signed is decided on subject and issuer names rather than by
     * verifying the signature: this only selects which certificates are
     * *offered* as anchors, and the path validator checks signatures itself.
     */
    fun addCertStoreWithSelfSignedAnchors(certStore: CertStore) {
        addCertStore(certStore)
        val certificates: Collection<Certificate> =
            certStore.getCertificates(null).orEmpty()
        addAnchors(
            certificates
                .filterIsInstance<X509Certificate>()
                .filter { it.subjectX500Principal == it.issuerX500Principal }
                .map { TrustAnchor(it, null) },
        )
    }
}
