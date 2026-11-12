package com.debangshu.webhooks.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Benchmarks print their tables to the console and also append them to target/benchmark-results.md. */
final class BenchmarkReport {

    private static final Path FILE = Path.of("target", "benchmark-results.md");

    private BenchmarkReport() {
    }

    static void write(String markdown) {
        System.out.println(markdown);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, markdown + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
