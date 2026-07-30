package li.songe.remap;

/**
 * Supplies placeholder values for compile-time API stubs.
 *
 * <p>Use {@link #value()} when a stub field requires an initializer but must not
 * become a compile-time constant. Code that references the field must be
 * bytecode-remapped before it runs; evaluating this placeholder directly is
 * always an error.
 */
public final class RemapStub {
    private RemapStub() {
    }

    /**
     * Prevents a stub declaration from exposing an inlineable constant value.
     *
     * @param <T> the value type inferred from the declaration
     * @return never returns
     * @throws AssertionError always, if an unremapped stub is evaluated
     */
    public static <T> T value() {
        throw new AssertionError("Remap stub was evaluated before bytecode remapping");
    }
}
