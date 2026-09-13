import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "ai.iqworks.consentiq.compose"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":consentiq-android"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.runtime.compose)

    testImplementation(libs.junit)
}

mavenPublishing {
    coordinates(
        groupId = project.property("GROUP") as String,
        artifactId = "consentiq-android-compose",
        version = project.property("VERSION_NAME") as String,
    )
    pom {
        name.set("ConsentIQ Android Compose")
        description.set("Jetpack Compose consent sheet and consent gate for the ConsentIQ Android SDK.")
    }
}
