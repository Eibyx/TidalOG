# TidalOG: auditoría de reproducción y radio

Base inspeccionada: `Eibyx/TidalOG`, rama `main`, commit `15a597b2d27d6b9886a894133d6fb6a754b36d14` (OpenTune 0.7.0). No se trabajó sobre el repositorio del autor original.

## Causa y comportamiento corregido

`Screens.kt` pasaba todos los resultados de búsqueda a `play(songs, songs.indexOf(track), searchLabel)`. La cola era, por definición, el resto del buscador. El mismo callback recibía el resultado destacado: si no pertenecía a `songs`, el índice -1 acababa coercionado a 0. Voz también unía hasta 30 coincidencias. Ahora ambos resultados visuales usan `playSearchTrack(track)` y voz elige `topTrack` o la primera coincidencia; el fallback local elige una sola canción.

La continuación antigua dependía de un listener de `MainViewModel`, esperaba `STATE_ENDED`, no cancelaba respuestas de otra cola y llamaba `play()` tras la petición. Se eliminó esa responsabilidad de la interfaz.

## Radio del servicio

- `PlaybackRadio`, propiedad de `PlaybackService`, observa el reproductor real y comprueba la cola cada dos segundos, también sin Activity ni MediaController. No usa un wake lock adicional; se conserva el modo de red y el servicio foreground de Media3.
- Precarga cuando quedan **25 segundos de cola**, sumando duración restante y pistas pendientes. Una duración desconocida espera información de Media3; al acabar hay una última oportunidad asíncrona. No cambia la fuente ni detiene el audio actual.
- `AutoRadio` solo recibe `TidalClient.trackRadio`, que usa `tracks/{id}/radio`. No utiliza búsqueda textual. Conserva la semilla original durante las transiciones automáticas. Si sus resultados están vacíos o todos excluidos, prueba una vez con la pista actual, si difiere. Un salto explícito puede renovar la semilla.
- Máximo **10 recomendaciones futuras** y **50 recomendaciones automáticas ya reproducidas**. No elimina entradas de álbumes, playlists ni agregadas a mano. Consulta nuevamente al consumir cada lote. No cambia el orden devuelto por TIDAL.
- `RepeatPolicy.radioCandidates` elimina archivos locales, IDs vacíos, duplicados de respuesta/cola y todo el historial dentro de `avoidRepeatHours`. A diferencia de `dropRecent`, no reintroduce recientes para rellenar una lista vacía.
- Cada cambio de cola, salto de pista, repetición o ajuste invalida el trabajo. El resultado comprueba generación, pista actual, semilla, cuenta, IDs de cola y autorización de autoplay antes de anexar. La sustitución por MediaSession se invalida antes de resolver la petición del controlador.
- Una petición activa por generación/pista. IO y HTTP 5xx permiten dos reintentos, a 2 y 4 segundos. 401/403/404/429 no se reintentan en este componente; se conserva la renovación OAuth existente de TidalClient. Un fallo/vacío agotado no provoca polling: se espera un cambio de pista/cola/ajustes. Si no quedan resultados elegibles la radio puede detenerse, deliberadamente.
- La cancelación cierra también el Call de OkHttp de las peticiones API; no deja una solicitud antigua ocupando IO hasta agotar el timeout. El flujo de login no se sustituye.
- Se añade al final; si un controlador agrega música después de recomendaciones precargadas, el servicio coloca las entradas manuales antes de ellas conservando su orden relativo. No se deduplican las elecciones manuales del usuario.
- No llama `play()` al recibir una respuesta. Si la cola ya terminó, continúa solo si `playWhenReady` sigue vigente; una pausa, audio focus o temporizador prevalecen.

## Orígenes y controles

| Origen/control | Resultado |
| --- | --- |
| Búsqueda y destacado | Solo la pista seleccionada, luego radio con Autoplay |
| Voz de la app o MediaSession/Android Auto | Una mejor coincidencia, luego radio con Autoplay |
| Álbum, playlist, favoritos | Conserva la cola elegida; radio solo cerca de agotarla |
| Radio de canción | Semilla empieza inmediatamente; comando al servicio solicita primer lote, incluso con Autoplay apagado; siguientes lotes requieren Autoplay |
| Radio de artista | Primer lote real de `artists/{id}/radio`, luego radio basada en la música elegida; respuesta inicial descarta cambios de intención posteriores |
| Cola manual | Sus entradas tienen prioridad frente a precargas |
| Archivo `local:` | No solicita radio; elimina recomendaciones automáticas pendientes al entrar a un archivo |
| Offline / Autoplay apagado | Cancela trabajo y retira únicamente recomendaciones automáticas futuras; el lote explícito de radio solicitado por el usuario es una acción manual |
| Repeat One / All | No extiende la cola; retira precargas futuras al habilitar repetición |
| Shuffle | Mantiene el mecanismo existente de orden físico; no cambia el modo ni pierde pistas manuales |
| Temporizador | Respeta pausa y fin de pista; ninguna respuesta fuerza play |

## Persistencia y optimizaciones aplicadas

`QueuePersistence` mueve restauración y guardado al servicio y mantiene las claves existentes de SharedPreferences. Un canal conflado y un único escritor IO evitan escritores UI/servicio en competencia; reutiliza JSON de metadatos y solo recodifica cuando cambia la lista de MediaItems. Guarda progreso cada diez segundos cuando reproduce, y eventos del reproductor. Restaura índice, posición, Shuffle, Repeat y marcas de precarga, en pausa. Una revisión impide que una restauración tardía sustituya una cola recién recibida. Los eventos iniciales sin cola no sobrescriben la cola guardada antes de leerla.

