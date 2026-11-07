# Esfera 3D

App Android para hacer **fotos 360° en 3D estereoscópico** con un barrido guiado de la cámara, guardarlas y verlas en la pantalla del móvil o con gafas de realidad virtual, moviendo el móvil para mirar alrededor.

## Instalar

1. En el móvil, abre la página **Releases** de este repositorio y descarga **Esfera3D.apk** de la última compilación.
2. Si Chrome avisa de que el archivo puede ser dañino, toca **Descargar de todas formas** y luego **Abrir**.
3. Si Android no deja instalar apps de esa fuente, toca **Ajustes**, activa **Permitir de esta fuente** y vuelve atrás.
4. Toca **Instalar** (si aparece Play Protect: **Más detalles › Instalar de todos modos**).

Cada APK nuevo se instala encima del anterior sin perder las fotos (todas las compilaciones van firmadas con la misma clave).

## Hacer una foto

1. **Nueva foto 3D 360**. La primera vez pide permiso de cámara.
2. Elige **Esfera completa** (3 vueltas: horizonte, arriba y abajo; unos 2 minutos) o **Rápida** (solo la vuelta del horizonte).
3. Sujeta el móvil **en vertical, con el brazo estirado**, apunta al horizonte y pulsa **Empezar**.
4. **Gira sobre tus pies, despacio, hacia la derecha** (una vuelta en 20–30 s). El móvil tiene que describir un círculo alrededor de tu cuerpo: no gires solo la muñeca. Mantén el punto de mira sobre la línea blanca; lo ya capturado se pinta en verde.
5. Al terminar cada vuelta el móvil vibra y te dice hacia dónde inclinarlo para la siguiente.
6. Al acabar, la app procesa la foto (unos segundos) y la abre en el visor.

Consejos: evita personas u objetos moviéndose cerca; con poca luz gira más despacio para que no salga movido.

## Cómo se construye el 3D

- **3D real** (estéreo omnidireccional por franjas): al girar con el brazo estirado, la cámara recorre un círculo de unos 45 cm de radio. Para el ojo izquierdo se toma de cada fotograma una franja estrecha algo a la derecha del centro, y para el derecho una algo a la izquierda; cosidas en orden, dan dos panorámicas vistas desde puntos separados como los ojos. La separación depende de **Ajustes › Distancia del móvil a tu cuerpo**.
- **3D figurado**: se mide la diferencia entre esas dos panorámicas para obtener un **mapa de profundidad**; donde no se puede medir (zonas lisas, cielo, cénit, nadir) se completa con un modelo de suelo, paredes y techo. Con ese mapa se generan los dos ojos a partir de la panorámica central. Funciona incluso si giraste sin separar el móvil del cuerpo y su intensidad se puede ajustar.
- La perspectiva vertical también se corrige con la profundidad (al girar con el brazo estirado, los objetos cercanos tienden a salir estirados).

## Ver las fotos

- **En la pantalla**: mueve el móvil hacia donde quieras mirar. Arrastra con el dedo para girar, pellizca para acercar. Con gafas de cartón rojo/cian activa **Anaglifo**.
- **Con gafas VR** (tipo Cardboard o visores de plástico): **Gafas VR**, mete el móvil en el visor y mira a tu alrededor. Tocar la pantalla centra la vista; el botón Atrás sale del modo gafas. Si la imagen se ve doble o curvada, en **Ajustes** del visor cambia el tipo de gafas, la separación de lentes o el tamaño.
- Botón **3D real / 3D figurado / 2D** para comparar.

## Guardar y compartir

Las fotos quedan dentro de la app. **Guardar en la galería del móvil** copia a *Imágenes/Esfera3D*:

- `Esfera3D_…_3D_TB.jpg`: estéreo 360 «arriba/abajo» (ojo izquierdo arriba), el formato habitual de los visores y reproductores 360 3D.
- `Esfera3D_…_2D.jpg`: panorámica plana con metadatos de foto esférica (Google Fotos la muestra en 360°).

**Importar** abre fotos 360 de otras cámaras o apps: planas (2:1), arriba/abajo (1:1) o lado a lado (4:1). A las planas les crea un 3D figurado.

## Compilar

GitHub compila solo el APK en cada cambio (pestaña *Actions*, flujo «Compilar APK») y lo publica en *Releases*. También se puede abrir la carpeta `android-app` con Android Studio.

Estructura:

- `android-app/app/src/main/java/com/ricardopch/esfera3d/core/`: cosido estéreo, profundidad, relleno y síntesis (Java puro, sin dependencias de Android).
- `android-app/app/src/main/java/com/ricardopch/esfera3d/`: pantallas (galería, captura guiada, visor), cámara (CameraX), sensores y almacenamiento.
- `android-app/app/src/main/res/raw/pano_frag.glsl`: visor por trazado de rayos (ventana y gafas con corrección de lente, estéreo real, figurado y anaglifo).
