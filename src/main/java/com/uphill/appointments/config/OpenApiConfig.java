package com.uphill.appointments.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI appointmentsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Uphill Appointments API")
                .description("Schedules medical appointments: auto-assigns a doctor and room, "
                        + "notifies the patient, and lists booked appointments.")
                .version("v1"));
    }
}
