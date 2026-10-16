plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    testImplementation("junit:junit:4.13.2")
}
tasks.test {
    maxHeapSize = "512m"
    inputs.property("upstreamFakePort", providers.environmentVariable("ATV_FAKE_PORT").orElse("none"))
}
