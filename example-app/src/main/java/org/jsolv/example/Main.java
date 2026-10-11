package org.jsolv.example;

/** Runs the illustrative workflows; their label contracts are trusted. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        new ConfiguredLabelsWorkflow().run();
        new PublishingWorkflow().publishDraft();
    }
}
