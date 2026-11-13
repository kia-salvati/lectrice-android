package app.lectrice.feature.settings

/** One row of the licenses screen (file 18 §3.2); [files] are texts in `assets/licenses/`. */
data class LicensedComponent(val name: String, val license: String, val files: List<String>)

/** A hand-kept list (no license plugin). `LicensesTest` checks every file is there. */
object Licenses {
    /** Shown first while the APK carries sherpa-onnx 1.x (espeak-ng, GPL-3.0). */
    const val GPL_NOTICE =
        "This app includes espeak-ng, licensed under the GNU GPL version 3. You can get the complete source code at " +
            "github.com/kia-salvati/lectrice-android and github.com/kia-salvati/lectrice, at the tag of this version."

    val components = listOf(
        LicensedComponent("Lectrice", "MIT OR Apache-2.0", listOf("lectrice-MIT.txt", "Apache-2.0.txt")),
        LicensedComponent("Kokoro 82M model and voices", "Apache-2.0", listOf("kokoro.txt", "Apache-2.0.txt")),
        LicensedComponent("sherpa-onnx", "Apache-2.0", listOf("sherpa-onnx.txt")),
        LicensedComponent("espeak-ng and espeak-ng-data", "GPL-3.0", listOf("GPL-3.0.txt", "espeak-ng-COPYING.APACHE.txt", "espeak-ng-COPYING.BSD2.txt", "espeak-ng-COPYING.UCD.txt")),
        LicensedComponent("piper-phonemize", "MIT", listOf("piper-phonemize.txt", "uni-algo.txt")),
        LicensedComponent("ONNX Runtime", "MIT", listOf("onnxruntime.txt", "onnxruntime-ThirdPartyNotices.txt")),
        LicensedComponent("kaldi-native-fbank, kaldi-decoder, kaldifst, OpenFst", "Apache-2.0", listOf("kaldi-native-fbank.txt", "kaldi-decoder.txt", "kaldifst.txt", "openfst.txt")),
        LicensedComponent("kissfft", "BSD-3-Clause", listOf("kissfft.txt", "kissfft-BSD-3-Clause.txt")),
        LicensedComponent("simple-sentencepiece, nlohmann/json, hclust-cpp", "Apache-2.0 / MIT", listOf("simple-sentencepiece.txt", "nlohmann-json.txt", "hclust-cpp.txt")),
        LicensedComponent("PDFium (with FreeType, ICU, libjpeg-turbo and others)", "BSD-3-Clause and others", listOf("pdfium.txt")),
        LicensedComponent("Jetpack Compose, AndroidX, Media3, Kotlin, kotlinx.coroutines", "Apache-2.0", listOf("Apache-2.0.txt")),
        LicensedComponent("JNA", "Apache-2.0 (dual-licensed with LGPL-2.1)", listOf("Apache-2.0.txt")),
        LicensedComponent("UniFFI runtime", "MPL-2.0", listOf("MPL-2.0.txt")),
        LicensedComponent("Sonic time-stretch (Rust port)", "Apache-2.0", listOf("Apache-2.0.txt")),
    )
}
