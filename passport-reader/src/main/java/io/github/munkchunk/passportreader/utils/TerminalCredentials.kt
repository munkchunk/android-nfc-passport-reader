package io.github.munkchunk.passportreader.utils

import org.jmrtd.cert.CVCAuthorizationTemplate
import org.jmrtd.cert.CVCPrincipal
import org.jmrtd.cert.CardVerifiableCertificate
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * What Terminal Authentication needs for one of the chip's CVCAs: the
 * terminal's private key, and its certificate chain in the order the chip
 * verifies it - first the certificate [caReference] issued, last the
 * terminal's own (BSI TR-03110-3; JMRTD's EACTAProtocol checks the same).
 */
internal class TerminalCredentials(
    val caReference: CVCPrincipal,
    val privateKey: PrivateKey,
    val chain: List<CardVerifiableCertificate>,
)

/** A private key and the certificates stored with it, in whatever order they were stored. */
internal class TerminalKeyEntry(
    val privateKey: PrivateKey,
    val certificates: List<Certificate>,
)

/**
 * The key entries in [keyStores]: each alias holding a private key, with its
 * certificate chain. Keys are read with an empty password, the convention
 * [io.github.munkchunk.passportreader.trust.CscaTrustStore.addCvcaKeyStore]
 * has always had.
 */
internal fun terminalKeyEntries(keyStores: List<KeyStore>): List<TerminalKeyEntry> =
    keyStores.flatMap { store ->
        store.aliases().toList().filter { store.isKeyEntry(it) }.mapNotNull { alias ->
            val key = store.getKey(alias, CharArray(0)) as? PrivateKey ?: return@mapNotNull null
            TerminalKeyEntry(key, store.getCertificateChain(alias)?.toList().orEmpty())
        }
    }

/**
 * Chooses credentials for the chip. [caReferences] are EF.CVCA's, most recent
 * first (TR-03110-3 A.7.2.4), and are tried in that order; the first key
 * entry whose certificates form a chain from that CVCA to an inspection
 * system certificate wins.
 *
 * @return null when no entry fits any of the chip's CVCAs.
 */
internal fun chooseTerminalCredentials(
    caReferences: List<CVCPrincipal>,
    entries: List<TerminalKeyEntry>,
): TerminalCredentials? {
    for (caReference in caReferences) {
        for (entry in entries) {
            val chain = terminalChain(caReference, entry.certificates) ?: continue
            return TerminalCredentials(caReference, entry.privateKey, chain)
        }
    }
    return null
}

/**
 * Orders [certificates] into the chain Terminal Authentication sends: start at
 * the one [caReference] issued, follow each holder to the certificate it
 * issued, and end at an inspection system (IS) certificate. A self-signed
 * CVCA certificate among them is left out, as the chip already holds its CVCA.
 *
 * @return null when the certificates do not form such a chain.
 */
internal fun terminalChain(
    caReference: CVCPrincipal,
    certificates: List<Certificate>,
): List<CardVerifiableCertificate>? {
    val candidates = certificates.filterIsInstance<CardVerifiableCertificate>()
        .filter { it.authorityReference != it.holderReference }
    val chain = mutableListOf<CardVerifiableCertificate>()
    var issuer = caReference
    while (chain.size < candidates.size) {
        val next = candidates.firstOrNull { it.authorityReference == issuer && it !in chain } ?: break
        chain += next
        issuer = next.holderReference
    }
    val endsAtTerminal = chain.lastOrNull()?.authorizationTemplate?.role == CVCAuthorizationTemplate.Role.IS
    return chain.takeIf { endsAtTerminal }
}
