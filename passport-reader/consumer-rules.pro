# Add ProGuard rules here that should be consumed by the referencing app.

# --- Preserve Scuba SmartCard classes used by JMRTD ---
-keep class net.sf.scuba.** { *; }

# --- Preserve JMRTD classes ---
-keep class org.jmrtd.** { *; }

# --- Preserve BouncyCastle (required for crypto, ASN.1, SecureRandom, etc.) ---
-keep class org.bouncycastle.** { *; }

# BouncyCastle's LDAP cert store and DANE fetcher refer to JNDI, which Android
# does not have. Neither is reachable from this library; without this, R8
# stops on the missing classes in any app that minifies.
-dontwarn javax.naming.**

# --- If you reference your own reader classes via reflection ---
-keep class io.github.munkchunk.passportreader.** { *; }
