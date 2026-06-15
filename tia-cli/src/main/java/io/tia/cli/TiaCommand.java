package io.tia.cli;

import picocli.CommandLine.Command;

@Command(name = "tia", mixinStandardHelpOptions = true, versionProvider = VersionProvider.class,
        subcommands = { ConvertCommand.class, IndexCommand.class, ImpactCommand.class,
                FlakyCommand.class, ReportCommand.class })
public class TiaCommand implements Runnable {
    @Override public void run() { System.out.println("Usage: tia [convert|index|impact|flaky|report] --help"); }
}
