package io.tia.cli.fixture;

/** E2E-1 fixture: when {@link #run()} executes, the {@code dead} branch stays uncovered. */
public class Probe implements Runnable {
    public int out;

    @Override
    public void run() {
        int x = 10;
        int y = x * 2;
        if (y < 0) {
            out = -1;   // unreached
        } else {
            out = y;
        }
    }
}
