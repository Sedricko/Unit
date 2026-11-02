plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.unit.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.unit.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 88  // LAB 0.3.54-lab: a proxima versao normal precisa de 89+
        versionName = "0.3.54-lab"  // LABORATORIO (ramo lab-assinatura); o -lab nao se atualiza sozinho
    }
    // chave fixa: sem ela cada build do GitHub assina diferente e o Android nao deixa atualizar por cima
    // shortcut: chave de estudo no repositorio, trocar por uma nos Secrets do GitHub se o app um dia for distribuido
    signingConfigs {
        getByName("debug") {
            storeFile = file("unit-estudo.keystore")
            storePassword = "android"
            keyAlias = "unit"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false  // app pequeno: sem R8 por enquanto, os erros ficam mais faceis de ler
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // o motor vai como lib*.so para ser extraido em nativeLibraryDir, onde o Android deixa executar
    packaging { jniLibs { useLegacyPackaging = true } }
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    testImplementation("junit:junit:4.13.2")
}
