package io.github.munkchunk.passportreader.verification

import io.github.munkchunk.passportreader.model.CheckResult
import io.github.munkchunk.passportreader.model.CheckVerdict
import org.jmrtd.BACKey
import org.jmrtd.protocol.EACCAResult
import org.jmrtd.protocol.EACTAResult
import java.security.cert.Certificate

/**
 * What a read has established so far about a document's authenticity.
 *
 * This is the mutable accumulator the read path writes into as each check
 * completes. It is deliberately not the type callers see: it is mapped to
 * [io.github.munkchunk.passportreader.model.VerificationReport] once the read
 * finishes, which is immutable, parcelable and free of JMRTD types.
 *
 * Six of the eight checks start at [CheckVerdict.UNKNOWN]; [sac] and [ca]
 * start null. That asymmetry is load-bearing rather than untidy. The read path
 * tests `cs?.verdict == UNKNOWN` to mean "nothing has claimed this yet, so my
 * result stands", and starting it null breaks that guard silently. Meanwhile
 * SAC and CA genuinely have no starting verdict: a document that never
 * attempts PACE should report nothing for it, not UNKNOWN.
 */
internal class VerificationState {

    /** Basic Access Control. */
    var bac: CheckResult? = null

    /** Supplemental Access Control, i.e. PACE. */
    var sac: CheckResult? = null

    /** Hash table: every data group hash against the SOd. */
    var ht: CheckResult? = null

    /** Document signer signature over the SOd. */
    var ds: CheckResult? = null

    /** Certificate path from the document signer to a trust anchor. */
    var cs: CheckResult? = null

    /** Active Authentication. */
    var aa: CheckResult? = null

    /** Chip Authentication. */
    var ca: CheckResult? = null

    /** Terminal Authentication, which this library does not perform. */
    var eac: CheckResult? = null

    /** Per data group hash comparison, keyed by data group number. */
    var hashResults: MutableMap<Int, HashMatchResult>? = null

    /** The chain built during [cs], document signer first. */
    var certificateChain: List<Certificate>? = null

    /** Keys tried while establishing BAC. Retained for diagnostics only. */
    var triedBacEntries: List<BACKey>? = null

    var eacResult: EACTAResult? = null
    var caResult: EACCAResult? = null

    init {
        // Runs after the property initialisers above, deliberately: Kotlin
        // executes these in declaration order, so an init block placed before
        // them would be undone by `= null`.
        //
        // Matches the contract the read path was written against: everything
        // setAll touches begins UNKNOWN, so a later check can tell whether it
        // is the first to reach a conclusion.
        setAll(CheckVerdict.UNKNOWN, null)
    }

    /**
     * Records a verdict together with the evidence it rests on.
     *
     * Paired rather than left as two assignments so a verdict and the material
     * behind it cannot drift apart: a FAILED chain with a stale certificate
     * list attached is worse than no list at all.
     */
    fun setBac(verdict: CheckVerdict, reason: String?, tried: List<BACKey>?) {
        bac = CheckResult(verdict, reason)
        triedBacEntries = tried
    }

    fun setCs(verdict: CheckVerdict, reason: String?, chain: List<Certificate>?) {
        cs = CheckResult(verdict, reason)
        certificateChain = chain
    }

    fun setHt(verdict: CheckVerdict, reason: String?, hashes: MutableMap<Int, HashMatchResult>?) {
        ht = CheckResult(verdict, reason)
        hashResults = hashes
    }

    fun setEac(verdict: CheckVerdict, reason: String?, result: EACTAResult?) {
        eac = CheckResult(verdict, reason)
        eacResult = result
    }

    fun setCa(verdict: CheckVerdict, reason: String?, result: EACCAResult?) {
        ca = CheckResult(verdict, reason)
        caResult = result
    }

    /**
     * Sets every check that a read can reach to the same verdict.
     *
     * Used when something fails early enough that no individual check got a
     * chance to run. [sac] and [ca] are left alone: both are settled before
     * this point and overwriting them would discard what was actually learned.
     */
    fun setAll(verdict: CheckVerdict, reason: String?) {
        val result = CheckResult(verdict, reason)
        aa = result
        bac = result
        cs = result
        ds = result
        ht = result
        eac = result
    }
}

/**
 * A stored hash from the SOd against the one computed from the data group.
 *
 * [computedHash] is null when the data group could not be read or hashed, in
 * which case there is nothing to compare and [isMatch] is false.
 *
 * [content] is the data group exactly as hashed. Anything that must rely on a
 * group's signed contents parses [verifiedContent], never a copy read
 * separately: a chip can refuse one read and answer the next differently.
 */
internal class HashMatchResult(
    val storedHash: ByteArray?,
    val computedHash: ByteArray?,
    private val content: ByteArray? = null,
) {
    /** The bytes that were hashed, when they matched the signed hash; null otherwise. */
    val verifiedContent: ByteArray?
        get() = content?.takeIf { isMatch }

    val isMatch: Boolean
        get() = storedHash != null && computedHash != null && storedHash.contentEquals(computedHash)

    override fun toString(): String =
        "HashMatchResult(match=$isMatch, stored=${storedHash?.size ?: 0} bytes, " +
            "computed=${computedHash?.size ?: 0} bytes)"
}
