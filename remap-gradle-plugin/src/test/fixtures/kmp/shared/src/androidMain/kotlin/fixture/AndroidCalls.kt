package fixture

import android.os.Bundle

fun read(bundle: Bundle): Int = (bundle as BundleHidden).readInt("answer")
fun merge(bundle: Bundle, other: Bundle) = (bundle as BundleHidden).putAll(other as BundleHidden)
fun empty(): Bundle = BundleHidden.EMPTY as Bundle
@Suppress("NOTHING_TO_INLINE")
inline fun inlineRead(bundle: Bundle): Int = (bundle as BundleHidden).readInt("answer")

// Exercise remapped descriptors/signatures, as well as metadata at the library boundary.
fun echo(value: BundleHidden): BundleHidden = value
fun echoArray(value: Array<BundleHidden>): Array<BundleHidden> = value
fun echoList(value: List<BundleHidden>): List<BundleHidden> = value
