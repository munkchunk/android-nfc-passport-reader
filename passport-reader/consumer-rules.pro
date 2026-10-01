# Add ProGuard rules here that should be consumed by the referencing app.

# --- Preserve Scuba SmartCard classes used by JMRTD ---
-keep class net.sf.scuba.** { *; }

# --- Preserve JMRTD classes ---
-keep class org.jmrtd.** { *; }

# --- Preserve BouncyCastle (required for crypto, ASN.1, SecureRandom, etc.) ---
-keep class org.bouncycastle.** { *; }

# --- If you reference your own reader classes via reflection ---
-keep class io.github.munkchunk.passportreader.** { *; }
