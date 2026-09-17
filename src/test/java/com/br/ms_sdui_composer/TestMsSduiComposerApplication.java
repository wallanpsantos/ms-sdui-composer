package com.br.ms_sdui_composer;

import org.springframework.boot.SpringApplication;

public class TestMsSduiComposerApplication {

	public static void main(String[] args) {
		SpringApplication.from(MsSduiComposerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
