package com.uphill.appointments.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;

import lombok.RequiredArgsConstructor;

/**
 * Bridges Logback into the OTel SDK bean {@code spring-boot-starter-opentelemetry}
 * already builds (traces/metrics export works out of the box; log export
 * needs this one explicit step, since neither that starter nor core Spring
 * Boot attaches an appender for you). The appender itself is declared in
 * {@code logback-spring.xml}; this just supplies it the {@link OpenTelemetry}
 * instance once the application context is fully up. Events logged before
 * this runs are buffered by the appender and replayed, so exact timing here
 * isn't critical - {@link ApplicationReadyEvent} just needs to fire after the
 * OTel SDK bean exists, which it reliably does.
 */
@Component
@RequiredArgsConstructor
class OpenTelemetryLoggingConfig implements ApplicationListener<ApplicationReadyEvent> {

    private final OpenTelemetry openTelemetry;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
