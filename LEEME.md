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
3. Sujeta el móvil **en vertical, delante del pecho y con los codos doblados** (a un palmo del cuerpo; no estires el brazo). Apunta a una pared o un mueble, **no a una ventana** (la exposición se fija al empezar), y pulsa **Empezar**.
4. **Gira sobre tus pies, despacio, a ritmo constante y siempre hacia la derecha** (una vuelta en unos 25–30 s; cada vuelta termina un poco después de pasar por donde empezó). El móvil describe un círculo pequeño alrededor de tu cuerpo: no gires solo la muñeca. Mantén el punto de mira sobre la línea blanca; lo ya capturado se pinta en verde.
5. Al terminar cada vuelta el móvil vibra y te dice hacia dónde inclinarlo para la siguiente.
6. Al acabar, la app procesa la foto (unos segundos) y la abre en el visor.

Consejos: evita personas u objetos moviéndose cerca; con poca luz gira más despacio para que no salga movido. En habitaciones pequeñas es especialmente importante no estirar el brazo: cuanto más lejos del cuerpo va el móvil, más se deforman los objetos cercanos.

Si algo sale mal, en **Ajustes** activa **Guardar datos de diagnóstico**: cada captura deja en *Descargas* un `Esfera3D_diagnostico_….zip` con los fotogramas reducidos y los datos de los sensores, para poder reproducir el cosido y corregirlo.

## Cómo se cose la foto

Durante el barrido la app solo guarda los fotogramas (la franja central de cada uno) con la orientación del móvil. Al terminar los cose, con todos los datos delante (alrededor de un minuto):

1. **Temblor de la mano**: el estabilizador óptico de la cámara mueve la imagen sin que el giroscopio lo sepa. La app mide con la propia imagen cuánto se ha movido cada fotograma respecto al anterior y corrige la parte rápida de la diferencia.
2. **Campo de visión real**: el declarado por el fabricante suele fallar un 5–15 % (el vídeo recorta el sensor). Se calcula a partir de la deformación en trapecio de la imagen al girar, que no depende de la distancia a los objetos.
3. **Escala del giroscopio**: tras una vuelta completa el rumbo medido no vuelve exactamente a su sitio (típicamente unos grados por vuelta, que se acumulan). Se mide comparando el final de cada vuelta con su principio y se corrige en todo el barrido.
4. **Mezcla en vez de corte**: cada columna de la panorámica es la mezcla de los dos fotogramas vecinos en rumbo, sin franjas con borde brusco. El cierre de cada vuelta se funde con su principio.
5. **Unión entre vueltas**: la vuelta de arriba y la de abajo se alinean con la del horizonte comparando la zona común por tramos (un desplazamiento suave a lo largo de la vuelta, elegido por programación dinámica) y se funden con un degradado.

## Cómo se construye el 3D

- **3D real** (estéreo omnidireccional): como el móvil gira alrededor de tu cuerpo, para el ojo izquierdo se toma de cada fotograma la franja situada unos grados a la derecha del centro, y para el derecho la de la izquierda; así cada ojo ve la escena desde un punto distinto. El ángulo depende de **Ajustes › Distancia del móvil a tu cuerpo**.
- **3D figurado**: se mide la diferencia entre esas dos panorámicas para obtener un **mapa de profundidad**; donde no se puede medir (zonas lisas, cénit, nadir) se completa con un modelo de suelo, paredes y techo. Con ese mapa se generan los dos ojos a partir de la panorámica central; su intensidad se puede ajustar.

Límites conocidos: los objetos muy cercanos (a menos de un metro) salen algo alargados en vertical, tanto más cuanto más lejos del cuerpo vaya el móvil; el cénit y el nadir se completan difuminando; la exposición se fija al empezar.

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

- `android-app/app/src/main/java/com/ricardopch/esfera3d/core/`: cosido (`SphereBuilder`), seguimiento del movimiento por imagen (`MotionEstimator`, `MotionTracker`), profundidad, relleno y síntesis (Java puro, sin dependencias de Android).
- `android-app/app/src/main/java/com/ricardopch/esfera3d/`: pantallas (galería, captura guiada, visor), cámara (CameraX), sensores y almacenamiento.
- `android-app/app/src/main/res/raw/pano_frag.glsl`: visor por trazado de rayos (ventana y gafas con corrección de lente, estéreo real, figurado y anaglifo).
