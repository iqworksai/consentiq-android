import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.ktlint) apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            pom {
                url.set("https://github.com/iqworksai/consentiq-android")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("iqworks")
                        name.set("IQWorks")
                        email.set("engineering@iqworks.ai")
                    }
                }
                scm {
                    url.set("https://github.com/iqworksai/consentiq-android")
                    connection.set("scm:git:git://github.com/iqworksai/consentiq-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/iqworksai/consentiq-android.git")
                }
            }
        }
    }
}
