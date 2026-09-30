package fixture;

import android.os.Bundle;
import li.songe.remap.RemapType;
import li.songe.remap.RemapMethod;
import li.songe.remap.RemapStub;

@RemapType(Bundle.class)
public class BundleHidden {
    public static final BundleHidden EMPTY = RemapStub.value();
    @RemapMethod("getInt")
    public int readInt(String key) { throw new AssertionError(); }
    public void putAll(BundleHidden other) { throw new AssertionError(); }
}
