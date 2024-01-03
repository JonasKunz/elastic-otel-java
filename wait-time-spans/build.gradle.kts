plugins {
  `java-library`
  id("me.champeau.jmh") version "0.7.2"
}

group = "co.elastic.apm"
version = "0.0.1-SNAPSHOT"


tasks.withType<Test>().configureEach {
  javaLauncher = javaToolchains.launcherFor() {
    languageVersion = JavaLanguageVersion.of(21)
  }
  jvmArgs("-Xcheck:jni")
}

repositories {
    mavenCentral()
}

dependencies {
  compileOnly("io.opentelemetry:opentelemetry-sdk")
  compileOnly("com.google.code.findbugs:jsr305:3.0.0")
  implementation("com.lmax:disruptor:3.4.4")
  implementation("com.blogspot.mydailyjava:weak-lock-free:0.18")
  implementation(project(":jvmti-access"))

  testCompileOnly("com.google.code.findbugs:jsr305:3.0.0")
  testImplementation("io.opentelemetry:opentelemetry-sdk")
  testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
  testImplementation("org.assertj:assertj-core:3.24.2")
  testImplementation("org.awaitility:awaitility:4.2.0")
  testImplementation("io.opentelemetry:opentelemetry-exporter-otlp")
  testImplementation("io.opentelemetry.semconv:opentelemetry-semconv:1.23.1-alpha")
}

jmh {
  fork = 3
  iterations = 5
  warmupIterations = 5
  //profilers.add("jfr")
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      versionMapping {
        usage("java-api") {
          fromResolutionOf("runtimeClasspath")
        }
        usage("java-runtime") {
          fromResolutionResult()
        }
      }
    }
  }
}
