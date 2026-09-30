package fixture

fun legacyRead(bundle: android.os.Bundle) = (bundle as BundleHidden).readInt("legacy")
