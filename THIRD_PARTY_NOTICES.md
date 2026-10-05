# Third-party notices

The source code of this project is MIT licensed (see `LICENSE`). The items below are
**not** covered by that licence; each keeps its own terms, and the licence text travels with
the artefact.

Nothing here was copied into the project. Third-party code is consumed as a binary
dependency through Gradle, and model weights and fonts ship as data files.

---

## Bundled data files

### Segmentation model — `magic_touch.tflite`

| | |
|---|---|
| Path | `app/src/main/assets/models/magic_touch.tflite` |
| Source | <https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite> |
| Component | MediaPipe Interactive Image Segmenter |
| Licence | Apache License 2.0 (part of the MediaPipe distribution) |
| Why it is here | Tap-to-cut-out subject removal. 5.9 MB. Used as the **primary** backend because it needs no Google Play services, so the object flow behaves identically on every device. |

### Fonts

| Family | Files | Licence | Text |
|---|---|---|---|
| Nunito | `nunito_variable.ttf` | SIL Open Font License 1.1 | `third_party/fonts/OFL-nunito.txt` |
| Inter | `inter_variable.ttf` | SIL Open Font License 1.1 | `third_party/fonts/OFL-inter.txt` |

Both are variable fonts covering several weights in one file, which is why only two TTFs ship
instead of one per weight. OFL permits embedding and redistribution; the notices must travel
with the fonts, hence `third_party/fonts/`.

### Dictionary data — `app/src/main/assets/lexicon/en.json`

| | |
|---|---|
| Source | [ECDICT](https://github.com/skywind3000/ECDICT) by the ECDICT authors |
| Licence | MIT |
| How it is used | Not vendored. `tools/build_lexicon.py` reads a locally downloaded `ecdict.csv` and emits the shipped JSON; the generator is committed, the raw CSV is not. |

---

## Runtime dependencies

Resolved versions are pinned in `gradle/libs.versions.toml`.

| Library | Version | Licence | Used for |
|---|---|---|---|
| `androidx.compose.*` (BOM) | 2026.02.01 | Apache-2.0 | UI toolkit |
| `androidx.camera.*` | 1.6.2 | Apache-2.0 | CameraX preview and capture |
| `androidx.navigation:navigation-compose` | 2.9.8 | Apache-2.0 | Navigation |
| `androidx.datastore:datastore-preferences` | 1.2.1 | Apache-2.0 | Settings |
| `androidx.work:work-runtime-ktx` | 2.12.0 | Apache-2.0 | Review reminders |
| `androidx.core:core-ktx`, `core-splashscreen` | 1.18.0, 1.2.0 | Apache-2.0 | Platform glue, splash screen |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.11.0 | Apache-2.0 | Plain-JSON storage |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Apache-2.0 | Async work |
| `com.google.mlkit:image-labeling` | 17.0.9 | Apache-2.0 | On-device image labelling (bundled model) |
| `com.google.android.gms:play-services-mlkit-subject-segmentation` | 16.0.0-beta1 | Android Software Development Kit License | **Optional** automatic segmentation |
| `com.google.mediapipe:tasks-vision` | 1.0.0 | Apache-2.0 | Interactive segmenter runtime |
| `org.jetbrains.kotlin:kotlin-*` | 2.2.10 | Apache-2.0 | Language |
| `com.android.tools.build:gradle` | 9.3.0 | Apache-2.0 | Build |

### A note on the ML Kit subject-segmentation dependency

`play-services-mlkit-subject-segmentation` is the one dependency that is **not** redistributable
under a permissive licence, and it pulls in Google Play services. It is used only when Play
services is present, to obtain automatic segmentation; the app is fully functional without it,
falling back to the bundled MediaPipe model.

This is a known cost of advertising the automatic shot classification. The alternative — dropping
it entirely — would make the object-versus-scene decision impossible to make without a tap on
every device. See `docs/DECISIONS.md`.

---

## Fonts and icons are original

The seven icons in `ui/icons/WordLensIcons.kt` are hand-authored geometric `ImageVector`s. They
contain no third-party icon geometry, and `androidx.compose.material:material-icons-extended` is
deliberately **not** a dependency: it carries roughly two thousand icons for an app that needs
seven, which is a poor trade against APK size.

## Model weights carry no separate attribution file

Neither `magic_touch.tflite` nor ML Kit's bundled label model ships a `NOTICE`. The Google
ML Kit and MediaPipe licence texts are included in the AARs themselves and are reproduced by
the Gradle dependency report (`./gradlew :app:dependencies`).