`MainViewModel` reutiliza la cola decodificada salvo cambios de timeline y evita construir el estado global de reproducción cada 500 ms si no han cambiado calidad/duración. El progreso sigue en su StateFlow separado. Se cancela la carga anterior de artwork del widget al cambiar de pista. No se introdujeron dependencias.

## Calidad, estabilidad y seguridad inspeccionadas

Se conservan ExoPlayer/Media3, resolución diferida de URLs, FLAC/DASH, gapless, selección Wi-Fi/móvil, descargas, letras, ReplayGain, equalizer y USB experimental. `TidalClient.stream` ya rechaza AAC para una petición Lossless; sus pruebas existentes se mantienen. La radio añade metadatos sin resolver ni rebajar la calidad. Las pruebas instrumentadas nuevas comprueban el decodificador FLAC y 96 kHz con el fixture existente, y la prueba live exige que la calidad reportada siga siendo FLAC.

Se conserva audio focus de Media3 (`setAudioAttributes(..., true)`), noisy/Bluetooth, controles MediaSession, biblioteca de Android Auto, recuperación de red y reintentos de reproducción existentes. La reconexión automática de un MediaController tras muerte del servicio sigue requiriendo validación/mejora; la radio ya no depende de esa conexión.

Tokens y PKCE se guardan con AES-GCM y Android Keystore; backup desactivado, estado PKCE comparado y desafío SHA-256 existentes. No se añadieron logs de cuerpos de respuesta, credenciales, telemetría ni permisos. Se conserva la identidad de cliente OAuth embebida existente: una app pública no puede mantener confidencial un secreto de cliente. No se añadieron credenciales de usuario o claves de firma.

No se presentan mediciones de CPU, batería, memoria o recomposiciones como un benchmark: los cambios anteriores reducen trabajo identificable por inspección. Doze/OEM, Wi-Fi→datos, interrupciones, Android Auto, Bluetooth, gapless real y USB bit-perfect 24/192 requieren pruebas en hardware y cuenta real. El soporte existente de bit-perfect es experimental; no se certifica mediante compilación o un emulador.

## Pruebas y reproducción

`AutoRadioTest` prueba selección 5/10, destacado ajeno a la lista, voz, umbral de precarga, semilla, duplicados/historial/local, capacidad, peticiones concurrentes, cancelación incluso con proveedor no cooperativo, sustitución de playlist con misma pista, cambios de cuenta/ajustes/pausa/timer/Repeat, prioridad manual, renovación de lotes, fallback acotado, vacíos, backoff y errores HTTP. `TidalClientTest` añade comprobación del endpoint real y cancelación HTTP, y conserva las pruebas Lossless/OAuth.

`PlaybackRadioIntegrationTest` usa ExoPlayer real con FLAC local y proveedor de radio inyectado, sin cuenta ni Activity: precarga, continuación de lotes, prioridad manual, sustitución de cola, Autoplay apagado, Repeat/Shuffle, timer, offline/local, radio explícita y restauración con marcas. `PlaybackSoakLiveTest` ahora exige precarga antes del final y FLAC en el soak. Los tests live siguen siendo opt-in con la cuenta configurada según las instrucciones originales.

Comando principal:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Compilación y ejecución adicional de integración (requiere dispositivo/emulador):

```sh
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.yyan115.opentune.PlaybackRadioIntegrationTest
```

Resultados reales de esta sesión se registran en `docs/tidalog-validation.md`.

## Distribución y firma del fork

Identidad conservada: nombre OpenTune, applicationId release `io.github.yyan115.opentune`, debug `io.github.yyan115.opentune.debug`, versión 0.7.0, versionCode 7000, minSdk 26 (Android 8). El namespace tampoco cambia. No se asume posesión de claves del autor. Un APK release con otra firma **no actualiza** una instalación original con el mismo applicationId; requiere exportar datos/desinstalar o decidir una identidad nueva y migración. Un APK debug coexiste con release, pero puede colisionar con otro debug firmado de forma diferente.

El workflow [Check](https://github.com/Eibyx/TidalOG/actions/workflows/check.yml) ya permite forks y ejecución manual. Ejecuta tests JVM, lint y builds, sube el APK debug en `build-results`, y mantiene dispositivos API 26/33/36. No se eliminaron tests existentes.

El workflow [Release](https://github.com/Eibyx/TidalOG/actions/workflows/release.yml) se limita a `Eibyx/TidalOG`, rama main y variable `TIDALOG_RELEASE_ENABLED=true`, después de Check. Antes de habilitarlo el propietario debe configurar:

- Secrets `RELEASE_KEYSTORE_BASE64` y `RELEASE_KEYSTORE_PASSWORD`, con su propia clave y contraseña.
- Variables `RELEASE_KEY_ALIAS` y `RELEASE_CERTIFICATE_SHA256` (64 caracteres hexadecimales de su certificado).
- Permisos de Actions para crear tags/releases. La comprobación de firma usa esa huella, no el certificado del autor original que permanece en el historial.

No se generó ni se subió una clave privada al repositorio. Un debug usa la firma de desarrollo generada localmente. No se publica automáticamente ninguna release durante esta tarea. El artefacto reproducible de Check permite revisar el APK sin habilitar publicación release. El nombre del archivo de release es `tidalog-VERSION.apk`; esto no cambia la identidad Android.
