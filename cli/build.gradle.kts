// :cli — nof-cli, an offline inspector for captured note protos (docs/cli.md).
// Built on :core; never touches the network.
//   ./gradlew :cli:installDist && cli/build/install/nof-cli/bin/nof-cli --help
//   ./gradlew :cli:run --args="summary /path/to/note.proto"
plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

kotlin {
  jvmToolchain(17)
}

application {
  applicationName = "nof-cli"
  mainClass.set("com.example.applenotes.cli.MainKt")
}

// `./gradlew :cli:run --args=...` resolves relative paths from where Gradle
// was started, not from cli/.
tasks.named<JavaExec>("run") {
  workingDir = rootProject.projectDir
}

dependencies {
  implementation(project(":core"))

  testImplementation(libs.junit)
  testImplementation(testFixtures(project(":core")))
}
