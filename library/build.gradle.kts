plugins {
    id("ownaudio.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "audio.own.music.library"

    testOptions {
        // Robolectric runs the Room DAO tests on the JVM against a real SQLite.
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    // The schema of every version is kept in the repository, for migrations and their tests.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // api: the models carry composite ids from :core:servers, and the own.audio source hands out
    // its client (IdentifySource) and the kit's SmartRule.
    api(project(":core:servers"))
    api(project(":kit:ownaudio"))
    // api: the app builds the Subsonic and Jellyfin clients (sign-in, Quick Connect) and hands
    // them to the sources.
    api(project(":kit:subsonic"))
    api(project(":kit:jellyfin"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
}

// Live tests read their servers from the environment and are skipped without it; a change there
// has to rerun them. Only whether each variable is set goes into Gradle's inputs, never a value.
tasks.withType<Test>().configureEach {
    listOf("SUBSONIC", "JELLYFIN").forEach { kind ->
        listOf("HOST", "USER", "PASSWORD").forEach { name ->
            inputs.property("live-$kind-$name", providers.environmentVariable("${kind}_$name").map { "set" }.orElse(""))
        }
    }
}
