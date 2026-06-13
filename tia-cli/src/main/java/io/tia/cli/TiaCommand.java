package io.tia.cli;

import picocli.CommandLine.Command;

@Command(name = "tia", mixinStandardHelpOptions = true, version = "tia 0.1.0",
        subcommands = { IndexCommand.class, ImpactCommand.class, FlakyCommand.class })
public class TiaCommand implements Runnable {
    @Override public void run() { System.out.println("Usage: tia [index|impact|flaky] --help"); }
}
