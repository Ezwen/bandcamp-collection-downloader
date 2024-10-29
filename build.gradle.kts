plugins {
    kotlin("jvm") version "2.0.21"
}

tasks.test {
    useJUnitPlatform()
}

repositories {
    mavenCentral()
}
dependencies {
    implementation ("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation ("org.jsoup:jsoup:1.18.1")
    implementation ("org.zeroturnaround:zt-zip:1.14")
    implementation ("org.slf4j:slf4j-simple:1.7.32")
    implementation ("com.sun.mail:javax.mail:1.6.2")
    implementation ("info.picocli:picocli:4.6.1")
    implementation ("com.google.code.gson:gson:2.11.0")
    implementation ("org.ini4j:ini4j:0.5.4")
    implementation ("org.xerial:sqlite-jdbc:3.47.0.0")
    testImplementation ("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly ("org.junit.jupiter:junit-jupiter-engine:5.8.1")
    testImplementation(kotlin("test")) // The Kotlin test library
}

task("fatJar", type = Jar::class) {
    manifest.attributes["Main-Class"] = "bandcampcollectiondownloader.main.MainKt"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory()) it else zipTree(it) })
    with(tasks.jar.get() as CopySpec)
}
