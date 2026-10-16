package com.gyeongsan.cabinet.adapter.out.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

public class ProcessRssMetrics implements MeterBinder {

    private final Path statusFile;

    public ProcessRssMetrics() {
        this(Path.of("/proc/self/status"));
    }

    ProcessRssMetrics(Path statusFile) {
        this.statusFile = statusFile;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (readKb("VmRSS:").isEmpty()) {
            return;
        }
        Gauge.builder("chatbot.process.rss.bytes", this, m -> m.readKb("VmRSS:").orElse(0) * 1024.0)
                .description("프로세스 상주 메모리(RSS). 네이티브 메모리(ONNX 모델)를 포함한다")
                .baseUnit("bytes")
                .register(registry);
        Gauge.builder(
                        "chatbot.process.rss.peak.bytes",
                        this,
                        m -> m.readKb("VmHWM:").orElse(0) * 1024.0)
                .description("프로세스 상주 메모리의 최고치(VmHWM)")
                .baseUnit("bytes")
                .register(registry);
    }

    OptionalLong readKb(String key) {
        try {
            for (String line : Files.readAllLines(statusFile, StandardCharsets.UTF_8)) {
                if (line.startsWith(key)) {
                    String digits = line.substring(key.length()).replaceAll("[^0-9]", "");
                    return digits.isEmpty()
                            ? OptionalLong.empty()
                            : OptionalLong.of(Long.parseLong(digits));
                }
            }
        } catch (IOException | RuntimeException e) {
            return OptionalLong.empty();
        }
        return OptionalLong.empty();
    }

    public static OptionalLong currentRssKb() {
        return new ProcessRssMetrics().readKb("VmRSS:");
    }

    public static OptionalLong peakRssKb() {
        return new ProcessRssMetrics().readKb("VmHWM:");
    }
}
