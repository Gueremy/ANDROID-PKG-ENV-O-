# PKG Bridge (Android) · v1.0.0

Aplicación Android nativa (Java + Android SDK) para **enviar archivos `.pkg` por FTP** desde el teléfono a una PS4 con GoldHEN, u otro servidor FTP propio de la red local.

## Funciones

- IP y puerto FTP configurables, con `2121` preconfigurado para GoldHEN.
- Carpeta FTP configurable, predeterminada: `/data/pkg`.
- Usuario y contraseña opcionales (usuario predeterminado: `anonymous`). La contraseña **no se guarda**.
- Selección de archivos `.pkg` con el selector de documentos del sistema Android.
- Transferencia en **streaming**, sin crear una copia adicional del archivo en el teléfono.
- Modo binario y pasivo, con porcentaje, MB enviados y velocidad media.
- Guardado local de IP, puerto, carpeta y usuario.
- Revisión de archivo existente para no sobrescribirlo accidentalmente.
- Transferencia a nombre temporal `.subiendo-XXXXXXXX`, seguido de renombrado a `.pkg` cuando el servidor confirma la copia.
- Botón para cancelar la transferencia.

## Conectar a la PS4

1. En la PS4 habilita GoldHEN → **Server Settings → Enable FTP Server**.
2. Consulta su IP en **Ajustes → Red → Ver estado de conexión** (o Ajustes → Sistema → Información del sistema).
3. Conecta la PS4 y el Android a **la misma red local**; evita la red de invitados con aislamiento entre dispositivos.
4. En PKG Bridge escribe la IP de la PS4 y el puerto `2121`; deja `/data/pkg` como destino.
5. Pulsa **Probar conexión FTP**. Si no responde, verifica el servidor FTP GoldHEN y la IP.
6. Pulsa **Elegir archivo .pkg**, selecciona el archivo y luego **Enviar PKG a la PS4**.
7. Cuando indique `100% · Completado`, en PS4 entra en GoldHEN → Debug Settings → Package Installer. Ajusta Package Source a `Hdd` o `All` y selecciona el paquete.

**Importante:** subir el archivo por FTP **no lo instala automáticamente**. La PS4 necesita espacio libre tanto para la copia como para la instalación. En el momento de transferir, mantén la app abierta y la pantalla encendida (la app activa `KEEP_SCREEN_ON`).

## Página de descarga y APK automático en GitHub

El proyecto incluye dos flujos: `.github/workflows/build-apk.yml` compila el APK y lo publica como **GitHub Release**, y `.github/workflows/pages.yml` publica la página `docs/index.html` con un botón para descargarlo.

### Publicación inicial (una sola vez)

1. En `https://github.com/new`, crea un **repositorio público** llamado exactamente `ANDROID-PKG-ENV-O-` en la cuenta `Gueremy`, preferiblemente **sin agregar README ni .gitignore** al crearlo.
2. Sube los archivos y carpetas del proyecto a la **raíz** del repositorio. Puedes extraer el ZIP, usar GitHub → `Add file` → `Upload files` desde un computador, o pedir ayuda a ChatGPT con la conexión GitHub para subirlos al repositorio ya creado.
3. En GitHub ve a **Settings → Pages → Build and deployment → Source: GitHub Actions**. Esto habilita la publicación de la página. GitHub requiere esta acción manual de un administrador.
4. En **Actions**, revisa que los flujos `Compilar y publicar PKG Bridge` y `Publicar página de descargas` terminen correctamente. Si el despliegue de Pages corrió antes de que lo habilitaras, usa **Run workflow** para repetirlo.
5. Tras una compilación exitosa estarán disponibles:
   - **Página:** `https://gueremy.github.io/ANDROID-PKG-ENV-O-/`
   - **APK directo:** `https://github.com/Gueremy/ANDROID-PKG-ENV-O-/releases/latest/download/PKGBridge.apk`
   - **Versiones:** `https://github.com/Gueremy/ANDROID-PKG-ENV-O-/releases/latest`

**Estos enlaces no existirán hasta que se cree el repositorio y GitHub Actions publique al menos una versión.**

El flujo genera la app en modo **debug**, apta para pruebas y firmada automáticamente por cada entorno de compilación. Una compilación futura puede tener otra firma; si Android rechaza la actualización, desinstala la anterior antes de instalar la nueva (se borrarán sus preferencias). Para distribución estable hace falta una clave de firma permanente privada.

### Cómo se publica una versión nueva

Cada cambio enviado a `main` ejecuta compilación y crea una GitHub Release `beta-N`. El botón de la página siempre dirige a la última versión publicada gracias a `/releases/latest/download/PKGBridge.apk`.

> No edites el enlace a mano: una vez activado, se actualiza automáticamente para las futuras versiones.

## Abrir con Android Studio

- JDK 17, Android SDK API 35, Gradle 8.13, plugin de Android 8.13.2.
- Abre la carpeta `PKGBridge` como proyecto Gradle.
- Si Android Studio requiere un **Gradle Wrapper** local, ejecuta `gradle wrapper --gradle-version 8.13` desde una consola con Gradle instalado y después sincroniza. Esto genera el `gradle-wrapper.jar` y los scripts oficiales. El flujo de GitHub Actions proporcionado no necesita ese paso.
- Menú **Build → Build APK(s)**, o ejecuta `gradle :app:assembleDebug`.
- APK generado en `app/build/outputs/apk/debug/app-debug.apk`.

## Consideraciones de seguridad

- FTP tradicional **no cifra archivos ni credenciales**. Úsalo únicamente en una red local confiable y no publiques el puerto FTP de la PS4 en internet.
- La aplicación no necesita permisos de almacenamiento general: utiliza el selector de documentos Android (`ACTION_OPEN_DOCUMENT`).
- Los archivos se envían directamente desde la URI proporcionada por Android; no se suben a un servidor externo.
- Las contraseñas no se guardan en SharedPreferences.
- Si la conexión se corta o cancelas, el programa intenta borrar el archivo temporal, pero un fallo del servidor o pérdida de conexión puede dejar un `.subiendo-*` parcialmente cargado. Puedes borrarlo desde un cliente FTP si hace falta.
- Si el PKG final ya existe, la app evita sobrescribirlo. Actualmente no permite renombrar ni eliminar archivos remotos desde la interfaz.
- La app no realiza instalaciones remotas automáticas, no usa Remote Package Installer y no tiene transferencias de fondo de larga duración.

## Estado de compilación

**Proyecto fuente listo para compilar, no APK precompilado.** Esta entrega no ha sido probada en una PS4 física ni compilada dentro de este entorno (no se dispone del Android SDK). Úsalo como primera versión (MVP) y valida la transferencia real en tu equipo.

## Estructura

- `app/src/main/java/cl/pkgbridge/app/MainActivity.java` — interfaz, FTP y selección de archivos.
- `app/src/main/res/layout/activity_main.xml` — pantalla principal.
- `app/src/main/res/drawable` — elementos visuales.
- `app/src/main/AndroidManifest.xml` — permisos y actividad.
- `.github/workflows/build-apk.yml` — compilación y publicación automática en Releases.
- `.github/workflows/pages.yml` — despliegue de la página de descargas.
- `docs/index.html` — página pública con el botón de descarga.

### Datos de prueba recomendados

Primero prueba con un `.pkg` pequeño, del que tengas autorización de uso, antes de enviar paquetes grandes. La velocidad real depende del Wi-Fi, la PS4 y su almacenamiento.
