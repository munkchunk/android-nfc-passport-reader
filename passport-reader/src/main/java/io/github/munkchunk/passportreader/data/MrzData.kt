package io.github.munkchunk.passportreader.data

import android.os.Parcel
import android.os.Parcelable

/**
 * Holds the raw MRZ strings for TD3 (passport): full (two lines), line1, line2.
 * Parcelable with only simple strings (safe to pass around).
 */
class MrzData(
    private val full: String,
    private val line1: String,
    private val line2: String
) : Parcelable {

    // Explicit getters per your requirement
    fun getFull(): String = full
    fun getLine1(): String = line1
    fun getLine2(): String = line2

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(full)
        dest.writeString(line1)
        dest.writeString(line2)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<MrzData> = object : Parcelable.Creator<MrzData> {
            override fun createFromParcel(p: Parcel): MrzData =
                MrzData(
                    p.readString().orEmpty(),
                    p.readString().orEmpty(),
                    p.readString().orEmpty()
                )
            override fun newArray(size: Int): Array<MrzData?> = arrayOfNulls(size)
        }

    }
}
