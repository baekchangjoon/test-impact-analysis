package io.tia.cli;

import picocli.CommandLine;

public final class Main {
    public static void main(String[] args) {
        System.exit(new CommandLine(new TiaCommand()).execute(args));
    }
}
