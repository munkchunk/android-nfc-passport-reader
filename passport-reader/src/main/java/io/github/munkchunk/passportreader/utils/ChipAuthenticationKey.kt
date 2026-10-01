package io.github.munkchunk.passportreader.utils

import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.SecurityInfo
import java.math.BigInteger
import java.security.PublicKey

/**
 * One of the chip's Chip Authentication keys and the protocols to try it
 * with, in order.
 *
 * [declared] is true when the protocols come from a ChipAuthenticationInfo,
 * false when they had to be inferred from the key's type.
 */
internal data class ChipAuthenticationKey(
    val keyId: BigInteger?,
    val publicKeyOid: String,
    val publicKey: PublicKey,
    val protocolOids: List<String>,
    val declared: Boolean,
)

/**
 * Plans Chip Authentication (ICAO 9303-11 §6.2) from DG14's SecurityInfos.
 *
 * Each ChipAuthenticationPublicKeyInfo is one key. A ChipAuthenticationInfo
 * names the protocol for a key, and §9.2.5-9.2.6 link the two by keyId, which
 * MUST be present on both when the chip has more than one key. Here an info
 * belongs to a key when their key agreement matches (DH or ECDH) and their
 * keyIds do not contradict each other: equal, or at least one absent.
 *
 * A key no info belongs to is still tried. DG14 SHALL contain the
 * ChipAuthenticationInfo (§9.2.8), but not every document does; for them
 * the protocol is inferred from the key type, 3DES first - JMRTD's own
 * default when given no protocol - then the AES variants of Tables 5 and 6.
 * A failed attempt leaves secure messaging as it was (§6.2.2), so trying
 * the next is safe.
 *
 * Keys with declared protocols come first. The caller stops at the first
 * success: a successful Chip Authentication restarts secure messaging with
 * new keys, so it must not run again.
 */
internal fun chipAuthenticationPlan(securityInfos: Collection<SecurityInfo>): List<ChipAuthenticationKey> {
    val infos = securityInfos.filterIsInstance<ChipAuthenticationInfo>()
    return securityInfos.filterIsInstance<ChipAuthenticationPublicKeyInfo>()
        .mapNotNull { key ->
            val agreement = keyAgreement(key.objectIdentifier) ?: return@mapNotNull null
            val matching = infos
                .filter { keyAgreementOfProtocol(it.objectIdentifier) == agreement && keyIdsAgree(it.keyId, key.keyId) }
            val declared = matching.map { it.objectIdentifier }.distinct()
            ChipAuthenticationKey(
                // A key without its own keyId takes its info's: the chip may expect it.
                keyId = key.keyId ?: matching.mapNotNull { it.keyId }.distinct().singleOrNull(),
                publicKeyOid = key.objectIdentifier,
                publicKey = key.subjectPublicKey,
                protocolOids = declared.ifEmpty { INFERRED_PROTOCOLS.getValue(agreement) },
                declared = declared.isNotEmpty(),
            )
        }
        .sortedByDescending { it.declared }
}

private fun keyIdsAgree(a: BigInteger?, b: BigInteger?): Boolean = a == null || b == null || a == b

/** id-PK-DH or id-PK-ECDH as the agreement it names; null for anything else. */
private fun keyAgreement(publicKeyOid: String): String? = when (publicKeyOid) {
    SecurityInfo.ID_PK_DH -> DH
    SecurityInfo.ID_PK_ECDH -> ECDH
    else -> null
}

/** An id-CA-* protocol's key agreement; null for anything else. */
private fun keyAgreementOfProtocol(protocolOid: String): String? = when (protocolOid) {
    in INFERRED_PROTOCOLS.getValue(DH) -> DH
    in INFERRED_PROTOCOLS.getValue(ECDH) -> ECDH
    else -> null
}

private const val DH = "DH"
private const val ECDH = "ECDH"

/** ICAO 9303-11 Tables 5 and 6, 3DES first. */
private val INFERRED_PROTOCOLS = mapOf(
    DH to listOf(
        SecurityInfo.ID_CA_DH_3DES_CBC_CBC,
        SecurityInfo.ID_CA_DH_AES_CBC_CMAC_128,
        SecurityInfo.ID_CA_DH_AES_CBC_CMAC_192,
        SecurityInfo.ID_CA_DH_AES_CBC_CMAC_256,
    ),
    ECDH to listOf(
        SecurityInfo.ID_CA_ECDH_3DES_CBC_CBC,
        SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128,
        SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_192,
        SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_256,
    ),
)
