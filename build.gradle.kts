plugins {
	java
	id("com.diffplug.spotless") version "7.0.2"
}

group = "io.github.arrteann"
if (version == Project.DEFAULT_VERSION) {
	version = "0.1.0-SNAPSHOT"
}

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

spotless {
	java {
		target("src/**/*.java")
		removeUnusedImports()
		trimTrailingWhitespace()
		endWithNewline()
	}
}

tasks.register<Exec>("installToJadx") {
	group = "jadx"
	description = "Build the plugin JAR and install it into the sibling ../jadx build."
	dependsOn(tasks.jar)
	val isWindows = System.getProperty("os.name").lowercase().contains("win")
	val jadxBin = rootProject.layout.projectDirectory.file(
		if (isWindows) "../jadx/build/jadx/bin/jadx.bat" else "../jadx/build/jadx/bin/jadx"
	).asFile
	onlyIf { jadxBin.exists() }
	commandLine(jadxBin.absolutePath, "plugins", "--install-jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}
