package li.songe.remap;

interface RemapStubFixture {
    byte BYTE_VALUE = RemapStub.value();
    short SHORT_VALUE = RemapStub.value();
    int INT_VALUE = RemapStub.value();
    long LONG_VALUE = RemapStub.value();
    float FLOAT_VALUE = RemapStub.value();
    double DOUBLE_VALUE = RemapStub.value();
    char CHAR_VALUE = RemapStub.value();
    boolean BOOLEAN_VALUE = RemapStub.value();
    String STRING_VALUE = RemapStub.value();
    Object OBJECT_VALUE = RemapStub.value();
    int[] ARRAY_VALUE = RemapStub.value();
}

final class RemapStubConsumer {
    private RemapStubConsumer() {
    }

    static int readInt() {
        return RemapStubFixture.INT_VALUE;
    }

    static String readString() {
        return RemapStubFixture.STRING_VALUE;
    }
}
