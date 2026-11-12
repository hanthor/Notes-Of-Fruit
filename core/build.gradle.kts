// :core — the reverse-engineered protocol code with no Android dependency:
// proto/ (protobuf wire format, topotext CRDT reader/writer, MergeableData
// tables) and client/ (CloudKit Web Services client over Ktor). The Android
// app depends on it; so does the :cli inspector. Tests run as plain JVM tests:
//   ./gradlew :core:test
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  // Fixture builders (Fx, testUuid, loadFixture) and the captured protos are
  // shared with :app's unit tests through testFixtures(project(":core")).
  `java-test-fixtures`
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  // api: AppleNotesClient's public signatures expose HttpClient and JsonElement.
  api(libs.ktor.client.core)
  api(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
