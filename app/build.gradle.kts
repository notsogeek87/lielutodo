import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

// Numéro de build croissant : fourni par la CI (BUILD_NUMBER = github.run_number). Il devient le versionCode
// (Android refuse d'installer par-dessus un versionCode non supérieur) et le dernier segment du versionName,
// pour que la bibliothèque de mise à jour compare correctement le tag `v1.0.<n>` à la version installée.
val buildNumber = (System.getenv("BUILD_NUMBER") ?: providers.gradleProperty("buildNumber").orNull)?.toIntOrNull() ?: 1
val appVersionBase = providers.gradleProperty("appVersionBase").get()
val appName = providers.gradleProperty("appName").get()

// En CI, GITHUB_REPOSITORY vaut « owner/repo » du dépôt qui construit : l'app se met à jour depuis ses propres
// releases, sans rien éditer après avoir copié le modèle. En local, la valeur de gradle.properties s'applique.
val githubRepository =
    (System.getenv("GITHUB_REPOSITORY") ?: providers.gradleProperty("appGithubRepository").get())
        .also { require(it.matches(Regex("[^/\\s]+/[^/\\s]+"))) { "appGithubRepository doit valoir owner/repo : $it" } }

// Signature release : keystore fourni par l'environnement (secrets CI ou keystore.properties local) s'il existe,
// sinon le keystore versionné (voir plus bas). Rien de secret n'est requis pour produire un APK installable.
val releaseKeystoreProps =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

fun signingValue(
    env: String,
    prop: String,
): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: releaseKeystoreProps.getProperty(prop)

android {
    // Mis à jour par scripts/new-app.sh (avec le dossier de sources et applicationId).
    namespace = "com.lielu.todo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lielu.todo"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "$appVersionBase.$buildNumber"

        buildConfigField("String", "GITHUB_REPOSITORY", "\"$githubRepository\"")
    }

    // Le keystore versionné (`app/debug.keystore`, mots de passe standard `android`) signe les builds debug ET,
    // par défaut, les builds release : un APK ne s'installe par-dessus un autre que s'ils ont la même signature,
    // et un runner CI neuf n'a pas de keystore (AGP en générerait un aléatoire à chaque build, cassant la mise à
    // jour intégrée). Ce n'est pas un secret. Pour une vraie clé de publication, définir KEYSTORE_FILE,
    // KEYSTORE_PASSWORD, KEY_ALIAS et KEY_PASSWORD (secrets CI) ou keystore.properties — attention : changer de
    // clé impose de désinstaller l'app une fois.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val customStore = signingValue("KEYSTORE_FILE", "storeFile")
            if (customStore != null) {
                storeFile = rootProject.file(customStore)
                storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
            } else {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // Pas de R8 par défaut : sans Gradle local pour vérifier les règles, un APK minifié casserait
            // silencieusement l'app (réflexion, WorkManager). À activer app par app, une fois testé.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    // « staging » a son propre applicationId et son nom : il s'installe à côté de « production » sur un même
    // appareil. Nommé « production » plutôt que « main » pour éviter le source set réservé `main` ; la CI
    // publie malgré tout cet APK comme la release de la branche `main`.
    flavorDimensions += "env"
    productFlavors {
        create("staging") {
            dimension = "env"
            applicationIdSuffix = ".staging"
            resValue("string", "app_name", "$appName Staging")
        }
        create("production") {
            dimension = "env"
            resValue("string", "app_name", appName)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ktlint {
    version.set("1.3.1")
    android.set(true)
    ignoreFailures.set(false)
}

// Sans ça, un test rouge en CI se résume à « AssertionError at XxxTest.kt:105 » : ni la valeur attendue, ni la
// pile. La CI est parfois le seul endroit où les tests tournent (voir CLAUDE.md) : son log doit suffire.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.lielugit.updater)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
