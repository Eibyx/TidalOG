# Validación real de TidalOG

Fecha del entorno: 2026-11-08 UTC. Base: `15a597b2d27d6b9886a894133d6fb6a754b36d14`.

## Herramientas

JDK Temurin 21.0.13, Gradle wrapper 8.13, Android SDK platform 36 y build-tools 35.0.0. Se instalaron en el workspace las herramientas ausentes y se configuraron proxy/CA solo en el entorno, sin modificar la confianza TLS del proyecto ni deshabilitar validación de certificados.

## Resultados

| Verificación | Resultado observado |
| --- | --- |
| `:core:test` | 118 tests, 0 fallos, 0 errores, 0 omitidos |
| `:app:testDebugUnitTest` | 21 tests, 0 fallos, 0 errores, 0 omitidos |
| `:app:lintDebug` | Correcto: 0 errores; reporte con 68 warnings y 1 hint |
| `:app:assembleDebug` | Correcto; APK instalable con firma debug verificada |
| `:app:assembleDebugAndroidTest` | Correcto; compilan las pruebas existentes y 11 nuevas de integración |
| Scripts Python de release | 14 tests correctos |
| `scripts/check_strings.py` | Correcto |
| `git diff --check` | Correcto |
| `apksigner verify --verbose --print-certs` | Correcto, firma APK v2, un firmante de desarrollo |
| Tests instrumentados nuevos | **No ejecutados**: `DeviceException: No online devices found` |
| Tests live de TIDAL / soak | **No ejecutados**: sin cuenta de TIDAL ni dispositivo operativo |
| `lintRelease` / `assembleRelease` | No ejecutados localmente; forman parte del workflow Check existente |
| Publicación GitHub Release | No realizada |

Comando conjunto registrado en la entrega inicial:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Resultado: **BUILD SUCCESSFUL**, 92 tareas, 20 ejecutadas y 72 up-to-date, 33 segundos. Las tareas reutilizadas se habían ejecutado satisfactoriamente en las pasadas anteriores; no se eliminaron ni omitieron tests para obtener el resultado. Los XML de resultados confirman las cifras anteriores.

Se añadieron **20 tests JVM**: 18 de AutoRadio y 2 de TidalClient (endpoint y cancelación); se preservaron los demás. Los 11 tests nuevos con ExoPlayer real están en `PlaybackRadioIntegrationTest`; su compilación no constituye una ejecución. Incluyen la carrera entre restauración y una petición asíncrona de cola todavía sin timeline. También se ampliaron comprobaciones de precarga y FLAC en `PlaybackSoakLiveTest`.

## Intento de pruebas instrumentadas

Se instalaron emulator y system-image API 26 default x86_64, se creó un AVD y se intentó arrancar sin aceleración. `emulator -accel-check` indicó `KVM requires a CPU that supports vmx or svm`. Tras resolver el espacio inicial con imágenes sparse, el invitado entra en un bucle de arranque:

```text
init: Failed to mount '/vendor': No such file or directory
init: Failed to mount required partitions early ...
init: panic: rebooting to bootloader
```

ADB lo identifica offline. El comando real:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.yyan115.opentune.PlaybackRadioIntegrationTest
```

terminó con **BUILD FAILED**, `DeviceException: No online devices found`, antes de ejecutar pruebas. No es un fallo de aserción ni una validación de comportamiento en Android 8. La matriz API 26/33/36 se conserva en Check para un runner con KVM. Tampoco se certifican rendimiento, Bluetooth, Android Auto, pantalla apagada/Doze, audio focus, gapless o bit-perfect USB mediante los tests JVM.

## Artefacto

Ruta: `app/build/outputs/apk/debug/app-debug.apk`. El hash siguiente corresponde al APK local de la entrega inicial; cada nueva ejecución de Debug APK entrega el hash de su propio APK en `SHA256SUMS`.

SHA-256:

```text
1ad50d5f578dd59baf7039fb56d3b8d3450d7914deb5a6abd38e828174f42587
```

Identidad comprobada con `aapt dump badging`: `io.github.yyan115.opentune.debug`, versionName 0.7.0, versionCode 7000, minSdk 26, target/compileSdk 36. No se modificaron appId/nombre/versión ni se usaron claves del autor original. Un debug usa una clave local de desarrollo, que no se incluye en Git ni sirve como identidad estable para una release.

Los reportes HTML/XML, logs de Gradle y diagnóstico del emulador quedan en el workspace. Para distribución reproducible en GitHub, Check sube `build-results` con el APK debug tras ejecutarse en la rama/PR. No se afirma que exista un artefacto de Actions hasta que GitHub termine ese workflow.

## Seguimiento de CI y artefactos

En la ejecución [42503154638](https://github.com/Eibyx/TidalOG/actions/runs/42503154638) el build de ambas variantes/lint y el job Android 33 terminaron correctamente. Android 26 y 36 detectaron fallos en las nuevas pruebas de integración: captura tardía de revisión de restauración y lecturas de `audioFormat`/`isPlaying` antes de completar actualizaciones asíncronas de Media3. La revisión se captura ahora antes de lanzar la coroutine y las pruebas esperan el estado esperado sin quitar las aserciones. Su nueva ejecución en la matriz de dispositivos está pendiente; no se consideran esos jobs anteriores aprobados.

El artefacto `build-results` anterior contenía `app/build/outputs/apk/debug/app-debug.apk` en subdirectorios. Ahora Check publica el APK separado en `tidalog-debug-apk`, con error si falta. El nuevo workflow `Debug APK` verifica tests JVM, lint, existencia y firma del APK y lo publica como `tidalog-debug.apk` en la raíz del ZIP, junto con `SHA256SUMS`.

Tras el ajuste de restauración se repitieron tests JVM, lint debug, assembleDebug y assembleDebugAndroidTest: BUILD SUCCESSFUL, 32 segundos (18 tareas ejecutadas, 74 up-to-date). La nueva matriz instrumentada de GitHub debe confirmar los ajustes descritos arriba.
