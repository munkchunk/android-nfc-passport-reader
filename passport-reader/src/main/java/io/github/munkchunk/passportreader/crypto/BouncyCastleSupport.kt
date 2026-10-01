package io.github.munkchunk.passportreader.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Provider
import java.security.Security

/**
 * The BouncyCastle provider this library performs its cryptography with.
 *
 * Android ships a cut-down BouncyCastle under the name "BC", missing
 * algorithms ePassports need -- ISO 9796-2 signatures for Active
 * Authentication among them -- so every call site passes [provider]
 * explicitly rather than relying on provider search order. [install] still
 * registers it, because a few JCA lookups inside dependencies resolve by name
 * rather than by instance.
 */
internal object BouncyCastleSupport {

    /** The provider instance to pass to `getInstance` calls. */
    val provider: Provider = BouncyCastleProvider()

    /**
     * Registers [provider] ahead of the platform's own. Idempotent, and safe
     * to call from several places during start-up.
     */
    @Synchronized
    fun install() {
        if (Security.getProvider(provider.name)?.javaClass == provider.javaClass) return
        Security.removeProvider(provider.name)
        Security.insertProviderAt(provider, 1)
    }

    /**
     * Runs [block] with [provider] first in the JCA search order, restoring the
     * previous order afterwards.
     *
     * Needed where an algorithm is looked up by name only and the platform
     * would otherwise answer first -- `KeyStore.getInstance("PKCS12")` being
     * the case in point. Scoped rather than a begin/end pair so the ordering
     * cannot be left disturbed by an early return or a throw.
     */
    @Synchronized
    fun <T> preferred(block: () -> T): T {
        val existing = Security.getProviders()
            .indexOfFirst { it.javaClass == provider.javaClass }
        if (existing < 0) {
            Security.insertProviderAt(provider, 1)
            return try {
                block()
            } finally {
                Security.removeProvider(provider.name)
            }
        }
        if (existing == 0) return block()

        Security.removeProvider(provider.name)
        Security.insertProviderAt(provider, 1)
        return try {
            block()
        } finally {
            Security.removeProvider(provider.name)
            Security.insertProviderAt(provider, existing + 1)
        }
    }
}
