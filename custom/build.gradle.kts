plugins {
  java
}

dependencies {
  compileOnly(project(":bootstrap"))
  compileOnly(project(":resources"))
  compileOnly("io.opentelemetry:opentelemetry-sdk")
  compileOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi")
  compileOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")
  compileOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-tooling")

  // needs to be added in order to allow access to AgentListener interface
  // this is currently required because autoconfigure is currently not exposed to the extension API.
  compileOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")

  // test dependencies
  testImplementation("io.opentelemetry:opentelemetry-sdk")
  testImplementation("io.opentelemetry.javaagent:opentelemetry-testing-common")
  testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
  testImplementation("org.assertj:assertj-core:3.24.2") // TODO : remove version for assertj
  testImplementation("org.freemarker:freemarker:2.3.27-incubating")
}
tasks.withType<Test> {
  systemProperty("elastic.otel.overwrite.config.docs", project.properties["elastic.otel.overwrite.config.docs"])
}
