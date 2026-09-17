package com.br.ms_sdui_composer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MsSduiComposerApplicationTests {

	@Test
	void contextLoads() {
	}

}
