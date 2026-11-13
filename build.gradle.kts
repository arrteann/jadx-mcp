plugins {
	java
}

group = "io.github.arrteann"
version = "0.1.0-SNAPSHOT"

repositories {
	mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(11)
	options.encoding = "UTF-8"
}

dependencies {
	compileOnly("io.github.skylot:jadx-core:dev")
	compileOnly("com.google.code.gson:gson:2.11.0")
	compileOnly("org.slf4j:slf4j-api:2.0.16")

	testImplementation("io.github.skylot:jadx-core:dev")
	testImplementation("io.github.skylot:jadx-java-input:dev")
	testImplementation("com.google.code.gson:gson:2.11.0")
	testImplementation("org.slf4j:slf4j-simple:2.0.16")
	testImplementation(platform("org.junit:junit-bom:5.11.4"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testImplementation("org.assertj:assertj-core:3.27.2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
	useJUnitPlatform()
	testLogging {
		events("passed", "skipped", "failed")
	}
}

tasks.jar {
	archiveBaseName.set("jadx-mcp")
	manifest {
		attributes(
			"Implementation-Title" to "jadx-mcp",
			"Implementation-Version" to project.version,
		)
	}
}
