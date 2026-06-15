package io.tia.core.convert.fixture;

/**
 * Tiny fixture for {@code TestwiseConverterTest}: when {@link #run()} executes, some lines
 * are covered and the {@code value = -1} branch is NOT — so the converter must report
 * partial (not full) coverage for this source file.
 */
public class SampleTarget implements Runnable {
    public int value;

    @Override
    public void run() {
        int a = 1;
        int b = a + 2;
        if (b > 100) {
            value = -1;     // not reached → must NOT appear as covered
        } else {
            value = b;
        }
    }
}
