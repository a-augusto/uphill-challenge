package com.uphill.appointments;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.uphill.appointments.support.TestcontainersConfig;

@SpringBootTest
@Import(TestcontainersConfig.class)
class AppointmentsApplicationTests {

	@Test
	void contextLoads() {
	}

}
