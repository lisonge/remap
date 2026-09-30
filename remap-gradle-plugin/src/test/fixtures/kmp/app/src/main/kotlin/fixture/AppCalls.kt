package fixture

import android.os.Bundle

fun consume(bundle: Bundle): Int = read(bundle) + inlineRead(bundle) + commonValue()

fun consumeHidden(value: BundleHidden): BundleHidden = echoList(echoArray(arrayOf(echo(value))).toList()).single()
