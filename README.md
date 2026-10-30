# H848 Connect

App de Android (Kotlin, Android 12 o más nuevo) que vigila tus auriculares Redragon H848.
Cuando los prendés, sube una ventana desde abajo: **¿Conectar H848? [Ahora no] [Conectar]**.
Si decís que no, se cierra y no vuelve a molestar hasta la próxima vez que los prendas.

## Conseguir el APK

**Opción A: Android Studio (en una compu)**
1. Abrí la carpeta `H848Connect` con Android Studio y esperá a que termine la sincronización.
2. Conectá el celu con depuración USB y apretá Run, o usá *Build > Build APK(s)* y pasá el archivo al celu.

**Opción B: GitHub (sin instalar nada, desde el navegador de una compu)**
1. Creá un repositorio nuevo en github.com y subí todo el contenido de esta carpeta (incluida la carpeta oculta `.github`).
2. Entrá a la pestaña *Actions*, esperá que termine "Build APK" y bajá el artefacto `H848Connect-apk`.
3. Descomprimilo e instalá `app-debug.apk` en el celu (te va a pedir permitir instalar apps de esa fuente).

## Configurar en el celu (una sola vez)

1. En Ajustes > Bluetooth, emparejá los H848 en modo Bluetooth (si no están emparejados ya).
2. Abrí la app y tocá **Elegir mis auriculares**.
3. Tocá **Permitir mostrar sobre otras apps** y activalo (de ahí sale la ventana desde abajo).
4. Tocá **Sacar restricción de batería** y elegí "Sin restricciones".
5. En **Ajustes de Xiaomi**, activá el inicio automático y, en la pantalla de apps recientes, dejá la app "fijada" (candadito), si no HyperOS/MIUI la mata.
6. Activá **Detección activada**. Tiene que quedar una notificación fija chiquita: es lo que mantiene la app viva.
7. Apagá los auriculares, prendelos y mirá si aparece la ventana. El **Registro** al final de la app cuenta qué vio.

## Cómo funciona y qué esperar

- **Detección:** escaneo Bluetooth LE filtrado por tus auriculares (casi no gasta batería). Si tus H848 no avisan por BLE, activá **Búsqueda activa**: cada 30 segundos prueba si responden. Gasta algo más de batería.
- **Conectar:** Android no deja que una app común fuerce la conexión de audio. La app abre un enlace con los auriculares y espera hasta 15 segundos a que el audio se conecte. Si no pasa, te deja una notificación que abre Ajustes > Bluetooth para elegirlos con un toque.
- Si el celu los conecta solo apenas se prenden, la app espera 3 segundos y no muestra nada.
- El Bluetooth del celular tiene que estar prendido: con el Bluetooth apagado, ninguna app puede enterarse.
- Sin el permiso de superposición (o con el celu bloqueado), el aviso sale como notificación con botones Conectar / Ahora no.
