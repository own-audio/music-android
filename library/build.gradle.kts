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

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
}
