// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.ksp) apply false
  // :core and :cli are plain Kotlin/JVM modules. Declaring the JVM plugin here
  // (not only in those modules) keeps ONE Kotlin Gradle plugin on the build
  // classpath, shared with AGP 9's built-in Kotlin in :app; declaring it only
  // in a subproject would load a second copy in a child class loader.
  alias(libs.plugins.kotlin.jvm) apply false
}
